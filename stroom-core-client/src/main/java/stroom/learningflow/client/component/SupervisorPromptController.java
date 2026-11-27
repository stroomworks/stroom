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

import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Shows a dialog ([SupervisorPrompt]) that asks a person for some text before an action on a learner is
/// taken, such as the reason a draft is rejected, a learner is given up or a binding is withdrawn, what
/// should be improved about a binding, or guidance for a learner.
///
/// OK and Cancel are handled differently. OK with nothing typed is still an answer (for example, asking for a
/// binding to be improved without saying how), whereas Cancel means the person changed their mind, so nothing
/// must be started. Whether an empty answer is acceptable is up to the caller.
///
/// The dialog is modal because the question is about the row selected behind it, and that selection must not
/// change while the dialog is open.
public class SupervisorPromptController extends Controller {

    private final SupervisorPrompt view;

    /// Made by GIN.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The dialog's content.
    @Inject
    public SupervisorPromptController(final EventBus eventBus, final SupervisorPrompt view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Shows the prompt as a dialog, owned by `opener` until it is closed.
    ///
    /// @param opener  The controller that opens the dialog and owns it while it is shown.
    /// @param caption The dialog's caption.
    /// @param message The question being asked, and what the answer will be used for.
    /// @param onOk    Called with the answer as typed, which may be empty, when OK is pressed. It is not
    ///                called on Cancel.
    public void ask(final HasControllerNode opener,
                    final String caption,
                    final String message,
                    final Consumer<String> onOk) {
        view.setMessage(message);
        view.setReply("");
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(560, 300))
                .modal()
                .caption(caption)
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    final String reply = view.getReply();
                    e.hide();
                    if (e.isOk()) {
                        onOk.accept(reply);
                    }
                })
                .fire();
    }
}
