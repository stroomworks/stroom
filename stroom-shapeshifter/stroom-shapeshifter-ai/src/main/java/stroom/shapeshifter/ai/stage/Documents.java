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

import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.Optional;

/// The Shapeshifter AI documents by uuid, as an attempt's row names one: what the worker
/// reads to learn how the attempt it is carrying on was told to learn. The document is read as it
/// stands now, not as it stood when the attempt opened, so an operator who changed it while the attempt
/// waited has changed it — and where that makes the replay put different questions, the attempt is
/// refused rather than answered from a record of something else (A45).
///
/// A node reads the document store; a scenario answers with the documents it built.
public interface Documents {

    Optional<ShapeshifterAiDoc> byUuid(String uuid);
}
