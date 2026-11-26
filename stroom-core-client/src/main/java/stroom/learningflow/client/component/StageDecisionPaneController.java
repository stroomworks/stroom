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

import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.learningflow.shared.LearningFlowStepDetails;
import stroom.pipeline.shared.stepping.ElementStepDetails;
import stroom.pipeline.stepping.client.component.ElementStepDetailsPane;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Fills the pane ([StageDecisionPane]) that the pipeline stepper shows for a Smart Pipeline element, in
/// place of a code pane. It is registered for the Smart Pipeline element type, and the stepper makes one for
/// each such element shown.
///
/// A Smart Pipeline element has no single document to show as code. What a person stepping through it needs
/// is its decision: which binding serves the learner, how the checks scored the stream, and, if it was learned
/// from, what was said to the model. Stepping is a dry run that never learns or changes anything, so for a
/// learner that nothing serves yet, the pane shows what the element *would* do, and says so.
///
/// The pane is read-only. Approving, rejecting and withdrawing translations are done on the pipeline's
/// Learning Status tab.
public class StageDecisionPaneController extends Controller implements ElementStepDetailsPane {

    private final StageDecisionPane view;
    private DocRef fragment;

    /// Made by GIN.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The pane.
    @Inject
    public StageDecisionPaneController(final EventBus eventBus, final StageDecisionPane view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.setOnOpenFragment(() -> {
            if (fragment != null) {
                OpenDocumentEvent.fire(this, fragment, true);
            }
        });
    }

    @Override
    public void setDetails(final ElementStepDetails details) {
        if (details instanceof final LearningFlowStepDetails stage) {
            view.setDecision(stage.getReason());
            view.setSummary(summary(stage));
            view.setVerdicts(stage.getVerdicts());
            view.setTranscript(stage.getTranscript());
            setFragment(stage.getFragment());
        } else {
            // Clear the pane for a stepping position this element has no details for (e.g. it never ran, or
            // an earlier element rejected the input). Otherwise the pane would go on showing the previous
            // position's decision, binding and transcript as though they belonged to this one.
            view.setDecision("");
            view.setSummary(Map.of());
            view.setVerdicts(List.of());
            view.setTranscript(List.of());
            setFragment(null);
        }
    }

    private void setFragment(final DocRef fragment) {
        this.fragment = fragment;
        view.setFragment(NullSafe.get(fragment, DocRef::getName));
    }

    private static Map<String, String> summary(final LearningFlowStepDetails stage) {
        final Map<String, String> terms = new LinkedHashMap<>(NullSafe.map(stage.getStream()));
        if (stage.getDocument() != null) {
            terms.put("Document", stage.getDocument().getName());
        }
        if (stage.getBindingUuid() != null) {
            terms.put("Binding", stage.getBindingUuid());
        }
        if (stage.getScore() != null) {
            terms.put("Score", String.valueOf(stage.getScore()));
        }
        if (stage.getRecordBoundary() != null) {
            terms.put("Record", stage.getRecordBoundary());
        }
        return terms;
    }
}
