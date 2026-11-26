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

import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.Button;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.AriaPanel;
import stroom.widget.component.client.Component;
import stroom.widget.dropdowntree.client.DropDownBox;
import stroom.widget.duration.client.DurationPicker;
import stroom.widget.form.client.RadioGroup;
import stroom.widget.form.client.TextArea;
import stroom.widget.text.client.Label;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/// A Learning Flow document's Setup tab, where an administrator configures how the document learns.
///
/// A route map ([RouteMap]) runs down the side, and beside it is the one part of the setup chosen on the map
/// ([SetupStop]). The map's stops are the sections ([SetupSection]), in the order a translation passes through
/// them, with the learning flow's steps listed under Learning steps when they are shown. Each stop shows a
/// summary of its settings or its problem ([SetupSummary]).
///
/// The settings most people need are visible; those rarely changed are collapsed under each section's
/// "Advanced options" ([Disclosure], [AdvancedSettings]). The steps appear on the map only when someone chooses
/// to customise them, when they do not match one of the built-in flows, or when something is wrong with them.
///
/// The parts are:
/// - **AI model and instructions**: whether learning is on, the model, the target schema and the instructions.
/// - **Learning steps**: which built-in flow the steps match, a link to show or hide them, Load default setup
///   (a menu of the built-in flows, once the steps are shown), any problem with the flow as a whole, and the
///   time and token limits for a run; under Advanced options, the default tries per step and the maximum
///   sample size.
/// - **A step**: the step's card and question text ([ApproachSection]).
/// - **Quality checks**: the preset; under Advanced options, the checks ([CheckList]) and the share of lines
///   held back for testing.
/// - **Approving new translations**: whether a person must approve them; under Advanced options, the minimum
///   records needed to learn from a stream, and what happens when the transform cannot be finished (the
///   fallback).
/// - **Monitoring translations in use**: the score below which a translation in use is flagged; under Advanced
///   options, how many records are kept to check later translations against, and when a waiting stream is
///   overdue.
///
/// Fractions are shown as whole percentages. [SetupTabController] fills it.
public class SetupTab extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final String OWN = "These are this Learning Flow's own checks.";

    @UiField
    RouteMap map;
    @UiField
    FlowPanel page;
    @UiField
    AriaPanel learningPart;
    @UiField
    AriaPanel approachPart;
    @UiField
    AriaPanel stepPart;
    @UiField
    AriaPanel qualityPart;
    @UiField
    AriaPanel goingLivePart;
    @UiField
    AriaPanel inServicePart;
    @UiField
    RadioGroup<LearningMode> learningMode;
    @UiField
    DropDownBox model;
    @UiField
    DropDownBox targetSchema;
    @UiField
    Label targetSchemaNote;
    @UiField
    TextArea instructions;
    @UiField
    LinkButton stepsToggle;
    @UiField
    Button loadDefault;
    @UiField
    Label approachStatus;
    @UiField
    Label flowProblem;
    @UiField
    ApproachSection approach;
    @UiField
    Disclosure approachAdvanced;
    @UiField
    ValueSpinner maxAttempts;
    @UiField
    DurationPicker attemptBudget;
    @UiField
    ValueSpinner tokenBudget;
    @UiField
    ValueSpinner sampleSizeLimit;
    @UiField
    RadioGroup<CheckPreset> preset;
    @UiField
    Label presetDescription;
    @UiField
    Disclosure qualityAdvanced;
    @UiField
    CheckList checkList;
    @UiField
    ValueSpinner heldOutFraction;
    @UiField
    RadioGroup<PromotionMode> promotionMode;
    @UiField
    Disclosure goingLiveAdvanced;
    @UiField
    ValueSpinner minRecordsToLearn;
    @UiField
    RadioGroup<Boolean> fallback;
    @UiField
    LinkButton fallbackText;
    @UiField
    Label fallbackNote;
    @UiField
    ValueSpinner relearnThreshold;
    @UiField
    Disclosure inServiceAdvanced;
    @UiField
    ValueSpinner regressionCap;
    @UiField
    DurationPicker maxDeferralAge;

    private final Map<SetupSection, AriaPanel> parts = new EnumMap<>(SetupSection.class);
    private final Map<SetupSection, Disclosure> advanced = new EnumMap<>(SetupSection.class);
    /// The durations as read from the document, written back unchanged unless the user changes the pickers
    /// ([MillisDurations#toMillis]).
    private long readAttemptBudgetMs;
    private long readMaxDeferralAgeMs;
    /// The stop whose part is currently shown.
    private SetupStop shown;
    private Runnable onChange = () -> {
    };
    private Consumer<Element> onLoadDefault = opener -> {
    };
    private Runnable onStepsToggle = () -> {
    };
    private Runnable onMaxAttempts = () -> {
    };
    private Consumer<CheckPreset> onPreset = value -> {
    };
    private Consumer<Boolean> onFallback = on -> {
    };
    private Runnable onFallbackText = () -> {
    };

    /// Makes the tab, empty.
    public SetupTab() {
        initWidget(BINDER.createAndBindUi(this));
        parts.put(SetupSection.LEARNING, learningPart);
        parts.put(SetupSection.APPROACH, approachPart);
        parts.put(SetupSection.QUALITY, qualityPart);
        parts.put(SetupSection.GOING_LIVE, goingLivePart);
        parts.put(SetupSection.IN_SERVICE, inServicePart);
        advanced.put(SetupSection.APPROACH, approachAdvanced);
        advanced.put(SetupSection.QUALITY, qualityAdvanced);
        advanced.put(SetupSection.GOING_LIVE, goingLiveAdvanced);
        advanced.put(SetupSection.IN_SERVICE, inServiceAdvanced);
        stepsToggle.setOnClick(() -> onStepsToggle.run());
        loadDefault.getElement().setAttribute("aria-haspopup", "menu");
        loadDefault.setOnClick(() -> {
            if (!isReadOnly()) {
                onLoadDefault.accept(loadDefault.getElement());
            }
        });

        learningMode.addItem(LearningMode.AUTOMATIC, "On: learn new feeds automatically");
        learningMode.addItem(LearningMode.DISABLED, "Off: translations in use keep working, new feeds wait");
        maxAttempts.setMin(1);
        maxAttempts.setMax(Integer.MAX_VALUE);
        tokenBudget.setMin(0);
        tokenBudget.setMax(Long.MAX_VALUE);
        sampleSizeLimit.setMin(1);
        sampleSizeLimit.setMax(Integer.MAX_VALUE);
        for (final CheckPreset value : CheckPreset.values()) {
            preset.addItem(value, value.getDisplayValue(), value.getDescription());
        }
        promotionMode.addItem(PromotionMode.AUTOMATIC, "Use them as soon as they pass");
        promotionMode.addItem(PromotionMode.REVIEW, "Wait for someone to approve them");
        fallback.addItem(false, "Pause for a person");
        fallback.addItem(true, "Serve what it can, with the rest as marked Unknown events");
        setPreset(null);

        learningMode.setOnChange(value -> onChange.run());
        instructions.addValueChangeHandler(event -> onChange.run());
        maxAttempts.setOnChange(value -> onMaxAttempts.run());
        attemptBudget.setOnChange(value -> onChange.run());
        tokenBudget.setOnChange(value -> onChange.run());
        sampleSizeLimit.setOnChange(value -> onChange.run());
        preset.setOnChange(value -> onPreset.accept(value));
        heldOutFraction.setOnChange(value -> onChange.run());
        promotionMode.setOnChange(value -> onChange.run());
        minRecordsToLearn.setOnChange(value -> onChange.run());
        fallback.setOnChange(on -> onFallback.accept(Boolean.TRUE.equals(on)));
        fallbackText.setOnClick(() -> onFallbackText.run());
        relearnThreshold.setOnChange(value -> onChange.run());
        regressionCap.setOnChange(value -> onChange.run());
        maxDeferralAge.setOnChange(value -> onChange.run());
        show(SetupStop.of(SetupSection.LEARNING));
    }

    // ---- The route map ----

    /// Draws the route map.
    ///
    /// @param steps        The learning flow's steps to show on the map.
    /// @param summary      Each section's summary and problem.
    /// @param stepProblems The problem with each step, by index.
    /// @param stop         The chosen stop.
    /// @param canAdd       Whether the map offers a button to add a step after the chosen step.
    public void showMap(final List<FlowStep> steps,
                        final SetupSummary summary,
                        final Map<Integer, String> stepProblems,
                        final SetupStop stop,
                        final boolean canAdd) {
        map.show(steps, summary, stepProblems, stop, canAdd);
    }

    /// Shows the part of the setup for a stop on the map, hiding all the others. Any step is shown in the
    /// step part. When the stop changes, the page scrolls back to the top.
    ///
    /// @param stop The stop.
    public void show(final SetupStop stop) {
        parts.forEach((section, part) -> part.setVisible(!stop.isStep() && section == stop.getSection()));
        stepPart.setVisible(stop.isStep());
        if (!stop.equals(shown)) {
            page.getElement().setScrollTop(0);
        }
        shown = stop;
    }

    /// Puts the keyboard focus on a stop's part, e.g. to take the user to where a readiness check's fix is made.
    ///
    /// @param stop The stop whose part is shown.
    public void focus(final SetupStop stop) {
        (stop.isStep()
                ? stepPart
                : parts.get(stop.getSection())).getElement().focus();
    }

    /// Puts the keyboard focus on the time limit for a learning run, under Learning steps, e.g. when a
    /// suggestion says to change the limits. The caller must show Learning steps first.
    public void focusLimits() {
        attemptBudget.focus();
    }

    // ---- AI model and instructions ----

    /// @return The model picker, for a document picker controller to attach to.
    public DropDownBox getModelBox() {
        return model;
    }

    /// @return The target schema picker, for a document picker controller to attach to.
    public DropDownBox getTargetSchemaBox() {
        return targetSchema;
    }

    /// Shows or hides a warning that the target schema's root element may hold more than one kind of child
    /// element. Every child of the output's root is treated as one record, so a header or other non-record
    /// child would be wrongly counted as a record.
    ///
    /// @param root     The root element's name; null to hide the warning.
    /// @param children The child elements the root may hold. The warning is shown only if there are two or
    ///                 more.
    public void setRootHoldsSeveralKinds(final String root, final List<String> children) {
        if (root == null || children.size() < 2) {
            targetSchemaNote.setVisible(false);
            return;
        }
        targetSchemaNote.setText("Every child of <" + root + "> is read as one record, and it may hold "
                                 + String.join(", ", children) + ". A header or other element among them would "
                                 + "be counted as a record, so choose a schema whose root holds only its "
                                 + "records.");
        targetSchemaNote.setVisible(true);
    }

    /// @return Whether learning is switched on.
    public LearningMode getLearningMode() {
        return learningMode.getValue();
    }

    /// @param mode Whether learning is switched on.
    public void setLearningMode(final LearningMode mode) {
        learningMode.setValue(mode);
    }

    /// @return The instructions included in every question to the model.
    public String getInstructions() {
        return instructions.getValue();
    }

    /// @param text The instructions included in every question to the model.
    public void setInstructions(final String text) {
        instructions.setValue(text);
    }

    /// Puts the keyboard focus on the model picker, e.g. when the user follows a fix for a missing model.
    public void focusModel() {
        model.focus();
    }

    // ---- Learning steps ----

    /// Sets whether the learning flow's steps are shown on the route map, updating the show/hide link and the
    /// Load default setup button to match.
    ///
    /// @param shown   Whether the steps are shown.
    /// @param canHide Whether they may be hidden again. They may not if they do not match a built-in flow, or
    ///                if something is wrong with them.
    public void setStepsShown(final boolean shown, final boolean canHide) {
        stepsToggle.setVisible(!shown || canHide);
        stepsToggle.setText(shown
                ? "Hide the learning steps"
                : "Customise the learning steps");
        stepsToggle.setAriaLabel(shown
                ? "Hide the learning steps from the route map"
                : "Customise the learning steps: show them on the route map");
        loadDefault.setVisible(shown);
    }

    /// Expands the Advanced options of the given sections, e.g. those with settings that differ from a new
    /// document's ([AdvancedSettings]). Other sections are left as they are.
    ///
    /// @param sections The sections to expand.
    public void openAdvanced(final Set<SetupSection> sections) {
        sections.forEach(section -> NullSafe.consume(advanced.get(section), disclosure -> disclosure.setOpen(true)));
    }

    /// Collapses every section's Advanced options, e.g. before another document is read.
    public void foldAdvanced() {
        advanced.values().forEach(disclosure -> disclosure.setOpen(false));
    }

    /// Shows which built-in flow the steps match exactly, or that they are the document's own.
    ///
    /// @param example The built-in flow the steps match, or null if they match none.
    public void setStartingPoint(final FlowExample example) {
        approachStatus.setText(example == null
                ? "This is the document's own approach. Load a default setup to replace it."
                : "In use: " + PlainWords.example(example) + ". Customise any step and it becomes the "
                  + "document's own.");
    }

    /// Shows any problem with the flow as a whole, under Learning steps.
    ///
    /// @param problem The problem, or null if there is none.
    public void setFlowProblem(final String problem) {
        flowProblem.setText(problem == null
                ? ""
                : problem);
        flowProblem.setVisible(problem != null);
    }

    /// @return The step part, for its controller to attach to.
    public ApproachSection getApproach() {
        return approach;
    }

    /// @return The default number of tries for a step that does not set its own.
    public int getMaxAttempts() {
        return maxAttempts.getIntValue();
    }

    /// @param value The default number of tries for a step that does not set its own.
    public void setMaxAttempts(final int value) {
        maxAttempts.setValue(value);
    }

    /// @return The time limit for one learning run, in milliseconds.
    public long getAttemptBudgetMs() {
        return MillisDurations.toMillis(attemptBudget.getValue(), readAttemptBudgetMs);
    }

    /// @param value The time limit for one learning run, in milliseconds.
    public void setAttemptBudgetMs(final long value) {
        readAttemptBudgetMs = value;
        attemptBudget.setValue(MillisDurations.toDuration(value));
    }

    /// @return The token limit for one learning run, or null for no limit (shown in the field as zero).
    public Long getTokenBudget() {
        final long tokens = tokenBudget.getValue();
        return tokens == 0
                ? null
                : tokens;
    }

    /// @param value The token limit for one learning run, or null for no limit (shown in the field as zero).
    public void setTokenBudget(final Long value) {
        tokenBudget.setValue(value == null
                ? 0
                : value);
    }

    /// @return The maximum size of the sample shown to the model, in bytes.
    public int getSampleSizeLimit() {
        return sampleSizeLimit.getIntValue();
    }

    /// @param value The maximum size of the sample shown to the model, in bytes.
    public void setSampleSizeLimit(final int value) {
        sampleSizeLimit.setValue(value);
    }

    // ---- Quality checks ----

    /// @return The list of checks, for its controller to attach to.
    public CheckList getCheckList() {
        return checkList;
    }

    /// Shows which preset the checks match, and its description.
    ///
    /// @param value The preset, or null if the checks match no preset.
    public void setPreset(final CheckPreset value) {
        preset.setValue(value, false);
        presetDescription.setText(value == null
                ? OWN
                : value.getDescription());
    }

    /// @return The share of lines held back for testing, a fraction in [0, 1].
    public double getHeldOutFraction() {
        return ScoreValues.fromPercent(heldOutFraction.getIntValue());
    }

    /// @param fraction The share of lines held back for testing, a fraction in [0, 1].
    public void setHeldOutFraction(final double fraction) {
        heldOutFraction.setValue(ScoreValues.toPercent(fraction));
    }

    // ---- Approving new translations ----

    /// @return Whether a new translation is used as soon as it passes or must be approved first.
    public PromotionMode getPromotionMode() {
        return promotionMode.getValue();
    }

    /// @param mode Whether a new translation is used as soon as it passes or must be approved first.
    public void setPromotionMode(final PromotionMode mode) {
        promotionMode.setValue(mode);
    }

    /// @return The minimum number of records a stream must have to be learned from.
    public int getMinRecordsToLearn() {
        return minRecordsToLearn.getIntValue();
    }

    /// @param records The minimum number of records a stream must have to be learned from.
    public void setMinRecordsToLearn(final int records) {
        minRecordsToLearn.setValue(records);
    }

    /// @param on Whether the flow has the fallback step, which serves marked Unknown events for records the
    ///           transform cannot handle. Without it, the session pauses for a person.
    public void setFallback(final boolean on) {
        fallback.setValue(on, false);
        fallbackText.setVisible(on);
    }

    /// Shows or hides a note that the fallback does not apply to the target schema. The fallback writes
    /// event-logging's marked Unknown events, so it cannot be used with any other schema.
    ///
    /// @param schema The target schema's name if it is not event-logging; null to hide the note.
    public void setFallbackNotApplying(final String schema) {
        if (schema == null) {
            fallbackNote.setVisible(false);
            return;
        }
        fallbackNote.setText("Marked Unknown events are event-logging's, and " + schema + " has none, so a "
                             + "transform that can't be finished pauses for a person whichever is chosen.");
        fallbackNote.setVisible(true);
    }

    // ---- Monitoring translations in use ----

    /// @return The rolling score below which a translation in use is flagged, a fraction in [0, 1].
    public double getRelearnThreshold() {
        return ScoreValues.fromPercent(relearnThreshold.getIntValue());
    }

    /// @param fraction The rolling score below which a translation in use is flagged, a fraction in [0, 1].
    public void setRelearnThreshold(final double fraction) {
        relearnThreshold.setValue(ScoreValues.toPercent(fraction));
    }

    /// @return The maximum number of records kept per translation (the regression corpus) to check later
    ///         translations against.
    public int getRegressionCap() {
        return regressionCap.getIntValue();
    }

    /// @param records The maximum number of records kept per translation (the regression corpus) to check
    ///                later translations against.
    public void setRegressionCap(final int records) {
        regressionCap.setValue(records);
    }

    /// @return How long a stream may wait before it is flagged as overdue (raised), in milliseconds.
    public long getMaxDeferralAgeMs() {
        return MillisDurations.toMillis(maxDeferralAge.getValue(), readMaxDeferralAgeMs);
    }

    /// @param value How long a stream may wait before it is flagged as overdue (raised), in milliseconds.
    public void setMaxDeferralAgeMs(final long value) {
        readMaxDeferralAgeMs = value;
        maxDeferralAge.setValue(MillisDurations.toDuration(value));
    }

    // ---- Callbacks ----

    /// @param onChange Run when the user changes a simple setting that needs no special handling; the
    ///                 controller reads it back with the getters.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// @param onStop Called with the stop the user chooses on the route map.
    public void setOnStop(final Consumer<SetupStop> onStop) {
        map.setOnSelect(onStop);
    }

    /// @param onLoadDefault Called with the Load default setup button's element when it is pressed, so the menu
    ///                      of built-in flows can be opened next to it (never when read-only).
    public void setOnLoadDefault(final Consumer<Element> onLoadDefault) {
        this.onLoadDefault = onLoadDefault;
    }

    /// @param onAddStep Called with the route map's Add step button element when it is pressed, so the menu of
    ///                  kinds of step to add after the chosen step can be opened next to it.
    public void setOnAddStep(final Consumer<Element> onAddStep) {
        map.setOnAdd(onAddStep);
    }

    /// @param onStepsToggle Run when the user asks to show or hide the learning steps on the route map.
    public void setOnStepsToggle(final Runnable onStepsToggle) {
        this.onStepsToggle = onStepsToggle;
    }

    /// @param onMaxAttempts Run when the user changes the default number of tries per step.
    public void setOnMaxAttempts(final Runnable onMaxAttempts) {
        this.onMaxAttempts = onMaxAttempts;
    }

    /// @param onPreset Called with the preset of checks the user chooses.
    public void setOnPreset(final Consumer<CheckPreset> onPreset) {
        this.onPreset = onPreset;
    }

    /// @param onFallback Called with whether the user wants marked Unknown events served when the transform
    ///                   cannot be finished.
    public void setOnFallback(final Consumer<Boolean> onFallback) {
        this.onFallback = onFallback;
    }

    /// @param onFallbackText Run when the user asks to see the fallback step's question text.
    public void setOnFallbackText(final Runnable onFallbackText) {
        this.onFallbackText = onFallbackText;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        learningMode.setEnabled(!readOnly);
        instructions.setReadOnly(readOnly);
        loadDefault.setEnabled(!readOnly);
        approach.setReadOnly(readOnly);
        maxAttempts.setReadOnly(readOnly);
        attemptBudget.setReadOnly(readOnly);
        tokenBudget.setReadOnly(readOnly);
        sampleSizeLimit.setReadOnly(readOnly);
        preset.setEnabled(!readOnly);
        heldOutFraction.setReadOnly(readOnly);
        promotionMode.setEnabled(!readOnly);
        minRecordsToLearn.setReadOnly(readOnly);
        fallback.setEnabled(!readOnly);
        relearnThreshold.setReadOnly(readOnly);
        regressionCap.setReadOnly(readOnly);
        maxDeferralAge.setReadOnly(readOnly);
    }

    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, SetupTab> {

    }
}
