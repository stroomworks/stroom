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

/// Describes the structure of a target schema in text for the model's prompts, for any schema other than
/// event-logging.
///
/// Event-logging has hand-written guidance (which branch means what, which element order the validator
/// enforces, what to avoid), which is part of the built-in templates. Every other schema is described from
/// its own XSD, using the same content-model reading the conformance check uses for its feedback: the root,
/// what it holds, and what those hold, a level at a time outwards from the root until the size budget runs
/// out.
///
/// The description is a starting point, not a specification. The model still learns the rest from what the
/// validator says when a candidate falls short. The description saves the first few candidates from being
/// spent discovering basic facts such as the name of the root element.
public final class TargetStructure {

    /// The maximum number of characters of schema description included in every question. A large schema
    /// described in full would crowd out the sample and the feedback, which matter more to the candidate.
    static final int BUDGET = 2_000;
    /// The maximum number of description lines, whatever the character budget; this many is enough to show
    /// the model the overall structure.
    static final int LINES = 12;

    private TargetStructure() {
    }

    /// Returns the passage of prompt text describing a target schema.
    ///
    /// @param target The schema. Never event-logging, which has its own built-in text, and never null.
    /// @return The passage, or a short sentence saying so where the XSD gives nothing to describe.
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

    /// Returns the element one record becomes, where the schema makes it clear: the root's only child. Each
    /// record is a child of the root, so a root that may hold only one kind of element identifies it; a root
    /// that may hold several, or a schema that declares no root, does not.
    ///
    /// @param target The schema. Never event-logging, whose record is `Event`, and never null.
    /// @return The element's local name, or empty where the schema doesn't make it plain.
    public static Optional<String> recordElement(final TargetSchema target) {
        final List<String> children = rootChildren(target);
        return children.size() == 1
                ? Optional.of(children.getFirst())
                : Optional.empty();
    }

    /// Returns the elements a schema's root may hold. Every child of the root is treated as one record, so a
    /// root that may hold more than one kind of element, such as a header beside its records, would have the
    /// header counted as a record; the Setup tab warns about this.
    ///
    /// @param target The schema, never null.
    /// @return Their names, in the order the XSD gives them; empty where the XSD declares no root, or the
    ///         root holds no elements.
    public static List<String> rootChildren(final TargetSchema target) {
        final String root = target.rootElement();
        if (root == null || target.text() == null) {
            return List.of();
        }
        return new ContentModels(List.of(target.text())).childrenOf(root);
    }

    // Describes the content models from the root outwards, breadth first, until the character budget or the
    // line limit runs out. Breadth first, because a candidate has to get the elements nearest the root right
    // before anything deeper matters.
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
