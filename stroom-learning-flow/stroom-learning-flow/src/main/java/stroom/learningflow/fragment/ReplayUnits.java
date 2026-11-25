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

/// Which unit a chain of elements can be replayed over (A1, design 01 §4), by the one thing that
/// decides it: whether a parser stands in the chain.
///
/// Derived rather than declared, in every place it is needed — of a written fragment, when a binding names
/// one; of a document's allowed elements, which say what its stage may learn; and of a stage's position
/// in a pipeline, which says what it may host. The three must agree, and saying so is what this is for.
public final class ReplayUnits {

    private ReplayUnits() {
    }

    /// @param elementTypes The element types of a chain, in any order.
    /// @param isParser     Whether a type parses raw input into records: the element registry's
    ///                     `parser` role in a node, a [stroom.learningflow.learning.StepRunner]'s
    ///                     own answer in the module.
    /// @return [ReplayUnit#STREAM] where any of them parses, since the bytes above a parser cannot be
    /// replayed from a record; [ReplayUnit#RECORD] otherwise.
    public static ReplayUnit ofElements(final Collection<String> elementTypes,
                                        final Predicate<String> isParser) {
        return elementTypes.stream().anyMatch(isParser)
                ? ReplayUnit.STREAM
                : ReplayUnit.RECORD;
    }

    /// Whether an element type parses raw input into records, as a node's element registry has it: the
    /// registry's `parser` role.
    ///
    /// @param registry    The node's element registry.
    /// @param elementType The element type.
    /// @return Whether the registry knows the type and gives it the parser role.
    public static boolean parses(final ElementRegistry registry, final String elementType) {
        final PipelineElementType type = registry.getElementType(elementType);
        return type != null && type.hasRole(PipelineElementType.ROLE_PARSER);
    }

    /// Why a thing of one unit cannot serve a stage of another, in the words an operator needs: what the
    /// subject has or lacks, and what the stage it would serve is given.
    ///
    /// @param subject The unit of the thing at fault — the fragment being bound, or the chains a
    ///                document's allowed elements could learn.
    /// @param what    What to call it, which is what the operator has to go and change.
    public static String mismatch(final ReplayUnit subject, final String what) {
        return subject == ReplayUnit.RECORD
                ? what + " has no parser, but this stage is fed by the source and is given raw data: "
                         + "something must parse it"
                : what + " has a parser, but the document's allowed elements have none: its stage expects "
                         + "markup it does not parse";
    }
}
