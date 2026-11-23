/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.element.KeptLearning;

import java.util.List;
import java.util.Set;

/// The retention job's view of what a harness without a database keeps: nothing it need look after.
/// What is in memory lasts as long as the JVM does, so nothing in it outlives the run to be found deleted
/// at midnight or aged out; a deleted pipeline is still heard of, and marked, through
/// [InMemoryDeletedPipelines].
public final class InMemoryKeptLearning implements KeptLearning {

    @Override
    public Set<String> pipelines() {
        return Set.of();
    }

    @Override
    public List<String> deletedBefore(final long timeMs) {
        return List.of();
    }

    @Override
    public void forget(final String pipelineUuid) {
        // Nothing is kept beyond the run, so nothing is forgotten.
    }
}
