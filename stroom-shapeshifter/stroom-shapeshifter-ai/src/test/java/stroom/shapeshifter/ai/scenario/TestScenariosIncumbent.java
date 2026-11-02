/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 02 §5, scenarios 8 and 9: a bound learner relearning against an incumbent. A candidate that
 * looks better on the learning sample but is worse on the held-out stream is not promoted (A15); one
 * that is worse on a record the binding was accepted on is not promoted either (A18); and one that is better
 * on both replaces the incumbent's fragment under the same binding. Scenario 10, a pinned binding, went with
 * pinning (design 06 A138).
 * <p>
 * The incumbent is the golden transform with an empty device name, which the document weighs, so it
 * scores well but not perfectly and its records still clear the bar. A person marks the learner for
 * relearning, as the Supervisor's serving list would lead them to, and the next stream of it opens the
 * session while the incumbent serves it (A100).
 */
class TestScenariosIncumbent {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEVICE_NAME =
            "          <Name><xsl:value-of select=\"data[@name='where']/@value\"/></Name>";
    private static final String SOURCE_USER = String.join("\n",
            "        <User>",
            "          <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
            "        </User>",
            "      </EventSource>");
    private static final String AUTHENTICATE_USER = String.join("\n",
            "          <User>",
            "            <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
            "          </User>",
            "        </Authenticate>");
    /**
     * The golden transform with an empty device name: schema-valid — the 3.0.0 schema wants a Device — but
     * short of a field the document requires.
     */
    private static final String V1 = CsvLines.replacing(XSLT, DEVICE_NAME, "          <Name/>");
    private static final String DOC = "doc-1";

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                // Below what the flawed candidates score, so that the comparison with the incumbent, not the
                // floor, is what decides.
                .promotionFloor(0.85)
                .relearnThreshold(0.95)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        // Weighed and not a gate: the flawed incumbent's flaw is a lower score, which the
                        // comparison weighs (A15, A18), and not a record short of the bar (design 04 §3),
                        // which would send the stream it was learned on straight back to be learned.
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, false,
                                new ExtractionQualityParameters(false,
                                        List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        // The gate a record is held to (design 04 §3, A66): an interactive event names its
                        // user. The incumbent clears it on every record; a candidate that leaves the user
                        // out falls short of it, on its own stream or on a record the binding was accepted on.
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 0.5, true,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "interactive events name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static Script learning(final Scenarios scenarios, final String xslt) {
        return scenarios.script(FOUR_FIELDS, xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
    }

    /**
     * A transform that names the user — at the source and on the logon — only for records the predicate
     * admits: right on the records it was shown, wrong on the ones it was not.
     */
    private static String userOnlyWhen(final String stylesheet, final String predicate) {
        final String source = CsvLines.replacing(stylesheet, SOURCE_USER, String.join("\n",
                "        <xsl:if test=\"" + predicate + "\">",
                "        <User>",
                "          <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
                "        </User>",
                "        </xsl:if>",
                "      </EventSource>"));
        return CsvLines.replacing(source, AUTHENTICATE_USER, String.join("\n",
                "          <User>",
                "            <Id><xsl:if test=\"" + predicate + "\">"
                + "<xsl:value-of select=\"data[@name='who']/@value\"/></xsl:if></Id>",
                "          </User>",
                "        </Authenticate>"));
    }

    /**
     * Learns v1, serves one stream with it — which marks the learner, v1 scoring below the relearn threshold
     * — and returns the document as it then stands.
     */
    private static StageRun incumbentMarked(final Scenarios scenarios, final String users) {
        final Script script = learning(scenarios, V1);
        final StageRun learned = scenarios.process(script, doc(), stream(1, CsvLines.lines(7, users, 0))).learned();
        script.verifyExhausted();
        assertThat(learned.decision()).isInstanceOf(Promoted.class);
        final double v1Score = ((Promoted) learned.decision()).score();
        assertThat(v1Score).isBetween(0.9, 0.95);

        final Script silent = Script.of();
        final StageRun served = scenarios.process(silent, learned.doc(), stream(2, CsvLines.lines(7, users,
                0))).learned();
        assertThat(served.decision()).isInstanceOf(Bound.class);
        assertThat(silent.asked()).isEmpty();
        // The score is kept for the Supervisor's list and is no longer a trigger (design 04 §6): the mark
        // is a person's, from reading it.
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC,
                "Scored " + v1Score + " below the threshold: improve it");
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isPresent();
        return served;
    }

