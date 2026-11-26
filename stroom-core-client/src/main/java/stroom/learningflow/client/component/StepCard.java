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

import stroom.learningflow.client.component.ApproachFlow.Condition;
import stroom.learningflow.client.component.ApproachFlow.Destination;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.StepGuard;
import stroom.widget.button.client.LinkButton;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.HeadingElement;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// Shows the learning-flow step chosen on the Setup tab's route map ([RouteMap]), so that it can be read and
/// edited.
///
/// The card shows the step's number and name as a heading, tags for the settings the step overrides, and
/// tools to move, copy or delete it; any problem with the step; and its rules ([RuleRow]), with what the step
/// does when no rule applies shown in grey beneath them. Below those, collapsed under "Advanced options", are
/// the settings specific to this step: which input it applies to, its number of tries and kinds of record,
/// and the parsers and filters it may choose from. Only the fields the step's kind uses are shown.
///
/// A step has no checks of its own. Each check applies to every step it can measure, and whether it is Off,
/// Advice or a Gate is set once in the Setup tab's Quality checks section. [ApproachSectionController] fills
/// the card from [ApproachFlow].
public class StepCard extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final Templates TEMPLATES = GWT.create(Templates.class);

    @UiField
    HeadingElement heading;
    @UiField
    FlowPanel tags;
    @UiField
    ToolbarButton moveUp;
    @UiField
    ToolbarButton moveDown;
    @UiField
    ToolbarButton copy;
    @UiField
    ToolbarButton delete;
    @UiField
    Label problem;
    @UiField
    FormGroup whenGroup;
    @UiField
    SelectionBox<StepGuard> when;
    @UiField
    FormGroup triesGroup;
    @UiField
    TextBox tries;
    @UiField
    FormGroup kindsGroup;
    @UiField
    TextBox kinds;
    @UiField
    FormGroup elementsGroup;
    @UiField
    FlowPanel elements;
    @UiField
    FlowPanel unlearnable;
    @UiField
    Label unlearnableText;
    @UiField
    LinkButton removeUnlearnable;
    @UiField
    Label warning;
    @UiField
    FlowPanel rulesSection;
    @UiField
    FlowPanel rules;
    @UiField
    FlowPanel usual;
    @UiField
    LinkButton addRule;
    @UiField
    LinkButton tuneToggle;
    @UiField
    FlowPanel tune;

    private final Map<String, TickBox> elementBoxes = new LinkedHashMap<>();
    private final List<RuleRow> ruleRows = new ArrayList<>();
    private Consumer<CardAction> onAction = action -> {
    };
    private Consumer<StepGuard> onWhen = guard -> {
    };
    private Consumer<String> onTries = text -> {
    };
    private Consumer<String> onKinds = text -> {
    };
    private Consumer<Boolean> onTune = open -> {
    };
    private Consumer<List<String>> onElements = ticked -> {
    };
    private BiConsumer<Integer, Condition> onRuleCondition = (rule, condition) -> {
    };
    private BiConsumer<Integer, Destination> onRuleDestination = (rule, destination) -> {
    };
    private Consumer<Integer> onRuleRemove = rule -> {
    };
    private boolean toolsShown;
    private boolean[] tools = new boolean[4];

    /// Makes an empty card.
    public StepCard() {
        initWidget(BINDER.createAndBindUi(this));
        when.setDisplayValueFunction(PlainWords::guard);
        when.addItems(StepGuard.values());
        tries.setInvalidReason(LearnFlow::limitProblem);
        kinds.setInvalidReason(LearnFlow::limitProblem);
        // Create the tick boxes here rather than when a step is shown. A FormGroup groups its controls under
        // its label when it is attached, so the boxes must already be present at that point.
        for (final String element : FlowStep.LEARNABLE_ELEMENTS) {
            final TickBox box = new TickBox();
            box.setLabel(element);
            box.setOnChange(value -> elementsChanged(box));
            elementBoxes.put(element, box);
            elements.add(box);
        }
        moveUp.setOnClick(() -> action(CardAction.MOVE_UP));
        moveDown.setOnClick(() -> action(CardAction.MOVE_DOWN));
        copy.setOnClick(() -> action(CardAction.COPY));
        delete.setOnClick(() -> action(CardAction.DELETE));
        addRule.setOnClick(() -> action(CardAction.ADD_RULE));
        when.setOnChange(guard -> onWhen.accept(guard));
        tries.addValueChangeHandler(event -> onTries.accept(tries.getValue()));
        kinds.addValueChangeHandler(event -> onKinds.accept(kinds.getValue()));
        removeUnlearnable.setOnClick(() -> {
            if (!isReadOnly()) {
                onElements.accept(tickedElements());
            }
        });
        tuneToggle.setOnClick(() -> {
            final boolean open = !tune.isVisible();
            // Don't collapse the section while one of its fields holds invalid input, so the error stays
            // visible until it is corrected
            if (open || (tries.getInvalidReason() == null && kinds.getInvalidReason() == null)) {
                setTuneOpen(open);
                onTune.accept(open);
            }
        });
    }

    /// Shows which step this is.
    ///
    /// @param number The step's position in the flow, counted from one.
    /// @param name   The step's name, e.g. "Write the parser".
    public void setStep(final int number, final String name) {
        heading.setInnerSafeHtml(TEMPLATES.heading(String.valueOf(number), name));
        heading.setAttribute("aria-label", "Step " + number + ": " + name);
    }

    /// Shows tags beside the step's name for the settings it overrides under "Advanced options".
    ///
    /// @param words The text of each tag, e.g. "5 tries"; empty for none.
    public void setTags(final List<String> words) {
        tags.clear();
        for (final String word : words) {
            final InlineLabel tag = new InlineLabel();
            tag.setText(word);
            tag.addStyleName("step-card__tag");
            tags.add(tag);
        }
    }

    /// @param open Whether "Advanced options" is expanded.
    public void setTuneOpen(final boolean open) {
        tune.setVisible(open);
        tuneToggle.setAriaExpanded(open);
    }

    /// Sets which tools apply to the step. No tools are shown while read-only.
    ///
    /// @param up     Whether the step can move up.
    /// @param down   Whether the step can move down.
    /// @param copies Whether the step can be copied.
    /// @param goes   Whether the step can be deleted.
    public void setTools(final boolean up, final boolean down, final boolean copies, final boolean goes) {
        tools = new boolean[]{up, down, copies, goes};
        toolsShown = true;
        showTools();
    }

    /// @param text The problem with the step, or null if there is none.
    public void setProblem(final String text) {
        problem.setText(text == null
                ? ""
                : text);
        problem.setVisible(text != null);
    }

    /// @param shown Whether the step has a guard (the kind of input it applies to) to choose.
    /// @param guard The step's guard.
    public void setWhen(final boolean shown, final StepGuard guard) {
        whenGroup.setVisible(shown);
        when.setValue(guard);
    }

    /// @param shown       Whether the step has a number of tries.
    /// @param text        The step's own number of tries as text, or empty to use the default.
    /// @param placeholder The text the empty field shows, i.e. the default.
    public void setTries(final boolean shown, final String text, final String placeholder) {
        triesGroup.setVisible(shown);
        tries.setValue(text);
        tries.setPlaceholder(placeholder);
    }

    /// @param shown Whether the step asks about kinds of record.
    /// @param text  The number of kinds of record as text, or empty to use the default.
    public void setKinds(final boolean shown, final String text) {
        kindsGroup.setVisible(shown);
        kinds.setValue(text);
    }

    /// Shows a warning about the step that does not make the flow invalid. It is shown beneath any problem, in
    /// the warning colour rather than the problem colour.
    ///
    /// @param text The warning, e.g. that a rule is triggered by a check the Learning Flow has switched off,
    ///             or null for none.
    public void setWarning(final String text) {
        warning.setText(text == null
                ? ""
                : text);
        warning.setVisible(text != null);
    }

    /// Shows the parsers and filters the AI may choose from, with a tick box for each of
    /// [FlowStep#LEARNABLE_ELEMENTS]. Only a step that chooses the elements has them. Any element the step
    /// names that the AI cannot learn is listed beneath, because a run ignores it, with a link to remove it.
    ///
    /// @param shown           Whether the step chooses the elements.
    /// @param ticked          The elements to tick.
    /// @param unlearnableOnes The elements the step names that the AI cannot learn; empty for none.
    public void setElements(final boolean shown, final List<String> ticked, final List<String> unlearnableOnes) {
        elementsGroup.setVisible(shown);
        elementBoxes.forEach((element, box) -> box.setValue(ticked.contains(element)));
        final String named = PlainWords.list(unlearnableOnes);
        unlearnable.setVisible(!unlearnableOnes.isEmpty());
        final String pronoun = unlearnableOnes.size() == 1
                ? "it"
                : "them";
        unlearnableText.setText("The AI can't learn " + named + ", so a run passes " + pronoun + " over.");
        removeUnlearnable.setText("Remove " + named);
        removeUnlearnable.setVisible(!isReadOnly());
    }

    /// @param shown Whether the step can have rules.
    public void setRulesShown(final boolean shown) {
        rulesSection.setVisible(shown);
    }

    /// Removes all the rules shown, so that [#addRule] can add them again.
    public void clearRules() {
        ruleRows.clear();
        rules.clear();
    }

    /// Adds a rule to the end of the step's rules.
    ///
    /// @param conditions   The conditions the rule may be triggered by.
    /// @param chosen       The condition the rule is triggered by.
    /// @param destinations The places the rule may go to.
    /// @param goes         The place the rule goes to.
    public void addRule(final List<Condition> conditions,
                        final Condition chosen,
                        final List<Destination> destinations,
                        final Destination goes) {
        final int index = ruleRows.size();
        final RuleRow row = new RuleRow();
        row.setRule(conditions, chosen, destinations, goes, index + 1);
        row.setReadOnly(isReadOnly());
        row.setOnCondition(condition -> onRuleCondition.accept(index, condition));
        row.setOnDestination(destination -> onRuleDestination.accept(index, destination));
        row.setOnRemove(() -> onRuleRemove.accept(index));
        ruleRows.add(row);
        rules.add(row);
    }

    /// @param canAdd Whether another rule may be added, i.e. not every condition already has a rule.
    public void setCanAddRule(final boolean canAdd) {
        addRule.setVisible(canAdd && !isReadOnly());
    }

    /// @param lines What the step does when none of its rules applies, one line each.
    public void setUsual(final List<String> lines) {
        usual.clear();
        for (final String line : lines) {
            final Label label = new Label();
            label.setText(line);
            label.addStyleName("step-card__usual-line");
            usual.add(label);
        }
    }

    /// Puts the keyboard focus on one of a rule's menus, e.g. to restore it after the rules are redrawn.
    ///
    /// @param rule              The rule's index.
    /// @param onDestinationMenu True to focus the destination menu, false to focus the condition menu.
    public void focusRule(final int rule, final boolean onDestinationMenu) {
        if (rule >= 0 && rule < ruleRows.size()) {
            ruleRows.get(rule).focus(onDestinationMenu);
        }
    }

    /// Puts the keyboard focus on the step's heading, e.g. after the step has been moved.
    public void focusHeading() {
        heading.focus();
    }

    /// @param onAction Called with each tool the user uses (never when read-only).
    public void setOnAction(final Consumer<CardAction> onAction) {
        this.onAction = onAction;
    }

    /// @param onWhen Called with the step's new guard when the user changes it.
    public void setOnWhen(final Consumer<StepGuard> onWhen) {
        this.onWhen = onWhen;
    }

    /// @param onTries Called with the number of tries, as typed, when it changes.
    public void setOnTries(final Consumer<String> onTries) {
        this.onTries = onTries;
    }

    /// @param onKinds Called with the number of kinds of record, as typed, when it changes.
    public void setOnKinds(final Consumer<String> onKinds) {
        this.onKinds = onKinds;
    }

    /// @param onTune Called with true when the user expands "Advanced options" and false when they collapse it.
    public void setOnTune(final Consumer<Boolean> onTune) {
        this.onTune = onTune;
    }

    /// @param onElements Called with the ticked parsers and filters after a box is ticked or unticked, or after
    ///                   the elements the AI cannot learn are removed. Unticking the last box is refused, so a
    ///                   tick box change never gives an empty list.
    public void setOnElements(final Consumer<List<String>> onElements) {
        this.onElements = onElements;
    }

    /// @param onRuleCondition Called with a rule's index and its new condition.
    public void setOnRuleCondition(final BiConsumer<Integer, Condition> onRuleCondition) {
        this.onRuleCondition = onRuleCondition;
    }

    /// @param onRuleDestination Called with a rule's index and its new destination.
    public void setOnRuleDestination(final BiConsumer<Integer, Destination> onRuleDestination) {
        this.onRuleDestination = onRuleDestination;
    }

    /// @param onRuleRemove Called with the index of the rule the user removes.
    public void setOnRuleRemove(final Consumer<Integer> onRuleRemove) {
        this.onRuleRemove = onRuleRemove;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        when.setReadOnly(readOnly);
        tries.setReadOnly(readOnly);
        kinds.setReadOnly(readOnly);
        elementBoxes.values().forEach(box -> box.setReadOnly(readOnly));
        removeUnlearnable.setVisible(!readOnly);
        ruleRows.forEach(row -> row.setReadOnly(readOnly));
        if (readOnly) {
            addRule.setVisible(false);
        }
        showTools();
    }

    private void showTools() {
        final boolean editable = toolsShown && !isReadOnly();
        moveUp.setVisible(editable && tools[0]);
        moveDown.setVisible(editable && tools[1]);
        copy.setVisible(editable && tools[2]);
        delete.setVisible(editable && tools[3]);
    }

    private void action(final CardAction action) {
        if (!isReadOnly()) {
            onAction.accept(action);
        }
    }

    // Handles a box being ticked or unticked. The last ticked box cannot be unticked, since the AI must have
    // something to choose from.
    private void elementsChanged(final TickBox changed) {
        final List<String> ticked = tickedElements();
        if (ticked.isEmpty()) {
            changed.setValue(true);
            elementsGroup.setFeedback("Tick at least one: the AI must have something to choose from.");
            return;
        }
        elementsGroup.clearFeedback();
        if (!isReadOnly()) {
            onElements.accept(ticked);
        }
    }

    private List<String> tickedElements() {
        final List<String> ticked = new ArrayList<>();
        elementBoxes.forEach((element, box) -> {
            if (Boolean.TRUE.equals(box.getValue())) {
                ticked.add(element);
            }
        });
        return ticked;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, StepCard> {

    }


    // --------------------------------------------------------------------------------


    interface Templates extends SafeHtmlTemplates {

        // The heading's content: the step's number in a circle, then its name (escaped by the template)
        @Template("<span class=\"step-card__number\">{0}</span><span class=\"step-card__name\">{1}</span>")
        SafeHtml heading(String number, String name);
    }


    // --------------------------------------------------------------------------------


    /// The actions the card's tools and Add rule link ask for.
    public enum CardAction {
        /// Move the step up one place.
        MOVE_UP,
        /// Move the step down one place.
        MOVE_DOWN,
        /// Insert a copy of the step after it.
        COPY,
        /// Delete the step.
        DELETE,
        /// Add a rule to the step.
        ADD_RULE
    }
}
