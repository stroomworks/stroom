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

package stroom.learningflow.state;

import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Wake;

import java.util.ArrayList;
import java.util.List;

/// An in-memory [Wake] that records what was woken, for scenarios to assert on. Waking a pipeline wakes
/// the tasks of the streams waiting on its learners, so it records their deferral rows as they were at the
/// time. It does not remove the rows. Every method is synchronised.
public final class InMemoryWake implements Wake {

    private final InMemoryDeferrals deferrals;
    private final List<String> pipelines = new ArrayList<>();
    private final List<Deferral> woken = new ArrayList<>();

    /// Creates a wake that reads the given deferrals.
    ///
    /// @param deferrals The deferral rows a wake reads to find the streams to wake.
    public InMemoryWake(final InMemoryDeferrals deferrals) {
        this.deferrals = deferrals;
    }

    @Override
    public synchronized int wake(final String pipelineUuid) {
        pipelines.add(pipelineUuid);
        final List<Deferral> waiting = deferrals.forPipeline(pipelineUuid);
        woken.addAll(waiting);
        return waiting.size();
    }

    /// Returns every row woken so far, in the order they were woken.
    public synchronized List<Deferral> woken() {
        return List.copyOf(woken);
    }

    /// Returns every pipeline woken so far, in the order they were woken, once for each wake.
    public synchronized List<String> pipelines() {
        return List.copyOf(pipelines);
    }
}
