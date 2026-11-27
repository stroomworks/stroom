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

package stroom.learningflow.client;

import stroom.dispatch.client.RestFactory;
import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.LearningFlowResource;
import stroom.widget.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

/// Fetches the built-in learning flows (the default setups a Learning Flow document can start from) from the
/// server once, and caches them for every screen in the client.
///
/// The built-in flows are held as JSON resources on the server. The client cannot read them directly, because
/// the shared `FlowExample` enum is compiled to JavaScript and the browser has neither classpath resources nor
/// Jackson, so the server supplies them, as it does the built-in question templates.
///
/// They cannot change while the server runs, so they are fetched once and never refreshed. A screen that
/// needs them calls [#get], which answers immediately if they are already cached. A screen that is drawing and
/// cannot wait calls [#known], which is empty until the fetch completes; until then a document appears to have
/// its own custom flow. That is why every screen that shows the built-in flow's name fetches before it draws.
@Singleton
public class FlowExamplesCache {

    private static final LearningFlowResource RESOURCE = GWT.create(LearningFlowResource.class);

    private final RestFactory restFactory;
    /// The cached flows. Null until the first successful fetch; never cleared afterwards.
    private List<BuiltInFlow> examples;
    /// Callers waiting for a fetch in progress, so that two screens opening at once make only one request.
    private final List<Consumer<List<BuiltInFlow>>> waiting = new ArrayList<>();
    private boolean fetching;

    @Inject
    public FlowExamplesCache(final RestFactory restFactory) {
        this.restFactory = restFactory;
    }

    /// Gets the built-in flows, fetching them from the server if they are not cached yet.
    ///
    /// The consumer is called once the flows are available. If the fetch fails, the consumer is not called at
    /// all (and no error is shown), so it is never called without the flows.
    ///
    /// @param consumer           Called with the built-in flows.
    /// @param taskMonitorFactory Shows the fetch as in progress.
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
                .method(LearningFlowResource::flowExamples)
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
                    // Leave the cache empty so the next call tries again. Nothing is broken meanwhile: a
                    // document still shows its steps, just not the built-in flow's name.
                    fetching = false;
                    waiting.clear();
                })
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    /// Gets the cached flows without fetching, for a screen that is drawing and cannot wait.
    ///
    /// @return The built-in flows, or empty if they have not been fetched yet.
    public List<BuiltInFlow> known() {
        return examples == null
                ? List.of()
                : examples;
    }
}
