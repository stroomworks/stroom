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

import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Asks for something in a dialog ([SupervisorPrompt]) before the Supervisor acts: the reason a draft is
/// rejected, a learner given up or a binding taken back, what should be better about a binding, or what a
/// learner should know.
///
/// OK and Cancel are told apart, and the difference matters: OK with nothing typed is an answer (to
/// improve a binding from what it already does), where Cancel is no answer at all, and a person who
/// changed their mind must not have started anything. Whether an empty answer is enough is the asker's
/// to decide.
///
/// Modal: what it asks about is the row selected behind it, which must not change under it.
public class SupervisorPromptController extends Controller {

    private final SupervisorPrompt view;

    /// Made by GIN, with its prompt.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     Its component.
    @Inject
    public SupervisorPromptController(final EventBus eventBus, final SupervisorPrompt view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Shows the prompt as a dialog, owned by `opener` until it is closed.
    ///
    /// @param opener  What shows it.
    /// @param caption The dialog's caption.
    /// @param message What is being asked, and what the answer is used for.
    /// @param onOk    Given the answer as typed, perhaps empty, when OK is pressed; not called on Cancel.
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
