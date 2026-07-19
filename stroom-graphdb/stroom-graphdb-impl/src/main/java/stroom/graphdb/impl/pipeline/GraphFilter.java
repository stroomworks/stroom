/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.graphdb.impl.pipeline;

import stroom.docref.DocRef;
import stroom.graphdb.impl.GraphDbDocCache;
import stroom.graphdb.impl.GraphStoreManager;
import stroom.graphdb.impl.GraphStores;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggedException;
import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.factory.PipelineProperty;
import stroom.pipeline.factory.PipelinePropertyDocRef;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;
import stroom.svg.shared.SvgImage;
import stroom.util.CharBuffer;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code graph-mutation:1} XML (Task P2.1) and writes node/edge mutations into one target
 * {@link GraphDbDoc}'s stores (Task P2.2) - the graph analogue of {@code stroom.planb.impl.pipeline.PlanBFilter},
 * with one load-bearing difference: this filter resolves its **single** target doc via a
 * {@link PipelineProperty}/{@link DocRef} (like {@code stroom.index.impl.DynamicIndexingFilter} resolves its
 * index), not per-record from an in-XML map name - a {@code GraphDbDoc} is one directly-opened, long-lived
 * {@link GraphStores}, not a shardable/mergeable Plan B store (design doc &sect;2.1; implementation plan's P2
 * scoping note explains why {@code PlanBFilter}'s own resolution strategy does not fit here).
 *
 * <p>Holds one {@link LmdbWriter} open across the whole stream (opened in {@link #startProcessing()}, closed in
 * {@link #endProcessing()}), calling {@link LmdbWriter#tryCommit()} after every mutation - batching is handled
 * entirely by {@link LmdbWriter}'s own internal change-count threshold, no manual batching logic here.</p>
 *
 * <p>A malformed record (missing a required attribute/child, an unparsable {@code validFrom}) is logged via the
 * normal pipeline error-reporting path and skipped - it does not abort the whole stream, mirroring
 * {@code PlanBFilter}'s own resilience to isolated bad records.</p>
 */
@ConfigurableElement(
        type = "GraphFilter",
        displayValue = "Graph Filter",
        description = """
                Takes XML input (conforming to the graph-mutation:1 schema) and writes node/edge mutations \
                into the target GraphDb's internal stores.""",
        category = Category.FILTER,
        roles = {
                PipelineElementType.ROLE_TARGET,
                PipelineElementType.ROLE_HAS_TARGETS},
        icon = SvgImage.DOCUMENT_PLAN_B)
public class GraphFilter extends AbstractXMLFilter {

    private static final String NODE_ELEMENT = "node";
    private static final String NODE_DELETE_ELEMENT = "node-delete";
    private static final String EDGE_ELEMENT = "edge";
    private static final String EDGE_DELETE_ELEMENT = "edge-delete";
    private static final String LABEL_ELEMENT = "label";
    private static final String PROPERTY_ELEMENT = "property";
    private static final String SRC_ELEMENT = "src";
    private static final String DST_ELEMENT = "dst";

    private static final String ID_ATTRIBUTE = "id";
    private static final String TYPE_ATTRIBUTE = "type";
    private static final String VALID_FROM_ATTRIBUTE = "validFrom";
    private static final String NAME_ATTRIBUTE = "name";

    private final ErrorReceiverProxy errorReceiverProxy;
    private final LocationFactoryProxy locationFactory;
    private final GraphDbDocCache graphDbDocCache;
    private final GraphStoreManager graphStoreManager;

    private final CharBuffer contentBuffer = new CharBuffer(32);

    private DocRef graphDbRef;
    private GraphStores stores;
    private LmdbWriter writer;
    private Locator locator;

    // Accumulated per-record state, valid only between a node/edge/node-delete/edge-delete's startElement and
    // its matching endElement - reset (implicitly, by re-assignment) at each record's startElement.
    private String currentId;
    private String currentType;
    private Instant currentValidFrom;
    private List<String> currentLabels;
    private Map<String, String> currentProperties;
    private String currentPropertyName;
    private String currentSrc;
    private String currentDst;

    @Inject
    public GraphFilter(final ErrorReceiverProxy errorReceiverProxy,
                       final LocationFactoryProxy locationFactory,
                       final GraphDbDocCache graphDbDocCache,
                       final GraphStoreManager graphStoreManager) {
        this.errorReceiverProxy = errorReceiverProxy;
        this.locationFactory = locationFactory;
        this.graphDbDocCache = graphDbDocCache;
        this.graphStoreManager = graphStoreManager;
    }

    @PipelineProperty(description = "The graph to write node/edge mutations into.", displayPriority = 1)
    @PipelinePropertyDocRef(types = GraphDbDoc.TYPE)
    public void setGraphDb(final DocRef graphDbRef) {
        this.graphDbRef = graphDbRef;
    }

    @Override
    public void startProcessing() {
        try {
            if (graphDbRef == null) {
                log(Severity.FATAL_ERROR, "Graph DB has not been set", null);
                throw LoggedException.create("Graph DB has not been set");
            }
            final GraphDbDoc doc = graphDbDocCache.get(graphDbRef.getName());
            if (doc == null) {
                log(Severity.FATAL_ERROR, "Unable to load graph db " + graphDbRef, null);
                throw LoggedException.create("Unable to load graph db " + graphDbRef);
            }
            stores = graphStoreManager.getOrOpen(doc);
            writer = stores.createWriter();
        } finally {
            super.startProcessing();
        }
    }

    @Override
    public void endProcessing() {
        try {
            if (writer != null) {
                writer.close();
            }
        } finally {
            super.endProcessing();
        }
    }

    @Override
    public void setDocumentLocator(final Locator locator) {
        this.locator = locator;
        super.setDocumentLocator(locator);
    }

    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts)
            throws SAXException {
        contentBuffer.clear();
        switch (localName.toLowerCase(Locale.ROOT)) {
            case NODE_ELEMENT -> {
                currentId = atts.getValue(ID_ATTRIBUTE);
                currentValidFrom = parseValidFrom(atts.getValue(VALID_FROM_ATTRIBUTE));
                currentLabels = new ArrayList<>();
                currentProperties = new LinkedHashMap<>();
            }
            case NODE_DELETE_ELEMENT -> {
                currentId = atts.getValue(ID_ATTRIBUTE);
                currentValidFrom = parseValidFrom(atts.getValue(VALID_FROM_ATTRIBUTE));
            }
            case EDGE_ELEMENT -> {
                currentType = atts.getValue(TYPE_ATTRIBUTE);
                currentValidFrom = parseValidFrom(atts.getValue(VALID_FROM_ATTRIBUTE));
                currentProperties = new LinkedHashMap<>();
                currentSrc = null;
                currentDst = null;
            }
            case EDGE_DELETE_ELEMENT -> {
                currentType = atts.getValue(TYPE_ATTRIBUTE);
                currentValidFrom = parseValidFrom(atts.getValue(VALID_FROM_ATTRIBUTE));
                currentSrc = null;
                currentDst = null;
            }
            case PROPERTY_ELEMENT -> currentPropertyName = atts.getValue(NAME_ATTRIBUTE);
            default -> {
                // Not a record-shaping element (e.g. the <graph> root) - nothing to do.
            }
        }
        super.startElement(uri, localName, qName, atts);
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        switch (localName.toLowerCase(Locale.ROOT)) {
            case LABEL_ELEMENT -> currentLabels.add(contentBuffer.toString());
            case PROPERTY_ELEMENT -> currentProperties.put(currentPropertyName, contentBuffer.toString());
            case SRC_ELEMENT -> currentSrc = contentBuffer.toString();
            case DST_ELEMENT -> currentDst = contentBuffer.toString();
            case NODE_ELEMENT -> addNode();
            case NODE_DELETE_ELEMENT -> deleteNode();
            case EDGE_ELEMENT -> addEdge();
            case EDGE_DELETE_ELEMENT -> deleteEdge();
            default -> {
                // Not a record-shaping element - nothing to do.
            }
        }
        contentBuffer.clear();
        super.endElement(uri, localName, qName);
    }

    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        contentBuffer.append(ch, start, length);
        super.characters(ch, start, length);
    }

    private void addNode() {
        if (currentId == null || currentValidFrom == null) {
            error("<node> requires both id and validFrom");
            return;
        }
        final long nodeUid = intern(stores.getNodeUids(), currentId);
        final List<Long> labelUids = new ArrayList<>(currentLabels.size());
        for (final String label : currentLabels) {
            labelUids.add(intern(stores.getLabelUids(), label));
        }
        final Map<String, Val> properties = toVals(currentProperties);
        stores.getNodes().insert(writer, nodeUid, currentValidFrom, labelUids, properties);

        // Anchor-index every property against every label - there is no per-label "which properties are
        // indexable" schema yet (GraphNodeTypeMapping only maps label -> domain type, design doc &sect;5.6),
        // so indexing everything is the only sensible v1 default; a future schema-driven selection would
        // narrow this, not change the mechanism.
        for (final long labelUid : labelUids) {
            for (final Map.Entry<String, String> property : currentProperties.entrySet()) {
                final long propKeyUid = intern(stores.getPropertyKeyUids(), property.getKey());
                stores.getPropertyIndex().insert(writer, labelUid, propKeyUid,
                        property.getValue().getBytes(StandardCharsets.UTF_8), nodeUid);
            }
        }
        writer.tryCommit();
    }

    private void deleteNode() {
        if (currentId == null || currentValidFrom == null) {
            error("<node-delete> requires both id and validFrom");
            return;
        }
        final long nodeUid = intern(stores.getNodeUids(), currentId);
        stores.getNodes().delete(writer, nodeUid, currentValidFrom);
        writer.tryCommit();
    }

    private void addEdge() {
        if (currentType == null || currentValidFrom == null || currentSrc == null || currentDst == null) {
            error("<edge> requires type, validFrom, src and dst");
            return;
        }
        final long edgeTypeUid = intern(stores.getEdgeTypeUids(), currentType);
        final long srcUid = intern(stores.getNodeUids(), currentSrc);
        final long dstUid = intern(stores.getNodeUids(), currentDst);
        final Map<String, Val> properties = toVals(currentProperties);

        // Dual-write contract (Task P1.1): both adjacency stores must be written for one logical edge.
        stores.getOutEdges().insert(writer, srcUid, edgeTypeUid, dstUid, currentValidFrom, properties);
        stores.getInEdges().insert(writer, srcUid, edgeTypeUid, dstUid, currentValidFrom, properties);
        writer.tryCommit();
    }

    private void deleteEdge() {
        if (currentType == null || currentValidFrom == null || currentSrc == null || currentDst == null) {
            error("<edge-delete> requires type, validFrom, src and dst");
            return;
        }
        final long edgeTypeUid = intern(stores.getEdgeTypeUids(), currentType);
        final long srcUid = intern(stores.getNodeUids(), currentSrc);
        final long dstUid = intern(stores.getNodeUids(), currentDst);

        stores.getOutEdges().delete(writer, srcUid, edgeTypeUid, dstUid, currentValidFrom);
        stores.getInEdges().delete(writer, srcUid, edgeTypeUid, dstUid, currentValidFrom);
        writer.tryCommit();
    }

    private static Map<String, Val> toVals(final Map<String, String> properties) {
        final Map<String, Val> vals = new LinkedHashMap<>(properties.size());
        for (final Map.Entry<String, String> property : properties.entrySet()) {
            vals.put(property.getKey(), ValString.create(property.getValue()));
        }
        return vals;
    }

    private long intern(final UidLookupDb db, final String key) {
        return db.put(writer.getWriteTxn(), directBuffer(key), uidBuffer ->
                UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate()));
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private Instant parseValidFrom(final String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (final DateTimeParseException e) {
            error("Unable to parse validFrom \"" + value + "\": " + e.getMessage());
            return null;
        }
    }

    private void error(final String message) {
        log(Severity.ERROR, message, null);
    }

    private void log(final Severity severity, final String message, final Exception e) {
        errorReceiverProxy.log(severity, locationFactory.create(locator), getElementId(), message, e);
    }
}
