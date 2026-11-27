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
import stroom.learningflow.learning.Question;
import stroom.learningflow.scoring.Judgement;
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
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the scorers that judge whether a translation means something, and how their feedback steers the
/// model.
///
/// Coverage points at the lines a splitter silently dropped. Schema conformance passes a transform whose
/// events validate but extract nothing, and extraction quality refuses it. A failing business rule is named
/// in the feedback. A candidate that never clears its threshold is parked for a person once its candidates,
/// and those of the exhaustion step, are spent. A check that is only advice refuses nothing, so a candidate
/// that falls short of advice alone is promoted, while one that passes a gate on the sample but fails it
/// over the whole stream is given up.
class TestScenariosScoring {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String EVEN_USERS_ONLY = Scenarios.resource("csv-fields-even-users.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    /// What a translation writes in place of an event for a record that is deliberately not one. A transform
    /// that filters records out has to declare it, or completeness counts the records it dropped as lost.
    private static final String SKIP =
            "    <Skipped xmlns=\"learningflow:skips\" reason=\"not an event\"/>\n";
    private static final String DEGENERATE = Scenarios.resource("csv-degenerate.xsl");
    private static final String DOC = "doc-1";
    private static final String AUTHENTICATE_USER_ID = String.join("\n",
            "            <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
            "          </User>",
            "        </Authenticate>");
    private static final String DEVICE = String.join("\n",
            "        <Device>",
            "          <Name><xsl:value-of select=\"data[@name='where']/@value\"/></Name>",
            "        </Device>");
    private static final String DOOR = String.join("\n",
            "        <Door>",
            "          <Name><xsl:value-of select=\"data[@name='where']/@value\"/></Name>",
            "        </Door>");
    private static final String AUTHENTICATE = String.join("\n",
            "        <Authenticate>",
            "          <Action>",
            "            <xsl:choose>",
            "              <xsl:when test=\"data[@name='what']/@value = 'logon'\">Logon</xsl:when>",
            "              <xsl:otherwise>Logoff</xsl:otherwise>",
            "            </xsl:choose>",
            "          </Action>",
            "          <User>",
            "            <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
            "          </User>",
            "        </Authenticate>");
    private static final String ALERT = String.join("\n",
            "        <Alert>",
            "          <Description><xsl:value-of select=\"data[@name='what']/@value\"/></Description>",
            "        </Alert>");
    private static final XPathAssertion NAMES_THE_USER = new XPathAssertion(
            "interactive events name the user",
            "not(EventDetail/Authenticate) or EventDetail/Authenticate/User/Id[normalize-space(.) != '']");

    /// Builds a document with the full set of scorers: compile, coverage and yield; schema conformance as a
    /// gate; extraction quality as a gate requiring the user; and a business rule.
    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.GATE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.GATE, 1.0,
                                new BusinessRulesParameters(List.of(NAMES_THE_USER), true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static List<String> feedback(final Question question) {
        return question.feedback().stream().map(StoredError::getMessage).toList();
    }

    @Test
    void scenario4DiscardedInputIsPointedAtThenCoverageIsFixed() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback())
                .reply(Scenarios.fenced(EVEN_USERS_ONLY))
                .expect(QuestionMatcher.configuration("DSParser")
                        // Scored over the learning prefix (five of the six lines), not the whole stream.
                        .withFeedbackMentioning("Input coverage scored 0.6 against a threshold of 0.9")
                        .withFeedbackMentioning("The split consumed 3 of 5 lines")
                        .withFeedbackMentioning("Lines not consumed: 2, 4")
                        .withPreviousConfiguration(EVEN_USERS_ONLY))
                .reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(script.scripted()).hasSize(4);
        // The second splitter consumed everything, so the split's verdict passes and the stream scores 1.0.
        assertThat(run.verdicts().get(0).judgements().stream().allMatch(Judgement::metThreshold)).isTrue();
        assertThat(((Promoted) run.decision()).score()).isEqualTo(1.0);
    }

