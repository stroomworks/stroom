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

package stroom.shapeshifter.client.component;

import stroom.dispatch.client.RestDataProvider;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.shared.BacklogStream;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// The selected learning pipeline's waiting streams ([WaitingStreamList]), the Supervisor's second tab
/// beneath the pipelines (design 06 A115), fetched a page at a time. A stream waits until its learner's
/// session promotes, or the learner gives up, and both are done from the pipeline's row and its sessions.
public class WaitingStreamListController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final WaitingStreamList view;
    private final RestDataProvider<BacklogStream, ResultPage<BacklogStream>> dataProvider;
    /// Whose waiting streams are listed: the pipeline chosen above, or nobody's before one is.
    private SupervisorLearner learner;

    /// Made by GIN, with its list.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        Its component.
    /// @param restFactory Makes REST requests.
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
                        .method(resource -> resource.waiting(whose.getDoc().getUuid(),
                                whose.getLearner().getPipelineUuid(),
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

    /// Lists this learner's waiting streams, from the first page, or none.
    ///
    /// @param learner The learning pipeline chosen above, or null where none is.
    public void setLearner(final SupervisorLearner learner) {
        this.learner = learner;
        view.showFirstPage();
        refresh();
    }

    /// Reads them again: what is waiting changes as sessions promote.
    public void refresh() {
        dataProvider.refresh();
    }
}
