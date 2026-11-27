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
import stroom.explorer.client.component.DocSelectionController;
import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowResource;
import stroom.learningflow.shared.LearningMode;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.security.shared.DocumentPermission;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.xmlschema.shared.XmlSchemaDoc;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Map;

/// Controls a Learning Flow document's Setup tab ([SetupTab]), reading the document into the tab and writing
/// the user's changes back.
///
/// The model is chosen from the AI model documents the user may use, and the target schema from the
/// `XMLSchema` documents the user may use. The checks are managed by [CheckListController], and the learning
/// flow (approach), including the chosen step, by [ApproachSectionController]. Choosing a preset of checks
/// replaces the checks, asking for confirmation first if the current checks match no preset and would be lost.
/// The fallback, chosen under Approving new translations, is a step of the flow, so it is switched on or off
/// through the approach's controller.
///
/// After every change, the route map is redrawn from the document as edited ([SetupSummary]). The part shown
/// is the stop chosen on the map ([SetupStop]), either a section or a step; for a step, the approach's
/// controller is told which step to show. The document editor calls [#switchLearningOn()], [#focusModel()],
/// [#usePreset] and [#showSection] to apply the fixes offered by the readiness checks.
///
/// Settings an administrator rarely changes are kept out of sight until needed. A section's Advanced options
/// expand automatically if they hold settings that differ from a new document's ([AdvancedSettings]). The
/// steps are shown on the map once the user asks for them, and always when they match no built-in flow or
/// something is wrong with them, so that a readiness check can send a person to fix them.
public class SetupTabController extends Controller {

    private static final LearningFlowResource RESOURCE = GWT.create(LearningFlowResource.class);

    private final SetupTab view;
    private final RestFactory restFactory;
    private final DocSelectionController modelSelection;
    private final DocSelectionController targetSchemaSelection;
    private final CheckListController checkList;
    private final ApproachSectionController approach;
    /// The document as last read. The tab's current settings are written over a copy of it to build the
    /// summaries for the route map.
    private LearningFlowDoc doc;
    /// The stop chosen on the route map, whose part is shown.
    private SetupStop stop = SetupStop.of(SetupSection.LEARNING);
    private boolean readOnly = true;
    /// Whether the user has asked for the learning flow's steps to be shown on the route map.
    private boolean stepsAsked;
    /// Whether the steps were on the map when it was last drawn.
    private boolean stepsWereShown;

    /// Made by GIN.
    ///
    /// @param eventBus             The application's event bus.
    /// @param view                 The tab.
    /// @param docSelectionProvider Makes the controllers for the model and target schema pickers.
    /// @param checkList            The controller for the list of checks.
    /// @param approach             The controller for the learning flow's steps.
    /// @param restFactory          Fetches the server's description of the target schema.
    @Inject
    public SetupTabController(final EventBus eventBus,
                              final SetupTab view,
                              final RestFactory restFactory,
                              final Provider<DocSelectionController> docSelectionProvider,
                              final CheckListController checkList,
                              final ApproachSectionController approach) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        initWidget(view);
        modelSelection = make(docSelectionProvider);
        modelSelection.attach(view.getModelBox());
        modelSelection.setIncludedTypes(OpenAIModelDoc.TYPE);
        modelSelection.setRequiredPermissions(DocumentPermission.USE);
        targetSchemaSelection = make(docSelectionProvider);
        targetSchemaSelection.attach(view.getTargetSchemaBox());
        targetSchemaSelection.setIncludedTypes(XmlSchemaDoc.TYPE);
        targetSchemaSelection.setRequiredPermissions(DocumentPermission.USE);
        this.checkList = own(checkList).attach(view.getCheckList());
        this.approach = own(approach).attach(view.getApproach(), view.getSharedTexts());

