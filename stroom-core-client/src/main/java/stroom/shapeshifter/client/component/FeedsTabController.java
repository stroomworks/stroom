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
import stroom.docref.DocRef;
import stroom.shapeshifter.client.component.Readiness.Fix;
import stroom.shapeshifter.shared.LearnerCriteria;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SuggestionAction;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.util.client.SelectionType;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.Timer;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// A Shapeshifter AI document's Feeds tab ([FeedsTab]; design 07): the Supervisor's learning pipelines,
/// sessions and waiting streams for this document alone (A115, A142, by `LearnerCriteria.docUuid`), as a
/// list of feeds and the one chosen ([FeedDetailController]). Above them, whether the document will learn
/// anything ([Readiness]), which its owner works out from the document as its tabs have it, and whose fixes
/// it hands to its owner.
///
/// A document's tab isn't refreshed by Stroom as a content tab is, so the tab reads its feeds again itself,
/// every ten seconds while it is on the page and auto refresh is on.
public class FeedsTabController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    /// Every feed a document has is read at once, and filtered and paged here, so that each filter can say
    /// how many it lists. A document's feeds are its pipelines, which number tens or hundreds, not more.
    private static final int MOST_FEEDS = 1000;
    private static final int REFRESH_MS = 10_000;

    private final FeedsTab view;
    private final FeedList feedList;
    private final FeedDetailController detail;
    private final RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>> dataProvider;
    private String docUuid;
    private FeedFilter filter = FeedFilter.ALL;
    private boolean showDeleted;
    private boolean autoRefresh = true;
    /// Whether the tab is the one shown, in the document shown: it isn't read again while it isn't.
    private boolean showing = true;
    private Consumer<Fix> onFix = fix -> {
    };

    /// Made by GIN, with its tab and the feed chosen's controller.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The tab.
    /// @param detail      The feed chosen.
    /// @param restFactory Makes REST requests.
    @Inject
    public FeedsTabController(final EventBus eventBus,
                              final FeedsTab view,
                              final FeedDetailController detail,
                              final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        this.feedList = view.getFeedList();
        initWidget(view);
        this.detail = own(detail).attach(view.getFeedDetail());
        detail.setOnActed(this::refreshList);

        dataProvider = new RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<SupervisorLearner>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                if (docUuid == null) {
                    dataConsumer.accept(ResultPage.empty());
                    return;
                }
                final LearnerCriteria asked = new LearnerCriteria(new PageRequest(0, MOST_FEEDS), null, docUuid,
                        null, showDeleted);
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.learners(asked))
                        .onSuccess(page -> {
                            final List<SupervisorLearner> all = NullSafe.list(page.getValues());
                            final List<SupervisorLearner> shown = new ArrayList<>();
                            final Map<FeedFilter, Integer> counts = new EnumMap<>(FeedFilter.class);
                            for (final FeedFilter value : FeedFilter.values()) {
                                counts.put(value, 0);
                            }
                            for (final SupervisorLearner learner : all) {
                                for (final FeedFilter value : FeedFilter.values()) {
                                    if (value.test(learner)) {
                                        counts.put(value, counts.get(value) + 1);
                                    }
                                }
                                if (filter.test(learner)) {
                                    shown.add(learner);
                                }
                            }
                            feedList.setCounts(counts);
                            dataConsumer.accept(ResultPage.createPageLimitedList(shown,
                                    new PageRequest(range.getStart(), range.getLength())));
                            keepSelected(shown);
                        })
                        .onFailure(errorHandler)
                        .owner(FeedsTabController.this)
                        .taskMonitorFactory(feedList.getTaskMonitor())
                        .exec();
            }
        };
        feedList.setDataProvider(dataProvider);
        own(feedList.getSelectionModel().addSelectionHandler(event ->
                detail.setFeed(feedList.getSelectionModel().getSelected())));
        feedList.setOnFilter(chosen -> {
            filter = chosen == null
                    ? FeedFilter.ALL
                    : chosen;
            feedList.showFirstPage();
            refreshList();
        });
        feedList.setOnShowDeleted(show -> {
            showDeleted = show;
            feedList.showFirstPage();
            refreshList();
        });
        feedList.setOnAutoRefreshToggle(on -> {
            autoRefresh = on;
            if (on) {
                refresh();
            }
        });

        view.getReadiness().setOnFix(fix -> onFix.accept(fix));
        watch(Contexts.READ_ONLY, readOnly -> view.getReadiness().setReadOnly(readOnly));

        final Timer timer = new Timer() {
            @Override
            public void run() {
                if (autoRefresh && showing && docUuid != null && asWidget().isAttached()) {
                    refresh();
                }
            }
        };
        timer.scheduleRepeating(REFRESH_MS);
        onDispose(timer::cancel);
    }

    /// Shows a document's feeds.
    ///
    /// @param docRef The document.
    /// @param doc    Its content, as saved: what its feeds learn under.
    public void read(final DocRef docRef, final ShapeshifterAiDoc doc) {
        final boolean another = docUuid == null || !docUuid.equals(docRef.getUuid());
        docUuid = docRef.getUuid();
        detail.setDocument(doc);
        if (another) {
            feedList.getSelectionModel().clear();
            detail.setFeed(null);
            feedList.showFirstPage();
        }
        refresh();
    }

    /// Shows what stops the document learning anything, where something does; nothing where it will learn.
    ///
    /// @param readiness What is right and wrong with it, as its tabs have it.
    /// @param changed   Whether it has changes that aren't saved.
    public void setReadiness(final Readiness readiness, final boolean changed) {
        view.setReadinessShown(!readiness.isReady());
        if (!readiness.isReady()) {
            view.getReadiness().setReadiness(readiness, changed);
        }
    }

    /// @param onFix Told the fix the user asks for, for the document's other tabs to make.
    public void setOnFix(final Consumer<Fix> onFix) {
        this.onFix = onFix;
    }

    /// @param onOpenSetup Told a feed's suggestion that is followed on the Setup tab.
    public void setOnOpenSetup(final Consumer<SuggestionAction> onOpenSetup) {
        detail.setOnOpenSetup(onOpenSetup);
    }

    /// @param onOpenSettings Run when the user asks for the Setup tab's AI model section from a feed.
    public void setOnOpenSettings(final Runnable onOpenSettings) {
        detail.setOnOpenSettings(onOpenSettings);
    }

    /// Says whether the tab is on screen: it is read again every ten seconds only while it is, and at once
    /// when it comes back.
    ///
    /// @param showing Whether it is the tab shown, in the document shown.
    public void setShowing(final boolean showing) {
        final boolean back = showing && !this.showing;
        this.showing = showing;
        if (back && autoRefresh && docUuid != null) {
            refresh();
        }
    }

    /// Reads the feeds again, and with them the feed chosen ([#keepSelected]).
    public void refresh() {
        refreshList();
    }

    private void refreshList() {
        dataProvider.refresh();
    }

    /// The selected row swapped for its copy in the list just read, without telling anyone the selection
    /// changed (the feed chosen hasn't), and the feed shown given its fresh row; where nothing is chosen,
    /// the first feed listed is.
    private void keepSelected(final List<SupervisorLearner> shown) {
        final SupervisorLearner selected = feedList.getSelectionModel().getSelected();
        if (selected == null) {
            if (!shown.isEmpty()) {
                feedList.getSelectionModel().setSelected(shown.get(0), new SelectionType(), true);
            }
            return;
        }
        final SupervisorLearner fresh = shown.stream()
                .filter(row -> selected.getLearner().equals(row.getLearner()))
                .findFirst()
                .orElse(null);
        if (fresh == null) {
            feedList.getSelectionModel().clear();
            detail.setFeed(null);
        } else {
            feedList.getSelectionModel().setSelected(fresh, new SelectionType(), false);
            detail.setFeed(fresh);
        }
    }
}
