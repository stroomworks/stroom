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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.ai.scoring.TargetSchema;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

/// What a document's target schema is (D1): the XML schema document it names, read as the group, namespace,
/// system id and version a schema filter and the questions need. A node reads the schema store; a harness with
/// no store gives every document the default, event-logging v3.0.0.
public interface TargetSchemas {

    /// The target of every document, whatever it names: for a harness with no schema store.
    TargetSchemas DEFAULT = doc -> TargetSchema.DEFAULT;

    /// The target a document's translations must conform to.
    ///
    /// @param doc The document.
    /// @return Its target.
    /// @throws IllegalArgumentException Where the document names no schema, one that can't be read, or one
    ///                                  that can't be learned yet; a configuration fault, refused before a
    ///                                  model is asked or a stream judged.
    TargetSchema of(ShapeshifterAiDoc doc);
}
