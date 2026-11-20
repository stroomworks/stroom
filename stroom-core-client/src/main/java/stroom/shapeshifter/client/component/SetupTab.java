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

import stroom.shapeshifter.shared.CheckPreset;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
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

/// A Learning Flow's Setup tab (design 07): a route map ([RouteMap]) down the side, and beside it the one part
/// of the setup chosen on it ([SetupStop]). The map's stops are the sections ([SetupSection]), in the order a
/// translation meets them, with the learning flow's steps under Learning steps once they are shown; each says
/// what it is set to or what is wrong in it ([SetupSummary]).
///
/// What an Admin needs to configure the AI is in sight; what they rarely change is folded under each section's
/// "Advanced options" ([Disclosure], [AdvancedSettings]), and the steps are on the map only once someone
/// customises them, or they are no starting point, or something is wrong with them.
///
/// The parts: **AI model and instructions**: whether it learns, the model and the instructions. **Learning steps**:
/// which starting point the steps are, showing or hiding them, Load default setup (a menu of the starting points,
/// once they are shown), what is wrong with the flow as a whole, and what a run may spend; under Advanced options,
/// the tries per step and the largest sample. **A step**: its card and question text ([ApproachSection]).
/// **Quality checks**: the starting set; under Advanced options, the checks ([CheckList]) and the lines kept back
/// to test against. **Approving new translations**: the pass mark and whether a person approves; under Advanced
/// options, how many records before a translation is trusted, and what a feed gets when the transform can't be
/// finished (the fallback). **Monitoring translations in use**: when a translation in use is flagged; under
/// Advanced options, how many records are kept to check later ones against, and when a waiting stream is overdue.
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
    ValueSpinner relearnThreshold;
    @UiField
    Disclosure inServiceAdvanced;
    @UiField
    ValueSpinner regressionCap;
    @UiField
    DurationPicker maxDeferralAge;

    private final Map<SetupSection, AriaPanel> parts = new EnumMap<>(SetupSection.class);
    private final Map<SetupSection, Disclosure> advanced = new EnumMap<>(SetupSection.class);
    /// The durations as read, kept while the pickers still show them ([MillisDurations#toMillis]).
    private long readAttemptBudgetMs;
    private long readMaxDeferralAgeMs;
    /// The stop whose part is shown.
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
    /// @param steps        The learning flow's steps.
    /// @param summary      What each section is set to and what is wrong in it.
    /// @param stepProblems What is wrong with a step, by its index.
    /// @param stop         The stop chosen.
    /// @param canAdd       Whether a step may be added after the step chosen, from the map.
    public void showMap(final List<FlowStep> steps,
                        final SetupSummary summary,
                        final Map<Integer, String> stepProblems,
                        final SetupStop stop,
                        final boolean canAdd) {
        map.show(steps, summary, stepProblems, stop, canAdd);
    }

    /// Shows the part of the setup a stop on the map is, alone, from its top where it is another stop's. A
    /// step is shown in the step's part.
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

    /// Puts the focus on the part shown, e.g. where a readiness check's fix is made.
    ///
    /// @param stop The stop whose part is shown.
    public void focus(final SetupStop stop) {
        (stop.isStep()
                ? stepPart
                : parts.get(stop.getSection())).getElement().focus();
    }

    /// Puts the focus on the time for each run, among the limits on a learning run under Learning steps,
    /// where a suggestion says to change them. Learning steps is shown first.
    public void focusLimits() {
        attemptBudget.focus();
    }

    // ---- AI model and instructions ----

    /// @return The model picker, which its controller attaches a document picker's controller to.
    public DropDownBox getModelBox() {
        return model;
    }

    /// @return Whether the document learns.
    public LearningMode getLearningMode() {
        return learningMode.getValue();
    }

    /// @param mode Whether the document learns.
    public void setLearningMode(final LearningMode mode) {
        learningMode.setValue(mode);
    }

    /// @return The instructions every question carries.
    public String getInstructions() {
        return instructions.getValue();
    }

    /// @param text The instructions every question carries.
    public void setInstructions(final String text) {
        instructions.setValue(text);
    }

    /// Puts the focus on the model picker, where a document with none is sent to choose one.
    public void focusModel() {
        model.focus();
    }

    // ---- Learning steps ----

    /// Shows the learning flow's steps on the route map, or hides them.
    ///
    /// @param shown   Whether they are shown.
    /// @param canHide Whether they may be hidden again: not where they are the document's own, or something
    ///                is wrong with them.
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

    /// Opens the Advanced options of some sections, e.g. those holding something not as a new document has it
    /// ([AdvancedSettings]); the others are left as they are.
    ///
    /// @param sections The sections.
    public void openAdvanced(final Set<SetupSection> sections) {
        sections.forEach(section -> NullSafe.consume(advanced.get(section), disclosure -> disclosure.setOpen(true)));
    }

    /// Folds every section's Advanced options, e.g. before another document is read.
    public void foldAdvanced() {
        advanced.values().forEach(disclosure -> disclosure.setOpen(false));
    }

    /// @param section A section.
    /// @return Whether its Advanced options are open; false for one with none.
    public boolean isAdvancedOpen(final SetupSection section) {
        return NullSafe.test(advanced.get(section), Disclosure::isOpen);
    }

    /// Says which default setup the flow is exactly, or that it is the document's own.
    ///
    /// @param example The example the steps are, or null for none.
    public void setStartingPoint(final FlowExample example) {
        approachStatus.setText(example == null
                ? "This is the document's own approach. Load a default setup to replace it."
                : "In use: " + PlainWords.example(example) + ". Customise any step and it becomes the "
                  + "document's own.");
    }

    /// What is wrong with the flow as a whole, under Learning steps.
    ///
    /// @param problem What is wrong, or null for nothing.
    public void setFlowProblem(final String problem) {
        flowProblem.setText(problem == null
                ? ""
                : problem);
        flowProblem.setVisible(problem != null);
    }

    /// @return The step's part, which its controller attaches to.
    public ApproachSection getApproach() {
        return approach;
    }

    /// @return The tries a step has where it doesn't say.
    public int getMaxAttempts() {
        return maxAttempts.getIntValue();
    }

    /// @param value The tries a step has where it doesn't say.
    public void setMaxAttempts(final int value) {
        maxAttempts.setValue(value);
    }

    /// @return The time one run may take, in milliseconds.
    public long getAttemptBudgetMs() {
        return MillisDurations.toMillis(attemptBudget.getValue(), readAttemptBudgetMs);
    }

    /// @param value The time one run may take, in milliseconds.
    public void setAttemptBudgetMs(final long value) {
        readAttemptBudgetMs = value;
        attemptBudget.setValue(MillisDurations.toDuration(value));
    }

    /// @return The tokens one run may spend, or null for no limit, which the field shows as zero.
    public Long getTokenBudget() {
        final long tokens = tokenBudget.getValue();
        return tokens == 0
                ? null
                : tokens;
    }

    /// @param value The tokens one run may spend, or null for no limit, shown as zero.
    public void setTokenBudget(final Long value) {
        tokenBudget.setValue(value == null
                ? 0
                : value);
    }

    /// @return The largest sample shown, in bytes.
    public int getSampleSizeLimit() {
        return sampleSizeLimit.getIntValue();
    }

    /// @param value The largest sample shown, in bytes.
    public void setSampleSizeLimit(final int value) {
        sampleSizeLimit.setValue(value);
    }

    // ---- Quality checks ----

    /// @return The checks' list, which its controller attaches to.
    public CheckList getCheckList() {
        return checkList;
    }

    /// Shows which starting set the checks are, and what it asks.
    ///
    /// @param value The set, or null where the checks are the document's own.
    public void setPreset(final CheckPreset value) {
        preset.setValue(value, false);
        presetDescription.setText(value == null
                ? OWN
                : value.getDescription());
    }

    /// @return The share of lines kept back, a fraction in [0, 1].
    public double getHeldOutFraction() {
        return ScoreValues.fromPercent(heldOutFraction.getIntValue());
    }

    /// @param fraction The share of lines kept back, a fraction in [0, 1].
    public void setHeldOutFraction(final double fraction) {
        heldOutFraction.setValue(ScoreValues.toPercent(fraction));
    }

    // ---- Approving new translations ----

    /// @return Whether what is learned is approved first.
    public PromotionMode getPromotionMode() {
        return promotionMode.getValue();
    }

    /// @param mode Whether what is learned is approved first.
    public void setPromotionMode(final PromotionMode mode) {
        promotionMode.setValue(mode);
    }

    /// @return The least records a stream must bring for its translation to be trusted.
    public int getMinRecordsToLearn() {
        return minRecordsToLearn.getIntValue();
    }

    /// @param records The least records a stream must bring for its translation to be trusted.
    public void setMinRecordsToLearn(final int records) {
        minRecordsToLearn.setValue(records);
    }

    /// @param on Whether the flow has the fallback, serving marked Unknowns when the transform can't be
    ///           finished; else the feed pauses for a person.
    public void setFallback(final boolean on) {
        fallback.setValue(on, false);
        fallbackText.setVisible(on);
    }

    // ---- Monitoring translations in use ----

    /// @return The score below which a translation in use is flagged, a fraction in [0, 1].
    public double getRelearnThreshold() {
        return ScoreValues.fromPercent(relearnThreshold.getIntValue());
    }

    /// @param fraction The score below which a translation in use is flagged, a fraction in [0, 1].
    public void setRelearnThreshold(final double fraction) {
        relearnThreshold.setValue(ScoreValues.toPercent(fraction));
    }

    /// @return The most records kept per translation to check later ones against.
    public int getRegressionCap() {
        return regressionCap.getIntValue();
    }

    /// @param records The most records kept per translation to check later ones against.
    public void setRegressionCap(final int records) {
        regressionCap.setValue(records);
    }

    /// @return How long a stream waits before it is overdue, in milliseconds.
    public long getMaxDeferralAgeMs() {
        return MillisDurations.toMillis(maxDeferralAge.getValue(), readMaxDeferralAgeMs);
    }

    /// @param value How long a stream waits before it is overdue, in milliseconds.
    public void setMaxDeferralAgeMs(final long value) {
        readMaxDeferralAgeMs = value;
        maxDeferralAge.setValue(MillisDurations.toDuration(value));
    }

    // ---- Callbacks ----

    /// @param onChange Run when the user changes a setting the controller only reads back.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// @param onStop Told the stop chosen on the route map.
    public void setOnStop(final Consumer<SetupStop> onStop) {
        map.setOnSelect(onStop);
    }

    /// @param onLoadDefault Given the Load default setup button, where the menu of the default setups opens
    ///                      (not when read only).
    public void setOnLoadDefault(final Consumer<Element> onLoadDefault) {
        this.onLoadDefault = onLoadDefault;
    }

    /// @param onAddStep Given the route map's Add step button, where the menu of the kinds of step to add after
    ///                  the step chosen opens.
    public void setOnAddStep(final Consumer<Element> onAddStep) {
        map.setOnAdd(onAddStep);
    }

    /// @param onStepsToggle Run when the user asks to show or hide the learning steps on the route map.
    public void setOnStepsToggle(final Runnable onStepsToggle) {
        this.onStepsToggle = onStepsToggle;
    }

    /// @param onMaxAttempts Run when the user changes the tries a step has where it doesn't say.
    public void setOnMaxAttempts(final Runnable onMaxAttempts) {
        this.onMaxAttempts = onMaxAttempts;
    }

    /// @param onPreset Told the starting set of checks the user chooses.
    public void setOnPreset(final Consumer<CheckPreset> onPreset) {
        this.onPreset = onPreset;
    }

    /// @param onFallback Told whether the user wants marked Unknowns served when the transform can't be
    ///                   finished.
    public void setOnFallback(final Consumer<Boolean> onFallback) {
        this.onFallback = onFallback;
    }

    /// @param onFallbackText Run when the user asks for the fallback's question text.
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
