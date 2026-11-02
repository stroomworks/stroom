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

/// One input stream a person might put through again, and the pipeline that processed it: what the
/// outputs hand back when a binding is retracted. The stage asks for none of them to be reprocessed (A129);
/// it counts them.
///
/// @param pipeline The uuid of the pipeline, or null where nothing was processing it: a harness, or the
///                 deferred worker, which runs no pipeline of its own.
public record Replayable(long inputId, String pipeline) {

}
