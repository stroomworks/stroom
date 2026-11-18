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

import stroom.shapeshifter.client.component.ApproachFlow.Condition;
import stroom.shapeshifter.client.component.ApproachFlow.Destination;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.StepGuard;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// The step of the learning flow chosen on the Setup tab's route map ([RouteMap]), read as sentences: its
/// number and name, a heading, tags for what it sets of its own, and its tools (move, copy, delete); what is wrong with
/// it; and its rules ([RuleRow]), with what it does where they say nothing in grey beneath them. Beneath
/// those, folded away under "Advanced options", is what only this step changes: when it is asked, its
/// tries and kinds, the parsers and filters it chooses from, and what judges it. Only the fields its kind
/// uses are shown.
/// [ApproachSectionController] fills it from [ApproachFlow].
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
    FlowPanel judgedBy;
    @UiField
    TickBox ownChecks;
    @UiField
    Label usualChecks;
    @UiField
    FlowPanel checks;
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

    private final Map<Check, TickBox> checkBoxes = new EnumMap<>(Check.class);
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
    private Consumer<List<Check>> onChecks = own -> {
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
        // Made here rather than when the step is shown, so that the group is there to name them when it is
        // shown: a FormGroup makes a group of several controls, named by its label, as it is attached
        for (final String element : FlowStep.LEARNABLE_ELEMENTS) {
            final TickBox box = new TickBox();
            box.setLabel(element);
            box.setOnChange(value -> elementsChanged(box));
            elementBoxes.put(element, box);
            elements.add(box);
        }
        for (final Check check : Check.values()) {
            final TickBox box = new TickBox();
            box.setLabel(PlainWords.check(check));
            box.setOnChange(value -> onChecks.accept(ownChecks()));
            checkBoxes.put(check, box);
            checks.add(box);
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
            // A field holding what can't be kept stays in sight until it is put right
            if (open || (tries.getInvalidReason() == null && kinds.getInvalidReason() == null)) {
                setTuneOpen(open);
                onTune.accept(open);
            }
        });
        ownChecks.setOnChange(own -> {
            checks.setVisible(Boolean.TRUE.equals(own));
            usualChecks.setVisible(!Boolean.TRUE.equals(own));
            if (!Boolean.TRUE.equals(own)) {
                checkBoxes.values().forEach(box -> box.setValue(false));
                onChecks.accept(List.of());
            }
        });
    }

    /// Shows which step this is.
    ///
    /// @param number Its place, counted from one.
    /// @param name   Its name, e.g. "Write the parser".
    public void setStep(final int number, final String name) {
        heading.setInnerSafeHtml(TEMPLATES.heading(String.valueOf(number), name));
        heading.setAttribute("aria-label", "Step " + number + ": " + name);
    }

    /// What the step sets of its own under "Advanced options", shown beside its name.
    ///
    /// @param words A tag each, e.g. "5 tries"; empty for none.
    public void setTags(final List<String> words) {
        tags.clear();
        for (final String word : words) {
            final InlineLabel tag = new InlineLabel();
            tag.setText(word);
            tag.addStyleName("step-card__tag");
            tags.add(tag);
        }
    }

    /// @param open Whether "Advanced options" is unfolded.
    public void setTuneOpen(final boolean open) {
        tune.setVisible(open);
        tuneToggle.setAriaExpanded(open);
    }

    /// Which tools apply to the step; none is shown while read only.
    ///
    /// @param up     Whether it can move up.
    /// @param down   Whether it can move down.
    /// @param copies Whether it can be copied.
    /// @param goes   Whether it can be deleted.
    public void setTools(final boolean up, final boolean down, final boolean copies, final boolean goes) {
        tools = new boolean[]{up, down, copies, goes};
        toolsShown = true;
        showTools();
    }

    /// @param text What is wrong with the step, or null for nothing.
    public void setProblem(final String text) {
        problem.setText(text == null
                ? ""
                : text);
        problem.setVisible(text != null);
    }

    /// @param shown Whether the step has a guard to choose.
    /// @param guard When it is asked.
    public void setWhen(final boolean shown, final StepGuard guard) {
        whenGroup.setVisible(shown);
        when.setValue(guard);
    }

    /// @param shown       Whether the step has tries.
    /// @param text        Its own tries as text, empty for the document's default.
    /// @param placeholder What the empty field shows: the default.
    public void setTries(final boolean shown, final String text, final String placeholder) {
        triesGroup.setVisible(shown);
        tries.setValue(text);
        tries.setPlaceholder(placeholder);
    }

    /// @param shown Whether the step asks about kinds of record.
    /// @param text  How many, as text, empty for the default.
    public void setKinds(final boolean shown, final String text) {
        kindsGroup.setVisible(shown);
        kinds.setValue(text);
    }

    /// What judges the step: its kind's usual checks, said in words, or its own, ticked. A check whose scorer
    /// isn't built is offered only where the step already names it, so that it is seen and kept.
    ///
    /// @param shown  Whether the step is judged by checks.
    /// @param usualText What judges it where it names none.
    /// @param own    Its own checks, or empty for the usual ones.
    public void setChecks(final boolean shown, final String usualText, final List<Check> own) {
        judgedBy.setVisible(shown);
        usualChecks.setText("Judged by its usual checks: " + usualText + ".");
        final boolean hasOwn = !own.isEmpty();
        ownChecks.setValue(hasOwn);
        checks.setVisible(hasOwn);
        usualChecks.setVisible(!hasOwn);
        checkBoxes.forEach((check, box) -> {
            box.setValue(own.contains(check));
            box.setVisible(check.getScorer() == null || check.getScorer().isBuilt() || own.contains(check));
        });
    }

    /// The parsers and filters the AI may choose from, a tick box each of [FlowStep#LEARNABLE_ELEMENTS]; only
    /// a step that chooses the elements has them. Those the step names that the AI can't learn are said
    /// beneath, since a run passes them over, with a way to remove them.
    ///
    /// @param shown           Whether the step chooses the elements.
    /// @param ticked          Those ticked.
    /// @param unlearnableOnes Those the step names that the AI can't learn; empty for none.
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

    /// @param shown Whether the step takes rules.
    public void setRulesShown(final boolean shown) {
        rulesSection.setVisible(shown);
    }

    /// Removes the rules shown, for [#addRule] to add them again.
    public void clearRules() {
        ruleRows.clear();
        rules.clear();
    }

    /// Adds a rule.
    ///
    /// @param conditions   What it may fire on.
    /// @param chosen       What it fires on.
    /// @param destinations Where it may go.
    /// @param goes         Where it goes.
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

    /// @param canAdd Whether another rule may be added: not where every condition has one.
    public void setCanAddRule(final boolean canAdd) {
        addRule.setVisible(canAdd && !isReadOnly());
    }

    /// @param lines What the step does where its rules say nothing, a line each.
    public void setUsual(final List<String> lines) {
        usual.clear();
        for (final String line : lines) {
            final Label label = new Label();
            label.setText(line);
            label.addStyleName("step-card__usual-line");
            usual.add(label);
        }
    }

    /// Puts the focus on one of a rule's menus, e.g. after its rules were redrawn under it.
    ///
    /// @param rule              The rule's index.
    /// @param onDestinationMenu Whether the destination's menu, else the condition's.
    public void focusRule(final int rule, final boolean onDestinationMenu) {
        if (rule >= 0 && rule < ruleRows.size()) {
            ruleRows.get(rule).focus(onDestinationMenu);
        }
    }

    /// Puts the focus on the step's heading, e.g. after it moved.
    public void focusHeading() {
        heading.focus();
    }

    /// @param onAction Told each tool the user uses (not when read only).
    public void setOnAction(final Consumer<CardAction> onAction) {
        this.onAction = onAction;
    }

    /// @param onWhen Told when the step is now asked.
    public void setOnWhen(final Consumer<StepGuard> onWhen) {
        this.onWhen = onWhen;
    }

    /// @param onTries Told the tries as typed.
    public void setOnTries(final Consumer<String> onTries) {
        this.onTries = onTries;
    }

    /// @param onKinds Told the kinds as typed.
    public void setOnKinds(final Consumer<String> onKinds) {
        this.onKinds = onKinds;
    }

    /// @param onTune Told whether the user unfolds or folds "Advanced options".
    public void setOnTune(final Consumer<Boolean> onTune) {
        this.onTune = onTune;
    }

    /// @param onChecks Told the step's own checks, or empty for its usual ones.
    public void setOnChecks(final Consumer<List<Check>> onChecks) {
        this.onChecks = onChecks;
    }

    /// @param onElements Told the parsers and filters ticked, after a box is ticked or unticked or those the AI
    ///                   can't learn are removed; never none.
    public void setOnElements(final Consumer<List<String>> onElements) {
        this.onElements = onElements;
    }

    /// @param onRuleCondition Told a rule's index and what it now fires on.
    public void setOnRuleCondition(final BiConsumer<Integer, Condition> onRuleCondition) {
        this.onRuleCondition = onRuleCondition;
    }

    /// @param onRuleDestination Told a rule's index and where it now goes.
    public void setOnRuleDestination(final BiConsumer<Integer, Destination> onRuleDestination) {
        this.onRuleDestination = onRuleDestination;
    }

    /// @param onRuleRemove Told the index of a rule the user removes.
    public void setOnRuleRemove(final Consumer<Integer> onRuleRemove) {
        this.onRuleRemove = onRuleRemove;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        when.setReadOnly(readOnly);
        tries.setReadOnly(readOnly);
        kinds.setReadOnly(readOnly);
        ownChecks.setReadOnly(readOnly);
        checkBoxes.values().forEach(box -> box.setReadOnly(readOnly));
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

    // A box ticked or unticked: the last one ticked stays ticked, since the AI must have something to choose
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

    private List<Check> ownChecks() {
        final List<Check> own = new ArrayList<>();
        checkBoxes.forEach((check, box) -> {
            if (Boolean.TRUE.equals(box.getValue())) {
                own.add(check);
            }
        });
        return own;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, StepCard> {

    }


    // --------------------------------------------------------------------------------


    interface Templates extends SafeHtmlTemplates {

        // The heading's content: the step's number in a circle, and its name (escaped by the template)
        @Template("<span class=\"step-card__number\">{0}</span><span class=\"step-card__name\">{1}</span>")
        SafeHtml heading(String number, String name);
    }


    // --------------------------------------------------------------------------------


    /// What the tools on a card do.
    public enum CardAction {
        /// The step moved up one.
        MOVE_UP,
        /// The step moved down one.
        MOVE_DOWN,
        /// A copy of the step after it.
        COPY,
        /// The step deleted.
        DELETE,
        /// A rule added to the step.
        ADD_RULE
    }
}
