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

package stroom.learningflow.scenario;

import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.XPathAssertion;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

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

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .relearnThreshold(0.95)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.ADVICE, 0.7,
                                new ExtractionQualityParameters(false,
                                                List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        new CheckSetting(Check.RULES, CheckState.GATE, 0.5,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                                "interactive events name the user",
                                                "not(EventDetail/Authenticate) "
                                                + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                                true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
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
        assertThat(v1Score).describedAs("the flawed incumbent").isLessThan(1.0);

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
        // stream — and, like v1, has no device: it passes every threshold on the six records it is shown.
        // It is not compared with v1's score (the one model of checks, D2); over the seven records it is
        // held to the bar it would be served to, and user6's record falls short of it.
        final String v2 = userOnlyWhen(V1, "not(data[@name='who']/@value = 'user6')");
        final Script relearn = learning(scenarios, v2);
        final StageRun run = scenarios.process(relearn, marked.doc(), stream(3, CsvLines.lines(7, "user",
                0))).learned();
        relearn.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Kept.class);
        final Kept kept = (Kept) run.decision();
        assertThat(kept.incumbent()).isEqualTo(v1);
        assertThat(kept.reason()).isEqualTo("Candidate failed the gates that judge records on 1 record of the stream");
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
