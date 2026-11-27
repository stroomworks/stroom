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

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.learningflow.shared.GuidanceRequest;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.SupervisorGuidance;
import stroom.learningflow.shared.SupervisorResource;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.function.Consumer;

/// Shows the dialog ([GuidanceEditor]) listing the notes (guidance) people have left for one learner, oldest
/// first, where a note can be added or withdrawn.
///
/// Withdrawing is the main reason for this dialog, rather than just showing a count of notes. A note that
/// turns out to be wrong is worse than none, because it is included in every question the learner asks from
/// then on, and a person can't know that without seeing which notes are in use. Withdrawing a note stops it
/// being included in later questions; the recorded turns that already included it are not changed.
///
/// The list is not paged, because the notes for one feed are expected to be few.
///
/// The dialog is not modal. It records which learner it is about when it is opened.
public class GuidanceEditorController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final GuidanceEditor view;
    private final RestFactory restFactory;
    private final Provider<SupervisorPromptController> promptProvider;
    private LearnerRef learner;
    private Runnable onChange = () -> {
    };
    /// Whether the notes may only be read, which is the case for a deleted pipeline's learner.
    private boolean readOnly;

    /// Created by GIN.
    ///
    /// @param eventBus       The application's event bus.
    /// @param view           The dialog's content.
    /// @param restFactory    Creates REST requests.
    /// @param promptProvider Creates the dialog that asks for the text of a new note.
    @Inject
    public GuidanceEditorController(final EventBus eventBus,
                                    final GuidanceEditor view,
                                    final RestFactory restFactory,
                                    final Provider<SupervisorPromptController> promptProvider) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        this.promptProvider = promptProvider;
        initWidget(view);
        view.setOnAdd(this::add);
        view.setOnRemove(this::remove);
        own(view.getSelectionModel().addSelectionHandler(event -> updateButtons()));
        updateButtons();
    }

    /// Shows the notes left for a learner, as a dialog owned by `opener` until it is closed. The dialog is
    /// shown first and filled once the notes have been fetched, so that it owns the request while it runs.
    ///
    /// @param opener   The screen that opens the dialog.
    /// @param learner  The learner.
    /// @param readOnly Whether the notes may only be read, as for a deleted pipeline's learner.
    /// @param onChange Run when a note is added or withdrawn, so that the screen the dialog was opened from
    ///                 can refresh its count of notes.
    public void show(final HasControllerNode opener,
                     final LearnerRef learner,
                     final boolean readOnly,
                     final Runnable onChange) {
        this.learner = learner;
        this.readOnly = readOnly;
        this.onChange = onChange;
        updateButtons();
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.CLOSE_DIALOG)
                .popupSize(PopupSize.resizable(800, 400))
                .modeless()
                .caption("What has been said to this learner")
                .fire();
        // Fetched after the dialog is shown so that the request shows on the dialog's spinner
        read(this::set);
    }

    private void add() {
        // Record the learner now rather than when OK is pressed, because the dialog can be reopened for
        // another learner while the prompt is open.
        final LearnerRef whose = learner;
        promptProvider.get().ask(this,
                "Tell this learner something",
                "What should this learner know? It is kept against the learner, so every question it "
                + "asks from now on carries it.",
                message -> {
                    // An empty note is not recorded.
                    if (NullSafe.isBlankString(message)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.addGuidance(whose.getPipelineUuid(),
                                    whose.getElementId(), new GuidanceRequest(message)))
                            .onSuccess(this::changed)
                            .owner(this)
                            .taskMonitorFactory(taskMonitor())
                            .exec();
                });
    }

    /// Withdraws the selected note, after confirmation. Confirmation is needed because notes are not
    /// versioned: a withdrawn note is gone, and someone wrote it.
    private void remove() {
        final SupervisorGuidance said = view.getSelectionModel().getSelected();
        if (said == null) {
            return;
        }
        final LearnerRef whose = learner;
        ConfirmEvent.fire(this,
                "Take this back? It stops being carried into what this learner asks. The turns "
                + "that already carried it still say they did.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.deleteGuidance(whose.getPipelineUuid(),
                                        whose.getElementId(), said.getId()))
                                .onSuccess(this::changed)
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    }
                });
    }

    private void changed(final List<SupervisorGuidance> said) {
        set(said);
        // The screen this was opened from shows the number of notes, which has just changed.
        onChange.run();
    }

    private void read(final Consumer<List<SupervisorGuidance>> consumer) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.fetchGuidance(learner.getPipelineUuid(),
                        learner.getElementId()))
                .onSuccess(consumer::accept)
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void set(final List<SupervisorGuidance> said) {
        view.setGuidance(NullSafe.list(said));
        updateButtons();
    }

    private void updateButtons() {
        view.setRemoveEnabled(!readOnly && view.getSelectionModel().getSelected() != null);
        view.setAddEnabled(!readOnly);
    }
}
