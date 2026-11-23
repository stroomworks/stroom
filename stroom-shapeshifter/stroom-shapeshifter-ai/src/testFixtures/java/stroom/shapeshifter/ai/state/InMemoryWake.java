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

import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Wake;

import java.util.ArrayList;
import java.util.List;

/// The wake as a record of what was woken: what scenarios assert on. Waking a pipeline wakes the tasks of
/// the streams waiting on its learners, so what is recorded is their rows as they stood. Every method is
/// synchronised.
public final class InMemoryWake implements Wake {

    private final InMemoryDeferrals deferrals;
    private final List<String> pipelines = new ArrayList<>();
    private final List<Deferral> woken = new ArrayList<>();

    /// @param deferrals The rows a wake reads, as a task wakes the streams its rows name.
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

    /// Every row woken so far, in the order they were woken.
    public synchronized List<Deferral> woken() {
        return List.copyOf(woken);
    }

    /// Every pipeline woken so far, in the order they were woken, once each time.
    public synchronized List<String> pipelines() {
        return List.copyOf(pipelines);
    }
}
