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
import stroom.shapeshifter.client.component.SessionList.SessionAction;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.component.client.Controller;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.SelectionType;

import com.google.gwt.core.client.GWT;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Set;
import java.util.function.Consumer;

/// The selected learning pipeline's sessions ([SessionList]), the Supervisor's first tab beneath the
/// pipelines (A28, design 06 A115, A143), fetched a page at a time. Nothing is listed until a pipeline is
/// chosen above, and there is no filter: the pipeline is the way in. Its actions are its owner's, which
/// decides what is offered.
public class SessionListController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final SessionList view;
    private final RestDataProvider<SupervisorAttempt, ResultPage<SupervisorAttempt>> dataProvider;
    /// Whose sessions are listed: the pipeline chosen above, or nobody's before one is.
    private LearnerRef learner;
    /// Told when a page read again has put its own row in place of the one selected, or let it go.
    private Runnable onSelectionRefreshed = () -> {
    };

    /// Made by GIN, with its list.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        Its component.
    /// @param restFactory Makes REST requests.
    @Inject
    public SessionListController(final EventBus eventBus,
                                 final SessionList view,
                                 final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        dataProvider = new RestDataProvider<SupervisorAttempt, ResultPage<SupervisorAttempt>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<SupervisorAttempt>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                if (learner == null) {
                    dataConsumer.accept(ResultPage.empty());
                    return;
                }
                final AttemptCriteria asked = AttemptCriteria.forLearner(
                        new PageRequest(range.getStart(), range.getLength()), learner);
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.find(asked))
                        .onSuccess(page -> {
                            dataConsumer.accept(page);
                            keepSelected(page);
                        })
                        .onFailure(errorHandler)
                        .owner(SessionListController.this)
                        .taskMonitorFactory(view.getTaskMonitor())
                        .exec();
            }
        };
        view.setDataProvider(dataProvider);
    }

    /// Lists this learner's sessions, from the first page, or none; no session is selected.
    ///
    /// @param learner The learner chosen above, or null where none is.
    public void setLearner(final LearnerRef learner) {
        this.learner = learner;
        view.getSelectionModel().clear();
        view.showFirstPage();
        refresh();
    }

    /// @return The selection, one session at most.
    public MultiSelectionModel<SupervisorAttempt> getSelectionModel() {
        return view.getSelectionModel();
    }

    /// @param onAction Given each action the user takes on the selected session.
    public void setOnAction(final Consumer<SessionAction> onAction) {
        view.setOnAction(onAction);
    }

    /// @param actions The actions that apply to the selected session now; the others are disabled.
    public void setAvailableActions(final Set<SessionAction> actions) {
        view.setAvailableActions(actions);
    }

    /// @param onSelectionRefreshed Told when a page read again has put its own row in place of the one
    ///                             selected, or let it go because the page no longer has it: what the
    ///                             session's actions are decided from has changed.
    public void setOnSelectionRefreshed(final Runnable onSelectionRefreshed) {
        this.onSelectionRefreshed = onSelectionRefreshed;
    }

    /// Reads the page again.
    public void refresh() {
        dataProvider.refresh();
    }

    /// The selection holds the row object it was made from, and a page read again makes new ones, so a
    /// session that has moved on since would still answer its old status to the guards that decide what
    /// is offered. The page's own row takes its place, without telling anyone it was chosen: it is the
    /// session already chosen, and choosing it again would read its turns afresh.
    private void keepSelected(final ResultPage<SupervisorAttempt> page) {
        final SupervisorAttempt selected = view.getSelectionModel().getSelected();
        if (selected == null) {
            return;
        }
        final SupervisorAttempt fresh = NullSafe.stream(page.getValues())
                .filter(row -> row.getId() == selected.getId())
                .findFirst()
                .orElse(null);
        if (fresh == null) {
            view.getSelectionModel().clear(false);
        } else {
            view.getSelectionModel().setSelected(fresh, new SelectionType(), false);
        }
        onSelectionRefreshed.run();
    }
}
