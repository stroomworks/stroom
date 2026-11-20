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

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.shapeshifter.client.component.ApproachFlow.Addable;
import stroom.shapeshifter.client.component.ApproachFlow.Condition;
import stroom.shapeshifter.client.component.ApproachFlow.Destination;
import stroom.shapeshifter.client.component.StepCard.CardAction;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowProblem.Severity;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Template;
import stroom.shapeshifter.shared.Transition;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.menu.client.Item;
import stroom.widget.menu.client.ShowMenuEvent;
import stroom.widget.menu.client.SimpleMenuItem;
import stroom.widget.popup.client.PopupPosition;
import stroom.widget.popup.client.PopupPosition.PopupLocation;
import stroom.widget.util.client.ElementUtil;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// How a new feed is learned: the learning flow of a Shapeshifter AI document (design 05, design 07), its
/// steps chosen one at a time on the Setup tab's route map and shown in [ApproachSection]. The first step
/// chooses from the parsers and filters the AI can learn ([FlowStep#LEARNABLE_ELEMENTS]). A starting point
/// replaces the steps with an example's (A34, [#startFrom]); the step chosen ([#select]) is edited in place on
/// its card, every choice from a menu ([ApproachFlow]), with the question text it is asked with beneath
/// (A85, A86). The flow is not shown or edited as text. What is wrong with a step is said on its card, and
/// what is wrong with the whole flow ([#flowProblem()]) under Learning steps (A88); the store refuses to save
/// a flow with problems.
///
/// Its owner ([SetupTabController]) holds the rest of the document and draws the route map from the steps
/// ([#getSteps()], [#getSelected()], [#stepProblems()]): the tries a step has where it doesn't say
/// ([#setDefaultTries]), and the fallback, chosen under Approving new translations ([#setFallback]). It is
/// told of every change ([#setOnChange]), the step chosen included where the change chose another.
///
/// The card is made again when another step is chosen or the steps change shape (added, moved, copied,
/// deleted, a starting point, the fallback); an edit within the card changes it where it is, so that what has
/// the focus keeps it.
public class ApproachSectionController extends Controller {

    private static final ShapeshifterAiResource RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final RestFactory restFactory;
    private final Provider<BuiltInTextController> builtInTextProvider;
    private ApproachSection view;
    /// The card of the step chosen; null where it has none (the fallback).
    private StepCard card;
    /// This document's own question texts, by template; the built-ins are shown where it has none.
    private final Map<Template, String> overrides = new HashMap<>();
    private Map<Template, String> builtIns = new HashMap<>();
    private Integer builtInVersion;
    private boolean builtInsRequested;
    private List<FlowStep> steps = new ArrayList<>();
    /// The flow as read, for what the steps don't hold: the built-in version its texts were saved against.
    private LearningFlow flow = LearningFlow.of(FlowExample.DIRECT);
    /// The step chosen, whose card and question text are shown.
    private int selected;
    /// The steps whose "Fine-tune this step" is unfolded, by index; folded again when the steps change shape.
    private final Set<Integer> tuned = new HashSet<>();
    /// The tries a step has where it doesn't say, which its card shows as its placeholder.
    private int defaultTries;
    /// What is wrong with each step, by index, and with the flow as a whole, as last checked.
    private Map<Integer, String> stepProblems = new HashMap<>();
    /// The Learning Flow's checks, so that a rule firing on one that is off can be warned about; the Setup
    /// tab keeps them up to date as its Quality checks are edited.
    private List<CheckSetting> checks = new ArrayList<>();
    private String flowProblem;
    private Runnable onChange = () -> {
    };
    private Texts texts = Texts.STEP;
    private boolean readOnly = true;

    /// Made by GIN; [#attach(ApproachSection)] gives it its section.
    ///
    /// @param eventBus            The application's event bus.
    /// @param builtInTextProvider Makes the dialog that loads a built-in text.
    /// @param restFactory         Fetches the built-in texts.
    @Inject
    public ApproachSectionController(final EventBus eventBus,
                                     final Provider<BuiltInTextController> builtInTextProvider,
                                     final RestFactory restFactory) {
        super(eventBus);
        this.restFactory = restFactory;
        this.builtInTextProvider = builtInTextProvider;
    }

    /// Attaches it to its section, declared in its owner's template.
    ///
    /// @param view The section.
    /// @return This.
    public ApproachSectionController attach(final ApproachSection view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);

