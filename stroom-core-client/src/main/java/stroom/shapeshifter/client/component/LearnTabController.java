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
import stroom.explorer.client.component.DocSelectionController;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.client.component.LearnTab.StepAction;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.shapeshifter.shared.Template;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.menu.client.Item;
import stroom.widget.menu.client.ShowMenuEvent;
import stroom.widget.menu.client.SimpleMenuItem;
import stroom.widget.popup.client.PopupPosition;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The Shapeshifter AI document's Learn tab ([LearnTab]; design 05; A82): the learning flow drawn as a
/// flowchart and edited in it. A step clicked on the chart opens in the form beside it
/// ([FlowStepEditorController]), and every change to the form redraws the chart; its transitions are
/// listed in the form, and a declared edge clicked on the chart selects its transition there. Steps
/// are added after the selected one, copied, removed and moved from the toolbar above the form. The
/// question text each kind of step is asked with sits beneath the form (A85), and the texts every
/// question shares, with the session's own settings, open from Start (A86, A94). The same flow as text
/// sits beneath the chart, two-way (A87). What is wrong with the flow paints the step it names and is
/// said beside it (A88).
///
/// The process the document runs is across the top of the tab ([ProcessHeader], A98), this stage
/// marked; the document keeps its figures.
public class LearnTabController extends Controller {

    private static final ShapeshifterAiResource RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final LearnTab view;
    private final RestFactory restFactory;
    private final FlowStepEditorController stepEditor;
    private final DocSelectionController modelSelection;
    private final Provider<BuiltInTextController> builtInTextProvider;
    private final List<FlowStep> steps = new ArrayList<>();
    /// This document's changed texts, by template; the built-ins the node serves are shown where the
    /// document has not changed one.
    private final Map<Template, String> overrides = new HashMap<>();
    private Map<Template, String> builtIns = new HashMap<>();
    private Integer builtInVersion;
    private boolean builtInsRequested;
    /// The step open in the form, or null where Start, End or nothing is selected.
    private Integer selected;
    /// The flow as read, for what the chart does not hold: the built-in version its texts were saved
    /// against.
    private LearningFlow flow = LearningFlow.of(FlowExample.DIRECT);
    private boolean readOnly = true;

