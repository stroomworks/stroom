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

import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Html;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// One session's conversation read as a conversation (design 06 A116): every turn in full, one after
/// another, where the grid beside it ([TurnList]) has a line of each. A turn is its number, the step that
/// asked it and which candidate, what kind of question it was; then what was asked, who answered and the
/// whole answer, kept as it was written; then how it was judged and what it scored.
///
/// What was asked is the line the turn recorded, not the rendered prompt: that carries the stream's own
/// text, which is not stored until it can be redacted (A17, A38).
///
/// Read only. It follows the choosing rather than doing any: the turn chosen in the grid or on the chart
/// is marked and brought into view here.
public class TurnTranscript extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final String SELECTED = "turn-transcript__turn--selected";
    private static final String DATA_TURN = "data-turn";
    private static final Attribute TERM = Attribute.className("turn-transcript__term");
    private static final Attribute VALUE = Attribute.className("turn-transcript__value");
    private static final Attribute ANSWER = Attribute.className("turn-transcript__value turn-transcript__answer");
    private static final SafeHtml SECTION = SafeHtmlUtils.fromSafeConstant("section");
    private static final SafeHtml DL = SafeHtmlUtils.fromSafeConstant("dl");
    private static final SafeHtml DT = SafeHtmlUtils.fromSafeConstant("dt");
    private static final SafeHtml DD = SafeHtmlUtils.fromSafeConstant("dd");

    @UiField
    Html turns;

    private Integer selected;

    /// Makes an empty transcript.
    public TurnTranscript() {
        initWidget(BINDER.createAndBindUi(this));
        setTurns(List.of());
    }

    /// Shows a conversation. Nothing is marked until a turn is chosen again.
    ///
    /// @param conversation The turns, in order; empty where there is no session or it asked nothing yet.
    public void setTurns(final List<SupervisorTurn> conversation) {
        selected = null;
        if (NullSafe.isEmptyCollection(conversation)) {
            turns.setHTML(new HtmlBuilder()
                    .div("Choose a session to read its conversation.",
                            Attribute.className("turn-transcript__empty"))
                    .toSafeHtml());
            return;
        }
        final HtmlBuilder html = new HtmlBuilder();
        for (final SupervisorTurn turn : conversation) {
            html.elem(section -> {
                section.div(heading(turn), Attribute.className("turn-transcript__heading"));
                section.elem(terms -> {
                    term(terms, "Asked", turn.getQuestion(), VALUE);
                    if (turn.getAnswer() == null) {
                        term(terms, "Answered", "Not yet: this is the question the session stopped at.", VALUE);
                    } else {
                        term(terms, "Answered by", turn.getAnsweredBy(), VALUE);
                        term(terms, "Answer", turn.getAnswer(), ANSWER);
                    }
                    if (turn.getOutcome() != null) {
                        term(terms, "Outcome", turn.getOutcome().getDisplayValue(), VALUE);
                    }
                    if (turn.getScore() != null) {
                        term(terms, "Score", turn.getScore().format(), VALUE);
                    }
                }, DL, Attribute.className("turn-transcript__terms"));
            }, SECTION, Attribute.className("turn-transcript__turn"),
                    new Attribute(DATA_TURN, Integer.toString(turn.getNumber())));
        }
        turns.setHTML(html.toSafeHtml());
    }

    /// Marks the turn chosen in the grid or on the chart, and brings it into view. It is chosen while the
    /// transcript's tab is hidden as often as while it is shown, so the turn is found among the
    /// transcript's own elements rather than the page's, and brought into view again when it is shown.
    ///
    /// @param number The turn's number, or null for none.
    public void setSelected(final Integer number) {
        final Element was = find(selected);
        if (was != null) {
            was.removeClassName(SELECTED);
        }
        selected = number;
        final Element turn = find(number);
        if (turn != null) {
            turn.addClassName(SELECTED);
            reveal(turn);
        }
    }

    @Override
    protected void onLoad() {
        super.onLoad();
        final Element turn = find(selected);
        if (turn != null) {
            reveal(turn);
        }
    }

    private Element find(final Integer number) {
        if (number == null) {
            return null;
        }
        final String wanted = Integer.toString(number);
        for (Element turn = turns.getElement().getFirstChildElement();
                turn != null;
                turn = turn.getNextSiblingElement()) {
            if (wanted.equals(turn.getAttribute(DATA_TURN))) {
                return turn;
            }
        }
        return null;
    }

    // Its heading at the top of the transcript's pane, since an answer may run longer than the pane, and
    // nothing else scrolled; only while it is on the page, where it has a place to scroll to
    private void reveal(final Element turn) {
        if (isAttached()) {
            final Element pane = turns.getElement().getParentElement();
            pane.setScrollTop(pane.getScrollTop() + turn.getAbsoluteTop() - pane.getAbsoluteTop());
        }
    }

    /// e.g. "Turn 3 · parse, candidate 2 · Configuration".
    private static String heading(final SupervisorTurn turn) {
        final StringBuilder sb = new StringBuilder("Turn ").append(turn.getNumber());
        if (turn.getStepId() != null) {
            sb.append(" · ").append(turn.getStepId());
            if (turn.getCandidate() > 1) {
                sb.append(", candidate ").append(turn.getCandidate());
            }
        }
        if (turn.getKind() != null) {
            sb.append(" · ").append(turn.getKind().getDisplayValue());
        }
        return sb.toString();
    }

    private static void term(final HtmlBuilder terms,
                             final String name,
                             final String value,
                             final Attribute valueClass) {
        terms.elem(name, DT, TERM);
        terms.elem(NullSafe.string(value), DD, valueClass);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, TurnTranscript> {

    }
}
