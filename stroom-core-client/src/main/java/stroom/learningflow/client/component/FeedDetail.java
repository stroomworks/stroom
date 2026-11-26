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

import stroom.learningflow.client.component.ConversationList.Row;
import stroom.learningflow.client.component.FeedProgress.Item;
import stroom.learningflow.client.component.FeedProgress.State;
import stroom.learningflow.client.component.FeedSituation.Tone;
import stroom.learningflow.shared.Suggestion;
import stroom.learningflow.shared.SuggestionAction;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.widget.button.client.Button;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.AriaPanel;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.TextArea;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.tab.client.LinkTabBar;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.HeadingElement;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/// Shows one pipeline's learning, on the pipeline's Learning Status tab. [FeedDetailController] fills it.
///
/// At the top are the pipeline's name and feed; its state as a chip and a sentence, with the actions that
/// apply as buttons and the rest under More ([FeedSituation]); the learned translation it uses, as a link;
/// how a new translation was judged and who reviewed it; and a box for leaving a note to the AI.
///
/// Beneath, the collapsible **Learning history** section ([Disclosure]), which is expanded automatically while
/// a run needs someone ([FeedSituation#needsHistory]), holds a choice of learning run, how far the chosen run
/// got ([FeedProgress]) and its conversation with the AI ([ConversationList]). A second tab, shown only while
/// streams are waiting, lists the streams waiting for the feed.
public class FeedDetail extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final TabData CONVERSATION = new TabDataImpl("Conversation");
    private static final TabData WAITING = new TabDataImpl("Waiting streams");

    @UiField
    Label nothing;
    @UiField
    FlowPanel content;
    @UiField
    HeadingElement title;
    @UiField
    SpanElement subtitle;
    @UiField
    FlowPanel status;
    @UiField
    InlineLabel chip;
    @UiField
    InlineLabel headline;
    @UiField
    Label detail;
    @UiField
    FlowPanel translationLine;
    @UiField
    LinkButton translation;
    @UiField
    Label timeZone;
    @UiField
    FlowPanel detailsPart;
    @UiField
    FlowPanel evidencePart;
    @UiField
    AriaPanel evidence;
    @UiField
    Label reviewed;
    @UiField
    AriaPanel details;
    @UiField
    FlowPanel suggestionsPart;
    @UiField
    AriaPanel suggestions;
    @UiField
    FlowPanel actions;
    @UiField
    Disclosure history;
    @UiField
    FormGroup runGroup;
    @UiField
    SelectionBox<SupervisorAttempt> run;
    @UiField
    FlowPanel progressSection;
    @UiField
    FlowPanel progress;
    @UiField
    Label progressNote;
    @UiField
    FlowPanel tabBarPanel;
    @UiField
    LinkTabBar tabBar;
    @UiField
    FlowPanel conversationPane;
    @UiField
    ConversationList conversation;
    @UiField
    FlowPanel noteBox;
    @UiField
    TextArea note;
    @UiField
    Button sendNote;
    @UiField
    Button showNotes;
    @UiField
    SimplePanel waitingPane;

    private Consumer<FeedAction> onAction = action -> {
    };
    private Consumer<SuggestionAction> onSuggestion = action -> {
    };
    private Consumer<Element> onMore = opener -> {
    };
    private Consumer<SupervisorAttempt> onRun = chosen -> {
    };
    private Consumer<String> onSendNote = text -> {
    };
    private Runnable onShowNotes = () -> {
    };
    private Runnable onWaitingShown = () -> {
    };
    private String toneClass;
    private String chipClass;

    /// Creates the detail, showing no feed.
    public FeedDetail() {
        initWidget(BINDER.createAndBindUi(this));
        tabBar.addTab(CONVERSATION);
        tabBar.addTab(WAITING);
        tabBar.addSelectionHandler(event -> showTab(event.getSelectedItem()));
        showTab(CONVERSATION);
        run.setOnChange(chosen -> onRun.accept(chosen));
        sendNote.setOnClick(() -> onSendNote.accept(note.getValue()));
        showNotes.setOnClick(() -> onShowNotes.run());
        history.setOnToggle(open -> {
            if (open && isWaitingShown()) {
                onWaitingShown.run();
            }
        });
        showNothing();
    }

    /// Shows that no feed is selected.
    public void showNothing() {
        nothing.setVisible(true);
        content.setVisible(false);
    }

    /// Shows a feed.
    ///
    /// @param name The pipeline's name.
    /// @param feed The feed and stream type, or empty if not known yet.
    public void showFeed(final String name, final String feed) {
        nothing.setVisible(false);
        content.setVisible(true);
        title.setInnerText(name);
        subtitle.setInnerText(feed);
    }

    /// Shows the feed's situation: its chip and sentences, any report on why its run stopped, and its main
    /// actions as buttons, with a More button if there are others.
    ///
    /// @param situation The feed's situation.
    public void setSituation(final FeedSituation situation) {
        if (toneClass != null) {
            status.removeStyleName(toneClass);
            chip.removeStyleName(chipClass);
        }
        toneClass = statusClass(situation.getTone());
        chipClass = chipClass(situation.getTone());
        status.addStyleName(toneClass);
        chip.addStyleName(chipClass);
        chip.setText(situation.getChip());
        headline.setText(situation.getHeadline());
        detail.setText(situation.getDetail());
        showReport(situation);
        actions.clear();
        boolean first = true;
        for (final FeedAction action : situation.getPrimary()) {
            final Button button = new Button(action.getLabel());
            // The first button is the action the situation most calls for, so it is styled as the primary
            // button; the others are Stroom's plain buttons
            if (first) {
                button.addStyleName("button--contained-primary");
            }
            button.setOpensDialog(action.getLabel().endsWith("…"));
            button.setOnClick(() -> onAction.accept(action));
            actions.add(button);
            first = false;
        }
        if (!situation.getMore().isEmpty()) {
            final Button more = new Button("More…");
            more.addStyleName("feed-detail__more");
            more.getElement().setAttribute("aria-haspopup", "menu");
            more.setOnClick(() -> onMore.accept(more.getElement()));
            actions.add(more);
        }
    }

    /// Shows which time zone the pipeline reads timestamps in, and what set it, or hides the line.
    ///
    /// @param said The sentence, or null to hide the line.
    public void setTimeZone(final String said) {
        timeZone.setText(said == null
                ? ""
                : said);
        timeZone.setVisible(said != null);
    }

    /// Shows how the run's translation was judged, one line per check, and who approved or rejected it, so
    /// that a person deciding whether to approve it can see the evidence. Hidden if there is nothing to show.
    ///
    /// @param lines      One line per check.
    /// @param reviewedBy Who approved or rejected it and when, or null if nobody has.
    public void setEvidence(final List<String> lines, final String reviewedBy) {
        evidence.clear();
        for (final String line : lines) {
            evidence.add(item(text(line)));
        }
        reviewed.setText(reviewedBy == null
                ? ""
                : reviewedBy);
        reviewed.setVisible(reviewedBy != null);
        evidencePart.setVisible(!lines.isEmpty() || reviewedBy != null);
    }

    /// Shows the learned translation the feed uses, as a link that opens it, or hides the line if it has
    /// none.
    ///
    /// @param name The translation's name, or null if there is none.
    public void setTranslation(final String name) {
        translationLine.setVisible(name != null);
        if (name != null) {
            translation.setText(name);
            translation.setTitle("Open the learned translation " + name);
        }
    }

    /// @param onOpenTranslation Run when the learned translation's link is pressed.
    public void setOnOpenTranslation(final Runnable onOpenTranslation) {
        translation.setOnClick(onOpenTranslation);
    }

    /// Sets the learning runs to choose from, newest first. The choice and the progress strip are hidden if
    /// there are none.
    ///
    /// @param runs   The runs; empty if the feed has had none.
    /// @param shown  The id of the run to select, or null for none.
    /// @param labels Gives the text shown for each run in the list.
    public void setRuns(final List<SupervisorAttempt> runs,
                        final Long shown,
                        final Function<SupervisorAttempt, String> labels) {
        runGroup.setVisible(!runs.isEmpty());
        progressSection.setVisible(!runs.isEmpty());
        run.setDisplayValueFunction(value -> value == null
                ? ""
                : labels.apply(value));
        run.clear();
        run.addItems(runs);
        for (final SupervisorAttempt value : runs) {
            if (shown != null && value.getId() == shown) {
                run.setValue(value);
            }
        }
    }

    /// Shows how far the run has got.
    ///
    /// @param shown The run's progress.
    public void setProgress(final FeedProgress shown) {
        progress.clear();
        for (final Item item : shown.getItems()) {
            final FlowPanel step = new FlowPanel();
            step.addStyleName("feed-detail__step");
            step.addStyleName(stepClass(item.getState()));
            step.setTitle(stateWords(item));
            // The step's state as text for screen readers, since the colour that shows it is not announced
            final InlineLabel state = new InlineLabel(stateWords(item) + ": ");
            state.addStyleName("sr-only");
            step.add(state);
            step.add(new InlineLabel(item.getLabel()));
            progress.add(step);
        }
        progressNote.setText(Objects.requireNonNullElse(shown.getNote(), ""));
        progressNote.setVisible(shown.getNote() != null);
    }

    /// Sets whether the Learning history section is shown at all, and whether the waiting streams' tab is
    /// shown, which it is only while streams are waiting.
    ///
    /// @param shown   Whether Learning history is shown; it is not for a feed with no runs and nothing waiting.
    /// @param waiting Whether any streams are waiting for the feed.
    public void setHistory(final boolean shown, final boolean waiting) {
        history.setVisible(shown);
        tabBarPanel.setVisible(waiting);
        if (!waiting && waitingPane.isVisible()) {
            showTab(CONVERSATION);
        }
    }

    /// @param open Whether Learning history is expanded.
    public void setHistoryOpen(final boolean open) {
        history.setOpen(open);
        if (open && isWaitingShown()) {
            onWaitingShown.run();
        }
    }

    /// Shows the run's conversation.
    ///
    /// @param rows       The run's questions, in order.
    /// @param fresh      Whether they belong to a different run from the one shown before.
    /// @param answerable Whether questions may be answered.
    public void setConversation(final List<Row> rows, final boolean fresh, final boolean answerable) {
        conversation.setRows(rows, fresh, answerable);
    }

    /// Shows or hides the note box, and shows the number of notes in use on its View notes button. The box is
    /// hidden when no note can be left, e.g. because the pipeline has been deleted.
    ///
    /// @param shown Whether a note can be left.
    /// @param notes How many notes are in use.
    public void setNotes(final boolean shown, final int notes) {
        noteBox.setVisible(shown);
        showNotes.setText(notes == 0
                ? "View notes"
                : "View notes (" + notes + ")");
        showNotes.setAriaLabel(notes == 0
                ? "View notes, none yet"
                : "View notes, " + PlainWords.count(notes, "note") + " in use");
    }

    /// Scrolls the note box into view and puts the focus in it, for when a suggestion says to leave the AI a
    /// note.
    public void focusNote() {
        note.getElement().scrollIntoView();
        note.setFocus(true);
    }

    /// Empties the note box, once its note has been sent.
    public void clearNote() {
        note.setValue("");
    }

    /// @param waitingList The streams waiting for the feed, shown on the second tab.
    public void setWaitingList(final IsWidget waitingList) {
        waitingPane.setWidget(waitingList);
    }

    /// @param onAction Called with each action the user takes.
    public void setOnAction(final Consumer<FeedAction> onAction) {
        this.onAction = onAction;
    }

    /// @param onSuggestion Called with the action of each suggestion the user follows.
    public void setOnSuggestion(final Consumer<SuggestionAction> onSuggestion) {
        this.onSuggestion = onSuggestion;
    }

    /// @param onMore Called with the More button's element, at which to open the menu of other actions.
    public void setOnMore(final Consumer<Element> onMore) {
        this.onMore = onMore;
    }

    /// @param onRun Called with the learning run the user chooses.
    public void setOnRun(final Consumer<SupervisorAttempt> onRun) {
        this.onRun = onRun;
    }

    /// @param onShowPrompt Called with the number of a question when the user asks to see the prompt exactly
    ///                     as it was sent to the AI.
    public void setOnShowPrompt(final Consumer<Integer> onShowPrompt) {
        conversation.setOnShowPrompt(onShowPrompt);
    }

    /// @param onAnswer Called with the number of a question the user wants to answer, or whose answer they
    ///                 want to change.
    public void setOnAnswer(final Consumer<Integer> onAnswer) {
        conversation.setOnAnswer(onAnswer);
    }

    /// @param onSendNote Called with the note the user sends.
    public void setOnSendNote(final Consumer<String> onSendNote) {
        this.onSendNote = onSendNote;
    }

    /// @param onShowNotes Run when the user asks to see the notes in use.
    public void setOnShowNotes(final Runnable onShowNotes) {
        this.onShowNotes = onShowNotes;
    }

    /// @param onWaitingShown Run when the waiting streams' tab comes into view, so that they can be fetched.
    public void setOnWaitingShown(final Runnable onWaitingShown) {
        this.onWaitingShown = onWaitingShown;
    }

    /// @return Whether the waiting streams' tab is selected and Learning history is shown and expanded, so
    ///         that the tab is in view.
    public boolean isWaitingShown() {
        return waitingPane.isVisible() && history.isOpen() && history.isVisible();
    }

    // Shows why the run stopped, under Details and Suggestions. A suggestion the tab can act on is a link
    private void showReport(final FeedSituation situation) {
        details.clear();
        for (final String line : situation.getDetails()) {
            details.add(item(text(line)));
        }
        detailsPart.setVisible(!situation.getDetails().isEmpty());
        suggestions.clear();
        for (final Suggestion suggestion : situation.getSuggestions()) {
            if (situation.canDo(suggestion)) {
                final LinkButton link = new LinkButton(suggestion.getText());
                link.addStyleName("feed-detail__suggestion-link");
                link.setOnClick(() -> onSuggestion.accept(suggestion.getAction()));
                suggestions.add(item(link));
            } else {
                suggestions.add(item(text(suggestion.getText())));
            }
        }
        suggestionsPart.setVisible(!situation.getSuggestions().isEmpty());
    }

    // A line of a report as text. It replaces the label's own style with one that lets the text wrap, which
    // the label's style does not
    private static Label text(final String line) {
        final Label text = new Label(line);
        text.setStyleName("feed-detail__report-text");
        return text;
    }

    // Wraps a widget as an item of an ARIA list
    private static AriaPanel item(final Widget content) {
        final AriaPanel item = new AriaPanel();
        item.setRole("listitem");
        item.addStyleName("feed-detail__report-item");
        item.add(content);
        return item;
    }

    private void showTab(final TabData tab) {
        tabBar.selectTab(tab);
        final boolean waiting = WAITING.equals(tab);
        conversationPane.setVisible(!waiting);
        waitingPane.setVisible(waiting);
        if (waiting && history.isOpen()) {
            onWaitingShown.run();
        }
    }

    private static String statusClass(final Tone tone) {
        switch (tone) {
            case GOOD:
                return "feed-detail__status--good";
            case WARNING:
                return "feed-detail__status--warning";
            case DANGER:
                return "feed-detail__status--danger";
            case INFO:
                return "feed-detail__status--info";
            default:
                return "feed-detail__status--idle";
        }
    }

    private static String chipClass(final Tone tone) {
        switch (tone) {
            case GOOD:
                return "feed-detail__chip--good";
            case WARNING:
                return "feed-detail__chip--warning";
            case DANGER:
                return "feed-detail__chip--danger";
            case INFO:
                return "feed-detail__chip--info";
            default:
                return "feed-detail__chip--idle";
        }
    }

    private static String stepClass(final State state) {
        switch (state) {
            case DONE:
                return "feed-detail__step--done";
            case NOW:
                return "feed-detail__step--now";
            case TRIED:
                return "feed-detail__step--tried";
            default:
                return "feed-detail__step--todo";
        }
    }

    private static String stateWords(final Item item) {
        switch (item.getState()) {
            case DONE:
                return "Done";
            case NOW:
                return "Where the run is now";
            case TRIED:
                return "Tried, then went back";
            default:
                return "Not reached";
        }
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, FeedDetail> {

    }
}
