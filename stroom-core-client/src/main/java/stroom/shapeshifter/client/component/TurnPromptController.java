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

import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

/// The dialog that shows what a learning run's question was put to the model with ([TurnPrompt]; decision
/// N9, Track 2 step 4). The prompt is rebuilt on the server when asked for rather than stored, so the
/// dialog opens saying so and fills when the server answers: with the prompt, or with why it can't be
/// shown, which the server says in words (no permission to view data, the stream deleted, a run asked
/// before its words were kept).
public class TurnPromptController extends Controller {

    private static final SupervisorResource RESOURCE = GWT.create(SupervisorResource.class);

    private final TurnPrompt view;
    private final RestFactory restFactory;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The dialog's content.
    /// @param restFactory Asks the server to rebuild the prompt.
    @Inject
    public TurnPromptController(final EventBus eventBus, final TurnPrompt view, final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        initWidget(view);
    }

    /// Shows the dialog, owned by `opener` until it is closed, and asks for the prompt.
    ///
    /// @param opener    What shows it.
    /// @param attemptId The learning run.
    /// @param number    The question's number in it.
    public void show(final HasControllerNode opener, final long attemptId, final int number) {
        view.showRebuilding();
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.CLOSE_DIALOG)
                .popupSize(PopupSize.resizable(760, 560))
                .caption("Question " + number + " as it was sent")
                .fire();
        restFactory
                .create(RESOURCE)
                .method(resource -> resource.prompt(attemptId, number))
                .onSuccess(rebuilt -> {
                    if (rebuilt.getRefusal() != null) {
                        view.showRefusal(rebuilt.getRefusal());
                    } else {
                        view.showPrompt(rebuilt.getPrompt(), rebuilt.getNotes());
                    }
                })
                .onFailure(error -> view.showRefusal("The prompt couldn't be rebuilt: " + error.getMessage()))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }
}
