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

package stroom.shapeshifter.client;

import stroom.dispatch.client.QuietTaskMonitorFactory;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.shared.BuiltInFlow;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.widget.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

/// The built-in default setups, fetched once (Track 2 step 1c).
///
/// They used to be constants the client could read straight out of `FlowExample`. They are now JSON on
/// the server, because the same enum is compiled to JavaScript and the browser has neither classpath
/// resources nor Jackson; so the client is given them, as it is given the built-in question templates.
///
/// Fetched once and kept: a built-in setup cannot change while the server runs, so there is nothing to
/// refresh and no timer here. A screen that needs them asks with [#get], which answers at once where
/// they are already in hand; one that is drawing and cannot wait reads [#known], which is empty until
/// the fetch lands — a document then reads as having its own approach for the moment it takes, which is
/// why every screen that shows the setup's name fetches before it draws.
@Singleton
public class FlowExamplesCache {

    private static final ShapeshifterAiResource RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final RestFactory restFactory;
    /// Null until the first fetch returns; never emptied afterwards.
    private List<BuiltInFlow> examples;
    /// Asked while a fetch is in flight, so that two screens opening at once make one request.
    private final List<Consumer<List<BuiltInFlow>>> waiting = new ArrayList<>();
    private boolean fetching;

    @Inject
    public FlowExamplesCache(final RestFactory restFactory) {
        this.restFactory = restFactory;
    }

    /// The setups, fetching them where this is the first ask.
    ///
    /// The consumer is told once they are in hand, and not at all where the fetch fails — which is shown
    /// — so it is never called with nothing.
    ///
    /// @param consumer           Told the setups.
    /// @param taskMonitorFactory Shows the fetch.
    public void get(final Consumer<List<BuiltInFlow>> consumer, final TaskMonitorFactory taskMonitorFactory) {
        if (examples != null) {
            consumer.accept(examples);
            return;
        }
        waiting.add(consumer);
        if (fetching) {
            return;
        }
        fetching = true;
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterAiResource::flowExamples)
                .onSuccess(found -> {
                    examples = found == null
                            ? List.of()
                            : found;
                    fetching = false;
                    final List<Consumer<List<BuiltInFlow>>> told = new ArrayList<>(waiting);
                    waiting.clear();
                    told.forEach(one -> one.accept(examples));
                })
                .onFailure(error -> {
                    // Left unfetched so the next ask tries again: a setup's name is worth another go,
                    // and nothing is broken meanwhile — a document still shows its steps.
                    fetching = false;
                    waiting.clear();
                })
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    /// The same, shown on this cache's own monitor.
    ///
    /// @param consumer Told the setups.
    public void get(final Consumer<List<BuiltInFlow>> consumer) {
        get(consumer, new QuietTaskMonitorFactory());
    }

    /// What is in hand, for a screen that is drawing and cannot wait.
    ///
    /// @return The setups, or empty where they have not been fetched yet.
    public List<BuiltInFlow> known() {
        return examples == null
                ? List.of()
                : examples;
    }

    /// The steps of one setup.
    ///
    /// @param example The setup.
    /// @return Its steps, or empty where the setups are not in hand.
    public List<FlowStep> steps(final FlowExample example) {
        for (final BuiltInFlow flow : known()) {
            if (flow.getExample() == example) {
                return flow.getSteps();
            }
        }
        return List.of();
    }
}
