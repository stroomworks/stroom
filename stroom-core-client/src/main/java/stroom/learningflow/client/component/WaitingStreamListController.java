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

package stroom.learningflow.client.component;

import stroom.dispatch.client.RestDataProvider;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.learningflow.shared.BacklogStream;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Fetches a Smart Pipeline's waiting streams a page at a time and shows them in a [WaitingStreamList], on the
/// pipeline's Learning Status tab under Learning history.
///
/// A stream waits until its learner's session promotes a translation or the learner gives up. Both are acted
/// on elsewhere on the Learning Status tab, not from this list.
public class WaitingStreamListController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final WaitingStreamList view;
    private final RestDataProvider<BacklogStream, ResultPage<BacklogStream>> dataProvider;
    /// The learner whose waiting streams are listed, i.e. the pipeline selected above, or null before one is.
    private SupervisorLearner learner;

    /// Made by GIN.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The list.
    /// @param restFactory Makes the REST requests that fetch the streams.
    @Inject
    public WaitingStreamListController(final EventBus eventBus,
                                       final WaitingStreamList view,
                                       final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        dataProvider = new RestDataProvider<BacklogStream, ResultPage<BacklogStream>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<BacklogStream>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                if (learner == null) {
                    dataConsumer.accept(ResultPage.empty());
                    return;
                }
                final SupervisorLearner whose = learner;
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.findWaiting(whose.getLearner().getPipelineUuid(),
                                whose.getLearner().getElementId(),
                                new PageRequest(range.getStart(), range.getLength())))
                        .onSuccess(dataConsumer)
                        .onFailure(errorHandler)
                        .owner(WaitingStreamListController.this)
                        .taskMonitorFactory(view.getTaskMonitor())
                        .exec();
            }
        };
        view.setDataProvider(dataProvider);
    }

    /// Lists the given learner's waiting streams, starting from the first page, or clears the list.
    ///
    /// @param learner The pipeline selected above, or null if none is selected.
    public void setLearner(final SupervisorLearner learner) {
        this.learner = learner;
        view.showFirstPage();
        refresh();
    }

    /// Fetches the streams again, since what is waiting changes as sessions promote translations.
    public void refresh() {
        dataProvider.refresh();
    }
}
