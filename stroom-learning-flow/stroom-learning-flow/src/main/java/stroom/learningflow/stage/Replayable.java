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

/// An input stream that a binding produced output for, and the pipeline that processed it. [Outputs]
/// returns these when a binding is retracted, so that the person can be told how many streams they may want
/// to reprocess. The stage does not reprocess them itself; it only counts them.
///
/// @param inputId  The id of the input stream.
/// @param pipeline The UUID of the pipeline, or null if no pipeline was processing it, i.e. a test harness,
///                 or the deferred worker, which runs no pipeline of its own.
public record Replayable(long inputId, String pipeline) {

}
