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
import stroom.graphdb.impl.GraphNodeDb;
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
import org.jspecify.annotations.Nullable;
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
import java.util.Optional;

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
 * {@link #endProcessing()}), but - unlike {@link LmdbWriter}'s other callers - does <b>not</b> rely on its
 * batched auto-commit threshold ({@link LmdbWriter#tryCommit()}). A node/edge write is not one write but several
 * (a node write plus N property-index anchors; an edge write is a dual out-edge/in-edge insert), so batching
 * writes across many records inside one long-lived transaction would let a mid-handler failure on record N leave
 * record N's *partial* writes staged alongside every already-succeeded record before it, to be silently committed
 * together at the next threshold flush - a one-sided edge or partially-indexed node with no record of the
 * inconsistency. Instead, {@link #perRecord} makes each record its own all-or-nothing unit: it
 * {@link LmdbWriter#commit() commits} on the handler's success and {@link LmdbWriter#abort() aborts} (rolling back
 * only that record's writes) on failure, trading {@link LmdbWriter}'s write-batching throughput for a hard
 * per-record durability guarantee.</p>
 *
 * <p>A bad record is logged via the normal pipeline error-reporting path and skipped - it does not abort the
 * whole stream, mirroring {@code PlanBFilter}'s own resilience to isolated bad records. This covers both a
 * malformed record (missing a required attribute/child, an unparsable {@code validFrom} - detected before any
 * store write) and a record that reaches the store layer but fails there (e.g. a node with more labels than the
 * store can encode, a property value too large for the LMDB buffer, or a pre-existing corrupt version blob) -
 * every per-record store mutation runs under {@link #perRecord}, the analogue of
 * {@code PlanBFilter.catchLmdbError}.</p>
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
                // Code-review fix: previously left over from whatever <node>/<edge> was processed last, so a
                // <label>/<property> mis-nested under a <node-delete> (invalid per the XSD, but SAX still fires
                // the events if nothing upstream validates) would silently mutate that stale state instead of
                // being flagged - resetting to null makes endElement's LABEL_ELEMENT/PROPERTY_ELEMENT cases able
                // to detect and reject it instead.
                currentLabels = null;
                currentProperties = null;
                currentPropertyName = null;
            }
            case EDGE_ELEMENT -> {
                currentType = atts.getValue(TYPE_ATTRIBUTE);
                currentValidFrom = parseValidFrom(atts.getValue(VALID_FROM_ATTRIBUTE));
                currentProperties = new LinkedHashMap<>();
                currentLabels = null;
                currentSrc = null;
                currentDst = null;
            }
            case EDGE_DELETE_ELEMENT -> {
                currentType = atts.getValue(TYPE_ATTRIBUTE);
                currentValidFrom = parseValidFrom(atts.getValue(VALID_FROM_ATTRIBUTE));
                currentLabels = null;
                currentProperties = null;
                currentPropertyName = null;
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
            case LABEL_ELEMENT -> {
                // currentLabels is only initialised for <node> - null here means <label> is mis-nested under
                // some other element (invalid per the XSD, but SAX still fires the events if nothing upstream
                // validates it); report that clearly rather than NPE-ing.
                if (currentLabels == null) {
                    error("<label> is only valid inside a <node> element");
                } else {
                    currentLabels.add(contentBuffer.toString());
                }
            }
            case PROPERTY_ELEMENT -> {
                if (currentProperties == null) {
                    error("<property> is only valid inside a <node> or <edge> element");
                } else if (currentPropertyName == null) {
                    error("<property> requires a name attribute");
                } else {
                    currentProperties.put(currentPropertyName, contentBuffer.toString());
                }
            }
            case SRC_ELEMENT -> currentSrc = contentBuffer.toString();
            case DST_ELEMENT -> currentDst = contentBuffer.toString();
            case NODE_ELEMENT -> perRecord(NODE_ELEMENT, this::addNode);
            case NODE_DELETE_ELEMENT -> perRecord(NODE_DELETE_ELEMENT, this::deleteNode);
            case EDGE_ELEMENT -> perRecord(EDGE_ELEMENT, this::addEdge);
            case EDGE_DELETE_ELEMENT -> perRecord(EDGE_DELETE_ELEMENT, this::deleteEdge);
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

    /**
     * Runs one record's handler as a single atomic unit against {@link #writer}, isolating a store-layer failure
     * to that single record. A well-formed-XML record can still fail once it reaches the stores - a node with
     * more labels than the fixed-width encoding allows ({@code IllegalArgumentException} from
     * {@link GraphNodeDb#insert}), a property value too big for the LMDB buffer
     * ({@code BufferOverflowException}), or a pre-existing corrupt version blob surfaced by the previous-version
     * lookup. A handler such as {@link #addNode} or {@link #addEdge} performs multiple writes (a node plus its
     * property-index anchors; a dual out-edge/in-edge insert) - if a later write throws after an earlier one in
     * the same record already succeeded, {@link LmdbWriter#abort()} rolls back <i>only</i> this record's writes
     * (already-committed prior records are unaffected) rather than leaving the partial write staged to be
     * committed later. On success the record's writes are {@link LmdbWriter#commit() committed} immediately. A
     * failed record is logged and skipped rather than aborting the whole stream, exactly as
     * {@code stroom.planb.impl.pipeline.PlanBFilter.catchLmdbError} does for its own store writes. The handlers'
     * own up-front validation ({@code "<node> requires ..."}) returns normally without writing anything and never
     * reaches the catch; the subsequent no-op {@link LmdbWriter#commit()} is harmless in that case.
     */
    private void perRecord(final String element, final Runnable handler) {
        try {
            handler.run();
            writer.commit();
        } catch (final RuntimeException e) {
            writer.abort();
            log(Severity.ERROR,
                    "Failed to write <" + element + ">: " + e.getClass().getSimpleName() + " - " + e.getMessage(),
                    e);
        }
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

        // Task P8.1: the node's immediately-preceding version, if any - looked up BEFORE insert() writes this
        // version, so "at or before currentValidFrom" still resolves to the prior one. Used below to skip
        // re-indexing an anchor whose (label, value) is unchanged from it - a real write-amplification source,
        // since every version previously re-indexed every label x property pair regardless of whether the value
        // had actually changed (the common case: one field updates, the rest don't).
        final Optional<GraphNodeDb.NodeVersion> previousVersion =
                stores.getNodes().getNode(writer.getWriteTxn(), nodeUid, currentValidFrom);

        stores.getNodes().insert(writer, nodeUid, currentValidFrom, labelUids, properties);

        // Anchor-index every property against every label - there is no per-label "which properties are
        // indexable" schema yet (GraphNodeTypeMapping only maps label -> domain type, design doc &sect;5.6),
        // so indexing everything is the only sensible v1 default; a future schema-driven selection would
        // narrow this, not change the mechanism.
        for (final long labelUid : labelUids) {
            // A label the previous version didn't already carry has no pre-existing anchors under it at all -
            // every property must be (re-)indexed for it regardless of whether the value also appears,
            // unchanged, under some other label.
            final boolean labelCarriedBefore = previousVersion.isPresent()
                    && previousVersion.get().labelUids().contains(labelUid);
            for (final Map.Entry<String, String> property : currentProperties.entrySet()) {
                final Val previousValue = labelCarriedBefore
                        ? previousVersion.get().properties().get(property.getKey())
                        : null;
                if (!anchorNeedsReindexing(labelCarriedBefore, previousValue, property.getValue())) {
                    // Unchanged - the prior version's anchor for this (label, propKey, value) already points
                    // at this same nodeUid and is never deleted out from under a surviving value (only a full
                    // GraphStores.rebuild() re-derives anchors from scratch), so it still resolves correctly;
                    // re-inserting it would be a pure duplicate write.
                    continue;
                }
                final long propKeyUid = intern(stores.getPropertyKeyUids(), property.getKey());
                stores.getPropertyIndex().insert(writer, labelUid, propKeyUid,
                        property.getValue().getBytes(StandardCharsets.UTF_8), nodeUid);
            }
        }
    }

    /**
     * Task P8.1: whether a (label, property) anchor genuinely needs (re-)indexing, or whether the prior
     * version's still-valid anchor already covers it - extracted as a small, directly-testable pure function
     * since {@link GraphFilter} itself (a SAX {@code ContentHandler} wired to a real {@link GraphStores}) is
     * awkward to unit-test at this granularity in isolation.
     *
     * @param labelCarriedByPreviousVersion whether the label being indexed was already present on the node's
     *                                      immediately-preceding version - if not, that label has no
     *                                      pre-existing anchors under it at all, so re-indexing is always needed
     *                                      regardless of the value.
     * @param previousValue                 the property's value on the previous version under this same label,
     *                                      or {@code null} if the label wasn't carried before (irrelevant then)
     *                                      or the property didn't exist on it.
     * @param newValue                      never null; the value being indexed now.
     */
    static boolean anchorNeedsReindexing(final boolean labelCarriedByPreviousVersion,
                                        final @Nullable Val previousValue, final String newValue) {
        if (!labelCarriedByPreviousVersion) {
            return true;
        }
        return previousValue == null || !previousValue.toString().equals(newValue);
    }

    private void deleteNode() {
        if (currentId == null || currentValidFrom == null) {
            error("<node-delete> requires both id and validFrom");
            return;
        }
        final long nodeUid = intern(stores.getNodeUids(), currentId);
        stores.getNodes().delete(writer, nodeUid, currentValidFrom);
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
