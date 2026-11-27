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

package stroom.learningflow.scoring;

import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.RecordSkeleton;
import stroom.util.xml.SaxonPolicy;
import stroom.util.xml.SaxonSecurity;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/// A step's XML output divided into records (the element children of its root, which is what a record is
/// in both `records:2` and `event-logging:3`), with XPath evaluation over each record. The scorers that
/// judge the meaning of the output (extraction quality and business rules) read it through this. Paths
/// are evaluated with the root's namespace as the default element namespace, so a document's settings can
/// say `EventSource/User/Id` without a namespace prefix on each step.
public final class OutputRecords {

    private static final Processor PROCESSOR = SaxonSecurity.newProcessor(SaxonPolicy.LOCKED);

    private final XdmNode root;
    private final List<XdmNode> records;
    private final List<XdmNode> skips;
    private final XPathCompiler compiler;

    private OutputRecords(final XdmNode root, final List<XdmNode> records, final List<XdmNode> skips) {
        this.root = root;
        this.records = records;
        this.skips = skips;
        this.compiler = PROCESSOR.newXPathCompiler();
        final String namespace = root.getNodeName().getNamespaceURI();
        if (!namespace.isEmpty()) {
            compiler.declareNamespace("", namespace);
        }
    }

    /// The records judged by the scorers of meaning: the output of a step that transformed records into
    /// records. A parser's output is records too, but they follow the input's structure rather than the
    /// target's, so the stream-level scorers judge that step and these scorers judge the steps after it.
    ///
    /// @param step The step.
    /// @return The records, or empty for a parser step or where the output does not parse (which the
    /// compile check reports).
    public static Optional<OutputRecords> ofTransformed(final Attempted step) {
        return step.parser()
                ? Optional.empty()
                : parse(step.result().output());
    }

    /// Checks that a path the document names compiles, so that a mistake in a setting is refused when the
    /// scorecard is built rather than failing every stream the stage sees.
    ///
    /// @param xpath The path.
    /// @throws IllegalArgumentException If it does not compile, naming the path and Saxon's error.
    public static void check(final String xpath) {
        try {
            PROCESSOR.newXPathCompiler().compile(xpath);
        } catch (final SaxonApiException e) {
            throw new IllegalArgumentException("XPath '" + xpath + "' does not compile: " + e.getMessage(), e);
        }
    }

    /// Parses a step's output into records, setting declared skips aside.
    ///
    /// @param output The step's output.
    /// @return The output as records, or empty where the output is not XML or does not parse.
    public static Optional<OutputRecords> parse(final String output) {
        if (output == null || !RecordSkeleton.isMarkup(output)) {
            return Optional.empty();
        }
        try {
            final XdmNode document = PROCESSOR.newDocumentBuilder().build(ConfinedXml.source(output));
            XdmNode root = null;
            for (final XdmNode child : document.children()) {
                if (child.getNodeKind() == XdmNodeKind.ELEMENT) {
                    root = child;
                }
            }
            if (root == null) {
                return Optional.empty();
            }
            final List<XdmNode> records = new ArrayList<>();
            final List<XdmNode> skips = new ArrayList<>();
            for (final XdmNode child : root.children()) {
                if (child.getNodeKind() == XdmNodeKind.ELEMENT) {
                    // A declared skip stands where an event would have been but is not one, so the scorers
                    // of meaning do not judge it.
                    if (DeclaredSkips.isMarker(child.getNodeName().getNamespaceURI(),
                            child.getNodeName().getLocalName())) {
                        skips.add(child);
                    } else {
                        records.add(child);
                    }
                }
            }
            return Optional.of(new OutputRecords(root, records, skips));
        } catch (final SaxonApiException e) {
            return Optional.empty();
        }
    }

    public List<XdmNode> records() {
        return records;
    }

    /// The records the translation declared it deliberately skipped ([DeclaredSkips]).
    ///
    /// @return The markers, in the order they appear.
    public List<XdmNode> skips() {
        return skips;
    }

    /// The reasons the translation gave for each record it skipped.
    ///
    /// @return The reasons given, in order, leaving out the markers that gave none.
    public List<String> skipReasons() {
        final List<String> reasons = new ArrayList<>();
        for (final XdmNode skip : skips) {
            final String reason = skip.getAttributeValue(new QName(DeclaredSkips.REASON));
            if (reason != null && !reason.isBlank()) {
                reasons.add(reason.strip());
            }
        }
        return reasons;
    }

