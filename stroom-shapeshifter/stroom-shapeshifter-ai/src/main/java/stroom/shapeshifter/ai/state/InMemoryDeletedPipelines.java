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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.DeletedPipelines;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// The deleted pipelines as a map of uuid to when each was marked: what scenarios inspect, node-local and
/// gone on restart. Every pipeline is taken to keep some learning, since a scenario marks only one that
/// does. Every method is synchronised: one node's tasks share it.
public final class InMemoryDeletedPipelines implements DeletedPipelines {

    private final Map<String, Long> marks = new HashMap<>();

    @Override
    public synchronized boolean deleted(final String pipelineUuid, final long timeMs) {
        return marks.putIfAbsent(pipelineUuid, timeMs) == null;
    }

    @Override
    public synchronized boolean restored(final String pipelineUuid) {
        return marks.remove(pipelineUuid) != null;
    }

    @Override
    public synchronized boolean isDeleted(final String pipelineUuid) {
        return marks.containsKey(pipelineUuid);
    }

    @Override
    public synchronized Set<String> deletedOf(final Collection<String> pipelineUuids) {
        return pipelineUuids.stream()
                .filter(marks::containsKey)
                .collect(Collectors.toSet());
    }
}
