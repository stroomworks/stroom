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

package stroom.learningflow.shared;

import stroom.docref.HasDisplayValue;

/// The kinds of question a learning flow's steps can ask. Each kind has its own expected reply format and its
/// own way of judging a reply, both in server code, so adding a kind means writing new code.
public enum QuestionKind implements HasDisplayValue {
    /// Which elements make up the chain, in order: element type names joined by `->`, checked against the
    /// document's allowed elements.
    CHAIN("Chain"),
    /// What one record is. For raw text, this is a parser configuration that cuts the sample into whole
    /// records; for input that is already XML or JSON, it is the element that holds one record.
    SPLIT("Split"),
    /// What one kind of record should become: an example target event, or the word `none`.
    TARGET("Target"),
    /// Each element's configuration in turn, judged by the document's checks and held to the targets.
    CONFIGURE("Configure"),
    /// The fallback (exhaustion) step: the transform's configuration once more, for a session whose
    /// candidates are spent while some records of the held stream still fall short. This time the model may
    /// write, for each record type it cannot translate, an `EventDetail/Unknown` carrying the
    /// [LearnedUnknown] marker. It is the only step that may ask for that output, and it is reached only by a
    /// transition from another step or by a person sending a parked session to it, never by the step above
    /// it passing.
    ADMIT("Admit");

    private final String displayValue;

    QuestionKind(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
