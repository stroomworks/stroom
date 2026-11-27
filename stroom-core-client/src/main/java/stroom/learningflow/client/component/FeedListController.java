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
import stroom.document.client.editor.DocumentEditorHost;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.learningflow.shared.LearnerCriteria;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.pipeline.client.component.PipelineEditor;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.Timer;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/// Wires a list of Smart Pipelines ([FeedList]). On a Learning Flow's Pipelines tab it lists that document's
/// pipelines; on the Smart Pipelines screen it lists those of every Learning Flow the user may read, and each
/// row also names its Learning Flow. Each row's button opens the pipeline at its Learning Status tab
/// ([PipelineAiTabController]), where its learning is followed and acted on, subject to the pipeline's
/// permissions.
///
/// The pipelines are filtered as Stroom's other lists are: by a quick filter on names above the table (the
/// pipeline's, and on the Smart Pipelines screen its Learning Flow's too), and by the dialog that the filter
/// button opens ([FeedFilterEditorController]), which filters by learner state and by whether deleted
/// pipelines are listed.
///
/// All the matching pipelines are fetched in one request and then filtered and paged here, so that the filter
/// dialog can say how many pipelines each choice would list. Stroom does not refresh a list that isn't a
/// content tab, so this refreshes itself every ten seconds while the list is on screen and auto refresh is on.
public class FeedListController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    /// The most pipelines fetched in one request. A Learning Flow usually has tens or hundreds of pipelines,
    /// and all Learning Flows together rarely have more than this.
    private static final int MOST_FEEDS = 1000;
    private static final int REFRESH_MS = 10_000;

    private final RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>> dataProvider;
    private final Provider<FeedFilterEditorController> filterEditorProvider;
    /// For each choice in the filter dialog, how many of the pipelines that pass the quick filter it would list,
    /// as of the last fetch.
    private final Map<FeedFilter, Integer> counts = new EnumMap<>(FeedFilter.class);
    private FeedList view;
    /// Whether the list has been told what to list yet (every Learning Flow's pipelines, or [#docUuid]'s).
    /// Until it has, it fetches nothing.
    private boolean scoped;
    /// The Learning Flow whose pipelines are listed, or null for every Learning Flow's.
    private String docUuid;
    private FeedFilter filter = FeedFilter.ALL;
    private boolean showDeleted;
    /// The quick filter's text, in lower case. A pipeline is listed if one of its names contains it.
    private String nameFilter = "";
    private boolean autoRefresh = true;
    /// Whether the list is on screen. It isn't refreshed while it isn't.
    private boolean showing = true;

    /// Created by GIN; [#attach(FeedList)] then gives it its view.
    ///
    /// @param eventBus             The application's event bus.
    /// @param restFactory          Creates REST requests.
    /// @param filterEditorProvider Creates the dialog that chooses which pipelines to show.
    @Inject
    public FeedListController(final EventBus eventBus,
                              final RestFactory restFactory,
                              final Provider<FeedFilterEditorController> filterEditorProvider) {
        super(eventBus);
        this.filterEditorProvider = filterEditorProvider;
        dataProvider = new RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<SupervisorLearner>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                if (!scoped) {
                    dataConsumer.accept(ResultPage.empty());
                    return;
                }
                final LearnerCriteria asked = new LearnerCriteria(new PageRequest(0, MOST_FEEDS), null, docUuid,
                        null, showDeleted);
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.findLearners(asked))
                        .onSuccess(page -> dataConsumer.accept(ResultPage.createPageLimitedList(
                                filtered(NullSafe.list(page.getValues())),
                                new PageRequest(range.getStart(), range.getLength()))))
                        .onFailure(errorHandler)
                        .owner(FeedListController.this)
                        .taskMonitorFactory(view.getTaskMonitor())
                        .exec();
            }
        };
        final Timer timer = new Timer() {
            @Override
            public void run() {
                if (autoRefresh && showing && scoped && view != null && view.isAttached()) {
                    refresh();
                }
            }
        };
        timer.scheduleRepeating(REFRESH_MS);
        onDispose(timer::cancel);
    }

    /// Attaches this controller to its view, which is either declared in the owning screen's template or
    /// created for a screen that consists of the list alone.
    ///
    /// @param view The list.
    /// @return This controller, for the owner to keep.
    public FeedListController attach(final FeedList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setDataProvider(dataProvider);
        view.setOnOpen(this::openPipeline);
        view.setOnNameFilter(text -> {
            nameFilter = NullSafe.trim(text).toLowerCase();
            view.showFirstPage();
            refresh();
        });
        view.setOnFilterButton(this::editFilter);
        view.setFiltering(false);
        view.setOnAutoRefreshToggle(on -> {
            autoRefresh = on;
            if (on) {
                refresh();
            }
        });
        return this;
    }

    /// Lists one Learning Flow's pipelines. If a different Learning Flow's pipelines were listed, the selection
    /// is cleared and the list goes back to its first page.
    ///
    /// @param docUuid The Learning Flow's UUID.
    public void showLearningFlow(final String docUuid) {
        final boolean another = !scoped || !Objects.equals(this.docUuid, docUuid);
        scoped = true;
        this.docUuid = docUuid;
        view.setLearningFlowShown(false);
        if (another) {
            view.getSelectionModel().clear();
            view.showFirstPage();
        }
        refresh();
    }

    /// Lists the pipelines of every Learning Flow that the user may read, each row naming its Learning Flow.
    /// They are not fetched here but at the next [#refresh()]: Stroom refreshes a content tab once it has
    /// opened it, so fetching here as well would fetch them twice.
    public void showEveryLearningFlow() {
        scoped = true;
        docUuid = null;
        view.setLearningFlowShown(true);
    }

    /// Tells the list whether it is on screen. It is refreshed every ten seconds only while it is, and
    /// immediately when it comes back on screen.
    ///
    /// @param showing Whether it is on screen.
    public void setShowing(final boolean showing) {
        final boolean back = showing && !this.showing;
        this.showing = showing;
        if (back && autoRefresh && scoped) {
            refresh();
        }
    }

    /// @return Whether auto refresh is on.
    public boolean isAutoRefresh() {
        return autoRefresh;
    }

    /// Fetches the pipelines again.
    public void refresh() {
        dataProvider.refresh();
    }

    // Returns the pipelines that pass both the quick filter and the dialog's filter. As it goes, it counts how
    // many pipelines each choice in the dialog would list, for the dialog to show
    private List<SupervisorLearner> filtered(final List<SupervisorLearner> all) {
        final List<SupervisorLearner> shown = new ArrayList<>();
        counts.clear();
        for (final FeedFilter value : FeedFilter.values()) {
            counts.put(value, 0);
        }
        for (final SupervisorLearner learner : all) {
            if (!hasName(learner)) {
                continue;
            }
            for (final FeedFilter value : FeedFilter.values()) {
                if (value.test(learner)) {
                    counts.put(value, counts.get(value) + 1);
                }
            }
            if (filter.test(learner)) {
                shown.add(learner);
            }
        }
        return shown;
    }

    /// Opens the dialog that chooses which pipelines to show. Once a choice is made the list goes back to its
    /// first page, because the list has changed length and the page the person was on may no longer exist.
    private void editFilter() {
        filterEditorProvider.get().edit(this, filter, showDeleted, counts, (chosen, deleted) -> {
            filter = chosen;
            showDeleted = deleted;
            view.setFiltering(filter.isFiltering(showDeleted));
            view.showFirstPage();
            refresh();
        });
    }

    // Whether a pipeline passes the quick filter: its name as the table shows it contains the text, or, when
    // every Learning Flow's pipelines are listed, its Learning Flow's name does
    private boolean hasName(final SupervisorLearner learner) {
        return nameFilter.isEmpty()
               || FeedList.name(learner).toLowerCase().contains(nameFilter)
               || (docUuid == null && FeedList.learningFlow(learner).toLowerCase().contains(nameFilter));
    }

    /// Opens a pipeline at its Learning Status tab, showing the learner's element. Does nothing for a pipeline
    /// that has been deleted.
    private void openPipeline(final SupervisorLearner learner) {
        if (learner.getPipeline() == null || learner.isPipelineDeleted()) {
            return;
        }
        OpenDocumentEvent.builder(this, learner.getPipeline())
                .forceOpen(true)
                .callbackOnOpen(opened -> {
                    if (opened instanceof final DocumentEditorHost<?> host
                        && host.getEditor() instanceof final PipelineEditor editor) {
                        editor.showAiTranslation(learner.getLearner().getElementId());
                    }
                })
                .fire();
    }
}
