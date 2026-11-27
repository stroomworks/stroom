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

package stroom.learningflow.fragment;

import stroom.learningflow.shared.ReplayUnit;
import stroom.pipeline.factory.ElementRegistry;
import stroom.pipeline.shared.data.PipelineElementType;

import java.util.Collection;
import java.util.function.Predicate;

/// Works out which unit (a whole stream or a single record) a chain of elements can be replayed over,
/// from the one thing that decides it: whether the chain contains a parser.
///
/// The unit is derived rather than declared wherever it is needed, so that every place that needs it
/// gets the same answer. It is used to check that the chains a Learning Flow document's allowed elements
/// can make are able to parse the raw stream the Smart Pipeline element is given.
public final class ReplayUnits {

    private ReplayUnits() {
    }

    /// Works out the replay unit of a chain of element types.
    ///
    /// @param elementTypes The element types of a chain, in any order.
    /// @param isParser     Whether a type parses raw input into records: on a node, whether the element
    ///                     registry gives it the `parser` role; in module tests, the
    ///                     `stroom.learningflow.learning.StepRunner`'s own answer.
    /// @return [ReplayUnit#STREAM] where any of them parses, since the raw bytes a parser reads cannot be
    /// replayed from a record; [ReplayUnit#RECORD] otherwise.
    public static ReplayUnit ofElements(final Collection<String> elementTypes,
                                        final Predicate<String> isParser) {
        return elementTypes.stream().anyMatch(isParser)
                ? ReplayUnit.STREAM
                : ReplayUnit.RECORD;
    }

    /// Whether an element type parses raw input into records, according to a node's element registry,
    /// i.e. whether the registry gives it the `parser` role.
    ///
    /// @param registry    The node's element registry.
    /// @param elementType The element type.
    /// @return Whether the registry knows the type and gives it the parser role.
    public static boolean parses(final ElementRegistry registry, final String elementType) {
        final PipelineElementType type = registry.getElementType(elementType);
        return type != null && type.hasRole(PipelineElementType.ROLE_PARSER);
    }

    /// Builds the message explaining why something with one replay unit cannot serve a stage that needs the
    /// other, in terms an operator can act on: what the subject has or lacks, and what the stage is given.
    ///
    /// @param subject The replay unit of the thing at fault, e.g. the chains a document's allowed elements
    ///                can make.
    /// @param what    The name to use for the thing at fault, which is what the operator has to change.
    /// @return The message.
    public static String mismatch(final ReplayUnit subject, final String what) {
        return subject == ReplayUnit.RECORD
                ? what + " has no parser, but this stage is fed by the source and is given raw data: "
                         + "something must parse it"
                : what + " has a parser, but the document's allowed elements have none: its stage expects "
                         + "markup it does not parse";
    }
}
