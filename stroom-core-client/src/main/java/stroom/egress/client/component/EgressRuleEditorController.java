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

package stroom.egress.client.component;

import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressResource;
import stroom.egress.shared.EgressRuleDto;
import stroom.egress.shared.EgressTestRequest;
import stroom.egress.shared.EgressTestResponse;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Edits a rule ([EgressRuleEditor]) in a dialog made for one use. OK saves it; the server tidies the destination and
/// refuses a rule it cannot accept, and that is said in the dialog.
public class EgressRuleEditorController extends Controller {

    private static final EgressResource RESOURCE = GWT.create(EgressResource.class);

    private final EgressRuleEditor view;
    private final RestFactory restFactory;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The content.
    /// @param restFactory Saves the rule and tests a destination.
    @Inject
    public EgressRuleEditorController(final EventBus eventBus,
                                      final EgressRuleEditor view,
                                      final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        initWidget(view);
        view.setOnKindChange(this::onKindChange);
        view.setOnTest(this::test);
    }

    /// Shows the rule in a dialog, owned by `opener` until it is closed.
    ///
    /// @param opener   What shows it.
    /// @param rule     The rule to edit, or one to start a new rule from, or null for an empty one.
    /// @param title    The dialog's caption.
    /// @param consumer Given the rule as stored, once OK has saved it.
    public void show(final HasControllerNode opener,
                     final EgressRuleDto rule,
                     final String title,
                     final Consumer<EgressRuleDto> consumer) {
        final Long id = rule == null
                ? null
                : rule.getId();
        view.setKind(rule == null
                ? EgressKind.HTTP
                : rule.getKind());
        view.setMatch(rule == null
                ? EgressMatch.EXACT
                : rule.getMatch());
        view.setDestination(rule == null
                ? ""
                : rule.getDestination());
        view.setComment(rule == null || rule.getComment() == null
                ? ""
                : rule.getComment());
        view.setEnabled(rule == null || rule.isEnabled());
        view.setTestResult("");

        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(640, 520))
                .caption(title)
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final EgressRuleDto edited = new EgressRuleDto(
                                id,
                                view.getKind(),
                                view.getDestination().trim(),
                                view.getMatch(),
                                view.getComment().trim(),
                                view.isEnabled());
                        restFactory.create(RESOURCE)
                                .method(resource -> resource.saveRule(edited))
                                .onSuccess(saved -> {
                                    e.hide();
                                    consumer.accept(saved);
                                })
                                .onFailure(RestErrorHandler.forPopup(this, e))
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    } else {
                        e.hide();
                    }
                })
                .fire();
    }

    // An email address can only match exactly
    private void onKindChange() {
        if (!view.getKind().isPrefixAllowed()) {
            view.setMatch(EgressMatch.EXACT);
        }
    }

    private void test() {
        final EgressTestRequest request = new EgressTestRequest(view.getKind(), view.getDestination().trim());
        restFactory.create(RESOURCE)
                .method(resource -> resource.test(request))
                .onSuccess(this::showTestResult)
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void showTestResult(final EgressTestResponse response) {
        final StringBuilder text = new StringBuilder();
        text.append(response.getOutcome());
        if (response.getNormalised() != null) {
            text.append(" - kept as ").append(response.getNormalised());
        }
        if (response.getReason() != null) {
            text.append(" - ").append(response.getReason());
        }
        if (response.getRuleId() != null) {
            text.append(" - by rule ").append(response.getRuleId());
        }
        view.setTestResult(text.toString());
    }
}
