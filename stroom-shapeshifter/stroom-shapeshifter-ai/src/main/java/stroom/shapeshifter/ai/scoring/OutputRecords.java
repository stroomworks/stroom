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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.ai.stage.RecordSkeleton;
import stroom.shapeshifter.shared.RecordBoundary;

import net.sf.saxon.s9api.Processor;
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

/**
 * A step's XML output as records — the children of its root, which is what both {@code records:2} and
 * {@code event-logging:3} make of one — with XPath over each. The scorers that judge the meaning of the
 * output (design 01 §8.4: extraction quality, business rules) read it through this. Paths are evaluated
 * with the root's namespace as the default element namespace, so a document writes
 * {@code EventSource/User/Id}, not a prefix per step.
 */
public final class OutputRecords {

    private static final Processor PROCESSOR = new Processor(false);

    private final XdmNode root;
    private final List<XdmNode> records;
    private final XPathCompiler compiler;

    private OutputRecords(final XdmNode root, final List<XdmNode> records) {
        this.root = root;
        this.records = records;
        this.compiler = PROCESSOR.newXPathCompiler();
        final String namespace = root.getNodeName().getNamespaceURI();
        if (!namespace.isEmpty()) {
            compiler.declareNamespace("", namespace);
        }
    }

    /**
     * The records a scorer of meaning judges: the output of a step that transformed records into records.
     * A parser's output is records too, but of the input's shape rather than the target's; design 01 §4
     * has the stream-level scorers judge that step, and these scorers judge what follows it. Empty where
     * the output does not parse, which the compile gate is there to say.
     */
    public static Optional<OutputRecords> ofTransformed(final Attempted step) {
        return step.parser()
                ? Optional.empty()
                : parse(step.result().output());
    }

    /**
     * Whether a path the document names can be compiled at all, so that a fault in a setting is refused
     * when the scorecard is built rather than failing every stream the stage sees.
     *
     * @throws IllegalArgumentException Naming the path and Saxon's complaint.
     */
    public static void check(final String xpath) {
        try {
            PROCESSOR.newXPathCompiler().compile(xpath);
        } catch (final SaxonApiException e) {
            throw new IllegalArgumentException("XPath '" + xpath + "' does not compile: " + e.getMessage(), e);
        }
    }

    /**
     * @return The output as records, or empty where the output is not markup or does not parse.
     */
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
            for (final XdmNode child : root.children()) {
                if (child.getNodeKind() == XdmNodeKind.ELEMENT) {
                    records.add(child);
                }
            }
            return Optional.of(new OutputRecords(root, records));
        } catch (final SaxonApiException e) {
            return Optional.empty();
        }
    }

    public List<XdmNode> records() {
        return records;
    }

    /**
     * The records a boundary names in this document (A35): the outermost elements of the element's local
     * name — one inside another is part of that record — or the items of the outermost array with the key,
     * or for {@link RecordBoundary#ROOT} the root's records, where the root holds one keyless array — a
     * top-level JSON array, as the parser wraps it — that array's items. Empty where the boundary names
     * nothing here.
     */
    public List<XdmNode> recordsBy(final RecordBoundary boundary) {
        final String named = boundary.isArray()
                ? boundary.getArray()
                : boundary.getElement();
        if (named == null || !named.matches("[\\w.:-]+")) {
            // A boundary is a name. One that is not cannot be put in a predicate safely, and a stream is not
            // failed for it: it names nothing here, and the caller falls back to the root's children.
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

    /// How deep in this document one record sits: the number of elements above it, which is what a
    /// `SplitFilter` is set to split at (§12 item 25). One for the children of a root; three for the items
    /// of an array under a key, since the JSON parser wraps its output in a records root. Read rather than
    /// assumed, because a boundary is a name and the name says nothing about where it sits. Empty where
    /// the boundary names nothing here.
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

    /**
     * The effective boolean value of a path against one record: whether it selects anything, or is true.
     *
     * @throws IllegalArgumentException If the path does not compile: a fault in the document's setting,
     *                                  which a scorer reports rather than scores.
     */
    public boolean holds(final XdmNode record, final String xpath) {
        try {
            final XPathSelector selector = compiler.compile(xpath).load();
            selector.setContextItem(record);
            return selector.effectiveBooleanValue();
        } catch (final SaxonApiException e) {
            throw new IllegalArgumentException("XPath '" + xpath + "' cannot be evaluated: " + e.getMessage(), e);
        }
    }

    /**
     * A path against one record, as a value; {@code count(...)} and the like.
     */
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
