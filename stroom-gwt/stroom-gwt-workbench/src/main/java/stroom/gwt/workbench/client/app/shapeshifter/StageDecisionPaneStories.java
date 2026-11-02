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

package stroom.gwt.workbench.client.app.shapeshifter;

import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.gwt.workbench.client.app.gin.editors.EditorsScreenGinjector;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.client.component.StageDecisionPaneController;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
import stroom.shapeshifter.shared.StageScore;
import stroom.shapeshifter.shared.StageVerdict;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Map;

/// The `App/Main/StageDecisionPane` stories, showing Stroom's real [StageDecisionPaneController]: the
/// pane the stepping screen shows for a supervised stage (a Shapeshifter AI element) at the stepping
/// cursor, where other elements show their code. `ShapeshifterAiPlugin` registers it with
/// `ElementStepDetailsPaneRegistry`, and `SteppingElementController` gives it each step's details,
/// which the stories give it as a step would. It makes no requests, and is read only.
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
                // A served stream: the decision, its terms, the fragment and the scores
                .story("Default", context -> render(context, true))
                .withPlay(play -> {
                    play.expect(play.findByText("Served by the binding")).toBeInTheDocument();
                    play.expect(play.getByText("Firewall logs")).toBeInTheDocument();
                    play.expect(play.getByText("Step 1 — 0.95 (passed)")).toBeInTheDocument();
                    play.click(play.getByRole("button", "Firewall parser fragment"));
                    play.expect(play.spy(OPEN_DOC)).toHaveBeenCalledWith("f-1");
                    expectNoProblems(play);
                })
                // A step the stage had nothing to say about clears the pane
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

    // A stream the stage served with its binding, as a step's details say
    private static ShapeshifterAiStepDetails details() {
        return new ShapeshifterAiStepDetails(
                false,
                ShapeshifterAiFixtures.DOC_REF,
                Map.of("Feed", "FIREWALL"),
                "SERVED",
                "Served by the binding",
                "b-1",
                FRAGMENT,
                false,
                0.95,
                "Event",
                List.of(new StageVerdict(1, 0.95, true, List.of(
                        new StageScore(ScorerType.COMPILE, 1.0, 1.0, true, true),
                        new StageScore(ScorerType.INPUT_COVERAGE, 0.9, 0.8, false, true)))),
                List.of(),
                List.of());
    }
}
