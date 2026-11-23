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

import stroom.shapeshifter.client.component.ConversationList.Row;
import stroom.shapeshifter.client.component.FeedProgress.Item;
import stroom.shapeshifter.client.component.FeedProgress.State;
import stroom.shapeshifter.client.component.FeedSituation.Tone;
import stroom.shapeshifter.shared.Suggestion;
import stroom.shapeshifter.shared.SuggestionAction;
import stroom.shapeshifter.shared.SupervisorAttempt;
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

/// One pipeline's learning, on its Learning Status tab: its name and feed; where it stands, said in a sentence
/// with a chip, and what can be done about it as buttons, the rest under More ([FeedSituation]); the learned
/// translation it runs, as a link; and a note to the AI. Folded under **Learning history** ([Disclosure]),
/// which is open while a run needs someone ([FeedSituation#needsHistory]): which learning run is shown, how far
/// it has got ([FeedProgress]) and its conversation ([ConversationList]), and, on a second tab shown only where
/// streams wait, the streams waiting for the feed. [FeedDetailController] fills it.
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

    /// Makes the detail, showing no feed.
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

    /// Shows that no feed is chosen.
    public void showNothing() {
        nothing.setVisible(true);
        content.setVisible(false);
    }

    /// Shows a feed.
    ///
    /// @param name        Its name.
    /// @param feed Its feed and type, or empty where not known yet.
    public void showFeed(final String name, final String feed) {
        nothing.setVisible(false);
        content.setVisible(true);
        title.setInnerText(name);
        subtitle.setInnerText(feed);
    }

    /// Shows where the feed stands: its chip and sentences, and its main actions as buttons, with More where
    /// there are others.
    ///
    /// @param situation Where it stands.
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
            // The first is what the situation most asks for; the others are Stroom's plain buttons
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

    /// Says which zone the pipeline reads timestamps in, and where it comes from, or nothing.
    ///
    /// @param said The sentence, or null to say nothing.
    public void setTimeZone(final String said) {
        timeZone.setText(said == null
                ? ""
                : said);
        timeZone.setVisible(said != null);
    }

    /// Shows how the run's translation was judged, one line per check, and who approved or rejected it
    /// (Track 2 step 4), or hides them where there is nothing to say.
    ///
    /// @param lines      One line per check.
    /// @param reviewedBy Who decided about it and when, or null where nobody has.
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

    /// Shows the learned translation the feed runs, as a link that opens it, or hides the line where it
    /// has none.
    ///
    /// @param name The translation's name, or null where there is none.
    public void setTranslation(final String name) {
        translationLine.setVisible(name != null);
        if (name != null) {
            translation.setText(name);
            translation.setTitle("Open the learned translation " + name);
        }
    }

    /// @param onOpenTranslation Told when the learned translation's link is pressed.
    public void setOnOpenTranslation(final Runnable onOpenTranslation) {
        translation.setOnClick(onOpenTranslation);
    }

    /// The learning runs to choose from, newest first.
    ///
    /// @param runs   The runs; empty where it has had none.
    /// @param shown  The id of the run shown, or null for none.
    /// @param labels What each run is called in the list.
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
            // Where it stands, for a screen reader, which is not shown the step's colour
            final InlineLabel state = new InlineLabel(stateWords(item) + ": ");
            state.addStyleName("sr-only");
            step.add(state);
            step.add(new InlineLabel(item.getLabel()));
            progress.add(step);
        }
        progressNote.setText(Objects.requireNonNullElse(shown.getNote(), ""));
        progressNote.setVisible(shown.getNote() != null);
    }

    /// Whether there is any learning history to show, and whether streams wait, whose tab is shown only then.
    ///
    /// @param shown   Whether Learning history is shown at all: not for a feed with no runs and nothing waiting.
    /// @param waiting Whether any streams wait for the feed.
    public void setHistory(final boolean shown, final boolean waiting) {
        history.setVisible(shown);
        tabBarPanel.setVisible(waiting);
        if (!waiting && waitingPane.isVisible()) {
            showTab(CONVERSATION);
        }
    }

    /// @param open Whether Learning history is unfolded.
    public void setHistoryOpen(final boolean open) {
        history.setOpen(open);
        if (open && isWaitingShown()) {
            onWaitingShown.run();
        }
    }

    /// @return Whether Learning history is unfolded.
    public boolean isHistoryOpen() {
        return history.isOpen();
    }

    /// Shows the run's conversation.
    ///
    /// @param rows       Its questions, in order.
    /// @param fresh      Whether they are another run's than shown before.
    /// @param answerable Whether a question may be answered.
    public void setConversation(final List<Row> rows, final boolean fresh, final boolean answerable) {
        conversation.setRows(rows, fresh, answerable);
    }

    /// The note box: hidden for a feed nothing can be said to (its pipeline deleted).
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

    /// Brings the note box into view, and puts the focus in it, where a suggestion says to leave the AI a note.
    public void focusNote() {
        note.getElement().scrollIntoView();
        note.setFocus(true);
    }

    /// Empties the note box, once its note is sent.
    public void clearNote() {
        note.setValue("");
    }

    /// @param waitingList The streams waiting for the feed, shown on the second tab.
    public void setWaitingList(final IsWidget waitingList) {
        waitingPane.setWidget(waitingList);
    }

    /// @param onAction Told each action the user takes.
    public void setOnAction(final Consumer<FeedAction> onAction) {
        this.onAction = onAction;
    }

    /// @param onSuggestion Told the action of each suggestion the user follows.
    public void setOnSuggestion(final Consumer<SuggestionAction> onSuggestion) {
        this.onSuggestion = onSuggestion;
    }

    /// @param onMore Given the More button, where the menu of the other actions opens.
    public void setOnMore(final Consumer<Element> onMore) {
        this.onMore = onMore;
    }

    /// @param onRun Told the learning run the user chooses.
    public void setOnRun(final Consumer<SupervisorAttempt> onRun) {
        this.onRun = onRun;
    }

    /// @param onShowPrompt Told the number of a question whose prompt, as it was sent, the user asks for.
    public void setOnShowPrompt(final Consumer<Integer> onShowPrompt) {
        conversation.setOnShowPrompt(onShowPrompt);
    }

    /// @param onAnswer Told the number of a question the user answers or changes the answer of.
    public void setOnAnswer(final Consumer<Integer> onAnswer) {
        conversation.setOnAnswer(onAnswer);
    }

    /// @param onSendNote Told the note the user sends.
    public void setOnSendNote(final Consumer<String> onSendNote) {
        this.onSendNote = onSendNote;
    }

    /// @param onShowNotes Run when the user asks to see the notes in use.
    public void setOnShowNotes(final Runnable onShowNotes) {
        this.onShowNotes = onShowNotes;
    }

    /// @param onWaitingShown Run when the waiting streams' tab is shown, for them to be read.
    public void setOnWaitingShown(final Runnable onWaitingShown) {
        this.onWaitingShown = onWaitingShown;
    }

    /// @return Whether the waiting streams' tab is shown, and Learning history open to show it.
    public boolean isWaitingShown() {
        return waitingPane.isVisible() && history.isOpen() && history.isVisible();
    }

    // Why the run stopped, under Details and Suggestions; a suggestion the tab can act on is a link
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

    // A line of a report as text that wraps: its own class, in place of a label's, which doesn't
    private static Label text(final String line) {
        final Label text = new Label(line);
        text.setStyleName("feed-detail__report-text");
        return text;
    }

    // One line of a list, as a list's item
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
