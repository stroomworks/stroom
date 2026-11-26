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

import stroom.gwt.workbench.client.app.editors.DocEditors;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.learningflow.client.component.LearnedTranslationController;
import stroom.learningflow.client.component.QualityTabController;

import com.google.gwt.user.client.ui.Widget;

/// Stories for two views of a Smart Pipeline other than its Learning Status tab, shown in Stroom's real pipeline
/// editor with fake REST replies ([AiPipelines]):
///
/// - `App/Editors/PipelineQualityAssurance` shows its **Quality Assurance** tab ([QualityTabController]): the
///   figures its translation is judged by, and each learning run's score.
/// - `App/Editors/PipelineStructureLearned` shows its Structure tab with the Smart Pipeline element selected
///   ([LearnedTranslationController]): **What it learned**, folded until asked for, then the learned
///   translation's elements and the documents they use, which can be opened.
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `GET /learningFlowSupervisor/v1/pipelines/p-1/learners` | the Smart Pipeline's learner |
/// | `POST /learningFlowSupervisor/v1/find` | its learning runs |
/// | `GET /learningFlowSupervisor/v1/learners/ss-1/settings` | the lines its Learning Flow draws |
/// | `POST /pipeline/v1/fetchPipelineLayers` for `f-2` | the learned translation's elements |
public final class PipelineQualityStories {

    private static final String TAB = "Quality Assurance";
    private static final String WHAT_IT_LEARNED = "What it learned";
    private static final String AI_ELEMENT = "Learn the feed";

    // The firewall pipeline's learner serving, with a recent rolling score over its records and no streams
    // waiting.
    private static final String TRANSLATING = LearningFlowFixtures.FIREWALL_TRANSLATING
            .replace("\"waiting\": 2", "\"waiting\": 0")
            .replace("\"records\": 0", "\"records\": 1204, \"rollingScore\": 0.98");
    // As TRANSLATING, but with a recent score below the threshold at which its Learning Flow flags it.
    private static final String BELOW_PAR = TRANSLATING
            .replace("\"rollingScore\": 0.98", "\"rollingScore\": 0.42")
            .replace("\"belowThreshold\": false", "\"belowThreshold\": true");

    // The learned translation's pipeline layers: a parser using a text converter, then a filter using an XSLT.
    private static final String FRAGMENT_LAYERS = """
            [{"sourcePipeline": {"type": "Pipeline", "uuid": "f-2", "name": "Firewall pipeline learned"},
              "pipelineData": {
                "elements": {"add": [{"id": "xsltFilter", "type": "XSLTFilter"},
                  {"id": "dsParser", "type": "DSParser", "name": "Parse the firewall log"}]},
                "links": {"add": [{"from": "dsParser", "to": "xsltFilter"}]},
                "properties": {"add": [
                  {"element": "dsParser", "name": "textConverter",
                    "value": {"entity": {"type": "TextConverter", "uuid": "tc-1", "name": "Firewall parser"}}},
                  {"element": "xsltFilter", "name": "xslt",
                    "value": {"entity": {"type": "XSLT", "uuid": "x-1", "name": "Firewall transform"}}}]}}}]""";

    private static final String QUALITY = "/learningFlowQuality/v1/pipelines/p-1/elements/learningFlow";
    // The first of the report's fourteen days, 30 October 2025 (UTC). The days run oldest first, and the
    // twelfth is the busiest.
    private static final long FIRST_DAY = 1_761_782_400_000L;
    private static final String REPORT = report();
    private static final String SAMPLES = """
            [{"text": "2025-10-30T10:00:01Z,10.0.0.1,443,ALLOW", "record": "<record/>",
              "output": "<Event><EventDetail><TypeId>Network/Open</TypeId></EventDetail></Event>",
              "kind": "TRANSLATION", "score": 0.95, "acceptedMs": 1761800000000, "target": "Network/Open"},
             {"text": "# firewall log v2", "record": "<record/>", "kind": "NONE", "score": 0.95,
              "acceptedMs": 1761700000000}]""";

