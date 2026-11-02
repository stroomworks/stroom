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

import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.Template;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.dropdowntree.client.DropDownBox;
import stroom.widget.duration.client.DurationPicker;
import stroom.widget.form.client.TextArea;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
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

/// The Shapeshifter AI document's Learn tab (design 05; A82): the learning flow drawn as a flowchart,
/// the selected step's form beside it with the step actions above, the texts the selected node is asked
/// with beneath the form (A85, A86), the session's own settings at Start (A94), and the same flow as
/// text beneath the chart (A87). [LearnTabController] fills it.
public class LearnTab extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ProcessHeader processHeader;

    @UiField
    FlowChart chart;
    @UiField
    ToolbarButton addStep;
    @UiField
    ToolbarButton copyStep;
    @UiField
    ToolbarButton deleteStep;
    @UiField
    ToolbarButton moveUp;
    @UiField
    ToolbarButton moveDown;
    /// Loading an example replaces the steps, so it is a button and not a picker: what it does is done
    /// once, and nothing about it describes the flow afterwards.
    @UiField
    ToolbarButton loadExample;
    @UiField
    FlowPanel nothingIntro;
    @UiField
    FlowPanel startIntro;
    @UiField
    FlowPanel endIntro;
    @UiField
    FlowPanel stepIntro;
    @UiField
    Label stepHeading;
    @UiField
    Label stepProblem;
    /// The session's settings, at Start (A94).
    @UiField
    FlowPanel startSection;
    @UiField
    SelectionBox<LearningMode> learningMode;
    @UiField
    DropDownBox model;
    @UiField
    TextArea instructions;
    @UiField
    ValueSpinner maxAttempts;
    @UiField
    DurationPicker attemptBudget;
    @UiField
    ValueSpinner tokenBudget;
    @UiField
    ValueSpinner sampleSizeLimit;
    @UiField
    DurationPicker maxDeferralAge;
    @UiField
    FlowStepEditor stepEditor;
    @UiField
    Label stepDefaults;
    @UiField
    FlowPanel textSection;
    /// The texts the selected node is asked with, one each.
    @UiField
    FlowPanel texts;
    @UiField
    Label flowProblem;
    /// The same flow in the grammar the harness and import/export carry, two-way (A87).
    @UiField
    TextArea flowText;

    private final Map<Template, LearnQuestionText> slots = new LinkedHashMap<>();
    private boolean stepShown;
    /// What the chart paints as selected, kept here so that a redraw after an edit keeps it.
    private Integer selectedStep;
    private Consumer<StepAction> onStepAction = action -> {
    };
    private Consumer<Element> onLoadExample = opener -> {
    };
    /// The durations as read, kept while the pickers still show them ([MillisDurations#toMillis]).
    private long readAttemptBudgetMs;
    private long readMaxDeferralAgeMs;
    private Runnable onSettingChange = () -> {
    };
    private Consumer<String> onFlowTextEdit = text -> {
    };
    private BiConsumer<Template, String> onTextEdit = (template, text) -> {
    };
    private Consumer<Template> onLoadBuiltIn = template -> {
    };

    /// Makes the tab, with no flow and nothing selected.
    public LearnTab() {
        initWidget(BINDER.createAndBindUi(this));
        maxAttempts.setMin(1);
        maxAttempts.setMax(Integer.MAX_VALUE);
        tokenBudget.setMin(0);
        tokenBudget.setMax(Long.MAX_VALUE);
        sampleSizeLimit.setMin(1);
        sampleSizeLimit.setMax(Integer.MAX_VALUE);
        learningMode.addItems(LearningMode.values());

        addStep.setOnClick(() -> stepAction(StepAction.ADD));
        copyStep.setOnClick(() -> stepAction(StepAction.COPY));
        deleteStep.setOnClick(() -> stepAction(StepAction.DELETE));
        moveUp.setOnClick(() -> stepAction(StepAction.MOVE_UP));
        moveDown.setOnClick(() -> stepAction(StepAction.MOVE_DOWN));
        loadExample.setOnClick(() -> {
            if (!isReadOnly()) {
                onLoadExample.accept(loadExample.getElement());
            }
        });

        learningMode.setOnChange(value -> onSettingChange.run());
        instructions.addValueChangeHandler(event -> onSettingChange.run());
        maxAttempts.setOnChange(value -> onSettingChange.run());
        attemptBudget.setOnChange(value -> onSettingChange.run());
        tokenBudget.setOnChange(value -> onSettingChange.run());
        sampleSizeLimit.setOnChange(value -> onSettingChange.run());
        maxDeferralAge.setOnChange(value -> onSettingChange.run());
        flowText.addValueChangeHandler(event -> onFlowTextEdit.accept(event.getValue() == null
                ? ""
                : event.getValue()));
        showNothingSelected();
    }

    /// @return The form of the selected step, which its controller attaches the form's controller to.
    public FlowStepEditor getStepEditor() {
        return stepEditor;
    }

    /// @return The model picker, which its controller attaches a document picker's controller to.
    public DropDownBox getModelBox() {
        return model;
    }

    /// @param onStepAction Told which step action was pressed (not when read only).
    public void setOnStepAction(final Consumer<StepAction> onStepAction) {
        this.onStepAction = onStepAction;
    }

    /// @param onLoadExample Given the Load an example button, where the menu of examples opens (not
    ///                      when read only).
    public void setOnLoadExample(final Consumer<Element> onLoadExample) {
        this.onLoadExample = onLoadExample;
    }

    /// @param onSettingChange Run when the user changes one of the session's settings.
    public void setOnSettingChange(final Runnable onSettingChange) {
        this.onSettingChange = onSettingChange;
    }

    /// @param onFlowTextEdit Told the flow's text when the user edits it.
    public void setOnFlowTextEdit(final Consumer<String> onFlowTextEdit) {
        this.onFlowTextEdit = onFlowTextEdit;
    }

    /// @param onTextEdit Told a question text and its words when the user edits them.
    public void setOnTextEdit(final BiConsumer<Template, String> onTextEdit) {
        this.onTextEdit = onTextEdit;
    }

    /// @param onLoadBuiltIn Told the question text a built-in is to be loaded into.
    public void setOnLoadBuiltIn(final Consumer<Template> onLoadBuiltIn) {
        this.onLoadBuiltIn = onLoadBuiltIn;
    }

    /// @param onSelected Told which step was clicked on the chart, by its index.
    public void setOnStepSelected(final Consumer<Integer> onSelected) {
        chart.setOnSelect(onSelected);
    }

    /// @param onSelected Told that Start or End was clicked on the chart: [FlowChart#START] or
    ///                   [FlowChart#END].
    public void setOnTerminalSelected(final Consumer<String> onSelected) {
        chart.setOnTerminalSelect(onSelected);
    }

    /// @param onSelected Told which declared transition was clicked on the chart: the step's index and
    ///                   the outcome's name, or `spent`.
    public void setOnEdgeSelected(final BiConsumer<Integer, String> onSelected) {
        chart.setOnEdgeSelect(onSelected);
    }

    /// Draws the flow, with what is wrong with it painted on the steps it names.
    ///
    /// @param steps    The steps.
    /// @param problems What is wrong with the flow.
    public void showChart(final List<FlowStep> steps, final List<FlowProblem> problems) {
        if (selectedStep != null && selectedStep >= steps.size()) {
            selectedStep = null;
        }
        chart.show(new ArrayList<>(steps), problems, null, selectedStep);
    }

    /// @param index The step the chart paints as selected, or null for none.
    public void setSelectedStep(final Integer index) {
        selectedStep = index;
        chart.setSelected(index);
    }

    /// @param terminal Start or End, which the chart paints as selected.
    public void setSelectedTerminal(final String terminal) {
        selectedStep = null;
        chart.setSelectedTerminal(terminal);
    }

    /// The side panel showing a step: its place and title, its form, what it does by default, and
    /// what is wrong with it.
    ///
    /// @param heading  Its place and title.
    /// @param defaults What it does where the flow says nothing.
    /// @param problem  What is wrong with it, or null for nothing.
    public void showStep(final String heading, final String defaults, final String problem) {
        stepHeading.setText(heading);
        stepDefaults.setText(defaults);
        setStepProblem(problem);
        show(stepIntro, true, false);
    }

    /// The side panel showing Start: the session's own settings and the texts every question shares.
    public void showStart() {
        show(startIntro, false, true);
    }

    /// The side panel showing End: what ending means.
    public void showEnd() {
        show(endIntro, false, false);
    }

    /// The side panel with nothing selected: how to choose something.
    public void showNothingSelected() {
        show(nothingIntro, false, false);
    }

    private void show(final FlowPanel intro, final boolean step, final boolean start) {
        stepShown = step;
        nothingIntro.setVisible(intro == nothingIntro);
        startIntro.setVisible(intro == startIntro);
        endIntro.setVisible(intro == endIntro);
        stepIntro.setVisible(intro == stepIntro);
        if (!step) {
            setStepProblem(null);
        }
        stepEditor.setVisible(step);
        stepDefaults.setVisible(step);
        startSection.setVisible(start);
        updateButtons();
    }

    /// @param problem What the form cannot stop a person writing, said beside it; null clears it.
    public void setStepProblem(final String problem) {
        stepProblem.setText(problem == null
                ? ""
                : problem);
        stepProblem.setVisible(problem != null);
    }

    /// What is wrong with the flow as a whole, beneath the chart: the document is written on every
    /// dirty check, so a fault here must not be a modal.
    ///
    /// @param problem What is wrong, or null for nothing.
    public void setFlowProblem(final String problem) {
        flowProblem.setText(problem == null
                ? ""
                : problem);
        flowProblem.setVisible(problem != null);
    }

    /// @param text The flow in the grammar import, export and the harness carry, one step a line.
    public void setFlowText(final String text) {
        flowText.setValue(text);
    }

    /// The texts the selected node is asked with, each shown and edited in its own place: a step's
    /// kind's, or the shared ones at Start; none at End, which hides the section.
    ///
    /// @param templates The texts.
    public void setTexts(final List<Template> templates) {
        slots.clear();
        texts.clear();
        for (final Template template : templates) {
            final LearnQuestionText slot = new LearnQuestionText();
            slot.setName(template.getDisplayValue());
            slot.setVariables(LearnFlow.variablesTitle(template));
            slot.setReadOnly(isReadOnly());
            slot.setOnEdit(words -> onTextEdit.accept(template, words));
            slot.setOnLoad(() -> onLoadBuiltIn.accept(template));
            slots.put(template, slot);
            texts.add(slot);
        }
        textSection.setVisible(!templates.isEmpty());
    }

    /// @return The texts shown, in order.
    public List<Template> getTexts() {
        return new ArrayList<>(slots.keySet());
    }

    /// Shows the words of one of the texts shown, and where they come from.
    ///
    /// @param template The text.
    /// @param words    Its words.
    /// @param source   Where they come from.
    public void showText(final Template template, final String words, final String source) {
        final LearnQuestionText slot = slots.get(template);
        if (slot != null) {
            slot.setWords(words, source);
        }
    }

    /// @return The model's instructions.
    public String getInstructions() {
        return instructions.getValue();
    }

    /// @param text Free-text instructions added to every question.
    public void setInstructions(final String text) {
        instructions.setValue(text);
    }

    /// @return The default candidates.
    public int getMaxAttempts() {
        return maxAttempts.getIntValue();
    }

    /// @param value The default candidates.
    public void setMaxAttempts(final int value) {
        maxAttempts.setValue(value);
    }

    /// @return The learning mode chosen.
    public LearningMode getLearningMode() {
        return learningMode.getValue();
    }

    /// @param mode The learning mode.
    public void setLearningMode(final LearningMode mode) {
        learningMode.setValue(mode);
    }

    /// @return The maximum deferral age, in milliseconds.
    public long getMaxDeferralAgeMs() {
        return MillisDurations.toMillis(maxDeferralAge.getValue(), readMaxDeferralAgeMs);
    }

    /// @param value The maximum deferral age, in milliseconds.
    public void setMaxDeferralAgeMs(final long value) {
        readMaxDeferralAgeMs = value;
        maxDeferralAge.setValue(MillisDurations.toDuration(value));
    }

    /// @return The time budget of one run, in milliseconds.
    public long getAttemptBudgetMs() {
        return MillisDurations.toMillis(attemptBudget.getValue(), readAttemptBudgetMs);
    }

    /// @param value The time budget of one run, in milliseconds.
    public void setAttemptBudgetMs(final long value) {
        readAttemptBudgetMs = value;
        attemptBudget.setValue(MillisDurations.toDuration(value));
    }

    /// @return The token budget, or null for unlimited, which the field shows as zero.
    public Long getTokenBudget() {
        final long tokens = tokenBudget.getValue();
        return tokens == 0
                ? null
                : tokens;
    }

    /// @param value The token budget, or null for unlimited, shown as zero.
    public void setTokenBudget(final Long value) {
        tokenBudget.setValue(value == null
                ? 0
                : value);
    }

    /// @return The sample size limit, in bytes.
    public int getSampleSizeLimit() {
        return sampleSizeLimit.getIntValue();
    }

    /// @param value The sample size limit, in bytes.
    public void setSampleSizeLimit(final int value) {
        sampleSizeLimit.setValue(value);
    }

    /// @return The process across the top of the tab, this stage marked, which the document keeps.
    public ProcessHeader getProcessHeader() {
        return processHeader;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        flowText.setReadOnly(readOnly);
        learningMode.setReadOnly(readOnly);
        instructions.setReadOnly(readOnly);
        maxAttempts.setReadOnly(readOnly);
        attemptBudget.setReadOnly(readOnly);
        tokenBudget.setReadOnly(readOnly);
        sampleSizeLimit.setReadOnly(readOnly);
        maxDeferralAge.setReadOnly(readOnly);
        slots.values().forEach(slot -> slot.setReadOnly(readOnly));
        updateButtons();
    }

    private void stepAction(final StepAction action) {
        if (!isReadOnly()) {
            onStepAction.accept(action);
        }
    }

    // Copy, Delete, Up and Down act on the step shown, so are shown only with one
    private void updateButtons() {
        final boolean enabled = !isReadOnly();
        addStep.setEnabled(enabled);
        loadExample.setEnabled(enabled);
        copyStep.setVisible(stepShown);
        deleteStep.setVisible(stepShown);
        moveUp.setVisible(stepShown);
        moveDown.setVisible(stepShown);
        copyStep.setEnabled(enabled);
        deleteStep.setEnabled(enabled);
        moveUp.setEnabled(enabled);
        moveDown.setEnabled(enabled);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, LearnTab> {

    }


    // --------------------------------------------------------------------------------


    /// What the step actions above the form do.
    public enum StepAction {
        /// A step after the selected one, or at the end.
        ADD,
        /// The selected step, copied without its id, after itself.
        COPY,
        /// The selected step removed.
        DELETE,
        /// The selected step moved up one.
        MOVE_UP,
        /// The selected step moved down one.
        MOVE_DOWN
    }
}
