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

import java.util.Map;

/// The prompts a run's turns were put to the model with, rebuilt from its recorded words over its stream
/// (decision N9): what [Stage#rebuild] returns.
///
/// @param prompts         The prompt each turn of the run's first walk was put to the model with, by its
///                        number: the system text with the guidance it carried, and the question.
/// @param withdrawn       How many notes each turn carried that have since been withdrawn and so can't be
///                        shown, by its number; turns with none are absent.
/// @param olderThanBuiltIn Whether the run was asked against an older built-in template version, so the
///                        checks' wording in the feedback may have changed since.
public record Rebuilt(Map<Integer, String> prompts, Map<Integer, Integer> withdrawn, boolean olderThanBuiltIn) {

    public Rebuilt {
        prompts = Map.copyOf(prompts);
        withdrawn = Map.copyOf(withdrawn);
    }
}
