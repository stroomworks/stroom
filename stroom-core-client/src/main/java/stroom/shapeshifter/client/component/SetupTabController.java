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
import stroom.docref.DocRef;
import stroom.explorer.client.component.DocSelectionController;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.shared.CheckPreset;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Map;

/// A Shapeshifter AI document's Setup tab ([SetupTab]): reads the document into its parts and writes it
/// back. The model is chosen from the `stroom-ai` model documents the user may use; the checks are
/// [CheckListController]'s, and the approach, with the step chosen, [ApproachSectionController]'s. Choosing
/// a starting set of checks replaces the checks, and asks first where that would lose checks of the
/// document's own. The fallback, chosen under Approving new translations, is a step of the approach's flow,
/// so it is switched there.
///
/// The route map is drawn again from the document as the tab has it after every change ([SetupSummary]),
/// and the part shown is the stop chosen on it ([SetupStop]): a section, or a step, which the approach is
/// told to show. The editor reaches it to put a readiness check right: [#switchLearningOn()],
/// [#focusModel()], [#usePreset] and [#showSection].
///
/// What an Admin rarely changes is out of sight until it is needed. A section's Advanced options open of their
/// own accord where they hold something not as a new document has it ([AdvancedSettings]). The steps are on the
/// map once the user asks for them, and always where they are the document's own or something is wrong with them,
/// which is where a readiness check sends a person to put them right.
public class SetupTabController extends Controller {

    private final SetupTab view;
    private final DocSelectionController modelSelection;
    private final CheckListController checkList;
    private final ApproachSectionController approach;
    /// The document as last read, which what the tab shows is written over to sum it up.
    private ShapeshifterAiDoc doc;
    /// The stop chosen on the route map, whose part is shown.
    private SetupStop stop = SetupStop.of(SetupSection.LEARNING);
    private boolean readOnly = true;
    /// Whether the user asked for the learning flow's steps on the route map.
    private boolean stepsAsked;
    /// Whether the steps were on the map as last drawn.
    private boolean stepsWereShown;

    /// Made by GIN, with its tab.
    ///
    /// @param eventBus             The application's event bus.
    /// @param view                 The tab.
    /// @param docSelectionProvider Makes the model's picker.
    /// @param checkList           The checks' list's controller.
    /// @param approach             The approach's controller.
    @Inject
    public SetupTabController(final EventBus eventBus,
                              final SetupTab view,
                              final Provider<DocSelectionController> docSelectionProvider,
                              final CheckListController checkList,
                              final ApproachSectionController approach) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        modelSelection = make(docSelectionProvider);
        modelSelection.attach(view.getModelBox());
        modelSelection.setIncludedTypes(OpenAIModelDoc.TYPE);
        modelSelection.setRequiredPermissions(DocumentPermission.USE);
        this.checkList = own(checkList).attach(view.getCheckList());
        this.approach = own(approach).attach(view.getApproach());

