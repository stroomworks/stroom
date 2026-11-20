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

/// The flows as measured (design 02 §6.3), which the Setup tab loads into a document's steps and nothing
/// more (A34, A37) — a document owns its steps, and these are three it might start from. `DIRECT` asks for
/// the chain and then each element's configuration (A21), and is what a new document starts with.
/// `TARGET_FIRST` first settles the record boundary, then asks what one record of each kind should become,
/// and holds every configuration to those events (A31), a value the records lack sending the parser back.
/// `ESCALATING` settles the split, then goes direct and asks for a target only when the transform stays
/// short (A39, A40).
///
/// **The steps themselves are not here.** They are JSON on the server, read by `FlowExamples` (Track 2
/// step 1c), because this enum is compiled to JavaScript and the browser has neither classpath
/// resources nor Jackson. This is the identity and the name; the client is given the steps by
/// `ShapeshifterAiResource.flowExamples()`.
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
