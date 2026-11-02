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

import stroom.alert.client.event.AlertEvent;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ErrorLoadParameters;
import stroom.shapeshifter.shared.EventClassificationParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;
import java.util.function.Function;

/// Adds or edits one scorer setting in a dialog ([ScorerSettingEditor]): type, weight, threshold, gate,
/// and the panel of parameters that belongs to the type (§8.4). Choosing a type shows its panel with
/// that type's defaults, or nothing for a scorer that takes none. A business rules scorer's assertions
/// are a list of their own ([XPathAssertionListController]).
///
/// The dialog isn't modal (an editor of an item in a list): its opener checks what it gives back
/// against the set as it is when OK is pressed.
public class ScorerSettingEditorController extends Controller {

    private final ScorerSettingEditor view;
    private final XPathAssertionListController assertionList;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus              The application's event bus.
    /// @param view                  The dialog's content.
    /// @param assertionListProvider Makes the controller of a business rules scorer's assertions.
    @Inject
    public ScorerSettingEditorController(final EventBus eventBus,
                                         final ScorerSettingEditor view,
                                         final Provider<XPathAssertionListController> assertionListProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        assertionList = make(assertionListProvider).attach(view.getAssertions());
        view.setOnTypeChange(type -> {
            view.showParameters(type);
            readParameters(ScorerParameters.defaultsFor(type));
        });
        watch(Contexts.READ_ONLY, view::setReadOnly);
    }

    /// Shows the editor as a dialog, owned by `opener` until it is closed. OK refuses while a number
    /// typed can't be kept (the field says why), or while there is a problem with the scorer (said in
    /// an alert, after which the dialog stays open); otherwise it gives the scorer to `onOk`.
    ///
    /// @param opener   What shows it.
    /// @param setting  The scorer to edit, or a new one.
    /// @param problem  Gives why a scorer can't be accepted as it stands (e.g. another of its type is
    ///                 in the set), or null if it can.
    /// @param onOk     What to do with the scorer when OK is pressed.
    public void edit(final HasControllerNode opener,
                     final ScorerSetting setting,
                     final Function<ScorerSetting, String> problem,
                     final Consumer<ScorerSetting> onOk) {
        read(setting);
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(600, 640))
                .caption("Scorer")
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final String invalid = validate();
                    final String reason = invalid != null
                            ? invalid
                            : problem.apply(write());
                    if (reason != null) {
                        AlertEvent.fireError(this, reason, e::reset);
                    } else {
                        onOk.accept(write());
                        e.hide();
                    }
                })
                .fire();
    }

    private void read(final ScorerSetting setting) {
        view.setType(setting.getType());
        view.setWeight(setting.getWeight());
        view.setThreshold(setting.getThreshold());
        view.setGate(setting.isGate());
        view.showParameters(setting.getType());
        readParameters(setting.getParameters());
    }

    private void readParameters(final ScorerParameters parameters) {
        // GWT compiles instanceof patterns but not pattern switches.
        if (parameters instanceof final YieldParameters yield) {
            view.setExpectedRatio(yield.getExpectedRatio());
            view.setBasis(yield.getBasis());
        } else if (parameters instanceof final SchemaConformanceParameters schema) {
            view.setSchemaGroup(schema.getSchemaGroup());
        } else if (parameters instanceof final ExtractionQualityParameters quality) {
            view.setAllowUnknownEventDetail(quality.isAllowUnknownEventDetail());
            view.setRequiredFields(quality.getRequiredFields());
        } else if (parameters instanceof final BusinessRulesParameters rules) {
            assertionList.read(rules.getAssertions());
            view.setIncludeTransformMessages(rules.isIncludeTransformMessages());
        } else if (parameters instanceof final ErrorLoadParameters errors) {
            view.setMinimumSeverity(errors.getMinimumSeverity());
        } else if (parameters instanceof final EventClassificationParameters classes) {
            view.setRecognisedTypes(classes.getRecognisedTypes());
        }
    }

    private ScorerParameters writeParameters(final ScorerType type) {
        return switch (type) {
            case COMPILE, INPUT_COVERAGE -> null;
            case YIELD -> new YieldParameters(view.getExpectedRatio(), view.getBasis());
            case SCHEMA_CONFORMANCE -> new SchemaConformanceParameters(view.getSchemaGroup());
            case EXTRACTION_QUALITY -> new ExtractionQualityParameters(
                    view.isAllowUnknownEventDetail(), view.getRequiredFields());
            case BUSINESS_RULES -> new BusinessRulesParameters(
                    assertionList.write(), view.isIncludeTransformMessages());
            case ERROR_LOAD -> new ErrorLoadParameters(view.getMinimumSeverity());
            case EVENT_CLASSIFICATION -> new EventClassificationParameters(view.getRecognisedTypes());
        };
    }

    // Why the dialog can't be accepted as it stands, or null if it can. The numbers' own checks have
    // refused already (their fields say why), so these hold only if one slipped past
    private String validate() {
        final ScorerType type = view.getType();
        if (type == null) {
            return "Choose a scorer.";
        }
        final Double weight = view.getWeight();
        if (weight == null || weight < 0) {
            return "The weight must be a number of zero or more.";
        }
        final Double threshold = view.getThreshold();
        if (threshold == null || threshold < 0 || threshold > 1) {
            return "The threshold must be a number from 0 to 1.";
        }
        if (type == ScorerType.YIELD) {
            final Double ratio = view.getExpectedRatio();
            if (ratio == null || ratio <= 0) {
                return "The expected ratio must be a number above zero.";
            }
        }
        return null;
    }

    private ScorerSetting write() {
        final ScorerType type = view.getType();
        return new ScorerSetting(
                type,
                view.getWeight(),
                view.getThreshold(),
                view.isGate(),
                writeParameters(type));
    }
}
