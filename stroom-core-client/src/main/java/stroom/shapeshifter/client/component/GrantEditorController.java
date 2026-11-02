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
import stroom.shapeshifter.shared.GrantRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Gives a parked session more to spend (A65, A77), in a dialog ([GrantEditor]): more tokens over its
/// life, more minutes for each run of it, or both (design 06 A145). Granted to the one session, never to
/// its document; what it has spent and been granted so far, and what the document allows, are set out
/// above the two fields ([GrantText#soFar]) so that a person grants knowing how far over the session
/// already is.
///
/// Modal: it acts on the session selected behind it, which must not change under it.
public class GrantEditorController extends Controller {

    private final GrantEditor view;

    /// Made by GIN, with its editor.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     Its component.
    @Inject
    public GrantEditorController(final EventBus eventBus, final GrantEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Asks what to grant, and hands it on when OK is pressed with something granted. A grant of nothing
    /// is refused here, with the dialog left open, rather than sent to be refused by the server.
    ///
    /// @param opener  What shows it, which owns it until it is closed.
    /// @param attempt The parked session.
    /// @param doc     Its document, for its budgets, or null where it has not been read.
    /// @param onGrant Who is handed the grant.
    public void grant(final HasControllerNode opener,
                      final SupervisorAttempt attempt,
                      final ShapeshifterAiDoc doc,
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
