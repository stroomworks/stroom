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

import stroom.alert.client.event.AlertEvent;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.ScorerParameters;
import stroom.learningflow.shared.TimesParameters;
import stroom.learningflow.shared.YieldParameters;
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

/// Shows the dialog ([CheckEditor]) that edits one of a Learning Flow's quality checks: whether it is Off,
/// Advice or a Gate, its threshold if it has one, and the settings specific to that check. Every check always
/// has a setting, so the dialog edits a given check rather than choosing one, and the check's name is its
/// caption. A business rules check's assertions are edited in a list with its own controller
/// ([XPathAssertionListController]).
public class CheckEditorController extends Controller {

    private final CheckEditor view;
    private final XPathAssertionListController assertionList;
    private Check check;

    /// Created by GIN.
    ///
    /// @param eventBus              The application's event bus.
    /// @param view                  The dialog's content.
    /// @param assertionListProvider Creates the controller for a business rules check's assertions.
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

    /// Shows the editor as a dialog, owned by `opener` until it is closed. If a field holds an invalid value,
    /// OK shows an error and leaves the dialog open; otherwise it passes the edited setting to `onOk`.
    ///
    /// @param opener  The screen that opens the dialog.
    /// @param setting The check's current setting.
    /// @param onOk    Called with the edited setting when OK is pressed.
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
        } else if (parameters instanceof final ExtractionQualityParameters quality) {
            view.setAllowUnknownEventDetail(quality.isAllowUnknownEventDetail());
            view.setRequiredFields(quality.getRequiredFields());
        } else if (parameters instanceof final TimesParameters times) {
            view.setTimes(times.getBeforeDays(), times.getAfterDays(), times.getTimeField());
        } else if (parameters instanceof final BusinessRulesParameters rules) {
            assertionList.read(rules.getAssertions());
            view.setIncludeTransformMessages(rules.isIncludeTransformMessages());
        }
    }

    private ScorerParameters writeParameters() {
        return switch (check) {
            case YIELD -> new YieldParameters(view.getExpectedRatio(), view.getBasis());
            case QUALITY -> new ExtractionQualityParameters(
                    view.isAllowUnknownEventDetail(), view.getRequiredFields());
            case RULES -> new BusinessRulesParameters(assertionList.write(), view.isIncludeTransformMessages());
            case TIMES -> new TimesParameters(view.getBeforeDays(), view.getAfterDays(), view.getTimeField());
            default -> null;
        };
    }

    // Returns why the dialog can't be accepted as it stands, or null if it can. The number fields' own
    // validation normally catches these first; this is a backstop in case one gets past it
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