    private PipelineQualityStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Editors/PipelineQualityAssurance", PipelineQualityStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The figures the translation in use is judged by, each with what it is based on, and each
                // learning run's score.
                .story("Default", context -> render(context, false, "[" + TRANSLATING + "]"))
                .withPlay(play -> {
                    openTab(play);
                    final Play figures = play.within(play.findByRole("list", "Quality figures"));
                    play.expect(figures.findByText("98%")).toBeInTheDocument();
                    play.expect(figures.getByText(TextMatch.startingWith("Over the last 1,204 records")))
                            .toBeInTheDocument();
                    play.expect(figures.getByText(TextMatch.containing("flags a translation in use below 60%")))
                            .toBeInTheDocument();
                    play.expect(figures.getByText("97%")).toBeInTheDocument();
                    final Play runs = play.within(play.getByRole("grid", "Learning runs"));
                    play.expect(runs.findByText("95%")).toBeInTheDocument();
                    // The run's status and its decision both read "Promoted".
                    play.expect(runs.getAllByText("Promoted")).toHaveLength(2);
                    // There is only one element, so the element chooser is hidden.
                    play.expect(play.queryByText("Smart Pipeline element")).not().toBeVisible();
                    // The volume is given in words and as a chart, with what the kept records became.
                    play.expect(play.findByText("1,830 records in 61 streams over the last 14 days."))
                            .toBeVisible();
                    play.expect(play.getByRole("img", TextMatch.startingWith("Records translated each day")))
                            .toBeInTheDocument();
                    final Play cardinality = play.within(play.getByRole("list", "What its records became"));
                    play.expect(cardinality.getByText("Marked Unknown")).toBeVisible();
                    // Each sample shows a record as received beside what it became.
                    final Play samples = play.within(play.findByRole("list", "Samples"));
                    play.expect(samples.findByText("2025-10-30T10:00:01Z,10.0.0.1,443,ALLOW")).toBeVisible();
                    play.expect(samples.getByText(TextMatch.containing("<TypeId>Network/Open</TypeId>")))
                            .toBeVisible();
                    play.expect(samples.getByText("No event: this kind of record yields none.")).toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // The day-by-day figures are folded beneath the chart.
                .story("VolumeDayByDay", context -> render(context, false, "[" + TRANSLATING + "]"))
                .withPlay(play -> {
                    openTab(play);
                    final Query toggle = play.findByRole("button", "Day by day");
                    play.expect(toggle).toHaveAttribute("aria-expanded", "false");
                    play.click(toggle);
                    final Play grid = play.within(play.getByRole("grid", "Volume by day"));
                    play.expect(grid.findByText("10 Nov")).toBeVisible();
                    play.expect(grid.getByText("600")).toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // A user who may not view data is told why there are no samples, and no alert is shown.
                .story("SamplesNotPermitted", context -> AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                        routes("[" + TRANSLATING + "]", RestReply.error(403, "You may not view data")), null))
                .withPlay(play -> {
                    openTab(play);
                    play.expect(play.findByText(TextMatch.startingWith("Samples aren't shown"))).toBeVisible();
                    play.expect(play.queryByRole("list", "Samples")).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                // When read only, the same figures are shown, as there is nothing to change on the tab.
                .story("ReadOnly", context -> render(context, true, "[" + TRANSLATING + "]"))
                .withPlay(play -> {
                    openTab(play);
                    play.expect(play.within(play.findByRole("list", "Quality figures")).findByText("98%"))
                            .toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // When the recent score is below the Learning Flow's flag threshold, the figure is shown as a
                // warning.
                .story("BelowTheFlag", context -> render(context, false, "[" + BELOW_PAR + "]"))
                .withPlay(play -> {
                    openTab(play);
                    final Query lately = play.within(play.findByRole("list", "Quality figures"))
                            .findByText("42%").closest("[role=listitem]");
                    play.expect(lately).toHaveClass("quality-tab__figure--warning");
                    play.expect(play.within(lately).getByText(TextMatch.containing("so it is flagged")))
                            .toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // An element that hasn't learned anything has nothing to judge, and says so.
                .story("NothingLearned", context -> render(context, false, "[]"))
                .withPlay(play -> {
                    openTab(play);
                    play.expect(play.findByText(TextMatch.startingWith("This element hasn't learned anything yet")))
                            .toBeVisible();
                    play.expect(play.queryByRole("list", "Quality figures")).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                // A pipeline without a Smart Pipeline element has no Quality Assurance tab.
                .story("NoAiTranslator", context -> AiPipelines.render(context, false, AiPipelines.NO_ELEMENT,
                        routes("[]"), null))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(DocEditors.tab(play, "Structure")).toBeInTheDocument());
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/pipeline/v1/fetchPipelineLayers").toSpyMatcher()));
                    play.expect(play.queryByText(TAB, StroomDom.LINK_TAB_LABEL)).not().toBeVisible();
                    DocEditors.expectNoProblems(play);
                });

        registry.component("App/Editors/PipelineStructureLearned", PipelineQualityStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // With the Smart Pipeline element selected, What it learned is folded until asked for. Unfolded,
                // it shows the learned translation and its elements, in the order records pass through them,
                // each with the documents it uses, which can be opened.
                .story("Default", context -> render(context, false, "[" + TRANSLATING + "]"))
                .withPlay(play -> {
                    selectAiElement(play);
                    final Query toggle = play.findByRole("button", WHAT_IT_LEARNED);
                    play.expect(toggle).toHaveAttribute("aria-expanded", "false");
                    play.click(toggle);
                    final Play parts = play.within(play.findByRole("list", "Learned elements"));
                    play.expect(parts.findByText("Parse the firewall log")).toBeVisible();
                    play.expect(parts.getByText("XSLT Filter")).toBeVisible();
                    play.expect(play.getByRole("button", "Firewall pipeline learned")).toBeVisible();
                    play.click(parts.getByRole("button", "Firewall parser"));
                    play.waitFor(() -> play.expect(play.spy(AiPipelines.OPENED)).toHaveBeenCalledWith("tc-1"));
                    DocEditors.expectNoProblems(play);
                })
                // Its link to Learning Status opens that tab at the element.
                .story("OpenLearningStatus", context -> render(context, false, "[" + TRANSLATING + "]"))
                .withPlay(play -> {
                    selectAiElement(play);
                    play.click(play.findByRole("button", WHAT_IT_LEARNED));
                    play.click(play.findByRole("button", "Open its Learning Status"));
                    play.expect(play.findByText("Translating with", ".feed-detail__translation-label"))
                            .toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // An element that hasn't learned anything says so when unfolded.
                .story("NothingLearned", context -> render(context, false, "[]"))
                .withPlay(play -> {
                    selectAiElement(play);
                    play.click(play.findByRole("button", WHAT_IT_LEARNED));
                    play.expect(play.findByText(TextMatch.startingWith("Nothing learned yet"))).toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // When another element is selected, there is nothing learned to show.
                .story("OtherElement", context -> render(context, false, "[" + TRANSLATING + "]"))
                .withPlay(play -> {
                    selectAiElement(play);
                    play.expect(play.findByRole("button", WHAT_IT_LEARNED)).toBeVisible();
                    play.click(play.getByText("Source", ".pipeline-element-box__label")
                            .closest(".pipeline-element-box"));
                    play.waitFor(() -> play.expect(play.queryByRole("button", WHAT_IT_LEARNED)).toBeNull());
                    DocEditors.expectNoProblems(play);
                });
    }

    private static void openTab(final Play play) {
        play.waitFor(() -> play.expect(DocEditors.tab(play, TAB)).toBeInTheDocument());
        play.click(DocEditors.tab(play, TAB));
    }

    // Shows the Structure tab and selects the Smart Pipeline element in its tree.
    private static void selectAiElement(final Play play) {
        play.waitFor(() -> play.expect(DocEditors.tab(play, "Structure")).toBeInTheDocument());
        play.click(DocEditors.tab(play, "Structure"));
        play.click(play.findByText(AI_ELEMENT, ".pipeline-element-box__label").closest(".pipeline-element-box"));
    }

    private static Widget render(final StoryContext context, final boolean readOnly, final String learners) {
        return AiPipelines.render(context, readOnly, AiPipelines.ONE_ELEMENT, routes(learners), null);
    }

    // The routes for the given firewall learners, the firewall's promoted run, its learned translation's
    // layers, and the quality resource's report and samples.
    private static RestFixtures.Builder routes(final String learners) {
        return routes(learners, RestReply.json(SAMPLES));
    }

    private static RestFixtures.Builder routes(final String learners, final RestReply samples) {
        return LearningFlowFixtures.pipelineRoutes(RestFixtures.builder()
                        .route(RequestMatcher.post("/pipeline/v1/fetchPipelineLayers")
                                        .withJsonBodyContaining("{\"uuid\": \"f-2\"}"),
                                RestReply.json(FRAGMENT_LAYERS))
                        .get(QUALITY, RestReply.json(REPORT))
                        .get(QUALITY + "/samples", samples),
                LearningFlowFixtures.DOC, LearningFlowFixtures.FIREWALL_PROMOTED, learners);
    }

    // Builds the quality report: fourteen days with 61 streams and 1,830 records in all, and the kinds of
    // the kept records.
    private static String report() {
        final long[] streams = {0, 2, 4, 5, 3, 0, 0, 6, 7, 5, 4, 20, 3, 2};
        final long[] records = {0, 60, 120, 150, 90, 0, 0, 180, 210, 150, 120, 600, 90, 60};
        final StringBuilder days = new StringBuilder();
        for (int i = 0; i < streams.length; i++) {
            if (i > 0) {
                days.append(", ");
            }
            days.append("{\"dayMs\": ").append(FIRST_DAY + i * 86_400_000L).append(", \"streams\": ")
                    .append(streams[i]).append(", \"records\": ").append(records[i]).append('}');
        }
        return "{\"days\": [" + days + "], \"bindingUuid\": \"b-2\", \"translated\": 12, \"unknown\": 2, "
               + "\"none\": 1, \"kinds\": 3}";
    }
}
