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

import java.util.Map;

/// The prompts that were sent to the model for a session's turns, rebuilt from the session's recorded text
/// and its input stream so that a person can inspect them. Prompts are rebuilt rather than stored so that
/// they are not a copy of the stream's data. Returned by [Stage#rebuild].
///
/// @param prompts         The prompt sent for each turn of the session's first round, keyed by turn number:
///                        the system text with the guidance it included, and the question.
/// @param withdrawn       For each turn, keyed by turn number, how many pieces of guidance it included that
///                        have since been withdrawn and so can't be shown; turns with none are absent.
/// @param olderThanBuiltIn Whether the session ran against an older version of the built-in templates, in
///                        which case the wording of the checks in the feedback may have changed since.
public record Rebuilt(Map<Integer, String> prompts, Map<Integer, Integer> withdrawn, boolean olderThanBuiltIn) {

    public Rebuilt {
        prompts = Map.copyOf(prompts);
        withdrawn = Map.copyOf(withdrawn);
    }
}
