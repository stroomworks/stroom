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

package stroom.learningflow.stage;

import stroom.learningflow.shared.LearningFlowDoc;

import java.util.Optional;

/// Looks up Learning Flow documents by UUID, so that the deferred worker can read the settings a session it
/// is resuming should learn under.
///
/// The document is read as it is now, not as it was when the session opened, so a change an operator made
/// while the session waited takes effect. If the change means that replaying the session's recorded turns
/// would ask different questions, the session is refused rather than resumed from answers to questions that
/// would no longer be asked.
///
/// A node reads the document store; tests use the documents they built.
public interface Documents {

    Optional<LearningFlowDoc> byUuid(String uuid);
}
