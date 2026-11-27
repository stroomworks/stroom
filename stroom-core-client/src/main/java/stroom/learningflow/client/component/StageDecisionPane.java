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

import stroom.learningflow.shared.StageScore;
import stroom.learningflow.shared.StageVerdict;
import stroom.learningflow.shared.SupervisorTurn;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/// The pane the pipeline stepper shows for a Smart Pipeline element, in place of the code pane other elements
/// have. It shows what the element did with the stream at the current stepping position.
///
/// It shows the element's decision; a list of details (the stream, the Learning Flow document, the binding
/// that serves it, its score and the record boundary); a link to the fragment that processed the stream; the
/// scores for each element of the fragment's chain; and, if the stream was learned from, every exchange with
/// the model. The pane is read-only. [StageDecisionPaneController] fills it.
public class StageDecisionPane extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    // CSS classes for a row's term and its value in the scores and transcript tables
    private static final Attribute TERM = Attribute.className("stage-decision-pane__term");
    private static final Attribute VALUE = Attribute.className("stage-decision-pane__value");
    private static final SafeHtml DL = SafeHtmlUtils.fromSafeConstant("dl");
    private static final SafeHtml DT = SafeHtmlUtils.fromSafeConstant("dt");
    private static final SafeHtml DD = SafeHtmlUtils.fromSafeConstant("dd");

    @UiField
    Label decision;
    @UiField
    Html summary;
    @UiField
    LinkButton fragment;
    @UiField
    Html verdicts;
    @UiField
    Html transcript;

    private Runnable onOpenFragment = () -> {
    };

    /// Makes an empty pane.
    public StageDecisionPane() {
        initWidget(BINDER.createAndBindUi(this));
        fragment.setOnClick(() -> onOpenFragment.run());
    }

    /// @param text The element's decision, as the element worded it; null or empty for none.
    public void setDecision(final String text) {
        decision.setText(NullSafe.string(text));
    }

    /// Shows details of the stream and what served it as a list of terms, in order, e.g. "Feed", "Document",
    /// "Score".
    ///
    /// @param terms The terms and their values; empty to show nothing.
    public void setSummary(final Map<String, String> terms) {
        if (terms.isEmpty()) {
            summary.setHTML(SafeHtmlUtils.EMPTY_SAFE_HTML);
            return;
        }
        final HtmlBuilder html = new HtmlBuilder();
        html.elem(list -> {
            for (final Entry<String, String> term : terms.entrySet()) {
                list.elem(term.getKey(), DT);
                list.elem(NullSafe.string(term.getValue()), DD);
            }
        }, DL, Attribute.className("stage-decision-pane__summary"));
        summary.setHTML(html.toSafeHtml());
    }

    /// @param name The name of the fragment that processed the stream, shown as a link; null if no binding
    ///             served it, which hides the link.
    public void setFragment(final String name) {
        fragment.setText(NullSafe.string(name));
        fragment.setVisible(name != null);
    }

    /// @param onOpenFragment Run when the fragment's link is clicked.
    public void setOnOpenFragment(final Runnable onOpenFragment) {
        this.onOpenFragment = onOpenFragment;
    }

    /// Shows the scores for each element of the fragment's chain, in chain order. For each element it lists
    /// every check that ran, with the score it gave, the threshold it had to meet, and whether it was a gate
    /// or only advisory.
    ///
    /// An element passes when every gate passes, so there is no overall total; the figure shown beside each
    /// element is the average of the checks that ran.
    ///
    /// @param stages The verdicts, one per element; null or empty to show nothing.
    public void setVerdicts(final List<StageVerdict> stages) {
        if (NullSafe.isEmptyCollection(stages)) {
            verdicts.setHTML(SafeHtmlUtils.EMPTY_SAFE_HTML);
            return;
        }
        final HtmlBuilder html = new HtmlBuilder();
        html.table(table -> {
            for (final StageVerdict verdict : stages) {
                table.tr(row -> row.th(cell -> cell.append("Step " + verdict.getStep() + " — "
                                                             + PlainWords.percent(verdict.getScore())
                                                             + (verdict.isPassed()
                                                                     ? ", every gate passed"
                                                                     : ", a gate was missed")),
                                new Attribute("colspan", "2")),
                        Attribute.className("stage-decision-pane__step"));
                for (final StageScore score : NullSafe.list(verdict.getScores())) {
                    table.tr(row -> {
                        row.td(PlainWords.checkName(score.getCheck()), TERM);
                        row.td(PlainWords.percent(score.getValue())
                               + (score.isMet()
                                       ? " (met "
                                       : " (missed ")
                               + PlainWords.percent(score.getThreshold())
                               + (score.isGate()
                                       ? ", a gate)"
                                       : ", advice)"), VALUE);
                    });
                }
            }
        }, Attribute.className("stage-decision-pane__table"));
        verdicts.setHTML(html.toSafeHtml());
    }

    /// Shows every exchange with the model, if this stream was learned from. It is empty if the stream was
    /// served by an existing translation, which is the case for every stream after the first a learner sees.
    ///
    /// @param turns The turns; null or empty to show nothing.
    public void setTranscript(final List<SupervisorTurn> turns) {
        if (NullSafe.isEmptyCollection(turns)) {
            transcript.setHTML(SafeHtmlUtils.EMPTY_SAFE_HTML);
            return;
        }
        final HtmlBuilder html = new HtmlBuilder();
        html.table(table -> {
            for (final SupervisorTurn turn : turns) {
                table.tr(row -> row.th(cell -> cell.append(turn.getNumber() + ". "
                                                             + turn.getKind().getDisplayValue()
                                                             + (turn.getStepId() == null
                                                                     ? ""
                                                                     : " (" + turn.getStepId() + ")")),
                                new Attribute("colspan", "2")),
                        Attribute.className("stage-decision-pane__step"));
                table.tr(row -> {
                    row.td("Asked", TERM);
                    row.td(NullSafe.string(turn.getQuestion()), VALUE);
                });
                table.tr(row -> {
                    row.td("Answered", TERM);
                    row.td(NullSafe.string(turn.getAnswer()), VALUE);
                });
            }
        }, Attribute.className("stage-decision-pane__table"));
        transcript.setHTML(html.toSafeHtml());
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, StageDecisionPane> {

    }
}
