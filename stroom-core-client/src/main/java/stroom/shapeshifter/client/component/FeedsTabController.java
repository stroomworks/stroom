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

import stroom.docref.DocRef;
import stroom.shapeshifter.client.component.Readiness.Fix;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// A Learning Flow's Pipelines tab ([FeedsTab]; design 07): its Smart Pipelines alone (A115, A142, by
/// `LearnerCriteria.docUuid`), a table of every pipeline that learns under it ([FeedListController]), each with
/// a button that opens the pipeline at its Learning Status tab. Above them, whether the Learning Flow will learn
/// anything ([Readiness]), which its owner works out from the document as its tabs have it, and whose fixes it
/// hands to its owner.
public class FeedsTabController extends Controller {

    private final FeedsTab view;
    private final FeedListController feedList;
    private Consumer<Fix> onFix = fix -> {
    };

    /// Made by GIN, with its tab.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The tab.
    /// @param feedList The list of its pipelines.
    @Inject
    public FeedsTabController(final EventBus eventBus,
                              final FeedsTab view,
                              final FeedListController feedList) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        this.feedList = own(feedList).attach(view.getFeedList());
        view.getReadiness().setOnFix(fix -> onFix.accept(fix));
        watch(Contexts.READ_ONLY, readOnly -> view.getReadiness().setReadOnly(readOnly));
    }

    /// Shows a Learning Flow's pipelines.
    ///
    /// @param docRef The Learning Flow.
    /// @param doc    Its content, as saved.
    public void read(final DocRef docRef, final ShapeshifterAiDoc doc) {
        feedList.showLearningFlow(docRef.getUuid());
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

    /// Says whether the tab is on screen: its pipelines are read again every ten seconds only while it is, and
    /// at once when it comes back.
    ///
    /// @param showing Whether it is the tab shown, in the document shown.
    public void setShowing(final boolean showing) {
        feedList.setShowing(showing);
    }

    /// Reads the pipelines again.
    public void refresh() {
        feedList.refresh();
    }
}
