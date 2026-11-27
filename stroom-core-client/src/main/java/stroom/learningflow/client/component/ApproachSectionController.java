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

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.learningflow.client.FlowExamplesCache;
import stroom.learningflow.client.component.ApproachFlow.Addable;
import stroom.learningflow.client.component.ApproachFlow.Condition;
import stroom.learningflow.client.component.ApproachFlow.Destination;
import stroom.learningflow.client.component.StepCard.CardAction;
import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowProblem;
import stroom.learningflow.shared.FlowProblem.Severity;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowResource;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.Template;
import stroom.learningflow.shared.Transition;
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

/// Edits the learning flow of a Learning Flow document: the steps it takes to learn how to translate a new
/// feed. The steps are selected one at a time on the Setup tab's route map and the selected one is shown in
/// [ApproachSection]. The first step chooses from the parsers and filters the AI can learn
/// ([FlowStep#LEARNABLE_ELEMENTS]).
///
/// Loading a default setup replaces the steps with a copy of that setup's ([#startFrom]); the document owns
/// its steps, so later changes to a default setup do not affect it. The selected step ([#select]) is edited in
/// place on its card, with every choice made from a menu ([ApproachFlow]), and the question texts it is asked
/// with are shown beneath. It also fills the question texts every step shares ([LearnFlow#SHARED_TEXTS]),
/// shown under Learning steps' Advanced options ([QuestionTexts]), as it holds all the document's texts. What
/// is wrong with a step is shown on its card, and what is wrong with the whole flow ([#flowProblem()]) is shown
/// under Learning steps; the server refuses to save a flow with problems.
///
/// The owning controller ([SetupTabController]) holds the rest of the document and draws the route map from
/// the steps ([#getSteps()], [#getSelected()], [#stepProblems()]). It supplies the number of tries a step has
/// when the step doesn't set its own ([#setDefaultTries]), and switches the fallback on or off from the
/// Approving new translations section ([#setFallback]). It is told of every change ([#setOnChange]),
/// including a change of selected step caused by an edit.
///
/// The card is rebuilt when another step is selected or the list of steps changes (a step added, moved,
/// copied or deleted, a default setup loaded, or the fallback switched). An edit within the card updates it in
/// place, so that the focus stays where it is.
public class ApproachSectionController extends Controller {

    private static final LearningFlowResource RESOURCE = GWT.create(LearningFlowResource.class);

    private final RestFactory restFactory;
    private final FlowExamplesCache flowExamples;
    private final Provider<BuiltInTextController> builtInTextProvider;
    private ApproachSection view;
    /// The question texts every step shares, under Learning steps' Advanced options.
    private QuestionTexts sharedTexts;
    /// The card of the selected step, or null if it has none (the fallback step).
    private StepCard card;
    /// This document's own question texts, by template. Where it has none, the built-in text is used.
    private final Map<Template, String> overrides = new HashMap<>();
    private Map<Template, String> builtIns = new HashMap<>();
    private Integer builtInVersion;
    private boolean builtInsRequested;
    private List<FlowStep> steps = new ArrayList<>();
    /// The flow as last read, kept only for the built-in version its texts were saved against. Its steps are
    /// never used ([#steps] holds those). Until a document is read it is an empty flow rather than one of the
    /// default setups, because the default setups are defined on the server and the client does not hold a
    /// copy of them.
    private LearningFlow flow = new LearningFlow(List.of(), Map.of(), null);
    /// The index of the selected step, whose card and question texts are shown.
    private int selected;
    /// The indexes of the steps whose "Fine-tune this step" section is expanded. All are collapsed again when
    /// the list of steps changes.
    private final Set<Integer> tuned = new HashSet<>();
    /// The number of tries a step has when it doesn't set its own, which its card shows as a placeholder.
    private int defaultTries;
    /// What is wrong with each step, by index, as last checked.
    private Map<Integer, String> stepProblems = new HashMap<>();
    /// The Learning Flow's quality checks, so that a warning can be given for a rule that depends on a check
    /// that is switched off. The Setup tab keeps them up to date as its Quality checks are edited.
    private List<CheckSetting> checks = new ArrayList<>();
    private String flowProblem;
    private Runnable onChange = () -> {
    };
    private boolean readOnly = true;

