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
import stroom.learningflow.shared.GrantRequest;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Shows the dialog ([GrantEditor]) that gives a parked session more to spend: more tokens over its life,
/// more minutes for each of its runs, or both. A session parks for a person when it runs out of budget. The
/// grant applies to that session only, never to its document. What the session has spent and been granted
/// so far, and what the document allows, are shown above the two fields ([GrantText#soFar]), so that a
/// person knows how far over budget the session already is.
///
/// The dialog is modal because it acts on the session selected behind it, which must not change while it is
/// open.
public class GrantEditorController extends Controller {

    private final GrantEditor view;

    /// Created by GIN.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The dialog's content.
    @Inject
    public GrantEditorController(final EventBus eventBus, final GrantEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Asks what to grant, and passes the grant on when OK is pressed. An invalid grant, such as one of
    /// nothing, is reported here with the dialog left open, rather than sent to the server to be refused.
    ///
    /// @param opener  The screen that opens the dialog and owns it until it is closed.
    /// @param attempt The parked session.
    /// @param doc     The session's Learning Flow document, for its budgets, or null if it has not been fetched.
    /// @param onGrant Called with the grant when OK is pressed.
    public void grant(final HasControllerNode opener,
                      final SupervisorAttempt attempt,
                      final LearningFlowDoc doc,
                      final Consumer<GrantRequest> onGrant) {
        view.setSoFar(GrantText.soFar(attempt, doc));
        view.setTokens(0L);
        view.setMinutes(0L);
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(560, 360))
                .modal()
                .caption("Give this session more to spend")
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final GrantRequest grant = GrantRequest.ofMinutes(view.getTokens(), view.getMinutes());
                    final String problem = grant.problem();
                    if (problem != null) {
                        AlertEvent.fireError(this, problem, e::reset);
                        return;
                    }
                    e.hide();
                    onGrant.accept(grant);
                })
                .fire();
    }
}