        view.setOnTextsToggle(() -> showTexts(texts == Texts.STEP
                ? Texts.SHARED
                : Texts.STEP));
        view.setOnTextEdit((template, words) -> {
            if (!readOnly) {
                setText(template, words);
            }
        });
        view.setOnLoadBuiltIn(this::loadBuiltIn);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
        });
        return this;
    }

    /// Shows a document's approach, with its first step chosen; or, where the steps are those shown (the
    /// document read back after a save), with the step chosen and the steps unfolded as they were.
    ///
    /// @param docRef The document.
    /// @param doc    Its content.
    public void read(final DocRef docRef, final ShapeshifterAiDoc doc) {
        fetchBuiltIns();
        defaultTries = doc.getMaxAttempts();
        checks = new ArrayList<>(doc.getChecks());
        flow = doc.getFlow();
        final boolean sameSteps = steps.equals(flow.getSteps());
        steps = new ArrayList<>(flow.getSteps());
        overrides.clear();
        overrides.putAll(flow.getTemplates());
        if (!sameSteps) {
            selected = 0;
            tuned.clear();
            texts = Texts.STEP;
        }
        rebuild();
    }

    /// @param doc The document.
    /// @return A copy of it with the approach shown. The tries a step has where it doesn't say are its
    ///         owner's to write.
    public ShapeshifterAiDoc write(final ShapeshifterAiDoc doc) {
        return doc.copy()
                .flow(new LearningFlow(new ArrayList<>(steps), new HashMap<>(overrides), flow.getBuiltInVersion()))
                .build();
    }

    /// The Learning Flow's checks, so that a step's card can warn that a rule fires on one that is off.
    /// The Setup tab tells the approach whenever its Quality checks change, since both are edited together.
    ///
    /// @param checks The checks, as the Setup tab has them now.
    public void setChecks(final List<CheckSetting> checks) {
        this.checks = new ArrayList<>(checks);
        refresh();
    }

    /// @param onChange Run after the user changes the approach, once the document is marked changed.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// The tries a step has where it doesn't say, as its owner shows them: each card's placeholder.
    ///
    /// @param tries The tries.
    public void setDefaultTries(final int tries) {
        if (tries != defaultTries) {
            defaultTries = tries;
            rebuild();
        }
    }

    /// @return Whether the flow has the fallback, which serves marked Unknowns when the transform can't be
    ///         finished.
    public boolean hasFallback() {
        return ApproachFlow.hasFallback(steps);
    }

    /// @return The fallback's index in the flow, or -1 where it has none.
    public int fallbackIndex() {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getKind() == QuestionKind.ADMIT) {
                return i;
            }
        }
        return -1;
    }

    /// @return The flow's steps, as shown.
    public List<FlowStep> getSteps() {
        return new ArrayList<>(steps);
    }

    /// @return The index of the step chosen.
    public int getSelected() {
        return selected;
    }

    /// @return What is wrong with each step, by index; a step that is fine has nothing.
    public Map<Integer, String> stepProblems() {
        return new HashMap<>(stepProblems);
    }

    /// @return What is wrong with the flow as a whole, or null for nothing.
    public String flowProblem() {
        return flowProblem;
    }

    /// @return The example the steps are exactly, or null where they are the document's own.
    public FlowExample example() {
        return ApproachFlow.example(steps);
    }

    /// Switches the fallback on or off, as chosen under Approving new translations
    /// ([ApproachFlow#withFallback]).
    ///
    /// @param on Whether to have it.
    public void setFallback(final boolean on) {
        if (on != hasFallback()) {
            reshape(ApproachFlow.withFallback(steps, on), selected);
        }
    }

    /// Chooses a step, whose card and question text are then shown. The question text every step shares
    /// stays shown where it was.
    ///
    /// @param index The step's index.
    public void select(final int index) {
        if (index < 0 || index >= steps.size()) {
            return;
        }
        if (selected != index) {
            selected = index;
            rebuild();
        }
    }

    // ---- The card ----

    /// Makes the card of the step chosen again, and everything that follows the steps.
    private void rebuild() {
        if (selected >= steps.size()) {
            selected = Math.max(0, steps.size() - 1);
        }
        view.clearStep();
        card = null;
        if (!steps.isEmpty()) {
            if (steps.get(selected).getKind() == QuestionKind.ADMIT) {
                view.showFallback();
            } else {
                card = view.showCard();
                fill(card, selected);
            }
        }
        refresh();
        showTexts(texts);
    }

    /// Everything that follows an edit within the card: the card's problem, usual lines and tags, and what
    /// is wrong with each step and with the flow as a whole.
    private void refresh() {
        final List<FlowProblem> problems = check();
        stepProblems = new HashMap<>();
        final List<String> whole = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            final String problem = LearnFlow.problemsOf(problems, i);
            if (problem != null) {
                stepProblems.put(i, problem);
            }
        }
        for (final FlowProblem problem : problems) {
            if (problem.isProblem() && (problem.getStep() == null || problem.getStep() >= steps.size())) {
                whole.add(problem.getMessage());
            }
        }
        flowProblem = whole.isEmpty()
                ? null
                : String.join("; ", whole) + ".";
        if (card != null) {
            final FlowStep step = steps.get(selected);
            card.setProblem(stepProblems.get(selected));
            card.setWarning(LearnFlow.problemsOf(problems, selected, Severity.WARNING));
            card.setUsual(ApproachFlow.usual(steps, selected));
            card.setTags(ApproachFlow.tuning(step));
            card.setElements(step.getKind() == QuestionKind.CHAIN, ApproachFlow.tickedElements(step),
                    ApproachFlow.unlearnableElements(step));
        }
    }

    private void fill(final StepCard card, final int index) {
        final FlowStep step = steps.get(index);
        final QuestionKind kind = step.getKind();
        card.setStep(index + 1, PlainWords.stepName(steps, index));
        card.setTools(ApproachFlow.canMove(steps, index, -1), ApproachFlow.canMove(steps, index, 1),
                ApproachFlow.canCopy(step), ApproachFlow.canDelete(step));
        card.setWhen(kind != QuestionKind.CHAIN, step.getWhen());
        card.setTries(true, LearnFlow.limitText(step.getCandidates()), defaultTries + " (default)");
        card.setKinds(kind == QuestionKind.TARGET, LearnFlow.limitText(step.getKinds()));
        card.setRulesShown(ApproachFlow.takesRules(step));
        card.setTuneOpen(tuned.contains(index));
        fillRules(card, index);

        card.setOnAction(action -> onCardAction(index, action));
        card.setOnWhen(guard -> edit(index, ApproachFlow.withWhen(steps.get(index), guard)));
        card.setOnTries(text -> {
            if (LearnFlow.limitProblem(text) == null) {
                edit(index, ApproachFlow.withTries(steps.get(index), LearnFlow.parseLimit(text)));
            }
        });
        card.setOnKinds(text -> {
            if (LearnFlow.limitProblem(text) == null) {
                edit(index, ApproachFlow.withKinds(steps.get(index), LearnFlow.parseLimit(text)));
            }
        });
        card.setOnTune(open -> {
            if (open) {
                tuned.add(index);
            } else {
                tuned.remove(index);
            }
        });
        card.setOnElements(ticked -> edit(index,
                steps.get(index).withElements(ApproachFlow.elements(ticked))));
        card.setOnRuleCondition((rule, condition) -> {
            final Transition was = steps.get(index).getTransitions().get(rule);
            // The fallback may be gone to only on running out of tries
            final boolean toFallback = was.getGoTo() != null && isFallback(was.getGoTo());
            final String goTo = toFallback && condition.getOutcome() != null
                    ? null
                    : was.getGoTo();
            editRules(index, ApproachFlow.setRule(steps, index, rule, new Transition(condition.getOutcome(), goTo)),
                    rule, false);
        });
        card.setOnRuleDestination((rule, destination) -> {
            final Transition was = steps.get(index).getTransitions().get(rule);
            editRules(index, ApproachFlow.setRule(steps, index, rule,
                    new Transition(was.getOn(), destination.getGoTo())), rule, true);
        });
        card.setOnRuleRemove(rule -> editRules(index, ApproachFlow.removeRule(steps, index, rule), -1, false));
    }

    private void fillRules(final StepCard card, final int index) {
        final FlowStep step = steps.get(index);
        card.clearRules();
        for (final Transition rule : step.getTransitions()) {
            final List<Condition> conditions = new ArrayList<>();
            for (final StepOutcome outcome : ApproachFlow.unusedConditions(step, rule)) {
                conditions.add(new Condition(outcome));
            }
            final List<Destination> destinations = ApproachFlow.destinations(steps, index, rule.getOn(),
                    rule.getGoTo());
            Destination goes = null;
            for (final Destination destination : destinations) {
                if (Objects.equals(destination.getGoTo(), rule.getGoTo())) {
                    goes = destination;
                }
            }
            card.addRule(conditions, new Condition(rule.getOn()), destinations, goes);
        }
        card.setCanAddRule(!ApproachFlow.unusedConditions(step, null).isEmpty());
    }

    private void onCardAction(final int index, final CardAction action) {
        if (readOnly) {
            return;
        }
        switch (action) {
            case MOVE_UP -> moveStep(index, -1);
            case MOVE_DOWN -> moveStep(index, 1);
            case COPY -> reshape(ApproachFlow.copy(steps, index), index + 1);
            case DELETE -> deleteStep(index);
            case ADD_RULE -> editRules(index, ApproachFlow.addRule(steps, index),
                    steps.get(index).getTransitions().size(), false);
            default -> throw new IllegalArgumentException("Unknown action " + action);
        }
    }

    private void moveStep(final int index, final int by) {
        if (ApproachFlow.canMove(steps, index, by)) {
            reshape(ApproachFlow.move(steps, index, by), index + by);
            if (card != null) {
                card.focusHeading();
            }
        }
    }

    /// A step nothing goes to is deleted at once; one that rules go to is asked about first, since those
    /// rules go too.
    private void deleteStep(final int index) {
        final FlowStep step = steps.get(index);
        final List<String> pointing = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep other = steps.get(i);
            if (i != index && other.getTransitions().stream()
                    .anyMatch(rule -> step.effectiveId().equals(rule.getGoTo()))) {
                pointing.add("'" + PlainWords.stepName(steps, i) + "'");
            }
        }
        if (pointing.isEmpty()) {
            reshape(ApproachFlow.delete(steps, index), index);
            return;
        }
        final List<FlowStep> before = steps;
        ConfirmEvent.fire(this,
                "Delete '" + PlainWords.stepName(steps, index) + "'? Rules on " + PlainWords.list(pointing)
                + " go to it; they will be removed, and those steps will do what they usually do.",
                ok -> {
                    // Only if the flow is as it was when this was asked
                    if (ok && !isDisposed() && steps == before) {
                        reshape(ApproachFlow.delete(steps, index), index);
                    }
                });
    }

    // The steps changed shape: the card is made again, the given step chosen, before the owner is told
    private void reshape(final List<FlowStep> changed, final int select) {
        if (readOnly) {
            return;
        }
        steps = changed;
        selected = select;
        tuned.clear();
        rebuild();
        changed();
    }

    // One step changed within its card
    private void edit(final int index, final FlowStep step) {
        if (readOnly || Objects.equals(steps.get(index), step)) {
            return;
        }
        steps = ApproachFlow.replace(steps, index, step);
        refresh();
        changed();
        if (index == selected && texts == Texts.STEP) {
            // A Find records step's guard decides which of its texts it is asked with
            final List<Template> now = LearnFlow.textsOf(step);
            if (!now.equals(view.getTexts())) {
                showTexts(Texts.STEP);
            }
        }
    }

    // A step's rules changed: its rules are made again, and the focus put back where it was
    private void editRules(final int index, final List<FlowStep> changed, final int rule, final boolean onDestination) {
        if (readOnly) {
            return;
        }
        steps = changed;
        if (card != null && index == selected) {
            fillRules(card, index);
            card.focusRule(rule, onDestination);
        }
        refresh();
        changed();
    }

    // The document is changed, and the owner told
    private void changed() {
        markChanged();
        onChange.run();
    }

    // ---- Starting points, adding, the text ----

    /// The default setups (the example flows) as a menu below the Load default setup button, each with what it
    /// does; the one in use is greyed.
    ///
    /// @param opener The button.
    public void showDefaultsMenu(final Element opener) {
        if (readOnly) {
            return;
        }
        final FlowExample inUse = example();
        final List<Item> items = new ArrayList<>();
        for (final FlowExample example : FlowExample.values()) {
            items.add(new SimpleMenuItem.Builder()
                    .text(example == inUse
                            ? PlainWords.example(example) + " (in use)"
                            : PlainWords.example(example))
                    .tooltip(PlainWords.exampleDescription(example))
                    .enabled(example != inUse)
                    .command(() -> startFrom(example))
                    .build());
        }
        showMenu("Default setups", items, opener);
    }

    /// Whether a step may be added after the step chosen: not after the fallback, which comes last, nor where
    /// no kind of step may be added.
    ///
    /// @return Whether the route map offers Add step under it.
    public boolean canAddAfterSelected() {
        if (readOnly || steps.isEmpty() || steps.get(selected).getKind() == QuestionKind.ADMIT) {
            return false;
        }
        for (final Addable addable : ApproachFlow.addable(steps)) {
            if (addable.getReason() == null) {
                return true;
            }
        }
        return false;
    }

    /// The kinds of step that may be added after the step chosen, as a menu below the route map's Add step
    /// button.
    ///
    /// @param opener The button.
    public void addAfterSelected(final Element opener) {
        showAddMenu(selected + 1, opener);
    }

    /// An example chosen as the starting point: its steps replace the flow's, once the user says yes (A34).
    /// Its first step is then chosen.
    ///
    /// @param example The example.
    public void startFrom(final FlowExample example) {
        if (readOnly || example.steps().equals(steps)) {
            return;
        }
        ConfirmEvent.fire(this,
                "Load the default setup '" + PlainWords.example(example) + "'? Its "
                + PlainWords.count(example.steps().size(),
                        "step") + " replace the " + PlainWords.count(steps.size(), "step") + " there are now.",
                ok -> {
                    if (ok && !isDisposed()) {
                        reshape(new ArrayList<>(example.steps()), 0);
                    }
                });
    }

    /// The kinds of step that may be added, as a menu below the place: those that can't be added are shown
    /// greyed, with why.
    private void showAddMenu(final int at, final Element opener) {
        if (readOnly) {
            return;
        }
        final List<Item> items = new ArrayList<>();
        for (final Addable addable : ApproachFlow.addable(steps)) {
            final SimpleMenuItem.Builder item = new SimpleMenuItem.Builder()
                    .text(addable.getOption().getDisplayValue())
                    .enabled(addable.getReason() == null)
                    .command(() -> reshape(ApproachFlow.insert(steps, at, addable.getOption()), at));
            if (addable.getReason() != null) {
                item.tooltip(addable.getReason());
            }
            items.add(item.build());
        }
        showMenu("Add a step", items, opener);
    }

    // A menu below its opener, placed by the opener's box, which an SVG element of the route map has too
    private void showMenu(final String name, final List<Item> items, final Element opener) {
        ShowMenuEvent
                .builder()
                .name(name)
                .items(items)
                .popupPosition(new PopupPosition(ElementUtil.getBoundingClientRectPlusWindowScroll(opener),
                        PopupLocation.BELOW))
                .opener(opener)
                .fire(this);
    }

    // ---- Question texts ----

    // The built-in text of every template, so that an override is edited from the words it replaces;
    // fetched once, when the tab is first read
    private void fetchBuiltIns() {
        if (builtInsRequested) {
            return;
        }
        builtInsRequested = true;
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterAiResource::templates)
                .onSuccess(served -> {
                    builtIns = new HashMap<>(served.getTemplates());
                    builtInVersion = served.getVersion();
                    showWords();
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    /// Shows the question texts of the step chosen, or those every question shares.
    private void showTexts(final Texts which) {
        texts = which;
        if (which == Texts.SHARED) {
            view.setTexts("Question text every step shares", "Show this step's text", LearnFlow.SHARED_TEXTS);
        } else if (steps.isEmpty()) {
            view.setTexts("Question text", "Show the text every step shares", List.of());
        } else {
            final FlowStep step = steps.get(selected);
            view.setTexts(step.getKind() == QuestionKind.ADMIT
                            ? "Question text of the fallback"
                            : "Question text of step " + (selected + 1) + ": " + PlainWords.stepName(steps, selected),
                    "Show the text every step shares", LearnFlow.textsOf(step));
        }
        showWords();
    }

    /// Every text shown as this document uses it, and where its words come from.
    private void showWords() {
        for (final Template template : view.getTexts()) {
            final String override = overrides.get(template);
            view.showText(template,
                    override != null
                            ? override
                            : builtIns.getOrDefault(template, ""),
                    LearnFlow.textStatus(template, override != null, builtIns.containsKey(template),
                            builtInVersion, flow.getBuiltInVersion()));
        }
    }

    /// One text edited in place, or loaded from a built-in: words equal to the built-in are no change, and
    /// the document goes on following the built-in.
    private void setText(final Template template, final String words) {
        if (words == null || words.equals(builtIns.get(template))) {
            overrides.remove(template);
        } else {
            overrides.put(template, words);
        }
        showWords();
        changed();
    }

    /// A built-in text loaded into one of the texts, from a dialog that lists the built-ins whose variables
    /// the text allows, with the words shown before they are loaded.
    private void loadBuiltIn(final Template slot) {
        if (readOnly) {
            return;
        }
        builtInTextProvider.get().load(this, slot, LearnFlow.suitable(slot), builtIns,
                words -> setText(slot, words));
    }

    // ---- Helpers ----

    /// The flow's own check, the one the store runs, asked of the steps, with the warnings the Learning
    /// Flow's checks raise.
    private List<FlowProblem> check() {
        return new LearningFlow(new ArrayList<>(steps), null, null).check(checks);
    }

    private boolean isFallback(final String id) {
        return steps.stream().anyMatch(step -> step.getKind() == QuestionKind.ADMIT && step.effectiveId().equals(id));
    }


    // --------------------------------------------------------------------------------


    /// Which question texts are shown beneath the step.
    private enum Texts {
        /// The step chosen's.
        STEP,
        /// Those every question shares.
        SHARED
    }
}
