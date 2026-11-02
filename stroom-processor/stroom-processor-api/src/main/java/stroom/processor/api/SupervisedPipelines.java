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

package stroom.processor.api;

import stroom.docref.DocRef;

/// Says whether a pipeline is one that may defer its tasks — one with a supervised stage in it — which
/// a node processes correctly only when it claims its own tasks (`stroom.processor.claimTasksOnWorker`):
/// a deferred task is woken by a row the worker writes, and a master-fed queue would not see it. Bound by
/// the module that knows what a supervised stage is; the processor asks every binding where a filter is
/// made, and refuses one over such a pipeline while the node is master-fed.
public interface SupervisedPipelines {

    /// Whether the pipeline holds a stage whose tasks may be deferred.
    boolean isSupervised(DocRef pipeline);
}
