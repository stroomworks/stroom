/*
 * Copyright 2026 Crown Copyright
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

package stroom.learningflow.learning;

import stroom.learningflow.scoring.ContentModels;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// What a schema says a document of it holds, written for the model (task 7, decision D2 option G).
///
/// Event-logging has guidance written by hand — which branch means what, which element order the
/// validator enforces, what not to reach for — and that stays its built-in text. Every other schema is
/// described from its own XSD, by the same content-model reading the conformance check already uses for
/// its feedback: the root, what it holds, and what those hold, a level at a time outwards from the root
/// until the budget runs out.
///
/// It is a starting point, not a specification. A model given it still learns the rest from what the
/// validator says when a candidate falls short, which is the loop the whole stage is built on; the
/// summary's job is to stop the first few attempts being spent discovering that the root is called
/// `Sightings`.
public final class TargetStructure {

    /// How much of a schema is worth carrying in every question. A large schema described in full would
    /// crowd out the sample and the feedback, which are what a candidate is actually judged on.
    static final int BUDGET = 2_000;
    /// However much room there is, a model shown this many lines has the shape of the thing.
    static final int LINES = 12;

    private TargetStructure() {
    }

    /// The passage describing a target schema.
    ///
    /// @param target The schema. Never event-logging, which has its own built-in text, and never null.
    /// @return The passage, or a short honest sentence where the XSD gives nothing to describe.
    public static String of(final TargetSchema target) {
        final String root = target.rootElement();
        if (root == null) {
            return "The schema at " + target.systemId() + " declares no root element, so write what it "
                   + "allows and read the validator's reply.";
        }
        final List<String> lines = describe(new ContentModels(List.of(target.text())), root);
        if (lines.isEmpty()) {
            return "holding one record's worth of " + target.namespaceURI() + " under <" + root + ">.";
        }
        final StringBuilder text = new StringBuilder("holding one record's worth under <")
                .append(root).append(">. The schema says:");
        lines.forEach(line -> text.append("\n- ").append(line));
        return text.append("\nNotation: ").append(ContentModels.legend())
                .append("\nWhere the schema allows an element this does not describe, write it and let the "
                        + "validator say whether it fits.")
                .toString();
    }

    /// The element one record becomes, where the schema makes it plain: the root's only child (task 7,
    /// W1-1). A record is a child of the root (W1-5), so a root that may hold only one kind of element
    /// names it; a root holding several, or a schema declaring no root, does not.
    ///
    /// @param target The schema. Never event-logging, whose record is `Event`, and never null.
    /// @return The element's local name, or empty where the schema doesn't make it plain.
    public static Optional<String> recordElement(final TargetSchema target) {
        final List<String> children = rootChildren(target);
        return children.size() == 1
                ? Optional.of(children.getFirst())
                : Optional.empty();
    }

    /// The elements a schema's root may hold (task 7, W1-5). Every child of the root is read as one record,
    /// so a root that may hold more than one kind of element, such as a header beside its records, would
    /// have the header counted as a record; the Setup tab warns of it.
    ///
    /// @param target The schema, never null.
    /// @return Their names, in the order the XSD gives them; empty where the XSD declares no root, or it
    ///         holds no elements.
    public static List<String> rootChildren(final TargetSchema target) {
        final String root = target.rootElement();
        if (root == null || target.text() == null) {
            return List.of();
        }
        return new ContentModels(List.of(target.text())).childrenOf(root);
    }

    /// The content models from the root outwards, broadest first, until the budget or the line count
    /// runs out. Breadth first rather than depth first because the elements nearest the root are the
    /// ones a candidate has to get right before anything deeper matters.
    private static List<String> describe(final ContentModels models, final String root) {
        final List<String> lines = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        final Deque<String> queue = new ArrayDeque<>();
        queue.add(root);
        seen.add(root);
        int spent = 0;
        while (!queue.isEmpty() && lines.size() < LINES && spent < BUDGET) {
            final String name = queue.removeFirst();
            for (final String described : models.describe(name)) {
                if (lines.size() >= LINES || spent + described.length() > BUDGET) {
                    break;
                }
                lines.add(described);
                spent += described.length();
            }
            for (final String child : models.childrenOf(name)) {
                if (seen.add(child)) {
                    queue.addLast(child);
                }
            }
        }
        return lines;
    }
}