    @Test
    void scenario8ACandidateBetterOnTheSampleButWorseOnTheStreamIsNotPromoted() {
        final Scenarios scenarios = new Scenarios();
        final StageRun marked = incumbentMarked(scenarios, "user");
        final Binding v1 = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        // v2 names the user for everyone but user6 — the one record in the held-out fifth of a seven-line
        // stream — and, like v1, has no device: it passes every threshold on the six records it is shown
        // and scores below v1 over the seven.
        final String v2 = userOnlyWhen(V1, "not(data[@name='who']/@value = 'user6')");
        final Script relearn = learning(scenarios, v2);
        final StageRun run = scenarios.process(relearn, marked.doc(), stream(3, CsvLines.lines(7, "user",
                0))).learned();
        relearn.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Kept.class);
        final Kept kept = (Kept) run.decision();
        assertThat(kept.incumbent()).isEqualTo(v1);
        assertThat(kept.reason()).contains("against the incumbent's").contains("on this stream");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(v1);
        assertThat(scenarios.stores.pipelines.list()).describedAs("v2's documents were not written").hasSize(1);
        assertThat(scenarios.stores.xslts.list()).hasSize(1);
        assertThat(run.transcript()).describedAs("chain, split, target, parser, transform").hasSize(5);
        assertThat(run.produced().fragment()).describedAs("the incumbent served").isEqualTo(v1.getPipeline());
    }

    @Test
    void scenario8ACandidateWorseOnAnAcceptedRecordIsNotPromoted() {
        // A18: v1 was accepted on a stream of users; the marking stream and the relearning stream are of
        // staff. v2 names the user only for staff, so it beats v1 on the stream it is judged on and would
        // have been promoted on A15 alone — and scores far below v1's recorded score on the accepted stream.
        final Scenarios scenarios = new Scenarios();
        final StageRun marked = incumbentMarked(scenarios, "user");
        final Binding v1 = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(scenarios.regressionSet.accepted(v1.getUuid())).hasSize(6);

        final String v2 = userOnlyWhen(XSLT, "starts-with(data[@name='who']/@value, 'staff')");
        final Script relearn = learning(scenarios, v2);
        final StageRun run = scenarios.process(relearn, marked.doc(), stream(3, CsvLines.lines(7, "staff",
                0))).learned();
        relearn.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Kept.class);
        assertThat(((Kept) run.decision()).reason()).contains("falls short of the bar on a record it was accepted on");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(v1);
        assertThat(scenarios.stores.xslts.list()).hasSize(1);
    }

    @Test
    void scenario9ABetterCandidateReplacesTheIncumbent() {
        final Scenarios scenarios = new Scenarios();
        final StageRun marked = incumbentMarked(scenarios, "user");
        final Binding v1 = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        final Script relearn = learning(scenarios, XSLT);
        final StageRun run = scenarios.process(relearn, marked.doc(), stream(3, CsvLines.lines(7, "user",
                0))).learned();
        relearn.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Rebound.class);
        final Rebound rebound = (Rebound) run.decision();
        assertThat(rebound.incumbent()).isEqualTo(v1);
        assertThat(rebound.score()).isEqualTo(1.0);
        final Binding v2 = rebound.binding();
        assertThat(v2.getUuid()).isEqualTo(v1.getUuid());
        assertThat(v2.getPipeline())
                .describedAs("the same fragment, edited in place (A58)")
                .isEqualTo(v1.getPipeline());
        assertThat(v2.getSnapshot()).describedAs("under a new snapshot (A61)").isNotEqualTo(v1.getSnapshot());
        assertThat(v2.getScore()).isEqualTo(1.0);
        assertThat(v2.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(v2);
        // One fragment and one stylesheet per learner, growing as the session learns (A58): what ran before
        // is the snapshot's to keep, not a sibling document's.
        assertThat(scenarios.stores.pipelines.list()).hasSize(1);
        assertThat(scenarios.stores.xslts.list()).hasSize(1);
        assertThat(scenarios.snapshots.forBinding(v1.getUuid())).hasSize(2);
        assertThat(scenarios.regressionSet.accepted(v1.getUuid())).hasSizeGreaterThan(6);
    }
}