    /// Created by GIN; [#attach(ApproachSection, QuestionTexts)] then gives it its section and the shared texts.
    ///
    /// @param eventBus            The application's event bus.
    /// @param builtInTextProvider Creates the dialog that loads a built-in text.
    /// @param restFactory         Creates the REST request that fetches the built-in texts.
    /// @param flowExamples        Fetches and caches the default setups from the server.
    @Inject
    public ApproachSectionController(final EventBus eventBus,
                                     final Provider<BuiltInTextController> builtInTextProvider,
                                     final RestFactory restFactory,
                                     final FlowExamplesCache flowExamples) {
        super(eventBus);
        this.restFactory = restFactory;
        this.builtInTextProvider = builtInTextProvider;
        this.flowExamples = flowExamples;
    }

    /// Attaches this controller to its section and to the question texts every step shares, both declared in
    /// the owning screen's template.
    ///
    /// @param view        The section.
    /// @param sharedTexts The question texts every step shares, under Learning steps' Advanced options.
    /// @return This controller.
    public ApproachSectionController attach(final ApproachSection view, final QuestionTexts sharedTexts) {
        this.view = Objects.requireNonNull(view, "view");
        this.sharedTexts = Objects.requireNonNull(sharedTexts, "sharedTexts");
        initWidget(view);

        view.setOnTextEdit(this::editText);
        view.setOnLoadBuiltIn(this::loadBuiltIn);
        sharedTexts.setTexts(LearnFlow.SHARED_TEXTS);
        sharedTexts.setOnTextEdit(this::editText);
        sharedTexts.setOnLoadBuiltIn(this::loadBuiltIn);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
        });
        return this;
    }

    /// Shows a document's learning flow with its first step selected. If the steps are the same as those
    /// already shown (e.g. the document read back after a save), the selected step and the expanded sections
    /// are kept as they were.
    ///
    /// @param docRef The document.
    /// @param doc    Its content.
    public void read(final DocRef docRef, final LearningFlowDoc doc) {
        fetchBuiltIns();
        fetchFlowExamples();
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
        }
        rebuild();
    }

    /// @param doc The document.
    /// @return A copy of it with the flow as edited here. The default number of tries is not written here;
    ///         the owning controller writes it.
    public LearningFlowDoc write(final LearningFlowDoc doc) {
        return doc.copy()
                .flow(new LearningFlow(new ArrayList<>(steps), new HashMap<>(overrides), flow.getBuiltInVersion()))
                .build();
    }

    /// Sets the Learning Flow's quality checks, so that a step's card can warn about a rule that depends on a
    /// check that is switched off. The Setup tab calls this whenever its Quality checks change, since both are
    /// edited together.
    ///
    /// @param checks The checks, as currently edited in the Setup tab.
    public void setChecks(final List<CheckSetting> checks) {
        this.checks = new ArrayList<>(checks);
        refresh();
    }

    /// @param onChange Run after the user changes the flow, once the document has been marked as changed.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// Sets the number of tries a step has when it doesn't set its own, which each card shows as a
    /// placeholder. The owning controller edits this setting.
    ///
    /// @param tries The default number of tries.
    public void setDefaultTries(final int tries) {
        if (tries != defaultTries) {
            defaultTries = tries;
            rebuild();
        }
    }

    /// @return Whether the flow has the fallback step, which writes marked Unknown events for the records the
    ///         transform still can't translate when it runs out of tries.
    public boolean hasFallback() {
        return ApproachFlow.hasFallback(steps);
    }

    /// @return The fallback step's index in the flow, or -1 if it has none.
    public int fallbackIndex() {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getKind() == QuestionKind.ADMIT) {
                return i;
            }
        }
        return -1;
    }

    /// @return A copy of the flow's steps, as currently edited.
    public List<FlowStep> getSteps() {
        return new ArrayList<>(steps);
    }

    /// @return The index of the selected step.
    public int getSelected() {
        return selected;
    }

    /// @return What is wrong with each step, by index; a step with no problems has no entry.
    public Map<Integer, String> stepProblems() {
        return new HashMap<>(stepProblems);
    }

    /// @return What is wrong with the flow as a whole, or null if nothing is.
    public String flowProblem() {
        return flowProblem;
    }

    /// @return The default setup whose steps exactly match the current steps, or null if they match none.
    public FlowExample example() {
        return ApproachFlow.example(flowExamples.known(), steps);
    }

    /// Switches the fallback step on or off, as chosen under Approving new translations
    /// ([ApproachFlow#withFallback]).
    ///
    /// @param on Whether the flow should have the fallback step.
    public void setFallback(final boolean on) {
        if (on != hasFallback()) {
            reshape(ApproachFlow.withFallback(steps, on), selected);
        }
    }

    /// Selects a step, whose card and question texts are then shown.
    ///
    /// @param index The step's index. An index out of range is ignored.
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

    /// Rebuilds the selected step's card, and everything else that depends on the steps.
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
        showStepTexts();
    }

    /// Updates everything that depends on an edit within the card: the card's problem and warning, the
    /// description of what the step usually does, its tags and elements, and what is wrong with each step and
    /// with the flow as a whole.
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
        card.setStep(index + 1, FlowWords.stepName(steps, index));
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
            // A rule may go to the fallback only on running out of tries, so if the condition changes to
            // anything else, the rule goes back to the step's usual exit
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

    /// Deletes a step. A step that no rule goes to is deleted at once. If other steps' rules go to it, the
    /// user is asked to confirm first, since those rules are removed as well.
    private void deleteStep(final int index) {
        final FlowStep step = steps.get(index);
        final List<String> pointing = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep other = steps.get(i);
            if (i != index && other.getTransitions().stream()
                    .anyMatch(rule -> step.effectiveId().equals(rule.getGoTo()))) {
                pointing.add("'" + FlowWords.stepName(steps, i) + "'");
            }
        }
        if (pointing.isEmpty()) {
            reshape(ApproachFlow.delete(steps, index), index);
            return;
        }
        final List<FlowStep> before = steps;
        ConfirmEvent.fire(this,
                "Delete '" + FlowWords.stepName(steps, index) + "'? Rules on " + PlainWords.list(pointing)
                + " go to it; they will be removed, and those steps will do what they usually do.",
                ok -> {
                    // Only delete if the flow hasn't changed since the user was asked
                    if (ok && !isDisposed() && steps == before) {
                        reshape(ApproachFlow.delete(steps, index), index);
                    }
                });
    }

    // Replaces the list of steps after a structural change: selects the given step and rebuilds the card,
    // then tells the owner
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

    // Applies an edit made to one step within its card
    private void edit(final int index, final FlowStep step) {
        if (readOnly || Objects.equals(steps.get(index), step)) {
            return;
        }
        steps = ApproachFlow.replace(steps, index, step);
        refresh();
        changed();
        if (index == selected) {
            // A split step's guard decides which of its question texts it is asked with, so the texts shown
            // may need to change
            final List<Template> now = LearnFlow.textsOf(step);
            if (!now.equals(view.getTexts())) {
                showStepTexts();
            }
        }
    }

    // Applies a change to a step's rules: the rules are rebuilt on the card, and the focus put back where it
    // was
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

    // Marks the document as changed and tells the owner
    private void changed() {
        markChanged();
        onChange.run();
    }

    // ---- Default setups and adding steps ----

    /// Shows the default setups as a menu below the Load default setup button, each with a description as its
    /// tooltip. The one that matches the current steps is disabled.
    ///
    /// @param opener The button's element.
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

    /// Whether a step may be added after the selected step. It may not after the fallback, which must come
    /// last, nor when no kind of step may be added.
    ///
    /// @return Whether the route map should offer Add step under the selected step.
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

    /// Shows the kinds of step that may be added after the selected step, as a menu below the route map's Add
    /// step button.
    ///
    /// @param opener The button's element.
    public void addAfterSelected(final Element opener) {
        showAddMenu(selected + 1, opener);
    }

    /// Loads a default setup: once the user confirms, its steps replace the flow's, and its first step is
    /// selected. The document gets its own copy of the steps.
    ///
    /// @param example The default setup.
    public void startFrom(final FlowExample example) {
        if (readOnly) {
            return;
        }
        // The default setups are defined on the server, so they are fetched rather than read from a constant.
        // If they have already been fetched, the cache answers at once.
        flowExamples.get(examples -> {
            if (isDisposed()) {
                return;
            }
            final List<FlowStep> loaded = ApproachFlow.steps(examples, example);
            if (loaded.isEmpty() || loaded.equals(steps)) {
                return;
            }
            ConfirmEvent.fire(this,
                    "Load the default setup '" + PlainWords.example(example) + "'? Its "
                    + PlainWords.count(loaded.size(),
                            "step") + " replace the " + PlainWords.count(steps.size(), "step")
                    + " there are now.",
                    ok -> {
                        if (ok && !isDisposed()) {
                            reshape(new ArrayList<>(loaded), 0);
                        }
                    });
        }, taskMonitor());
    }

    /// Shows the kinds of step that may be added at a position, as a menu below the opener. Those that can't
    /// be added are disabled, with the reason as a tooltip.
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

    // Shows a menu below its opener. It is positioned using the opener's bounding box, which works for the
    // route map's SVG elements as well as for HTML ones
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

    /// Fetches the default setups, so that the flow can be named after the one it matches. The section is
    /// rebuilt when they arrive, so that a document that matches one says so rather than briefly appearing to
    /// have a setup of its own.
    private void fetchFlowExamples() {
        flowExamples.get(examples -> {
            if (!isDisposed()) {
                rebuild();
            }
        }, taskMonitor());
    }

    /// @return The default setups fetched so far, for the owning controller, which also names the flow.
    public List<BuiltInFlow> flowExamples() {
        return flowExamples.known();
    }

    // Fetches the built-in text of every template, so that the document's own text is edited starting from the
    // words it replaces. They are fetched once, when the tab is first read
    private void fetchBuiltIns() {
        if (builtInsRequested) {
            return;
        }
        builtInsRequested = true;
        restFactory
                .create(RESOURCE)
                .method(LearningFlowResource::fetchTemplates)
                .onSuccess(served -> {
                    builtIns = new HashMap<>(served.getTemplates());
                    builtInVersion = served.getVersion();
                    showWords();
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    /// Shows the question texts of the selected step.
    private void showStepTexts() {
        if (steps.isEmpty()) {
            view.setTexts("Question text", List.of());
        } else {
            final FlowStep step = steps.get(selected);
            view.setTexts(step.getKind() == QuestionKind.ADMIT
                            ? "Question text of the fallback"
                            : "Question text of step " + (selected + 1) + ": " + FlowWords.stepName(steps, selected),
                    LearnFlow.textsOf(step));
        }
        showWords();
    }

    /// Shows the words of every text shown, the step's and those every step shares, as this document uses
    /// them, and where they come from.
    private void showWords() {
        for (final Template template : view.getTexts()) {
            view.showText(template, words(template), source(template));
        }
        for (final Template template : sharedTexts.getTexts()) {
            sharedTexts.showText(template, words(template), source(template));
        }
    }

    // A text's words as this document uses them: its own, or the built-in's
    private String words(final Template template) {
        final String override = overrides.get(template);
        return override != null
                ? override
                : builtIns.getOrDefault(template, "");
    }

    // Where a text's words come from
    private String source(final Template template) {
        return LearnFlow.textStatus(template, overrides.get(template) != null, builtIns.containsKey(template),
                builtInVersion, flow.getBuiltInVersion());
    }

    // A text edited in place, by someone who may edit the document
    private void editText(final Template template, final String words) {
        if (!readOnly) {
            setText(template, words);
        }
    }

    /// Sets a text edited in place, or loaded from a built-in text. Words equal to the built-in text are not
    /// stored as the document's own, so the document keeps using the built-in text and follows later changes
    /// to it.
    private void setText(final Template template, final String words) {
        if (words == null || words.equals(builtIns.get(template))) {
            overrides.remove(template);
        } else {
            overrides.put(template, words);
        }
        showWords();
        changed();
    }

    /// Opens the dialog that loads a built-in text into one of the texts. It lists the built-in texts whose
    /// variables the text allows, and previews the words before they are loaded.
    private void loadBuiltIn(final Template slot) {
        if (readOnly) {
            return;
        }
        builtInTextProvider.get().load(this, slot, LearnFlow.suitable(slot), builtIns,
                words -> setText(slot, words));
    }

    // ---- Helpers ----

    /// Checks the steps with the same check the server runs when saving, plus the warnings that depend on the
    /// Learning Flow's quality checks.
    private List<FlowProblem> check() {
        return new LearningFlow(new ArrayList<>(steps), null, null).check(checks);
    }

    private boolean isFallback(final String id) {
        return steps.stream().anyMatch(step -> step.getKind() == QuestionKind.ADMIT && step.effectiveId().equals(id));
    }
}
