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

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.SupervisorGuidance;
import stroom.shapeshifter.shared.SupervisorResource;
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

/// Everything a supervisor has said to one learner (A46, A128), oldest first, in a dialog
/// ([GuidanceEditor]), with a way to say another thing and a way to take one back.
///
/// Taking one back is why this exists rather than a count on a row. A hint that turned out to be wrong is
/// worse than no hint: it is carried into every question the learner asks from then on, and a person who
/// cannot see what is standing cannot know that. The turns that already carried it still say they did
/// (A45): what is withdrawn stops being carried, and does not stop having been.
///
/// Not paged. Guidance is what one person wrote about one feed; a learner with a hundred hints is one
/// somebody should be talking to rather than typing at.
///
/// Not modal: an editor of the row it was opened from, which takes which learner it is about when it is
/// opened.
public class GuidanceEditorController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final GuidanceEditor view;
    private final RestFactory restFactory;
    private final Provider<SupervisorPromptController> promptProvider;
    private String docUuid;
    private LearnerRef learner;
    private Runnable onChange = () -> {
    };
    /// Whether what is shown may only be read: a deleted pipeline's learner is told nothing more (design
    /// 06 A137).
    private boolean readOnly;

    /// Made by GIN, with its list.
    ///
    /// @param eventBus       The application's event bus.
    /// @param view           Its component.
    /// @param restFactory    Makes REST requests.
    /// @param promptProvider Makes the prompt that asks what to say.
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

    /// Shows what has been said to a learner, as a dialog owned by `opener` until it is closed, filled
    /// once it has been read: shown first, so that it is owned while it is read.
    ///
    /// @param opener   What shows it.
    /// @param docUuid  The learner's document.
    /// @param learner  The learner, as the row names it.
    /// @param readOnly Whether it may only be read, as a deleted pipeline's learner may.
    /// @param onChange Run when something is said or taken back, so that the count on the row the dialog
    ///                 was opened from is read again.
    public void show(final HasControllerNode opener,
                     final String docUuid,
                     final LearnerRef learner,
                     final boolean readOnly,
                     final Runnable onChange) {
        this.docUuid = docUuid;
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
        // Its request shows on the dialog's spinner
        read(this::set);
    }

    private void add() {
        // Which learner this is about, taken now rather than when OK is pressed: the dialog can be
        // reopened on another while a prompt is up.
        final LearnerRef whose = learner;
        final String onDocument = docUuid;
        promptProvider.get().ask(this,
                "Tell this learner something",
                "What should this learner know? It is kept against the learner, so every question it "
                + "asks from now on carries it.",
                message -> {
                    // Empty is nothing to record.
                    if (NullSafe.isBlankString(message)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.hint(onDocument,
                                    new GuidanceRequest(whose, message)))
                            .onSuccess(this::changed)
                            .owner(this)
                            .taskMonitorFactory(taskMonitor())
                            .exec();
                });
    }

    /// Confirmed, because nothing here is versioned: a hint taken back is gone, and its words were
    /// somebody's.
    private void remove() {
        final SupervisorGuidance said = view.getSelectionModel().getSelected();
        if (said == null) {
            return;
        }
        final LearnerRef whose = learner;
        final String onDocument = docUuid;
        ConfirmEvent.fire(this,
                "Take this back? It stops being carried into what this learner asks. The turns "
                + "that already carried it still say they did.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.withdraw(onDocument, said.getId(),
                                        whose.getPipelineUuid(), whose.getElementId()))
                                .onSuccess(this::changed)
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    }
                });
    }

    private void changed(final List<SupervisorGuidance> said) {
        set(said);
        // The row this was opened from shows how much has been said, and it has just changed.
        onChange.run();
    }

    private void read(final Consumer<List<SupervisorGuidance>> consumer) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.guidance(docUuid, learner.getPipelineUuid(),
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
