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
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;


/// A Shapeshifter AI document's Score tab ([ScoreTab]): the scores (design 05 §10; A91, A93), the gate
/// a learned fragment must pass, the flag a serving binding raises, and beneath them the scorer set of
/// design §8.4 ([ScorerListController]). Nothing can be changed while the document is read only
/// ([Contexts#READ_ONLY]).
///
/// The process the document runs is across the top of the tab ([ProcessHeader], A98), this stage
/// marked; the document keeps its figures.
public class ScoreTabController extends Controller {

    private final ScoreTab view;
    private final ScorerListController scorerList;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus   The application's event bus.
    /// @param view       The tab.
    /// @param scorerList The scorers' list's controller.
    @Inject
    public ScoreTabController(final EventBus eventBus,
                              final ScoreTab view,
                              final ScorerListController scorerList) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        this.scorerList = own(scorerList).attach(view.getScorerList());

        view.setOnChange(this::markChanged);
        scorerList.setOnChange(this::markChanged);
        watch(Contexts.READ_ONLY, view::setReadOnly);
    }

    /// Shows a document's scores.
    ///
    /// @param docRef The document.
    /// @param doc    Its content.
    public void read(final DocRef docRef, final ShapeshifterAiDoc doc) {
        view.setPromotionMode(doc.getPromotionMode());
        view.setPromotionFloor(doc.getPromotionFloor());
        view.setHeldOutFraction(doc.getHeldOutFraction());
        view.setMinRecordsToLearn(doc.getMinRecordsToLearn());
        view.setRegressionCap(doc.getRegressionCap());
        view.setRegressionRetentionDays(doc.getRegressionRetentionDays());
        view.setRelearnThreshold(doc.getRelearnThreshold());
        scorerList.read(doc.getScorers());
    }

    /// @param doc The document.
    /// @return A copy of it with the scores shown.
    public ShapeshifterAiDoc write(final ShapeshifterAiDoc doc) {
        return doc.copy()
                .scorers(scorerList.write())
                .promotionMode(view.getPromotionMode())
                .promotionFloor(view.getPromotionFloor())
                .heldOutFraction(view.getHeldOutFraction())
                .minRecordsToLearn(view.getMinRecordsToLearn())
                .regressionCap(view.getRegressionCap())
                .regressionRetentionDays(view.getRegressionRetentionDays())
                .relearnThreshold(view.getRelearnThreshold())
                .build();
    }

    /// @return The process across the top of the tab, this stage marked, which the document keeps
    ///         up to date.
    public ProcessHeader getProcessHeader() {
        return view.getProcessHeader();
    }
}
