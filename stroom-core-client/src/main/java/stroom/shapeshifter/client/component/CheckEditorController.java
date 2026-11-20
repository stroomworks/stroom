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
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckState;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerParameters;
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

/// Edits one check of a Learning Flow in a dialog ([CheckEditor]): whether it is Off, Advice or a
/// Gate, its threshold where it has one, and the panel of settings that belongs to the check (the one
/// model of checks). Every check has a setting, so the check itself isn't chosen; it names the dialog. A
/// business rules check's assertions are a list of their own ([XPathAssertionListController]).
public class CheckEditorController extends Controller {

    private final CheckEditor view;
    private final XPathAssertionListController assertionList;
    private Check check;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus              The application's event bus.
    /// @param view                  The dialog's content.
    /// @param assertionListProvider Makes the controller of a business rules check's assertions.
    @Inject
    public CheckEditorController(final EventBus eventBus,
                                         final CheckEditor view,
                                         final Provider<XPathAssertionListController> assertionListProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        assertionList = make(assertionListProvider).attach(view.getAssertions());
        watch(Contexts.READ_ONLY, view::setReadOnly);
    }

    /// Shows the editor as a dialog, owned by `opener` until it is closed. OK refuses while a number
    /// typed can't be kept (the field says why); otherwise it gives the setting to `onOk`.
    ///
    /// @param opener  What shows it.
    /// @param setting The check's setting.
    /// @param onOk    What to do with the setting when OK is pressed.
    public void edit(final HasControllerNode opener,
                     final CheckSetting setting,
                     final Consumer<CheckSetting> onOk) {
        read(setting);
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(600, 640))
                .caption(PlainWords.checkName(setting.getCheck()))
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final String reason = validate();
                    if (reason != null) {
                        AlertEvent.fireError(this, reason, e::reset);
                    } else {
                        onOk.accept(write());
                        e.hide();
                    }
                })
                .fire();
    }

    private void read(final CheckSetting setting) {
        check = setting.getCheck();
        view.showCheck(check);
        view.setState(setting.getState());
        view.setThreshold(setting.getThreshold());
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
        }
    }

    private ScorerParameters writeParameters() {
        return switch (check) {
            case YIELD -> new YieldParameters(view.getExpectedRatio(), view.getBasis());
            case CONFORMANCE -> new SchemaConformanceParameters(view.getSchemaGroup());
            case QUALITY -> new ExtractionQualityParameters(
                    view.isAllowUnknownEventDetail(), view.getRequiredFields());
            case RULES -> new BusinessRulesParameters(assertionList.write(), view.isIncludeTransformMessages());
            default -> null;
        };
    }

    // Why the dialog can't be accepted as it stands, or null if it can. The numbers' own checks have
    // refused already (their fields say why), so these hold only if one slipped past
    private String validate() {
        if (view.getState() == null) {
            return "Choose whether the check is off, advice or a gate.";
        }
        if (check.hasThreshold()) {
            final Double threshold = view.getThreshold();
            if (threshold == null || threshold < 0 || threshold > 1) {
                return "The threshold must be a number from 0 to 1.";
            }
        }
        if (check == Check.YIELD) {
            final Double ratio = view.getExpectedRatio();
            if (ratio == null || ratio <= 0) {
                return "The expected ratio must be a number above zero.";
            }
        }
        return null;
    }

    private CheckSetting write() {
        return new CheckSetting(
                check,
                check == Check.COMPILE
                        ? CheckState.GATE
                        : view.getState(),
                view.getThreshold(),
                writeParameters());
    }
}