    /// Finds the records a boundary identifies in this document. Depending on the boundary these are:
    ///
    /// - the outermost elements with the element's local name (one nested inside another is part of
    ///   that record);
    /// - the items of the outermost array with the key;
    /// - for [RecordBoundary#ROOT], the root's records, or, where the root holds a single array with no
    ///   key (a top-level JSON array, as the parser wraps it), that array's items;
    /// - for a children boundary, the children of every occurrence of the named container.
    ///
    /// @param boundary The record boundary.
    /// @return The records, or empty where the boundary matches nothing here.
    public List<XdmNode> recordsBy(final RecordBoundary boundary) {
        if (boundary.isChildren()) {
            return childrenOf(boundary.getChildren());
        }
        final String named = boundary.isArray()
                ? boundary.getArray()
                : boundary.getElement();
        if (named == null || !named.matches("[\\w.:-]+")) {
            // A boundary should be a name. One that is not cannot be put in a predicate safely, but the
            // stream is not failed for it: it matches nothing, and the caller falls back to the root's
            // children.
            return List.of();
        }
        if (boundary.isArray()) {
            if (RecordBoundary.ROOT.equalsIgnoreCase(boundary.getArray())) {
                if (records.size() == 1) {
                    final List<XdmNode> items = nodes(records.get(0),
                            "self::*[local-name() = 'array'][not(@key)]/*");
                    if (!items.isEmpty()) {
                        return items;
                    }
                }
                return records;
            }
            return nodes(root, "(descendant::*[local-name() = 'array'][@key = '" + named + "']"
                               + "[not(ancestor::*[local-name() = 'array'][@key = '" + named + "'])])[1]/*");
        }
        return nodes(root, "descendant::*[local-name() = '" + named + "']"
                           + "[not(ancestor::*[local-name() = '" + named + "'])]");
    }

    /// The element children of every occurrence of a named element. These are the records of a feed that
    /// mixes several kinds of record as siblings under one container element.
    ///
    /// @param parent The container's local name, or [RecordBoundary#ROOT] for the document's own root.
    /// @return The records, in document order; empty where the parent matches nothing here.
    private List<XdmNode> childrenOf(final String parent) {
        if (RecordBoundary.ROOT.equalsIgnoreCase(parent)) {
            return records;
        }
        if (parent == null || !parent.matches("[\\w.:-]+")) {
            // A boundary should be a name; one that is not cannot be put in a predicate safely.
            return List.of();
        }
        // Every occurrence of the container, not only the first, because a stream may be a sequence of
        // batches and the records of later batches count too. Nested containers of the same name belong
        // to the outermost, as with a named-element boundary.
        return nodes(root, "descendant-or-self::*[local-name() = '" + parent + "']"
                           + "[not(ancestor::*[local-name() = '" + parent + "'])]/*");
    }

    /// How deep in this document a record sits: the number of elements above it, which is the depth a
    /// `SplitFilter` is set to split at. One for the children of a root; three for the items of an array
    /// under a key, since the JSON parser wraps its output in a root element. It is measured rather than
    /// assumed, because a boundary is a name and the name says nothing about where it sits.
    ///
    /// @param boundary The record boundary.
    /// @return The depth, or empty where the boundary matches nothing here.
    public OptionalInt depthOf(final RecordBoundary boundary) {
        return recordsBy(boundary).stream()
                .findFirst()
                .map(OutputRecords::ancestors)
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }

    private static int ancestors(final XdmNode node) {
        int depth = 0;
        for (XdmNode above = node.getParent(); above != null; above = above.getParent()) {
            if (above.getNodeKind() == XdmNodeKind.ELEMENT) {
                depth++;
            }
        }
        return depth;
    }

    private List<XdmNode> nodes(final XdmNode from, final String xpath) {
        return evaluate(from, xpath).stream().map(item -> (XdmNode) item).toList();
    }

    public XdmNode root() {
        return root;
    }

    /// Evaluates a path against one record as a boolean: whether it selects anything, or is true.
    ///
    /// @param record The record.
    /// @param xpath  The path.
    /// @return The path's effective boolean value.
    /// @throws IllegalArgumentException If the path does not compile, which is a mistake in the document's
    ///                                  setting that a scorer reports rather than scores.
    public boolean holds(final XdmNode record, final String xpath) {
        try {
            final XPathSelector selector = compiler.compile(xpath).load();
            selector.setContextItem(record);
            return selector.effectiveBooleanValue();
        } catch (final SaxonApiException e) {
            throw new IllegalArgumentException("XPath '" + xpath + "' cannot be evaluated: " + e.getMessage(), e);
        }
    }

    /// Evaluates a path against one record as a value, e.g. for `count(...)`.
    ///
    /// @param record The record.
    /// @param xpath  The path.
    /// @return The value.
    /// @throws IllegalArgumentException If the path cannot be evaluated.
    public XdmValue evaluate(final XdmNode record, final String xpath) {
        try {
            final XPathSelector selector = compiler.compile(xpath).load();
            selector.setContextItem(record);
            return selector.evaluate();
        } catch (final SaxonApiException e) {
            throw new IllegalArgumentException("XPath '" + xpath + "' cannot be evaluated: " + e.getMessage(), e);
        }
    }

    public long count(final XdmNode record, final String xpath) {
        final XdmValue value = evaluate(record, "count(" + xpath + ")");
        final XdmItem item = value.itemAt(0);
        return Long.parseLong(item.getStringValue());
    }
}
