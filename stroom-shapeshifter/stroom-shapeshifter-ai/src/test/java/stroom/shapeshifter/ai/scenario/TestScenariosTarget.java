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

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.learning.TargetChecks;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RecordBoundary;
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
 * Design 02 §5, scenarios 34–37 (ruling A31): a stage learned against a target. The model proposes the
 * events before any configuration is written and is held to them: a target that says nothing is refused
 * where it is proposed; a splitter that keeps half the record is caught at the splitter; a transform that
 * puts a value in the wrong place is caught against the target; and the record boundary is settled first,
 * for whole records, before any target is asked.
 */
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
    /**
     * The degeneracy trap as a target: validates, extracts nothing.
     */
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

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
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

    @Test
    void scenario34ATargetThatSaysNothingIsRefusedWhereItIsProposed() {
        final Scenarios scenarios = new Scenarios();
        // The structure answers the second target and the split; the first target is scripted: the trap.
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.target().withoutFeedback()).reply(Scenarios.fenced(DEGENERATE_EVENT))
                .expect(QuestionMatcher.configuration("DSParser").withTargets(1)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // Chain, split, target, target again, parser, transform: the refused target was re-asked with the
        // shortfall of the document the model itself wrote, before any configuration existed.
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
        // The targets went with the promotion, as the regression set's goldens.
        final Binding binding = ((Promoted) run.decision()).binding();
        assertThat(scenarios.regressionSet.accepted(binding.getUuid()))
                .describedAs("each entry carries what the flow said its kind of record should become")
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.target()).isNotNull());
    }

    @Test
    void aNoneForAKindSeenMoreThanOnceIsQuestionedBeforeItIsTaken() {
        final Scenarios scenarios = new Scenarios();
        // Design 02 §6.3: a live model answered none for the alarm kind. Six records of one kind: the first
        // none is questioned; the structure then answers with the event, and the whole stream is learned.
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
        // With one candidate: the questioning of the first none is not a candidate spent.
        final ShapeshifterAiDoc doc = doc().copy()
                .flow(LearningFlow.of(FlowExample.TARGET_FIRST).withSteps(List.of(
                        FlowStep.parse("CHAIN"), FlowStep.parse("SPLIT when text"),
                        FlowStep.parse("TARGET candidates 1"), FlowStep.parse("CONFIGURE"))))
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
        // A target from the raw sample, no split: the target's record is a line of the sample, and the split
        // question is never asked. One kind, so one target.
        final ShapeshifterAiDoc doc = doc().copy()
                .flow(LearningFlow.of(FlowExample.TARGET_FIRST).withSteps(List.of(
                        FlowStep.parse("CHAIN"), FlowStep.parse("TARGET kinds 1"),
                        FlowStep.parse("CONFIGURE"))))
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
        // The store refuses this on save; a document that reaches the stage with it anyway asks nothing.
        final ShapeshifterAiDoc doc = doc().copy()
                .flow(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.parse("CONFIGURE"), FlowStep.parse("CHAIN"))))
                .build();
        final Script script = Script.of();

        final StageRun run = scenarios.process(script, doc, stream(1, CsvLines.lines(6))).learned();

        assertThat(script.asked()).isEmpty();
        assertThat(run.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) run.decision()).reason())
                .contains("The document's flow cannot be held (see its Learning tab)")
                .contains("The first step must be CHAIN");
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
        // Coverage was full — every line consumed — and the transform was not asked until the records
        // carried what the target needs.
        final Configuration reAsk = (Configuration) script.scripted().get(2);
        assertThat(reAsk.feedback()).extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Input coverage"));
        assertThat(script.scripted()).hasSize(4);
    }

    @Test
    void scenario36TheTransformMustReproduceTheTarget() {
        final Scenarios scenarios = new Scenarios();
        // Valid, extracts every field, names the user — and puts the place where the user goes and the
        // user where the place goes. Every scorer of meaning passes it; only the target says no.
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
        // Blocks of several lines between ---- lines. The scorers here are about structure alone.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy()
                .minRecordsToLearn(2)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                // A split that keeps only the first line of each block consumed the block — coverage is full —
                // and emitted a fraction of it as record text; that is what refuses it, before any target.
                .expect(QuestionMatcher.split().withoutFeedback()).reply(Scenarios.fenced(BLOCK_SPLIT
                        .replace("<regex pattern=\"^(?s)\\s*(.+?)\\s*$\">",
                                "<regex pattern=\"^\\s*([^\\n]+)\" maxMatch=\"1\">")
                        .replace("      </regex>\n", "      </regex>\n      <all/>\n")))
                .expect(QuestionMatcher.split().withFeedbackMentioning("characters as record text"))
                .reply(Scenarios.fenced(BLOCK_SPLIT))
                // Three kinds of block by their first line, the most the conversation asks about; none becomes an
                // event here, since this scenario is about the boundary.
                .expect(QuestionMatcher.target()).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.target()).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.target()).reply(TargetChecks.NONE)
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(MULTI_LINE.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(ONE_EVENT_PER_RECORD));

        final StageRun run = scenarios.process(script, doc, new Input(1, "AUDIT", "Raw Events", Map.of(),
                MULTI_LINE.input())).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The targets were asked for whole records — blocks of one and of five lines — not lines, and
        // after the boundary was settled.
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

    /**
     * The nth block of the multi-line corpus case, trimmed, as the block split yields it.
     */
    @Test
    void scenario37XmlVariantTheSplitNamesTheElementThatIsOneRecord() {
        // A35: input already in XML, the records two levels down. The scorers are about structure and the
        // schema; yield by records would count the root's one child, so it is not asked here (design 01 §10.1).
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy()
                .minRecordsToLearn(2)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS"))))
                .build();
        final Script script = scenarios.xmlScript("entry", NESTED_XSL)
                .expect(QuestionMatcher.chain()).reply("XSLTFilter")
                // The container of the records is not a record: one occurrence for three records.
                .expect(QuestionMatcher.split().withoutFeedback()).reply("entries")
                .expect(QuestionMatcher.split().withFeedbackMentioning("a container of records is not a record"))
                .reply("<entry>")
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(NESTED_XSL));

        final StageRun run = scenarios.process(script, doc,
                new Input(1, "DOCVAULT", "Raw Events", Map.of("Format", "XML"), NESTED_XML)).learned();

        script.verifyExhausted();
        // The conversation learned the whole stream; the record element the split settled reaches the stage's
        // count (A35) — three entries, not the root's one <entries> — so the stream meets the two records the
        // document asks and is promoted, and the binding carries the element for the streams it will serve.
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
