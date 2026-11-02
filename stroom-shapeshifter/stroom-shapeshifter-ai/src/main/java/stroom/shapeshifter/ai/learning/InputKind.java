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

package stroom.shapeshifter.ai.learning;

/// What the stage's input is, as the flow's guards read it and the split question is put (A31, A35): raw
/// text a parser with a configuration cuts; XML that is already records; or JSON a run-only parser turns into
/// records. Settled by the chain: its first element says which.
public enum InputKind {
    TEXT,
    XML,
    JSON
}