        own(modelSelection.addSelectionListener(docRef -> changed()));
        own(targetSchemaSelection.addSelectionListener(docRef -> {
            describeTargetSchema(docRef);
            changed();
        }));
        view.setOnChange(this::changed);
        view.setOnMaxAttempts(() -> {
            approach.setDefaultTries(view.getMaxAttempts());
            changed();
        });
        view.setOnPreset(this::choosePreset);
        view.setOnFallback(approach::setFallback);
        view.setOnFallbackText(() -> {
            final int fallback = approach.fallbackIndex();
            if (fallback >= 0) {
                stepsAsked = true;
                go(SetupStop.step(fallback));
                view.focus(stop);
            }
        });
        view.setOnLoadDefault(approach::showDefaultsMenu);
        view.setOnStepsToggle(this::toggleSteps);
        view.setOnAddStep(approach::addAfterSelected);
        view.setOnStop(this::go);
        checkList.setOnChange(() -> {
            showPreset();
            // A step's card warns when a rule is triggered by a check that is off, so pass on the new checks
            approach.setChecks(checkList.write());
            changed();
        });
        // The approach's controller marks the document as changed itself. A change may select another step.
        approach.setOnChange(() -> {
            // Keep the steps shown if they were shown when changed, e.g. after a built-in flow is loaded over
            // custom steps, even though they would now be hidden by default
            stepsAsked = stepsAsked || stepsWereShown;
            view.setFallback(approach.hasFallback());
            followApproach();
            showSummary();
        });
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
            modelSelection.setReadOnly(readOnly);
            targetSchemaSelection.setReadOnly(readOnly);
            // Redraw the map, since it offers Add step only when the document can be changed
            showSummary();
        });
    }

    /// Shows a document's setup.
    ///
    /// @param docRef The document's reference.
    /// @param doc    The document's content.
    public void read(final DocRef docRef, final LearningFlowDoc doc) {
        this.doc = doc;
        stepsAsked = false;
        stepsWereShown = false;
        view.foldAdvanced();
        view.setLearningMode(doc.getLearningMode());
        modelSelection.setSelectedEntityReference(doc.getModel(), true);
        targetSchemaSelection.setSelectedEntityReference(doc.getTargetSchema(), true);
        describeTargetSchema(doc.getTargetSchema());
        view.setInstructions(doc.getInstructions());

        view.setMaxAttempts(doc.getMaxAttempts());
        view.setAttemptBudgetMs(doc.getAttemptBudgetMs());
        view.setTokenBudget(doc.getTokenBudget());
        view.setSampleSizeLimit(doc.getSampleSizeLimit());
        approach.read(docRef, doc);

        // approach.read(...) above has already given the approach the document's checks
        checkList.read(doc.getChecks());
        showPreset();
        view.setHeldOutFraction(doc.getHeldOutFraction());

        view.setPromotionMode(doc.getPromotionMode());
        view.setMinRecordsToLearn(doc.getMinRecordsToLearn());
        view.setFallback(approach.hasFallback());

        view.setRelearnThreshold(doc.getRelearnThreshold());
        view.setRegressionCap(doc.getRegressionCap());
        view.setMaxDeferralAgeMs(doc.getMaxDeferralAgeMs());
        followApproach();
        view.show(stop);
        showSummary();
    }

    /// @param doc The document.
    /// @return A copy of the document with the settings shown on the tab.
    public LearningFlowDoc write(final LearningFlowDoc doc) {
        return approach.write(doc.copy()
                .learningMode(view.getLearningMode())
                .model(modelSelection.getSelectedEntityReference())
                .targetSchema(targetSchemaSelection.getSelectedEntityReference())
                .instructions(view.getInstructions())
                .maxAttempts(view.getMaxAttempts())
                .attemptBudgetMs(view.getAttemptBudgetMs())
                .tokenBudget(view.getTokenBudget())
                .sampleSizeLimit(view.getSampleSizeLimit())
                .checks(checkList.write())
                .heldOutFraction(view.getHeldOutFraction())
                .promotionMode(view.getPromotionMode())
                .minRecordsToLearn(view.getMinRecordsToLearn())
                .relearnThreshold(view.getRelearnThreshold())
                .regressionCap(view.getRegressionCap())
                .maxDeferralAgeMs(view.getMaxDeferralAgeMs())
                .build());
    }

    /// Shows a section and puts the keyboard focus on it, e.g. where a readiness check's fix is made. The
    /// owner must show the tab first.
    ///
    /// @param section The section.
    public void showSection(final SetupSection section) {
        go(SetupStop.of(section));
        view.focus(stop);
    }

    /// Shows the limits on a learning run, under Learning steps, e.g. when a person follows the suggestion
    /// given for a run that stopped at a limit. The owner must show the tab first.
    public void showLimits() {
        go(SetupStop.of(SetupSection.APPROACH));
        view.focusLimits();
    }

    /// Switches learning on, as offered by a readiness check on the Pipelines tab (`FeedsTab`). This marks the
    /// document as changed, so it still needs to be saved. The owner must show the tab first, so that the
    /// document has been read into it, and must only call this when the document can be changed.
    public void switchLearningOn() {
        if (view.getLearningMode() != LearningMode.AUTOMATIC) {
            view.setLearningMode(LearningMode.AUTOMATIC);
            changed();
        }
    }

    /// Puts the keyboard focus on the model picker, when a readiness check on the Pipelines tab sends a person
    /// to choose a model.
    public void focusModel() {
        view.focusModel();
    }

    /// Puts the focus on the target schema picker, where the Pipelines tab sends a person to choose one.
    public void focusTargetSchema() {
        view.focusTargetSchema();
    }

    /// Replaces the checks with a preset, as offered by the readiness check for a Learning Flow with no quality
    /// gate. This marks the document as changed, so it still needs to be saved. The owner must show the tab
    /// first, so that the document has been read into it, and must only call this when the document can be
    /// changed.
    ///
    /// @param preset The preset.
    public void usePreset(final CheckPreset preset) {
        checkList.read(preset.settings());
        approach.setChecks(preset.settings());
        showPreset();
        changed();
    }

    // Applies a preset the user chose. If the current checks match a preset, it is applied at once; otherwise
    // the user is asked first, since custom checks would be lost.
    private void choosePreset(final CheckPreset preset) {
        if (readOnly || preset == null) {
            return;
        }
        if (CheckPreset.of(checkList.write()) != null) {
            usePreset(preset);
            return;
        }
        ConfirmEvent.fire(this,
                "Replace this Learning Flow's own checks with the " + preset.getDisplayValue()
                + " set? Their settings, and any business rules, are replaced.",
                ok -> {
                    if (isDisposed()) {
                        return;
                    }
                    if (ok) {
                        usePreset(preset);
                    } else {
                        showPreset();
                    }
                });
    }

    // Asks the server to read the target schema's XSD, and shows two notes from it. The fallback writes
    // event-logging's marked Unknown events, so it does not apply to any other schema. Every child of the
    // output's root is treated as a record, so a root that may hold several kinds of element gets a warning.
    // If the schema can't be read, no notes are shown; the server enforces these limits regardless.
    private void describeTargetSchema(final DocRef target) {
        view.setFallbackNotApplying(null);
        view.setRootHoldsSeveralKinds(null, List.of());
        if (target == null) {
            return;
        }
        restFactory
                .create(RESOURCE)
                .method(resource -> resource.targetSchema(target.getUuid()))
                .onSuccess(schema -> {
                    final boolean stillChosen = target.equals(targetSchemaSelection.getSelectedEntityReference());
                    if (stillChosen && schema != null) {
                        view.setFallbackNotApplying(schema.isEventLogging()
                                ? null
                                : schema.getName());
                        view.setRootHoldsSeveralKinds(schema.getRootElement(), schema.getRootChildren());
                    }
                })
                .onFailure(error -> {
                    view.setFallbackNotApplying(null);
                    view.setRootHoldsSeveralKinds(null, List.of());
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void showPreset() {
        view.setPreset(CheckPreset.of(checkList.write()));
    }

    private void changed() {
        markChanged();
        showSummary();
    }

    // Shows or hides the steps on the map. If they are hidden while a step is shown, Learning steps is shown
    // instead.
    private void toggleSteps() {
        stepsAsked = !stepsShown();
        if (!stepsShown() && stop.isStep()) {
            go(SetupStop.of(SetupSection.APPROACH));
        } else {
            showSummary();
        }
    }

    // Whether the steps must be shown: they match no built-in flow, or something is wrong with them
    private boolean stepsNeeded() {
        return approach.example() == null || approach.flowProblem() != null || !approach.stepProblems().isEmpty();
    }

    private boolean stepsShown() {
        return stepsAsked || stepsNeeded();
    }

    // Goes to a stop: shows its part and redraws the map with it chosen
    private void go(final SetupStop chosen) {
        if (chosen.isStep()) {
            approach.select(chosen.getStep());
            stop = SetupStop.step(approach.getSelected());
        } else {
            stop = chosen;
        }
        view.show(stop);
        showSummary();
    }

    // If a step is shown, follows the approach's selected step, which a change to the steps may move
    private void followApproach() {
        if (stop.isStep()) {
            stop = SetupStop.step(approach.getSelected());
        }
    }

    private void showSummary() {
        final boolean stepsShown = stepsShown();
        stepsWereShown = stepsShown;
        if (doc != null) {
            final LearningFlowDoc shown = write(doc);
            view.showMap(stepsShown
                            ? approach.getSteps()
                            : List.of(),
                    SetupSummary.of(shown, approach.flowExamples()),
                    stepsShown
                            ? approach.stepProblems()
                            : Map.of(),
                    stop,
                    !readOnly && stop.isStep() && approach.canAddAfterSelected());
            view.openAdvanced(AdvancedSettings.changed(shown));
        }
        view.setStepsShown(stepsShown, !stepsNeeded());
        view.setStartingPoint(approach.example());
        view.setFlowProblem(approach.flowProblem());
    }
}
