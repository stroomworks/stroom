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

package stroom.shapeshifter.shared;

import stroom.docref.HasDisplayValue;

import java.util.List;

/// The flows as measured (design 02 §6.3), which the Learning tab loads into a document's steps and nothing
/// more (A34, A37) — a document owns its steps, and these are three it might start from. `DIRECT` asks for
/// the chain and then each element's configuration (A21), and is what a new document starts with.
/// `TARGET_FIRST` first settles the record boundary, then asks what one record of each kind should become,
/// and holds every configuration to those events (A31), a value the records lack sending the parser back.
/// `ESCALATING` settles the split, then goes direct and asks for a target only when the transform stays
/// short (A39, A40).
public enum FlowExample implements HasDisplayValue {
    DIRECT("Direct", List.of(
            FlowStep.parse("CHAIN"),
            FlowStep.parse("CONFIGURE parser"),
            FlowStep.parse("CONFIGURE transform on spent goto admit"),
            // The exhaustion step (design 04 §4): reached only when the transform's candidates are spent
            // with records of the held stream still short, and the one step that may ask for the admitted
            // form (A53).
            FlowStep.parse("ADMIT"))),
    TARGET_FIRST("Target first", List.of(
            FlowStep.parse("CHAIN"),
            FlowStep.parse("SPLIT"),
            FlowStep.parse("TARGET kinds 3"),
            FlowStep.parse("CONFIGURE parser"),
            FlowStep.parse("CONFIGURE transform on preservation-short goto parser on spent goto admit"),
            FlowStep.parse("ADMIT"))),
    ESCALATING("Escalating", List.of(
            FlowStep.parse("CHAIN"),
            // The split is asked of every input (A39): what one record is costs one question and is what
            // the count, the target and yield rest on; a parser refused on yield — a multi-line record it
            // cut per line — goes back to it rather than trying again blind (A40).
            FlowStep.parse("SPLIT"),
            FlowStep.parse("CONFIGURE parser on yield-short goto split"),
            FlowStep.parse("first: CONFIGURE transform candidates 2 on passed goto end on spent goto target"),
            FlowStep.parse("TARGET kinds 3"),
            FlowStep.parse("again: CONFIGURE parser"),
            FlowStep.parse("CONFIGURE transform on preservation-short goto again on spent goto admit"),
            FlowStep.parse("ADMIT")));

    private final String displayValue;
    private final List<FlowStep> steps;

    FlowExample(final String displayValue, final List<FlowStep> steps) {
        this.displayValue = displayValue;
        this.steps = steps;
    }

    /// The example's steps, in order: what a document loading it gets as its own.
    public List<FlowStep> steps() {
        return steps;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
