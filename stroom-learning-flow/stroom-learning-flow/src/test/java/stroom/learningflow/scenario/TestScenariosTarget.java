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

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.learning.TargetChecks;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.XPathAssertion;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests learning against targets, where the model proposes the events each kind of record should become
/// before any configuration is written, and is then held to them.
///
/// A target that extracts nothing is refused where it is proposed. A splitter that keeps only half the
/// record is caught at the splitter, and a transform that puts a value in the wrong place is caught against
/// the target. The record boundary is settled first, so that targets are asked for whole records.
class TestScenariosTarget {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String TWO_FIELDS = Scenarios.resource("csv-two-fields.ds3.xml");
    private static final String BLOCK_SPLIT = Scenarios.resource("block-split.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String ONE_EVENT_PER_RECORD = Scenarios.resource("one-event-per-record.xsl");
    private static final String NESTED_XML = Scenarios.resource("nested-entries.xml");
    private static final String NESTED_XSL = Scenarios.resource("nested-entries.xsl");
    private static final Golden MULTI_LINE = Scenarios.corpus("003_multiline_regex");
    private static final String DOC = "doc-1";
    /// A target event that validates but extracts nothing, putting every field under `Unknown`.
    private static final String DEGENERATE_EVENT = String.join("\n",
            "<Event xmlns=\"event-logging:3\">",
            "  <EventTime><TimeCreated>2020-06-17T08:00:00.000Z</TimeCreated></EventTime>",
            "  <EventSource>",
            "    <System><Name>Door Access</Name><Environment>Test</Environment></System>",
            "    <Generator>CSV</Generator>",
            "    <Device><Name>unknown</Name></Device>",
            "  </EventSource>",
            "  <EventDetail>",
            "    <TypeId>record</TypeId>",
            "    <Unknown>",
            "      <Data Name=\"who\" Value=\"user0\"/><Data Name=\"where\" Value=\"office\"/>",
            "      <Data Name=\"what\" Value=\"logon\"/>",
            "    </Unknown>",
            "  </EventDetail>",
            "</Event>");
    private static final String DEVICE_NAME =
            "          <Name><xsl:value-of select=\"data[@name='where']/@value\"/></Name>";
    private static final String SOURCE_USER_ID = String.join("\n",
            "        <User>",
            "          <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>");

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
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.ADVICE, 1.0,
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

    @Test
    void scenario34ATargetThatSaysNothingIsRefusedWhereItIsProposed() {
        final Scenarios scenarios = new Scenarios();
        // The script answers the split and the second target automatically. The first target is scripted
        // as the event that extracts nothing.
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.target().withoutFeedback()).reply(Scenarios.fenced(DEGENERATE_EVENT))
                .expect(QuestionMatcher.configuration("DSParser").withTargets(1)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // Chain, split, target, target again, parser, transform: the refused target was asked for again with
        // feedback on the shortfalls of the event the model itself wrote, before any configuration existed.
        final List<Question> asked = script.asked();
        assertThat(asked).hasSize(6);
        assertThat(asked.get(1)).isInstanceOf(Split.class);
        assertThat(asked.get(2)).isInstanceOf(TargetFor.class);
        final TargetFor reAsk = (TargetFor) asked.get(3);
        assertThat(reAsk.record()).isEqualTo(CsvLines.lines(1).strip());
        assertThat(reAsk.feedback()).extracting(error -> error.getMessage())
                .anyMatch(message -> message.startsWith("Extraction quality scored"))
                .anyMatch(message -> message.contains("records name Unknown in EventDetail"))
                .anyMatch(message -> message.contains("Required field EventSource/User/Id is present in 0 of 1"));
        // The targets are kept with the promotion, as the expected events in the regression corpus.
        final Binding binding = ((Promoted) run.decision()).binding();
        assertThat(scenarios.regressionSet.accepted(binding.getUuid()))
                .describedAs("each entry carries what the flow said its kind of record should become")
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.target()).isNotNull());
    }

    @Test
    void aNoneForAKindSeenMoreThanOnceIsQuestionedBeforeItIsTaken() {
        final Scenarios scenarios = new Scenarios();
        // A model may answer "none" (no event) for a kind of record that should become one. With six records
        // of one kind, the first "none" is questioned; the script then answers with the event, and the whole
        // stream is learned.
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.target().withoutFeedback()).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.configuration("DSParser").withTargets(1)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        final List<Question> asked = script.asked();
        assertThat(asked).hasSize(6);
        final TargetFor questioned = (TargetFor) asked.get(3);
        assertThat(questioned.feedback()).extracting(error -> error.getMessage())
                .anyMatch(message -> message.startsWith("This kind of record is seen 5 times in the sample"));
        final Binding binding = ((Promoted) run.decision()).binding();
        assertThat(scenarios.regressionSet.accepted(binding.getUuid()))
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.target()).isNotNull());
    }

    @Test
    void aSecondNoneIsTaken() {
        final Scenarios scenarios = new Scenarios();
        // With only one candidate, this shows that questioning the first "none" does not spend a candidate.
        final LearningFlowDoc doc = doc().copy()
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST).withSteps(List.of(
                        FlowStep.of(QuestionKind.CHAIN), FlowStep.of(QuestionKind.SPLIT, StepGuard.TEXT),
                        FlowStep.builder(QuestionKind.TARGET)
                                .candidates(1)
                                .build(), FlowStep.of(QuestionKind.CONFIGURE))))
                .build();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.target().withoutFeedback()).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.target().withFeedbackMentioning("reply none again")).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.configuration("DSParser").withTargets(1)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc, stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        final Binding binding = ((Promoted) run.decision()).binding();
        assertThat(scenarios.regressionSet.accepted(binding.getUuid()))
                .describedAs("a kind of record that yields no event has no target event to carry")
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.target()).isNull());
    }

    @Test
    void scenario39TheFlowIsTheDocuments() {
        final Scenarios scenarios = new Scenarios();
        // The document's flow asks for a target from the raw sample with no split step, so the target's record
        // is a line of the sample and the split question is never asked. There is one kind, so one target.
        final LearningFlowDoc doc = doc().copy()
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST).withSteps(List.of(
                        FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.TARGET).kinds(1).build(),
                        FlowStep.of(QuestionKind.CONFIGURE))))
                .build();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withTargets(1)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc, stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        final List<Question> asked = script.asked();
        assertThat(asked).hasSize(4);
        assertThat(asked).noneMatch(Split.class::isInstance);
        assertThat(((TargetFor) asked.get(1)).record()).isEqualTo(CsvLines.lines(1).strip());
        assertThat(((TargetFor) asked.get(1)).total()).isEqualTo(1);
    }

    @Test
    void scenario39AFlowTheStageCannotHoldIsAbandonedBeforeTheModelIsAsked() {
        final Scenarios scenarios = new Scenarios();
        // The document store refuses this flow on save; a document that reaches the stage with it anyway asks
        // the model nothing.
        final LearningFlowDoc doc = doc().copy()
                .flow(FlowExamples.flow(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.of(QuestionKind.CONFIGURE), FlowStep.of(QuestionKind.CHAIN))))
                .build();
        final Script script = Script.of();

        final StageRun run = scenarios.process(script, doc, stream(1, CsvLines.lines(6))).learned();

        assertThat(script.asked()).isEmpty();
        assertThat(run.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) run.decision()).reason())
                .isEqualTo("The learning steps have a problem, so learning can't start.");
        assertThat(((GivenUp) run.decision()).report().getDetails())
                .contains("The first step must be 'Choose the elements'.");
    }

    @Test
    void scenario35AFieldLostAtExtractionIsCaughtThere() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("the records carry no value for [logon, office]")
                        .withFeedbackMentioning("Extract every field the event uses"))
                .reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // Coverage was full (every line was consumed), and the transform was not asked for until the records
        // carried what the target needs.
        final Configuration reAsk = (Configuration) script.scripted().get(2);
        assertThat(reAsk.feedback()).extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Input coverage"));
        assertThat(script.scripted()).hasSize(4);
    }

    @Test
    void scenario36TheTransformMustReproduceTheTarget() {
        final Scenarios scenarios = new Scenarios();
        // Valid, extracts every field and names the user, but puts the place where the user goes and the user
        // where the place goes. Every scorer of meaning passes it; only the comparison with the target fails.
        final String swapped = CsvLines.replacing(CsvLines.replacing(XSLT, DEVICE_NAME,
                        "          <Name><xsl:value-of select=\"data[@name='who']/@value\"/></Name>"),
                SOURCE_USER_ID, String.join("\n",
                        "        <User>",
                        "          <Id><xsl:value-of select=\"data[@name='where']/@value\"/></Id>"));
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback()).reply(Scenarios.fenced(swapped))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("no event produced equals the target for the record")
                        .withFeedbackMentioning("Produce exactly:"))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        final Configuration reAsk = (Configuration) script.scripted().get(3);
        assertThat(reAsk.feedback()).extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Schema conformance"))
                .noneMatch(message -> message.startsWith("Extraction quality"))
                .noneMatch(message -> message.startsWith("Business rules"));
    }

    @Test
    void scenario37RecordBoundariesFirst() {
        // Blocks of several lines between ---- lines. The scorers here are about structure only.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc().copy()
                .minRecordsToLearn(2)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                // A split that keeps only the first line of each block consumes the whole block, so coverage is
                // full, but emits only a fraction of it as record text. That is what refuses it, before any
                // target is asked for.
                .expect(QuestionMatcher.split().withoutFeedback()).reply(Scenarios.fenced(BLOCK_SPLIT
                        .replace("<regex pattern=\"^(?s)\\s*(.+?)\\s*$\">",
                                "<regex pattern=\"^\\s*([^\\n]+)\" maxMatch=\"1\">")
                        .replace("      </regex>\n", "      </regex>\n      <all/>\n")))
                .expect(QuestionMatcher.split().withFeedbackMentioning("characters as record text"))
                .reply(Scenarios.fenced(BLOCK_SPLIT))
                // Three kinds of block by their first line, the most the session asks about. None becomes an
                // event here, since this test is about the boundary.
                .expect(QuestionMatcher.target()).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.target()).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.target()).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(MULTI_LINE.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(ONE_EVENT_PER_RECORD));

        final StageRun run = scenarios.process(script, doc, new Input(1, "AUDIT", "Raw Events", Map.of(),
                MULTI_LINE.input())).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The targets were asked for whole records (blocks of one and of five lines), not single lines, and
        // only after the boundary was settled.
        final List<Question> asked = script.asked();
        assertThat(asked.get(1)).isInstanceOf(Split.class);
        assertThat(asked.get(2)).isInstanceOf(Split.class);
        final List<TargetFor> targets = asked.stream()
                .filter(TargetFor.class::isInstance)
                .map(TargetFor.class::cast)
                .toList();
        assertThat(targets).hasSize(TargetChecks.REPRESENTATIVES);
        assertThat(targets.get(0).record()).isEqualTo(block(1));
        assertThat(targets).anyMatch(target -> target.record().contains("\n"));
        assertThat(targets).allMatch(target -> !target.record().contains("----"));
    }

    @Test
    void scenario37XmlVariantTheSplitNamesTheElementThatIsOneRecord() {
        // The input is already XML, with the records two levels down. The scorers are about structure and the
        // schema; yield is switched off.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc().copy()
                .minRecordsToLearn(2)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        CheckSetting.of(Check.YIELD, CheckState.OFF),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
        final Script script = scenarios.xmlScript("entry", NESTED_XSL)
                .expect(QuestionMatcher.chain()).reply("XSLTFilter")
                // The container of the records is not a record: it occurs once, for three records.
                .expect(QuestionMatcher.split().withoutFeedback()).reply("entries")
                .expect(QuestionMatcher.split().withFeedbackMentioning("a container of records is not a record"))
                .reply("<entry>")
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(NESTED_XSL));

        final StageRun run = scenarios.process(script, doc,
                new Input(1, "DOCVAULT", "Raw Events", Map.of("Format", "XML"), NESTED_XML)).learned();

        script.verifyExhausted();
        // The stage counts records by the element the split settled: three entries, not the one <entries>
        // container. So the stream meets the document's minimum of two records and is promoted, and the
        // binding keeps the element as its record boundary for the streams it will serve.
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(((Promoted) run.decision()).score()).isEqualTo(1.0);
        assertThat(((Promoted) run.decision()).binding().getRecordBoundary())
                .isEqualTo(RecordBoundary.ofElement("entry"));
        final List<Question> asked = script.asked();
        // Chain, split, split again, one target (all three entries are one kind), the transform.
        assertThat(asked).hasSize(5);
        final Split split = (Split) asked.get(1);
        assertThat(split.documentType()).isNull();
        assertThat(((TargetFor) asked.get(3)).record()).contains("<entry id=\"e1\">");
        final Configuration transform = (Configuration) asked.get(4);
        assertThat(transform.split().element()).isEqualTo("entry");
        assertThat(transform.targets().get(0).event()).isPresent();
    }

    /// Returns the nth block of the multi-line corpus case, trimmed, as the block split yields it.
    private static String block(final int n) {
        final String[] blocks = MULTI_LINE.input().split("----\n");
        int seen = 0;
        for (final String candidate : blocks) {
            if (!candidate.isBlank() && ++seen == n) {
                return candidate.strip();
            }
        }
        throw new IllegalArgumentException("No block " + n);
    }
}
