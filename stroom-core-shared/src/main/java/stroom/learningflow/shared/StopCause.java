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

/// Why a learning run stopped short of a translation, so that a screen can offer the fix that fits without
/// reading the words of its [StopReport].
public enum StopCause {
    /// A step ran out of tries: every answer to it fell short.
    STEP_SPENT,
    /// A step's transition for the answer's outcome said to leave the flow.
    STEP_RULE,
    /// A step's element failed on its input, with nothing to ask the model.
    STEP_FAILED,
    /// A person chose Finish with Unknowns, but the flow has no way to the fallback.
    FALLBACK_MISSING,
    /// The run took longer than its time for each run.
    TIME_BUDGET,
    /// The run spent more tokens than it may.
    TOKEN_BUDGET,
    /// The model didn't reply in time.
    MODEL_TIMEOUT,
    /// The model couldn't be reached.
    MODEL_UNREACHABLE,
    /// The model refused the request: its key, its name, or its limits.
    MODEL_REFUSED,
    /// The document names no model.
    NO_MODEL,
    /// The document's setup can't be learned with: its element types or its steps.
    SETUP,
    /// Anything else.
    OTHER
}
