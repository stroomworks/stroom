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

package stroom.shapeshifter.shared;

import stroom.docref.HasDisplayValue;

/**
 * The four typed questions a flow is made of (design 01 §10.2): each is a reply grammar and a judge, and
 * they are the vocabulary a learning flow's steps are written in. A new kind is new code.
 */
public enum QuestionKind implements HasDisplayValue {
    /**
     * Which elements, in order: names joined by {@code ->}, checked against the allowed elements.
     */
    CHAIN("Chain"),
    /**
     * What one record is (A31): for raw text a parser configuration that cuts the sample into whole
     * records; for input that is already XML the element that is one record (A35).
     */
    SPLIT("Split"),
    /**
     * What one kind of record should become: an event, or the word none (A31).
     */
    TARGET("Target"),
    /**
     * Each element's configuration in turn, judged by the scorecard and held to the targets.
     */
    CONFIGURE("Configure"),
    /**
     * The exhaustion step (design 04 §4, A51, A53): the transform's configuration once more, for a
     * session whose candidates are spent with records of the held stream still short — this time with a
     * match for each record type it cannot translate whose output is the admitted form, an
     * {@code EventDetail/Unknown} carrying the marker of {@link LearnedUnknown}. The only step that may
     * ask for that form, and reached only by a transition from a spent step.
     */
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
