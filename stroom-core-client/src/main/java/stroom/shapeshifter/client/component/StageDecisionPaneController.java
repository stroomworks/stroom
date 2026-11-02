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

import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.pipeline.shared.stepping.ElementStepDetails;
import stroom.pipeline.stepping.client.component.ElementStepDetailsPane;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The stage pane (A30, design 01 §11.7), [StageDecisionPane]: what a supervised stage did with the
/// stream at the cursor, where the code pane would be. Registered for the Shapeshifter AI element, whose
/// stepping controller makes one for each element shown.
///
/// A supervisor has no document to show as code, and what a person selecting it needs is the decision:
/// what serves this learner, what the scorers made of the stream, and what was said to the model where it
/// was learned. Stepping is a dry run (A30, A121), so a learner nothing serves shows what the stage
/// *would* do and the pane says so.
///
/// Read only. Approving, rejecting and retracting are the Supervisor's.
public class StageDecisionPaneController extends Controller implements ElementStepDetailsPane {

    private final StageDecisionPane view;
    private DocRef fragment;

    /// Made by GIN, with its pane.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     Its component.
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
        if (details instanceof final ShapeshifterAiStepDetails stage) {
            view.setDecision(stage.getReason());
            view.setSummary(summary(stage));
            view.setVerdicts(stage.getVerdicts());
            view.setTranscript(stage.getTranscript());
            setFragment(stage.getFragment());
        } else {
            // A step this element had nothing to say about (one it never ran on, one the parser above it
            // refused) clears the pane. Left alone it would show the step before as this one, which is
            // worse than showing nothing: a decision, a binding and a transcript that are not this
            // record's, presented as though they were.
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

    private static Map<String, String> summary(final ShapeshifterAiStepDetails stage) {
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
        if (stage.isProvisional()) {
            terms.put("Provisional", "yes, until the learner brings enough records to judge it");
        }
        if (stage.getRecordBoundary() != null) {
            terms.put("Record", stage.getRecordBoundary());
        }
        return terms;
    }
}
