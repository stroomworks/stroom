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

import stroom.learningflow.client.component.FeedSituation.Tone;
import stroom.widget.button.client.Button;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Document;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/// Shows the conversation between a learning run and the AI, on a pipeline's Learning Status tab. Each
/// question the run asked is a row showing its number, the step that asked it, who answered it and how the
/// answer was judged. A row
/// expands to show the answer's score against what was needed, what was asked and the whole answer, with a
/// button to answer the question yourself, or to change the answer and run again from that point.
///
/// The rows that are expanded stay expanded when the same run is shown again after a refresh.
public class ConversationList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final Templates TEMPLATES = GWT.create(Templates.class);

    @UiField
    Label empty;
    @UiField
    FlowPanel turns;

    private final Set<Integer> open = new HashSet<>();
    private boolean answerable;
    private Consumer<Integer> onAnswer = number -> {
    };
    private Consumer<Integer> onShowPrompt = number -> {
    };

    /// Creates an empty conversation.
    public ConversationList() {
        initWidget(BINDER.createAndBindUi(this));
        setRows(List.of(), true, false);
    }

    /// Shows a run's questions.
    ///
    /// @param rows       The questions, in the order they were asked.
    /// @param fresh      Whether they belong to a different run from the one shown. If so, only the last
    ///                   question is expanded; if not, the rows already expanded stay expanded.
    /// @param answerable Whether questions may be answered here. They can't be when the pipeline has been
    ///                   deleted.
    public void setRows(final List<Row> rows, final boolean fresh, final boolean answerable) {
        this.answerable = answerable;
        if (fresh) {
            open.clear();
            if (!rows.isEmpty()) {
                open.add(rows.get(rows.size() - 1).number);
            }
        }
        turns.clear();
        empty.setVisible(rows.isEmpty());
        for (final Row row : rows) {
            turns.add(turn(row));
        }
    }

    /// @param onShowPrompt Called with the number of a question when the user asks to see the prompt exactly
    ///                     as it was sent to the AI.
    public void setOnShowPrompt(final Consumer<Integer> onShowPrompt) {
        this.onShowPrompt = onShowPrompt;
    }

    /// @param onAnswer Called with the number of the question the user wants to answer, or whose answer they
    ///                 want to change.
    public void setOnAnswer(final Consumer<Integer> onAnswer) {
        this.onAnswer = onAnswer;
    }

    // Builds one question's row: the button that expands it, and the details it expands to
    private Widget turn(final Row row) {
        final FlowPanel turn = new FlowPanel();
        turn.addStyleName("conversation-list__turn");

        final FlowPanel body = new FlowPanel();
        body.addStyleName("conversation-list__body");
        body.getElement().setId(Document.get().createUniqueId());

        final Button toggle = new Button();
        toggle.addStyleName("conversation-list__toggle");
        toggle.setContent(TEMPLATES.toggle(String.valueOf(row.number), row.step, row.who,
                resultClasses(row.tone), row.result));
        toggle.setAriaLabel("Question " + row.number + ": " + row.step + ", answered by " + row.who + ": "
                            + row.result);
        toggle.getElement().setAttribute("aria-controls", body.getElement().getId());
        turn.add(toggle);

        if (row.score != null) {
            body.add(text(row.score, "conversation-list__score"));
        }
        body.add(text("Asked", "conversation-list__term"));
        body.add(text(row.question, "conversation-list__question"));
        // The question above is the summary the turn recorded. The full prompt sent to the AI contains the
        // feed's data, so it is not held here; the server rebuilds it on request, and only for a user who
        // is allowed to view data
        final LinkButton prompt = new LinkButton("Show the prompt as it was sent");
        prompt.addStyleName("conversation-list__prompt-link");
        prompt.setOpensDialog(true);
        prompt.setOnClick(() -> onShowPrompt.accept(row.number));
        body.add(prompt);
        body.add(text("Answer", "conversation-list__term"));
        if (row.answer == null) {
            body.add(text("Not answered yet: this is the question the run stopped at.", "conversation-list__note"));
        } else {
            body.add(text(row.answer, "conversation-list__answer"));
        }
        if (answerable) {
            final Button answer = new Button(row.answer == null
                    ? "Answer this myself"
                    : "Change this answer and run again from here");
            answer.addStyleName("conversation-list__answer-button");
            answer.setOpensDialog(true);
            answer.setOnClick(() -> onAnswer.accept(row.number));
            body.add(answer);
        }
        turn.add(body);

        show(toggle, body, open.contains(row.number));
        toggle.setOnClick(() -> {
            final boolean opening = !open.contains(row.number);
            if (opening) {
                open.add(row.number);
            } else {
                open.remove(row.number);
            }
            show(toggle, body, opening);
        });
        return turn;
    }

    private static void show(final Button toggle, final FlowPanel body, final boolean shown) {
        body.setVisible(shown);
        toggle.getElement().setAttribute("aria-expanded", String.valueOf(shown));
    }

    private static String resultClasses(final Tone tone) {
        switch (tone) {
            case GOOD:
                return "conversation-list__result conversation-list__result--good";
            case WARNING:
            case DANGER:
                return "conversation-list__result conversation-list__result--warning";
            default:
                return "conversation-list__result";
        }
    }

    private static Label text(final String text, final String className) {
        final Label label = new Label(text == null
                ? ""
                : text);
        label.addStyleName(className);
        return label;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, ConversationList> {

    }


    // --------------------------------------------------------------------------------


    interface Templates extends SafeHtmlTemplates {

        // The content of a question's expand button: its number, its step, who answered and how the answer
        // was judged. All the values are escaped
        @Template("<span class=\"conversation-list__number\">{0}</span>"
                  + "<span class=\"conversation-list__step\">{1}</span>"
                  + "<span class=\"conversation-list__who\">{2}</span>"
                  + "<span class=\"{3}\">{4}</span>")
        SafeHtml toggle(String number, String step, String who, String resultClasses, String result);
    }


    // --------------------------------------------------------------------------------


    /// The values shown for one question of the conversation.
    public static final class Row {

        private final int number;
        private final String step;
        private final String who;
        private final String result;
        private final Tone tone;
        private final String score;
        private final String question;
        private final String answer;

        /// @param number   The question's position in the run, from 1.
        /// @param step     The step that asked it.
        /// @param who      Who answered, e.g. "AI" or a person's name, or "Nobody yet".
        /// @param result   How the answer was judged.
        /// @param tone     Whether the judgement is good, a warning or neutral, which sets its colour.
        /// @param score    The score that decided it, as a sentence, or null for none.
        /// @param question What was asked.
        /// @param answer   The answer, or null where none has been given.
        public Row(final int number,
                   final String step,
                   final String who,
                   final String result,
                   final Tone tone,
                   final String score,
                   final String question,
                   final String answer) {
            this.number = number;
            this.step = step;
            this.who = who;
            this.result = result;
            this.tone = tone;
            this.score = score;
            this.question = question;
            this.answer = answer;
        }
    }
}
