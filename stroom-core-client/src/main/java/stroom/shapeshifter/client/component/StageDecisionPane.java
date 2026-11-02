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

import stroom.shapeshifter.shared.StageScore;
import stroom.shapeshifter.shared.StageVerdict;
import stroom.shapeshifter.shared.SupervisorTurn;
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

/// The stage pane (A30, design 01 §11.7): what a supervised stage did with the stream at the stepping
/// cursor, shown where the code pane would be. Its decision; the stream, its document, what serves it and
/// the record, as terms; the fragment that processed it, as a link; what the scorers made of each step of
/// the chain; and every exchange with the model where the stream was learned. Read only.
/// [StageDecisionPaneController] fills it.
public class StageDecisionPane extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    // A row's term and its value, apart, in the scores and the conversation
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

    /// @param text What the stage decided, in its own words; empty for nothing.
    public void setDecision(final String text) {
        decision.setText(NullSafe.string(text));
    }

    /// Shows the stream and what served it as terms, in order, e.g. "Feed", "Document", "Score".
    ///
    /// @param terms The terms and their values; empty for nothing.
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

    /// @param name The name of the fragment the stream was processed by, offered as a link; null where
    ///             nothing was bound.
    public void setFragment(final String name) {
        fragment.setText(NullSafe.string(name));
        fragment.setVisible(name != null);
    }

    /// @param onOpenFragment Run when the fragment's link is pressed.
    public void setOnOpenFragment(final Runnable onOpenFragment) {
        this.onOpenFragment = onOpenFragment;
    }

    /// What the scorers made of each step of the chain (design 01 §8.4), in chain order. A gate that was
    /// missed is what decides a promotion, so it is named as one.
    ///
    /// @param stages The verdicts, or empty for none.
    public void setVerdicts(final List<StageVerdict> stages) {
        if (NullSafe.isEmptyCollection(stages)) {
            verdicts.setHTML(SafeHtmlUtils.EMPTY_SAFE_HTML);
            return;
        }
        final HtmlBuilder html = new HtmlBuilder();
        html.table(table -> {
            for (final StageVerdict verdict : stages) {
                table.tr(row -> row.th(cell -> cell.append("Step " + verdict.getStep() + " — "
                                                             + verdict.getWeightedTotal()
                                                             + (verdict.isPassed()
                                                                     ? " (passed)"
                                                                     : " (failed)")),
                                new Attribute("colspan", "2")),
                        Attribute.className("stage-decision-pane__step"));
                for (final StageScore score : NullSafe.list(verdict.getScores())) {
                    table.tr(row -> {
                        row.td(score.getScorer().getDisplayValue(), TERM);
                        row.td(score.getValue() + (score.isMet()
                                ? " (met "
                                : " (missed ") + score.getThreshold() + (score.isGate()
                                ? ", a gate)"
                                : ")"), VALUE);
                    });
                }
            }
        }, Attribute.className("stage-decision-pane__table"));
        verdicts.setHTML(html.toSafeHtml());
    }

    /// Every exchange with the model, where this stream was learned. Empty where it was served, which is
    /// every stream after the first of its learner.
    ///
    /// @param turns The turns, or empty for none.
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
