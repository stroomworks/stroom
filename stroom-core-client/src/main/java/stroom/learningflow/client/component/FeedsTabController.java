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

import stroom.docref.DocRef;
import stroom.learningflow.client.component.Readiness.Fix;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Fills a Learning Flow's Pipelines tab ([FeedsTab]) with a table of the Smart Pipelines that learn under
/// that document alone ([FeedListController]), each with a button that opens the pipeline at its Learning
/// Status tab. Above the table it shows whether the Learning Flow will learn anything ([Readiness]). The
/// document editor works that out from the document as its tabs currently have it, and this controller passes
/// any fix the user asks for back to the editor to make.
public class FeedsTabController extends Controller {

    private final FeedsTab view;
    private final FeedListController feedList;
    private Consumer<Fix> onFix = fix -> {
    };

    /// Created by GIN.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The tab.
    /// @param feedList The controller for the list of the document's pipelines.
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
    /// @param doc    Its content, as saved. Not used; the pipelines are found by the document's UUID.
    public void read(final DocRef docRef, final LearningFlowDoc doc) {
        feedList.showLearningFlow(docRef.getUuid());
    }

    /// Shows the banner saying what stops the document learning anything, or hides it if the document will
    /// learn.
    ///
    /// @param readiness What is right and wrong with the document, as its tabs currently have it.
    /// @param changed   Whether the document has unsaved changes.
    public void setReadiness(final Readiness readiness, final boolean changed) {
        view.setReadinessShown(!readiness.isReady());
        if (!readiness.isReady()) {
            view.getReadiness().setReadiness(readiness, changed);
        }
    }

    /// @param onFix Called with the fix the user asks for, so that the document's other tabs can make it.
    public void setOnFix(final Consumer<Fix> onFix) {
        this.onFix = onFix;
    }

    /// Tells the tab whether it is on screen. Its pipelines are refreshed every ten seconds only while it is,
    /// and immediately when it comes back on screen.
    ///
    /// @param showing Whether it is the selected tab of the document being shown.
    public void setShowing(final boolean showing) {
        feedList.setShowing(showing);
    }

    /// Fetches the pipelines again.
    public void refresh() {
        feedList.refresh();
    }
}
