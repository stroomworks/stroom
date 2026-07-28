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
import stroom.graphdb.impl.GraphAnchorEncoding;
import stroom.graphdb.impl.GraphDbDocCache;
import stroom.graphdb.impl.GraphNodeDb;
import stroom.graphdb.impl.GraphShardWriters;
import stroom.graphdb.impl.GraphShardWriters.GraphShardWriter;
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
import stroom.pipeline.state.MetaHolder;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValBoolean;
import stroom.query.language.functions.ValLong;
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
import java.util.Set;

/**
 * Parses {@code graph-mutation:1} XML and writes node/edge mutations into one target {@link GraphDbDoc} - the
 * graph analogue of {@code stroom.planb.impl.pipeline.PlanBFilter}, differing in that this filter resolves its
 * <b>single</b> target doc via a {@link PipelineProperty}/{@link DocRef} (like
 * {@code stroom.index.impl.DynamicIndexingFilter} resolves its index) rather than per-record from an in-XML map
 * name.
 *
 * <p>Writes are not made into the graph's own store. {@link #startProcessing()} asks
 * {@link GraphShardWriters} for a fragment - a complete but empty graph store holding nothing but this stream's
 * mutations - and {@link #endProcessing()} closes it, which ships it to every node that holds graph data to be
 * merged. Writing directly into the live store, as this filter originally did, made the graph on each node
 * consist only of the streams that node happened to process, so every query silently returned a partial answer;
 * routing through a fragment is what removes that.</p>
 *
 * <p>Holds one {@link LmdbWriter} open across the whole stream (obtained in {@link #startProcessing()}, closed in
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
 * <p>What happens to a bad record depends on {@link #setStrict(boolean) strict}. Either way the failure is
 * detected in the same places: a malformed record (a missing required attribute or child, an unparsable
 * {@code validFrom}, an element the vocabulary does not define - all caught before any store write) and a record
 * that reaches the store layer and fails there (a node with more labels than the store can encode, a property
 * value too large for the LMDB buffer, a corrupt existing version blob), the latter because every per-record
 * store mutation runs under {@link #perRecord}, the analogue of {@code PlanBFilter.catchLmdbError}.</p>
 *
 * <p><b>Lenient</b> (the default, and {@code PlanBFilter}'s behaviour) logs the record at {@code ERROR} and
 * carries on, so one bad record cannot cost a whole stream. <b>Strict</b> reports at {@code FATAL_ERROR} and
 * fails the stream. The choice is a real one rather than a preference: lenient loses data quietly, so a graph
 * can look healthy while missing a subset of its input, whereas strict cannot lose data silently but lets a
 * single bad record block a feed. Strict is the right default for a graph whose completeness is load-bearing.</p>
 *
 * <p>Strict mode is implemented in {@link #error} rather than at each validation site: {@link #error} throws, so
 * the sites that would otherwise report and return carry on failing the stream without needing to know about the
 * setting. {@link #perRecord} rethrows that exception rather than treating it as a record-level failure, having
 * first rolled back the record's partial writes.</p>
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

    private static final String GRAPH_ELEMENT = "graph";

    /**
     * Every element name the vocabulary defines. Anything else is a typo or a vocabulary mismatch, and is
     * reported rather than ignored - a misspelled element used to contribute nothing and say nothing.
     */
    private static final Set<String> KNOWN_ELEMENTS = Set.of(
            GRAPH_ELEMENT,
            NODE_ELEMENT,
            NODE_DELETE_ELEMENT,
            EDGE_ELEMENT,
            EDGE_DELETE_ELEMENT,
            LABEL_ELEMENT,
            PROPERTY_ELEMENT,
            SRC_ELEMENT,
            DST_ELEMENT);

    /** The {@code <property type="...">} values the vocabulary allows. Absent means {@link #TYPE_STRING}. */
    private static final String TYPE_STRING = "string";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_BOOLEAN = "boolean";

    private static final String ID_ATTRIBUTE = "id";
    private static final String TYPE_ATTRIBUTE = "type";
    private static final String VALID_FROM_ATTRIBUTE = "validFrom";
    private static final String NAME_ATTRIBUTE = "name";

    private final ErrorReceiverProxy errorReceiverProxy;
    private final LocationFactoryProxy locationFactory;
    private final GraphDbDocCache graphDbDocCache;
    private final GraphShardWriters graphShardWriters;
    private final MetaHolder metaHolder;

    private final CharBuffer contentBuffer = new CharBuffer(32);

    private DocRef graphDbRef;
    private boolean strict;
    private GraphShardWriter shardWriter;
    private GraphStores stores;
    private LmdbWriter writer;
    private Locator locator;

    // Accumulated per-record state, valid only between a node/edge/node-delete/edge-delete's startElement and
    // its matching endElement - reset (implicitly, by re-assignment) at each record's startElement.
    private String currentId;
    private String currentType;
    private Instant currentValidFrom;
    private List<String> currentLabels;
    private Map<String, TypedText> currentProperties;
    private String currentPropertyName;
    private String currentPropertyType;
    private String currentSrc;
    private String currentDst;

    @Inject
    public GraphFilter(final ErrorReceiverProxy errorReceiverProxy,
                       final LocationFactoryProxy locationFactory,
                       final GraphDbDocCache graphDbDocCache,
                       final GraphShardWriters graphShardWriters,
                       final MetaHolder metaHolder) {
        this.errorReceiverProxy = errorReceiverProxy;
        this.locationFactory = locationFactory;
        this.graphDbDocCache = graphDbDocCache;
        this.graphShardWriters = graphShardWriters;
        this.metaHolder = metaHolder;
    }

    @PipelineProperty(description = "The graph to write node/edge mutations into.", displayPriority = 1)
    @PipelinePropertyDocRef(types = GraphDbDoc.TYPE)
    public void setGraphDb(final DocRef graphDbRef) {
        this.graphDbRef = graphDbRef;
    }

    @PipelineProperty(
            description = "Fail the whole stream if any record is bad, instead of logging it and carrying on. " +
                          "Leave this off and a malformed record is skipped, so a graph can look healthy while " +
                          "silently missing part of its input. Turn it on where the graph has to be complete or " +
                          "known to be broken.",
            defaultValue = "false",
            displayPriority = 2)
    public void setStrict(final boolean strict) {
        this.strict = strict;
    }

    @Override
    public void startProcessing() {
        try {
            if (graphDbRef == null) {
                log(Severity.FATAL_ERROR, "Graph DB has not been set", null);
                throw LoggedException.create("Graph DB has not been set");
            }
            // Resolved by UUID, not name. A pipeline property is long-lived configuration, so a graph renamed
            // after the pipeline was built would otherwise stop resolving with no warning until the next stream
            // ran - and two graphs sharing a name failed outright. The name is used only if the reference somehow
            // carries no UUID, which a document picker always supplies.
            final GraphDbDoc doc = graphDbRef.getUuid() != null
                    ? graphDbDocCache.getByUuid(graphDbRef.getUuid())
                    : graphDbDocCache.get(graphDbRef.getName());
            if (doc == null) {
                log(Severity.FATAL_ERROR, "Unable to load graph db " + graphDbRef, null);
                throw LoggedException.create("Unable to load graph db " + graphDbRef);
            }
            // Write into a fragment of this stream's own rather than into the live store, so the mutations can be
            // shipped to every node that holds graph data and merged there.
            shardWriter = graphShardWriters.createWriter(metaHolder.getMeta(), doc);
            stores = shardWriter.getStores();
            writer = shardWriter.getWriter();
        } finally {
            super.startProcessing();
        }
    }

    @Override
    public void endProcessing() {
        try {
            if (shardWriter != null) {
                // Closes the writer and environment, then sends the fragment for merging.
                shardWriter.close();
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
                currentPropertyType = null;
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
                currentPropertyType = null;
                currentSrc = null;
                currentDst = null;
            }
            case PROPERTY_ELEMENT -> {
                currentPropertyName = atts.getValue(NAME_ATTRIBUTE);
                currentPropertyType = atts.getValue(TYPE_ATTRIBUTE);
            }
            default -> checkKnownElement(localName);
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
                    currentProperties.put(
                            currentPropertyName,
                            new TypedText(currentPropertyType, contentBuffer.toString()));
                }
            }
            case SRC_ELEMENT -> currentSrc = contentBuffer.toString();
            case DST_ELEMENT -> currentDst = contentBuffer.toString();
            case NODE_ELEMENT -> perRecord(NODE_ELEMENT, this::addNode);
            case NODE_DELETE_ELEMENT -> perRecord(NODE_DELETE_ELEMENT, this::deleteNode);
            case EDGE_ELEMENT -> perRecord(EDGE_ELEMENT, this::addEdge);
            case EDGE_DELETE_ELEMENT -> perRecord(EDGE_DELETE_ELEMENT, this::deleteEdge);
            default -> {
                // Already reported by startElement's own default branch, so say nothing a second time.
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
            shardWriter.markDirty();
        } catch (final LoggedException e) {
            // Strict mode: a handler's own validation has already reported this at FATAL_ERROR. Roll back the
            // record's partial writes, then let it fail the stream.
            writer.abort();
            throw e;
        } catch (final RuntimeException e) {
            writer.abort();
            final String message =
                    "Failed to write <" + element + ">: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            if (strict) {
                log(Severity.FATAL_ERROR, message, e);
                throw LoggedException.create(message);
            }
            log(Severity.ERROR, message, e);
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
            for (final Map.Entry<String, Val> property : properties.entrySet()) {
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
                        GraphAnchorEncoding.anchorValueBytes(property.getValue()), nodeUid);
                // Note the anchor is derived from the decoded value, not from the raw XML text. For an untyped
                // (string) property the two are identical, but for a typed one only the decoded form is
                // reproducible by merge - which only ever sees decoded values - so anchoring on raw text would
                // make a merged graph answer property lookups differently from a directly-ingested one.
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
                                        final @Nullable Val previousValue, final Val newValue) {
        if (!labelCarriedByPreviousVersion) {
            return true;
        }
        // Compared on rendered form because that - not the value's type - is what the anchor key is made of. A
        // property retyped from string "42" to long 42 keys the same anchor, so it genuinely needs no rewrite.
        return previousValue == null || !previousValue.toString().equals(newValue.toString());
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

    /**
     * Converts a record's accumulated property text into typed values.
     *
     * <p>An absent or {@code string} type stays a {@link ValString}, which is what every property used to be. A
     * declared type is parsed, and a value that does not parse is reported as a bad record rather than silently
     * falling back to text - a property that was meant to be a number and quietly became a string would order and
     * compare lexically, which is precisely the surprise typing exists to remove.</p>
     *
     * <p><b>Preconditions:</b> {@code properties} is not null.
     * <b>Postconditions:</b> returns one entry per input entry, unless {@link #error} threw.
     * <b>Null status:</b> {@code properties} is not nullable; the return value is never null.
     *
     * @param properties the record's accumulated properties.
     * @return the typed values, in input order.
     */
    private Map<String, Val> toVals(final Map<String, TypedText> properties) {
        final Map<String, Val> vals = new LinkedHashMap<>(properties.size());
        for (final Map.Entry<String, TypedText> property : properties.entrySet()) {
            vals.put(property.getKey(), toVal(property.getKey(), property.getValue()));
        }
        return vals;
    }

    private Val toVal(final String name, final TypedText typedText) {
        final String text = typedText.text();
        final String type = typedText.type() == null
                ? TYPE_STRING
                : typedText.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case TYPE_STRING -> {
                return ValString.create(text);
            }
            case TYPE_LONG -> {
                try {
                    return ValLong.create(Long.parseLong(text.trim()));
                } catch (final NumberFormatException e) {
                    error("<property name=\"" + name + "\" type=\"long\"> value \"" + text
                          + "\" is not a whole number");
                    return ValString.create(text);
                }
            }
            case TYPE_BOOLEAN -> {
                final String trimmed = text.trim();
                // XML Schema's boolean lexical space, which is what the shipped XSD constrains this to.
                if ("true".equals(trimmed) || "1".equals(trimmed)) {
                    return ValBoolean.create(true);
                }
                if ("false".equals(trimmed) || "0".equals(trimmed)) {
                    return ValBoolean.create(false);
                }
                error("<property name=\"" + name + "\" type=\"boolean\"> value \"" + text
                      + "\" is not true or false");
                return ValString.create(text);
            }
            default -> {
                error("<property name=\"" + name + "\"> has unknown type \"" + typedText.type() + "\"");
                return ValString.create(text);
            }
        }
    }

    /**
     * A property's declared type and its text, as they appeared. Kept together so the conversion happens once, at
     * the point the record is written, rather than being spread over the SAX callbacks.
     *
     * @param type the {@code type} attribute's value, or null if it was absent.
     * @param text the element's text content.
     */
    private record TypedText(@Nullable String type, String text) {

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

    /**
     * Reports an element the vocabulary does not define.
     *
     * <p>Only element names are checked, not their nesting - nesting is the XSD's job, and
     * {@code graph-mutation:1} is now shipped as a schema document so a {@code SchemaFilter} upstream can
     * enforce it. What this catches is the case a schema cannot: a pipeline with no validation in front of it,
     * where a misspelled element contributed nothing and reported nothing.</p>
     *
     * <p><b>Postconditions:</b> a message has been reported if {@code localName} is not a known element; in
     * strict mode a {@link LoggedException} has also been thrown.
     * <b>Null status:</b> {@code localName} is not nullable.
     *
     * @param localName the element name as it appeared, before case folding.
     */
    private void checkKnownElement(final String localName) {
        if (!KNOWN_ELEMENTS.contains(localName.toLowerCase(Locale.ROOT))) {
            error("<" + localName + "> is not a graph-mutation element");
        }
    }

    /**
     * Reports a bad record. In strict mode this <b>throws</b>, so every caller that would otherwise have
     * returned normally and carried on now fails the stream instead - which is why the individual validation
     * sites need no strict-mode handling of their own.
     *
     * <p><b>Postconditions:</b> the message has been reported; in strict mode a {@link LoggedException} has been
     * thrown.
     * <b>Null status:</b> {@code message} is not nullable.
     *
     * @param message what was wrong with the record.
     */
    private void error(final String message) {
        if (strict) {
            log(Severity.FATAL_ERROR, message, null);
            throw LoggedException.create(message);
        }
        log(Severity.ERROR, message, null);
    }

    private void log(final Severity severity, final String message, final Exception e) {
        errorReceiverProxy.log(severity, locationFactory.create(locator), getElementId(), message, e);
    }
}
