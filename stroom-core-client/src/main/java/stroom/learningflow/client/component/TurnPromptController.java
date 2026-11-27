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

import stroom.dispatch.client.RestFactory;
import stroom.learningflow.shared.SupervisorResource;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

/// Shows the dialog ([TurnPrompt]) with the prompt that was sent to the model for one question of a learning
/// run. It is opened from a Smart Pipeline's Learning Status tab.
///
/// Prompts are not stored; the server rebuilds one on request. The dialog therefore opens saying that the
/// prompt is being rebuilt, and is filled in when the server replies. The reply is either the prompt or the
/// reason it cannot be shown, in words for the user (e.g. no permission to view the data, the stream has been
/// deleted, or the run predates the recording of the texts needed to rebuild it).
public class TurnPromptController extends Controller {

    private static final SupervisorResource RESOURCE = GWT.create(SupervisorResource.class);

    private final TurnPrompt view;
    private final RestFactory restFactory;

    /// Made by GIN.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The dialog's content.
    /// @param restFactory Used to ask the server to rebuild the prompt.
    @Inject
    public TurnPromptController(final EventBus eventBus, final TurnPrompt view, final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        initWidget(view);
    }

    /// Shows the dialog, owned by `opener` until it is closed, and asks the server to rebuild the prompt.
    ///
    /// @param opener    The controller that opens the dialog and owns it while it is shown.
    /// @param attemptId The id of the learning run (attempt).
    /// @param number    The question's number within the run.
    public void show(final HasControllerNode opener, final long attemptId, final int number) {
        view.showRebuilding();
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.CLOSE_DIALOG)
                .popupSize(PopupSize.resizable(760, 560))
                .caption("Question " + number + " as it was sent")
                .fire();
        restFactory
                .create(RESOURCE)
                .method(resource -> resource.fetchPrompt(attemptId, number))
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
