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

package stroom.learningflow.state;

import stroom.learningflow.element.KeptLearning;

import java.util.List;
import java.util.Set;

/// An in-memory [KeptLearning], which gives the retention job nothing to do. In-memory learning lasts only
/// as long as the JVM, so none of it outlives the run to be found by the nightly check for deleted
/// pipelines or removed for age. A deleted pipeline is still recorded and marked through
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
        // Nothing is kept beyond the run, so there is nothing to delete.
    }
}