    @Test
    void scenario5TheDegenerateTransformValidatesAndIsRefused() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(DEGENERATE))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Extraction quality scored")
                        .withFeedbackMentioning("(a gate)")
                        .withFeedbackMentioning("Typed-element ratio")
                        .withFeedbackMentioning("records name Unknown in EventDetail")
                        .withFeedbackMentioning("Required field EventSource/User/Id is present in 0 of 5 records"))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The degenerate candidate passed the schema gate, so none of its feedback came from conformance.
        final Question reAsk = script.scripted().get(3);
        assertThat(feedback(reAsk)).noneMatch(message -> message.startsWith("Schema conformance scored"));
        assertThat(feedback(reAsk)).noneMatch(message -> message.startsWith("Record "));
        // The promoted transform scores 1.0 on every check of meaning over the whole stream.
        assertThat(run.verdicts().get(1).judgements())
                .extracting(judgement -> judgement.setting().getCheck())
                .containsExactly(Check.COMPILE, Check.COMPLETENESS, Check.YIELD, Check.CONFORMANCE,
                        Check.QUALITY, Check.RULES);
        assertThat(run.verdicts().get(1).judgements())
                .extracting(judgement -> judgement.score().value())
                .containsOnly(1.0);
    }

    @Test
    void schemaFeedbackCarriesTheContentModelOfWhatFellShort() {
        // A Door with only a Name, then an Alert with only a Description. The schema validator names only one
        // missing child; the feedback adds the parent's full content model, and the parent the validator did
        // not name.
        final Scenarios scenarios = new Scenarios();
        final String doorAndAlert = CsvLines.replacing(CsvLines.replacing(XSLT, DEVICE, DOOR), AUTHENTICATE, ALERT);
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(doorAndAlert))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("The content of element 'Door' is not complete")
                        .withFeedbackMentioning("The schema says: Door (in EventSource) contains, in order: "
                                                + "Name, Description?, Location {")
                        .withFeedbackMentioning("SingleEntry, RemoveAll, AddAccess { AccessZone+ }"))
                .reply(Scenarios.fenced(CsvLines.replacing(XSLT, AUTHENTICATE, ALERT)))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Invalid content was found starting with element '{Description}'")
                        .withFeedbackMentioning("The schema says: Alert contains, in order: Type [Vulnerability | "
                                                + "IDS | Malware | Network | Change | Error | Other], Severity?"))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
    }

    @Test
    void anEventOutsideTheSchemaNamespaceIsToldWhereItBelongs() {
        // A transform that declares event-logging:3 only on the literal Events element writes every Event in
        // no namespace. The validator then says only that Event was found where Event is expected, so the
        // feedback explains the namespace problem.
        final Scenarios scenarios = new Scenarios();
        final String unqualified = XSLT.replace("xmlns=\"event-logging:3\"", "xmlns:evt=\"event-logging:3\"")
                .replace("<Events ", "<Events xmlns=\"event-logging:3\" ");
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(unqualified))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Event was found where Event is expected, so it is in the wrong "
                                                + "namespace or none: declare xmlns=\"event-logging:3\" on the "
                                                + "xsl:stylesheet element"))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
    }

    @Test
    void scenario6ABrokenBusinessRuleNamesItself() {
        final Scenarios scenarios = new Scenarios();
        // Schema-valid (the 3.0.0 schema needs a User under Authenticate), but the user it names is empty.
        final String noUserOnLogon = CsvLines.replacing(XSLT, AUTHENTICATE_USER_ID,
                "            <Id/>\n          </User>\n        </Authenticate>");
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(noUserOnLogon))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Business rules scored 0.0 against a threshold of 1.0")
                        .withFeedbackMentioning("Rule 'interactive events name the user'")
                        .withFeedbackMentioning("fails for 5 of 5 records"))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The flawed transform still validated and still extracted the user at the source, so only the rule
        // gave feedback.
        assertThat(feedback(script.scripted().get(3))).noneMatch(message -> message.startsWith("Schema conformance"));
        assertThat(feedback(script.scripted().get(3))).noneMatch(message -> message.startsWith("Extraction quality"));
    }

    @Test
    void scenario7ACandidateThatNeverClearsItsThresholdIsAbandonedAtTheLimit() {
        final Scenarios scenarios = new Scenarios();
        // Three candidates, set explicitly because the document's default is five.
        final LearningFlowDoc doc = doc().copy()
                .maxAttempts(3)
                .checks(doc().getChecks().stream()
                        .map(setting -> setting.getCheck() == Check.YIELD
                                ? new CheckSetting(Check.YIELD, CheckState.GATE, 0.75,
                                        new YieldParameters(1.0, YieldBasis.RECORDS))
                                : setting)
                        .toList())
                .build();
        final String everyOther = CsvLines.replacing(XSLT, "  <xsl:template match=\"record\">",
                "  <xsl:template match=\"record[number(substring(data[@name='who']/@value, 5)) mod 2 = 1]\"/>\n\n"
                + "  <xsl:template match=\"record\">");
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(everyOther))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(everyOther))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(everyOther))
                // With the candidates spent, the flow's exhaustion step asks for the transform as many times
                // again, and a stylesheet that still drops every other record fails there too.
                .expect(QuestionMatcher.admission("XSLTFilter").withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(everyOther))
                .expect(QuestionMatcher.admission("XSLTFilter").withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(everyOther))
                .expect(QuestionMatcher.admission("XSLTFilter").withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(everyOther));

        final StageRun run = scenarios.process(script, doc, stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(script.scripted()).hasSize(8);
        // With its candidates spent, the session is parked for a person rather than giving the learner up.
        assertThat(run.decision()).isInstanceOf(Parked.class);
        assertThat(((Parked) run.decision()).reason())
                .isEqualTo("Couldn't translate what's left as marked Unknowns: all 3 answers fell short.");
        assertThat(((Parked) run.decision()).report().getDetails()).first().isEqualTo("It was serving XSLTFilter.");
        assertThat(feedback(script.scripted().get(4)))
                .anyMatch(message -> message.contains("against an expected 1.0"));
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();
        assertThat(scenarios.stores.pipelines.list()).isEmpty();
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isEmpty();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER,
                1L)).describedAs("the stream waits on the parked session").isPresent();

        // The model is not asked about the learner again while a person decides: the next stream waits on the
        // same session.
        final Script silent = Script.of();
        final StageRun again = scenarios.process(silent, run.doc(), stream(2, CsvLines.lines(6))).learned();
        assertThat(again.decision()).isInstanceOf(Deferred.class);
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(1);
    }

    @Test
    void scenario7ACandidateThatFallsShortOnlyOfAdviceIsPromoted() {
        // Yield is advice in this document. A transform that drops every other record falls short of it, and
        // is still promoted, since advice never refuses a candidate. Its lower score reflects the shortfall.
        final Scenarios scenarios = new Scenarios();
        // The records it drops are declared as skipped, so completeness is satisfied and yield is the only
        // check that falls short.
        final String everyOther = CsvLines.replacing(XSLT, "  <xsl:template match=\"record\">",
                "  <xsl:template match=\"record[number(substring(data[@name='who']/@value, 5)) mod 2 = 1]\">\n"
                + SKIP + "  </xsl:template>\n\n"
                + "  <xsl:template match=\"record\">");
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(everyOther));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(((Promoted) run.decision()).score()).isLessThan(1.0);
    }

    @Test
    void scenario7ACandidateThatPassesAGateOnTheSampleButFailsItOverTheStreamIsGivenUp() {
        // Yield is a gate at 0.9. The model learns from the first five records; the sixth is held out. A
        // transform that drops only the sixth yields fully on the sample and five in six over the stream.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc().copy()
                .checks(doc().getChecks().stream()
                        .map(setting -> setting.getCheck() == Check.YIELD
                                ? new CheckSetting(Check.YIELD, CheckState.GATE, 0.9,
                                        new YieldParameters(1.0, YieldBasis.RECORDS))
                                : setting)
                        .toList())
                .build();
        // The record is declared as skipped rather than dropped, so that yield is the only check that fails.
        final String dropsTheLast = CsvLines.replacing(XSLT, "  <xsl:template match=\"record\">",
                "  <xsl:template match=\"record[data[@name='who']/@value = 'user5']\">\n"
                + SKIP + "  </xsl:template>\n\n"
                + "  <xsl:template match=\"record\">");
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(dropsTheLast));

        final StageRun run = scenarios.process(script, doc, stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(script.scripted()).hasSize(3);
        assertThat(run.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) run.decision()).reason()).isEqualTo("A gate failed");
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER).orElseThrow())
                .contains("Candidate failed the gate Yield");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();
        assertThat(scenarios.stores.pipelines.list()).describedAs("nothing written").isEmpty();
        // The worker gave up on a stream it read back from the store, so it does not error the stream itself.
        // Instead the stream's task is woken to error it, as for a give-up named by the flow.
        assertThat(scenarios.wake.pipelines()).containsExactly(Scenarios.LEARNER.pipelineUuid());
        final StageRun woken = scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER,
                stream(1, CsvLines.lines(6)));
        assertThat(woken.decision()).isInstanceOf(Sentinel.class);
    }
}
