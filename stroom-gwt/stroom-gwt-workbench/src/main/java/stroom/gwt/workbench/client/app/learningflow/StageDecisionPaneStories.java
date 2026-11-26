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

package stroom.gwt.workbench.client.app.learningflow;

import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.gwt.workbench.client.app.gin.editors.EditorsScreenGinjector;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.learningflow.client.component.StageDecisionPaneController;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.LearningFlowStepDetails;
import stroom.learningflow.shared.StageScore;
import stroom.learningflow.shared.StageVerdict;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Map;

/// The `App/Main/StageDecisionPane` stories, showing Stroom's real [StageDecisionPaneController]. This is
/// the pane the stepping screen shows for a Learning Flow element at the stepping cursor, in place of the
/// code other elements show. `LearningFlowPlugin` registers it with `ElementStepDetailsPaneRegistry`, and
/// `SteppingElementController` gives it each step's details, which the stories supply as a step would. The
/// pane makes no requests and is read only.
public final class StageDecisionPaneStories {

    private static final String OPEN_DOC = "openDoc";
    private static final DocRef FRAGMENT = new DocRef("Pipeline", "f-1", "Firewall parser fragment");

    private StageDecisionPaneStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Main/StageDecisionPane", StageDecisionPaneStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // A served stream shows the decision, the document and stream it applies to, the fragment and
                // the scores.
                .story("Default", context -> render(context, true))
                .withPlay(play -> {
                    play.expect(play.findByText("Served by the binding")).toBeInTheDocument();
                    play.expect(play.getByText("Firewall logs")).toBeInTheDocument();
                    // A step is decided by every gate passing, not by a total score. Each check shows its own
                    // value and whether it was a gate or only advice.
                    play.expect(play.getByText("Step 1 — 95%, every gate passed")).toBeInTheDocument();
                    play.expect(play.getByText("Compiles")).toBeInTheDocument();
                    play.expect(play.getByText("100% (met 100%, a gate)")).toBeInTheDocument();
                    play.expect(play.getByText("Reads all the input")).toBeInTheDocument();
                    play.expect(play.getByText("90% (met 80%, advice)")).toBeInTheDocument();
                    play.click(play.getByRole("button", "Firewall parser fragment"));
                    play.expect(play.spy(OPEN_DOC)).toHaveBeenCalledWith("f-1");
                    expectNoProblems(play);
                })
                // A step for which the element has no details clears the pane.
                .story("NothingToSay", context -> render(context, false))
                .withPlay(play -> {
                    play.expect(play.findByText("Scores")).toBeInTheDocument();
                    play.expect(play.queryByText("Served by the binding")).toBeNull();
                    play.expect(play.queryByRole("button", "Firewall parser fragment")).toBeNull();
                    expectNoProblems(play);
                });
    }

    private static void expectNoProblems(final Play play) {
        play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
        play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
    }

    private static Widget render(final StoryContext context, final boolean served) {
        final EditorsScreenGinjector injector = GWT.create(EditorsScreenGinjector.class);
        final ScreenHarness harness = ScreenHarness.builder(context, RestFixtures.builder().build())
                .injector(injector)
                .build();
        harness.fn(OPEN_DOC);
        harness.getEventBus().addHandler(OpenDocumentEvent.getType(), event ->
                harness.spy(OPEN_DOC, event.getDocRef().getUuid()));
        harness.afterStartUp(() -> {
            final StageDecisionPaneController pane = harness.show(injector.getStageDecisionPane());
            pane.setDetails(served
                    ? details()
                    : null);
        });
        return harness.asWidget();
    }

    // The step details for a stream the element served with its binding.
    private static LearningFlowStepDetails details() {
        return new LearningFlowStepDetails(
                false,
                LearningFlowFixtures.DOC_REF,
                Map.of("Feed", "FIREWALL"),
                "SERVED",
                "Served by the binding",
                "b-1",
                FRAGMENT,
                0.95,
                "Event",
                List.of(new StageVerdict(1, 0.95, true, List.of(
                        new StageScore(Check.COMPILE, 1.0, 1.0, true, true),
                        new StageScore(Check.COVERAGE, 0.9, 0.8, false, true)))),
                List.of(),
                List.of());
    }
}