    /// Made by GIN, with its tab and the form of the selected step.
    ///
    /// @param eventBus             The application's event bus.
    /// @param view                 The tab.
    /// @param stepEditor           The form of the selected step.
    /// @param docSelectionProvider Makes the model's picker.
    /// @param builtInTextProvider  Makes the dialog that loads a built-in text.
    /// @param restFactory          Fetches the built-in texts.
    @Inject
    public LearnTabController(final EventBus eventBus,
                              final LearnTab view,
                              final FlowStepEditorController stepEditor,
                              final Provider<DocSelectionController> docSelectionProvider,
                              final Provider<BuiltInTextController> builtInTextProvider,
                              final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        this.builtInTextProvider = builtInTextProvider;
        this.restFactory = restFactory;
        initWidget(view);

        this.stepEditor = own(stepEditor).attach(view.getStepEditor());
        stepEditor.setOnChange(this::stepEdited);

        // The session's own settings open from Start (A94): the model it talks to, the instructions
        // every question carries, and what it may spend
        modelSelection = make(docSelectionProvider);
        modelSelection.attach(view.getModelBox());
        modelSelection.setIncludedTypes(OpenAIModelDoc.TYPE);
        modelSelection.setRequiredPermissions(DocumentPermission.USE);
        own(modelSelection.addSelectionListener(docRef -> markChanged()));

        view.setOnStepSelected(this::select);
        view.setOnTerminalSelected(this::selectTerminal);
        view.setOnEdgeSelected((step, edge) -> {
            select(step);
            stepEditor.selectTransition(edge);
        });
        view.setOnStepAction(this::onStepAction);
        view.setOnLoadExample(this::showExamples);
        view.setOnSettingChange(this::markChanged);
        view.setOnFlowTextEdit(this::flowTextEdited);
        view.setOnTextEdit(this::textEdited);
        view.setOnLoadBuiltIn(this::loadBuiltIn);

        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
            modelSelection.setReadOnly(readOnly);
        });
    }

    /// Shows a Shapeshifter AI document's flow and learning settings, with its first step selected.
    ///
    /// @param docRef The document.
    /// @param doc    Its content.
    public void read(final DocRef docRef, final ShapeshifterAiDoc doc) {
        fetchBuiltIns();
        modelSelection.setSelectedEntityReference(doc.getModel(), true);
        view.setInstructions(doc.getInstructions());
        view.setMaxAttempts(doc.getMaxAttempts());
        view.setAttemptBudgetMs(doc.getAttemptBudgetMs());
        view.setLearningMode(doc.getLearningMode());
        view.setMaxDeferralAgeMs(doc.getMaxDeferralAgeMs());
        view.setTokenBudget(doc.getTokenBudget());
        view.setSampleSizeLimit(doc.getSampleSizeLimit());
        flow = doc.getFlow();
        steps.clear();
        steps.addAll(flow.getSteps());
        overrides.clear();
        overrides.putAll(flow.getTemplates());
        flowChanged();
        select(steps.isEmpty()
                ? null
                : 0);
    }

    /// Writes what the tab shows into a copy of a document.
    ///
    /// @param doc The document.
    /// @return A copy of it with the tab's flow and learning settings.
    public ShapeshifterAiDoc write(final ShapeshifterAiDoc doc) {
        return doc.copy()
                .model(modelSelection.getSelectedEntityReference())
                .instructions(view.getInstructions())
                .maxAttempts(view.getMaxAttempts())
                .attemptBudgetMs(view.getAttemptBudgetMs())
                .learningMode(view.getLearningMode())
                .maxDeferralAgeMs(view.getMaxDeferralAgeMs())
                .tokenBudget(view.getTokenBudget())
                .sampleSizeLimit(view.getSampleSizeLimit())
                .flow(new LearningFlow(new ArrayList<>(steps), new HashMap<>(overrides), flow.getBuiltInVersion()))
                .build();
    }

    /// @return The process across the top of the tab, this stage marked, which the document keeps
    ///         up to date.
    public ProcessHeader getProcessHeader() {
        return view.getProcessHeader();
    }

    // The built-in text of every template, so an override is edited from the words it replaces;
    // fetched once, when the tab is first read (and so owned)
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
                    showTexts();
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void onStepAction(final StepAction action) {
        if (readOnly) {
            return;
        }
        switch (action) {
            case ADD:
                addStep();
                break;
            case COPY:
                copyStep();
                break;
            case DELETE:
                deleteStep();
                break;
            case MOVE_UP:
                moveStep(-1);
                break;
            case MOVE_DOWN:
                moveStep(1);
                break;
            default:
                break;
        }
    }

    /// A step after the selected one, or at the end: where a step sits is part of what the flow means,
    /// so a person adding one while looking at another means it to go there.
    private void addStep() {
        final int at = selected == null
                ? steps.size()
                : selected + 1;
        steps.add(at, FlowStep.of(QuestionKind.CONFIGURE));
        flowChanged();
        markChanged();
        select(at);
    }

    /// Without its id: two steps of one name is a flow whose transitions cannot say which they mean,
    /// and the copy is the one that should be renamed.
    private void copyStep() {
        if (selected == null) {
            return;
        }
        final int at = selected + 1;
        steps.add(at, LearnFlow.copyWithoutId(steps.get(selected)));
        flowChanged();
        markChanged();
        select(at);
    }

    /// A step nothing else points at goes without ceremony; one a transition names is confirmed with
    /// what would be left pointing at nothing, since the store refuses a flow whose `goto` names no
    /// step and the refusal arrives a long way from the delete that caused it.
    private void deleteStep() {
        if (selected == null) {
            return;
        }
        final int at = selected;
        final FlowStep step = steps.get(at);
        final List<String> pointing = LearnFlow.pointingAt(steps, at);
        if (pointing.isEmpty()) {
            removeStep(at);
            return;
        }
        ConfirmEvent.fire(this,
                "Remove step '" + step.effectiveId() + "'? " + String.join(", ", pointing)
                + " goes to it, and a flow whose transition names no step is refused on save.",
                ok -> {
                    // The flow may have changed while it was asked: remove the step only if it is
                    // still where it was
                    if (ok && !isDisposed() && at < steps.size() && steps.get(at) == step) {
                        removeStep(at);
                    }
                });
    }

    private void removeStep(final int at) {
        steps.remove(at);
        flowChanged();
        markChanged();
        select(steps.isEmpty()
                ? null
                : Math.min(at, steps.size() - 1));
    }

    private void moveStep(final int by) {
        if (selected == null) {
            return;
        }
        final int from = selected;
        final int to = from + by;
        if (to < 0 || to >= steps.size()) {
            return;
        }
        final FlowStep step = steps.remove(from);
        steps.add(to, step);
        flowChanged();
        markChanged();
        select(to);
    }

    /// The examples as a menu below the button (A34, A37); a flow that has steps is confirmed first.
    private void showExamples(final Element opener) {
        if (readOnly) {
            return;
        }
        final List<Item> items = new ArrayList<>();
        for (final FlowExample example : FlowExample.values()) {
            items.add(new SimpleMenuItem.Builder()
                    .text(example.getDisplayValue())
                    .command(() -> confirmThenLoad(example))
                    .build());
        }
        ShowMenuEvent
                .builder()
                .name("Examples")
                .items(items)
                .popupPosition(new PopupPosition(opener.getAbsoluteLeft() - 3,
                        opener.getAbsoluteTop() + opener.getClientHeight() + 1))
                .opener(opener)
                .fire(this);
    }

    private void confirmThenLoad(final FlowExample example) {
        final int count = steps.size();
        if (count == 0) {
            loadExample(example);
            return;
        }
        ConfirmEvent.fire(this,
                "Replace this flow's " + count + " step" + (count == 1
                        ? ""
                        : "s") + " with the " + example.getDisplayValue() + " example?",
                ok -> {
                    if (ok && !isDisposed()) {
                        loadExample(example);
                    }
                });
    }

    /// An example flow was chosen: its steps are what the flow starts from (A34). A starting point and
    /// not a setting, since what is saved is the steps, which the person then edits.
    private void loadExample(final FlowExample example) {
        if (readOnly) {
            return;
        }
        final List<FlowStep> exampleSteps = example.steps();
        steps.clear();
        steps.addAll(exampleSteps);
        select(exampleSteps.isEmpty()
                ? null
                : 0);
        flowChanged();
        markChanged();
    }

    /// The text beneath the chart was edited (A87): every line is parsed, and the chart follows where
    /// every line parses. A line that does not is reported where the flow's problems are, and nothing
    /// changes until it does.
    private void flowTextEdited(final String text) {
        if (readOnly) {
            return;
        }
        final List<FlowStep> parsed;
        try {
            parsed = LearnFlow.parse(text);
        } catch (final IllegalArgumentException e) {
            view.setFlowProblem(e.getMessage());
            return;
        }
        steps.clear();
        steps.addAll(parsed);
        flowChanged();
        markChanged();
        select(selected != null && selected < steps.size()
                ? selected
                : null);
    }

    /// A step was clicked, or chosen by an edit: it opens in the form, and the chart paints it.
    private void select(final Integer index) {
        selected = index == null || index < 0 || index >= steps.size()
                ? null
                : index;
        view.setSelectedStep(selected);
        if (selected == null) {
            view.showNothingSelected();
            setTexts(LearnFlow.SHARED_TEXTS);
            return;
        }
        final FlowStep step = steps.get(selected);
        stepEditor.setDefaultCandidates(view.getMaxAttempts());
        stepEditor.read(step, ids());
        view.showStep(LearnFlow.heading(selected + 1, steps.size(), step), LearnFlow.defaults(step),
                LearnFlow.problemsOf(check(), selected));
        setTexts(LearnFlow.textsOf(step));
    }

    /// Start or End was clicked: Start opens the session's settings and the shared texts (A86, A94),
    /// End says what ending means.
    private void selectTerminal(final String terminal) {
        selected = null;
        view.setSelectedTerminal(terminal);
        if (FlowChart.START.equals(terminal)) {
            view.showStart();
            setTexts(LearnFlow.SHARED_TEXTS);
        } else {
            view.showEnd();
            setTexts(List.of());
        }
    }

    /// A field of the form, or a transition, changed: the step is rewritten from the form where the
    /// form can be read as a step, and the chart follows. What the form cannot stop a person writing
    /// (an id that is not a word, a limit below one, a name another step has) is said beside the form,
    /// and the step keeps its last good state until it is put right.
    private void stepEdited() {
        if (selected == null || readOnly) {
            return;
        }
        final FlowStep written;
        try {
            written = stepEditor.write();
        } catch (final RuntimeException fault) {
            view.setStepProblem(fault.getMessage());
            return;
        }
        final String clash = LearnFlow.nameClash(steps, selected, written);
        if (clash != null) {
            view.setStepProblem(clash);
            return;
        }
        steps.set(selected, written);
        flowChanged();
        markChanged();
        view.showStep(LearnFlow.heading(selected + 1, steps.size(), written), LearnFlow.defaults(written),
                LearnFlow.problemsOf(check(), selected));
        // Only where the texts the step is asked with have changed (its kind or guard), so that the
        // words being edited beneath the form aren't redrawn under the cursor
        final List<Template> texts = LearnFlow.textsOf(written);
        if (!texts.equals(view.getTexts())) {
            setTexts(texts);
        }
    }

    /// The flow changed: the chart and the text say what the steps now say, and what is wrong with
    /// the flow is painted and listed.
    private void flowChanged() {
        final List<FlowProblem> problems = check();
        view.showChart(steps, problems);
        view.setFlowText(LearnFlow.format(steps));
        view.setFlowProblem(LearnFlow.flowProblem(problems));
    }

    /// The flow's own check, which is the one the store runs, asked of the steps alone.
    private List<FlowProblem> check() {
        return new LearningFlow(new ArrayList<>(steps), null, null).check();
    }

    private List<String> ids() {
        final List<String> ids = new ArrayList<>();
        for (final FlowStep step : steps) {
            ids.add(step.effectiveId());
        }
        return ids;
    }

    private void setTexts(final List<Template> templates) {
        view.setTexts(templates);
        showTexts();
    }

    /// Every text shown as this document uses it, and where its words come from.
    private void showTexts() {
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

    /// One text edited in place, or loaded from a built-in: words equal to the built-in are no change,
    /// and the document keeps following the built-in.
    private void setText(final Template template, final String words) {
        if (words == null || words.equals(builtIns.get(template))) {
            overrides.remove(template);
        } else {
            overrides.put(template, words);
        }
        showTexts();
        markChanged();
    }

    private void textEdited(final Template template, final String words) {
        if (!readOnly) {
            setText(template, words);
        }
    }

    /// A built-in text is loaded into one of the node's texts from a dialog that lists the built-ins
    /// whose variables the text allows, with the words shown before they are loaded.
    private void loadBuiltIn(final Template slot) {
        if (readOnly) {
            return;
        }
        builtInTextProvider.get().load(this, slot, LearnFlow.suitable(slot), builtIns,
                words -> setText(slot, words));
    }
}