        own(modelSelection.addSelectionListener(docRef -> changed()));
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
            // A step's card warns where a rule fires on a check that is off, so the approach is told
            approach.setChecks(checkList.write());
            changed();
        });
        // The approach marks the document changed itself; a change may choose another step
        approach.setOnChange(() -> {
            // Steps changed in sight stay in sight, e.g. once a default setup is loaded over the document's own
            stepsAsked = stepsAsked || stepsWereShown;
            view.setFallback(approach.hasFallback());
            followApproach();
            showSummary();
        });
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
            modelSelection.setReadOnly(readOnly);
            // The map offers Add step only where the document can be changed
            showSummary();
        });
    }

    /// Shows a document's setup.
    ///
    /// @param docRef The document.
    /// @param doc    Its content.
    public void read(final DocRef docRef, final ShapeshifterAiDoc doc) {
        this.doc = doc;
        stepsAsked = false;
        stepsWereShown = false;
        view.foldAdvanced();
        view.setLearningMode(doc.getLearningMode());
        modelSelection.setSelectedEntityReference(doc.getModel(), true);
        view.setInstructions(doc.getInstructions());

        view.setMaxAttempts(doc.getMaxAttempts());
        view.setAttemptBudgetMs(doc.getAttemptBudgetMs());
        view.setTokenBudget(doc.getTokenBudget());
        view.setSampleSizeLimit(doc.getSampleSizeLimit());
        approach.read(docRef, doc);

        // approach.read(...) above takes the document's checks with it, so it isn't told them again here
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
    /// @return A copy of it with the setup shown. The regression retention, which nothing reads (ISSUES item
    ///         24), is kept as it was.
    public ShapeshifterAiDoc write(final ShapeshifterAiDoc doc) {
        return approach.write(doc.copy()
                .learningMode(view.getLearningMode())
                .model(modelSelection.getSelectedEntityReference())
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

    /// Shows a section and puts the focus on it, e.g. where a readiness check's fix is made. Its owner shows
    /// the tab first.
    ///
    /// @param section The section.
    public void showSection(final SetupSection section) {
        go(SetupStop.of(section));
        view.focus(stop);
    }

    /// Shows the limits on a learning run, under Learning steps, where a stopped run's suggestion sends a
    /// person. Its owner shows the tab first.
    public void showLimits() {
        go(SetupStop.of(SetupSection.APPROACH));
        view.focusLimits();
    }

    /// Switches learning on, as the Feeds tab's readiness check offers; the document is then changed, to
    /// be saved. Its owner shows the tab first, which reads the document into it, and asks only where the
    /// document can be changed.
    public void switchLearningOn() {
        if (view.getLearningMode() != LearningMode.AUTOMATIC) {
            view.setLearningMode(LearningMode.AUTOMATIC);
            changed();
        }
    }

    /// Puts the focus on the model picker, where the Feeds tab sends a person to choose a model.
    public void focusModel() {
        view.focusModel();
    }

    /// Puts a ready-made set of checks in place, as the readiness check offers for a Learning Flow whose
    /// checks are all off; the document is then changed, to be saved. Its owner shows the tab first, which
    /// reads the document into it, and asks only where the document can be changed.
    ///
    /// @param preset The set.
    public void usePreset(final CheckPreset preset) {
        checkList.read(preset.settings());
        approach.setChecks(preset.settings());
        showPreset();
        changed();
    }

    // A set chosen: put in place at once where the checks are a set already; asked first where it would
    // replace checks of the Learning Flow's own
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

    private void showPreset() {
        view.setPreset(CheckPreset.of(checkList.write()));
    }

    private void changed() {
        markChanged();
        showSummary();
    }

    // The steps shown on the map, or hidden where they may be; the step shown, if one is, gives way to Learning
    // steps
    private void toggleSteps() {
        stepsAsked = !stepsShown();
        if (!stepsShown() && stop.isStep()) {
            go(SetupStop.of(SetupSection.APPROACH));
        } else {
            showSummary();
        }
    }

    // Whether the steps must be in sight: they are the document's own, or something is wrong with them
    private boolean stepsNeeded() {
        return approach.example() == null || approach.flowProblem() != null || !approach.stepProblems().isEmpty();
    }

    private boolean stepsShown() {
        return stepsAsked || stepsNeeded();
    }

    // A stop chosen on the route map: its part shown, and the map drawn with it chosen
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

    // Where a step is shown, the step the approach has chosen, which a change to the steps may move
    private void followApproach() {
        if (stop.isStep()) {
            stop = SetupStop.step(approach.getSelected());
        }
    }

    private void showSummary() {
        final boolean stepsShown = stepsShown();
        stepsWereShown = stepsShown;
        if (doc != null) {
            final ShapeshifterAiDoc shown = write(doc);
            view.showMap(stepsShown
                            ? approach.getSteps()
                            : List.of(),
                    SetupSummary.of(shown),
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
