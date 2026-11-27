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

package stroom.learningflow.shared;

import stroom.docref.HasDisplayValue;

import java.util.List;

/// The example learning flows that the Setup tab can load into a document's steps. Loading one only copies its
/// steps; the document owns its steps from then on, and these are starting points.
///
/// - `DIRECT` asks for the chain and then each element's configuration, and is what a new document starts
///   with.
/// - `TARGET_FIRST` first settles the record boundary, then asks what one record of each kind should become,
///   and holds every configuration to those target events; a value the records lack sends the session back
///   to the parser.
/// - `ESCALATING` settles the split, then asks directly for the configurations, and asks for a target only
///   when the transform keeps falling short.
///
/// The steps themselves are not defined here. They are JSON resources on the server, read by `FlowExamples`,
/// because this enum is compiled to JavaScript and the browser has neither classpath resources nor Jackson.
/// This enum holds only the identity and the name; the client gets the steps from
/// `LearningFlowResource.flowExamples()`.
public enum FlowExample implements HasDisplayValue {
    DIRECT("Direct"),
    TARGET_FIRST("Target first"),
    ESCALATING("Escalating");

    private final String displayValue;

    FlowExample(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
