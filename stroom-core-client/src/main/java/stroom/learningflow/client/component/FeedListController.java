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

/// A list of Smart Pipelines ([FeedList]): those of one Learning Flow, on its Pipelines tab, or of every
/// Learning Flow the user may read, on the Smart Pipelines screen, where each row also names its Learning Flow.
/// Each row's button opens the pipeline at its Learning Status tab ([PipelineAiTabController]), where its
/// learning is followed and acted on, by the pipeline's permissions.
///
/// The pipelines are filtered as Stroom's other lists are: by a quick filter of the names above the table
/// (the pipeline's, and on the Smart Pipelines screen its Learning Flow's too), and by the dialog its filter
/// button opens ([FeedFilterEditorController]), of where they stand and whether those whose pipeline was deleted
/// are listed too.
///
/// Every pipeline asked for is read at once, and filtered and paged here, so that each filter can say how many
/// it lists. A list that isn't a content tab isn't refreshed by Stroom, so it reads its pipelines again itself,
/// every ten seconds while it is on the page and auto refresh is on.
public class FeedListController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    /// The most pipelines read at once. A Learning Flow's pipelines number tens or hundreds; every Learning
    /// Flow's, rarely more than this.
    private static final int MOST_FEEDS = 1000;
    private static final int REFRESH_MS = 10_000;

    private final RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>> dataProvider;
    private final Provider<FeedFilterEditorController> filterEditorProvider;
    /// How many of the pipelines the quick filter lets through each choice of the dialog lists, as last read.
    private final Map<FeedFilter, Integer> counts = new EnumMap<>(FeedFilter.class);
    private FeedList view;
    /// Whether it lists anything yet: every Learning Flow's pipelines, or [#docUuid]'s.
    private boolean scoped;
    /// The Learning Flow whose pipelines are listed, or null for every Learning Flow's.
    private String docUuid;
    private FeedFilter filter = FeedFilter.ALL;
    private boolean showDeleted;
    /// The quick filter's text, lower case: a pipeline is listed where one of its names has it.
    private String nameFilter = "";
    private boolean autoRefresh = true;
    /// Whether the list is on the page: it isn't read again while it isn't.
    private boolean showing = true;

    /// Made by GIN; [#attach(FeedList)] gives it its view.
    ///
    /// @param eventBus             The application's event bus.
    /// @param restFactory          Makes REST requests.
    /// @param filterEditorProvider Makes the dialog of which pipelines to show.
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
                        .method(resource -> resource.learners(asked))
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

    /// Attaches this to its view, declared in its owner's template, or made for a screen that is the list
    /// alone.
    ///
    /// @param view The view.
    /// @return This, for its owner to keep.
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

    /// Lists one Learning Flow's pipelines, from the first page where they are another's.
    ///
    /// @param docUuid The Learning Flow's uuid.
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

    /// Lists every Learning Flow's pipelines that the user may read, each naming its Learning Flow. They are
    /// read at the next [#refresh()], as Stroom refreshes a content tab once it has opened it, so that they
    /// aren't read twice.
    public void showEveryLearningFlow() {
        scoped = true;
        docUuid = null;
        view.setLearningFlowShown(true);
    }

    /// Says whether the list is on screen: it is read again every ten seconds only while it is, and at once
    /// when it comes back.
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

    /// Reads the pipelines again.
    public void refresh() {
        dataProvider.refresh();
    }

    // The pipelines the quick filter and the dialog let through, counting as it goes how many each choice of the
    // dialog would list
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

    /// Asks which pipelines to show, from the first page once answered: the page a person is on is a page of a
    /// list that has just changed length.
    private void editFilter() {
        filterEditorProvider.get().edit(this, filter, showDeleted, counts, (chosen, deleted) -> {
            filter = chosen;
            showDeleted = deleted;
            view.setFiltering(filter.isFiltering(showDeleted));
            view.showFirstPage();
            refresh();
        });
    }

    // Whether the quick filter lets a pipeline through: its name as the table shows it has the text, or, where
    // every Learning Flow's are listed, its Learning Flow's name has it
    private boolean hasName(final SupervisorLearner learner) {
        return nameFilter.isEmpty()
               || FeedList.name(learner).toLowerCase().contains(nameFilter)
               || (docUuid == null && FeedList.learningFlow(learner).toLowerCase().contains(nameFilter));
    }

    /// Opens a pipeline at its Learning Status tab, showing its element.
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
