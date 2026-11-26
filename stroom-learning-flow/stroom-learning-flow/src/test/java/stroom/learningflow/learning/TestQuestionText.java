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

package stroom.learningflow.learning;

import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.NodeFixture;
import stroom.learningflow.learning.Question.Chain;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.scoring.Records;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.Template;
import stroom.learningflow.shared.XPathAssertion;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the text of each question put to the model, checking that each carries what the model needs to
/// answer it: the sample, the allowed elements, the reply format, earlier shortfalls, and what the document's
/// checks require.
class TestQuestionText {

    private static final Sample SAMPLE = new Sample("a,b,c\n", Map.of("Feed", "DOOR-ACCESS", "Format", "CSV"));
    private static final QuestionText WORDS = QuestionText.builtIn(null);

    @Test
    void theChainQuestionCarriesKeyValuesElementsSampleAndGrammar() {
        final String text = WORDS.render(new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of()));

        assertThat(text)
                .contains("- Feed: DOOR-ACCESS")
                .contains("- Format: CSV")
                .contains("- DSParser: parses raw text")
                .contains("- XSLTFilter: transforms XML records")
                .contains("a,b,c")
                .contains("joined by ->")
                .doesNotContain("What fell short");
    }

    @Test
    void aReAskCarriesThePreviousConfigurationAndWhatFellShort() {
        final StoredError shortfall = new StoredError(Severity.ERROR, null, new ElementId("Scorecard"),
                "Input coverage scored 0.6 against a threshold of 0.9");
        final String text = WORDS.render(new Configuration("DSParser", "TextConverter", SAMPLE, "a,b,c\n",
                "<dataSplitter/>", null, List.of(), false,
                        Question.Records.UNKNOWN, List.of(), false, List.of(shortfall)));

        assertThat(text)
                .contains("Write the TextConverter document for the DSParser element")
                .contains("schemaLocation is mandatory")
                .contains("Do not use ignoreErrors")
                .contains("Your previous configuration was:")
                .contains("<dataSplitter/>")
                .contains("What fell short:")
                .contains("Input coverage scored 0.6 against a threshold of 0.9")
                .contains("single fenced XML code block");
    }

    @Test
    void aTransformShownOneRecordIsToldThatIsAllItWillEverGet() {
        // The fragment gives this element one record at a time, so the question asks for a configuration that
        // handles one record and says so, because a stylesheet written for the whole stream would be written
        // for input it will never receive.
        final String text = WORDS.render(new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"><record/></records>", null,
                Boundary.ofArray("events", 3), List.of(), true, new Question.Records(12, 2), List.of(), false,
                        List.of()));

        assertThat(text)
                .contains("The input below is **one record**")
                .contains("run once for each record")
                .contains("do not look outside it")
                .describedAs("and what one record does not show: how many follow, and that they differ")
                .contains("12 records of 2 different shapes")
                .contains("handle every shape it may be given")
                .describedAs("and not the whole-stream wording it replaces")
                .doesNotContain("nothing for the values around them");
    }

    @Test
    void theOtherKindsOfRecordAreShownOneOfEach() {
        // The configuration is run over every record, so a question that showed only the first kind of record
        // would invite a configuration that handles only that kind.
        final String text = WORDS.render(new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"><record kind=\"login\"/></records>", null,
                Boundary.ofArray("events", 3), List.of(), true, new Question.Records(12, 2),
                List.of("<records xmlns=\"records:2\"><record kind=\"logout\"/></records>"), false, List.of()));

        assertThat(text)
                .contains("one record at a time, each on its own run")
                .contains("Here is one record of each of them")
                .contains("kind=\"logout\"")
                .describedAs("after the one-record instruction, not before it: these are other runs, not "
                             + "context for this record")
                .containsSubsequence("do not look outside it", "each on its own run");
    }

    @Test
    void whereTheKindsShownAreCappedTheQuestionSaysSoRatherThanClaimingOneOfEach() {
        // The number of example records shown is capped. A model told it has seen every shape when it has
        // only seen some would write a configuration that drops the others, which is the very failure that
        // showing one record of each kind is meant to prevent.
        final String text = WORDS.render(new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"><record kind=\"login\"/></records>", null,
                Boundary.ofArray("events", 3), List.of(), true, new Question.Records(40, 5),
                List.of("<records xmlns=\"records:2\"><record kind=\"logout\"/></records>",
                        "<records xmlns=\"records:2\"><record kind=\"alarm\"/></records>"), false, List.of()));

        assertThat(text)
                .contains("Here is one record of 2 of the 4 other shapes")
                .contains("there are 2 more this question does not show")
                .doesNotContain("one record of each of them");
    }

    @Test
    void markupThatIsNotRecordsTwoIsSaidToBeTheStreamsOwn() {
        // The transformation rules describe the usual case, where the input is records:2, but a chain with no
        // parser gives the transform the feed's XML as it arrived. A stylesheet that sets
        // xpath-default-namespace="records:2" over that matches nothing and writes no elements, without Saxon
        // raising any error. A live run with a real model wasted several attempts on exactly this.
        final String text = WORDS.render(new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<auditLog application=\"DocVault\"><entry id=\"a1\"/></auditLog>", null,
                Boundary.ofElement("entry", 1), List.of(), true, new Question.Records(8, 2), List.of(), false,
                        List.of()));

        assertThat(text)
                .contains("This input is the stream's own markup and not records:2")
                .contains("do not set xpath-default-namespace to records:2");
    }

    @Test
    void aParsersRecordsAreNotContradicted() {
        final String text = WORDS.render(new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"><record/></records>", null,
                Boundary.ofArray("events", 3), List.of(), true, new Question.Records(12, 2), List.of(), false,
                        List.of()));

        assertThat(text).doesNotContain("the stream's own markup");
    }

    @Test
    void theTransformQuestionCarriesTheSchemasFailureModesAndTheDegeneracyTrap() {
        final String text = WORDS.render(new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"/>", null, null, List.of(), false, Question.Records.UNKNOWN,
                List.of(), false, List.of()));

        assertThat(text)
                .contains("event-logging:3")
                .contains("EventTime/TimeCreated, EventSource and EventDetail, in that order")
                .contains("one of Device, Client, Server or Door")
                .contains("Do not put fields into Data elements or use the Unknown branch")
                .contains("<records xmlns=\"records:2\"/>")
                .doesNotContain("previous configuration");
    }

    @Test
    void theSystemTextCarriesTheDocumentsInstructions() {
        assertThat(QuestionText.builtIn("Badge readers.").system())
                .contains("The document that governs this stage says:")
                .contains("Badge readers.");
        assertThat(WORDS.system()).doesNotContain("governs this stage says");
    }

    @Test
    void theSystemTextForADocumentSaysWhatItsScorersDemand() {
        // Without this, a model is not told which fields the checks require until after it has been scored.
        // In live runs the model put the reader's location under Device/Location, not knowing that
        // Device/Name was required.
        final LearningFlowDoc doc = LearningFlowDoc.builder()
                .uuid("doc-1")
                .name("door-access")
                .instructions("Badge readers.")
                .checks(List.of(
                        CheckSetting.of(Check.COMPILE, CheckState.GATE),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        CheckSetting.of(Check.YIELD, CheckState.OFF),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false,
                                                List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        new CheckSetting(Check.RULES, CheckState.ADVICE, 0.5,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                                "interactive events name the user",
                                                "not(EventDetail/Authenticate) or EventDetail/Authenticate/User/Id")),
                                                true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
        assertThat(QuestionText.of(doc).system())
                .startsWith(QuestionText.builtIn("Badge readers.").system())
                .contains("Events are scored on carrying a value in each of: EventSource/User/Id, "
                          + "EventSource/Device/Name. Fill each wherever the record has a value for it")
                .contains("- interactive events name the user: not(EventDetail/Authenticate) or ");
        assertThat(QuestionText.of(LearningFlowDoc.builder().uuid("doc-2").name("bare").build()).system())
                .isEqualTo(WORDS.system());
    }

    @Test
    void aTemplateOverrideIsRenderedWithItsBlocksAndTheRestFollowsTheBuiltIn() {
        // The chain question uses the document's own template, and every other question uses the built-in one.
        final LearningFlow flow = FlowExamples.flow(FlowExample.TARGET_FIRST)
                .withTemplates(Map.of(Template.CHAIN, "Pick from:\n${elements}\nGiven:\n${sample}${feedback}"));
        final QuestionText words = QuestionText.of(flow);
        final Chain chain = new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of());

        assertThat(words.render(chain))
                .startsWith("Pick from:\n- DSParser: parses raw text")
                .contains("Given:\n```xml\na,b,c\n```")
                .doesNotContain("joined by ->");
        assertThat(words.render(new Split(SAMPLE, "DSParser", "TextConverter", InputKind.TEXT, List.of())))
                .isEqualTo(WORDS.render(new Split(SAMPLE, "DSParser", "TextConverter", InputKind.TEXT, List.of())));
    }

    @Test
    void aTemplateNamingAVariableItDoesNotHaveIsRefused() {
        final LearningFlow flow = FlowExamples.flow(FlowExample.DIRECT)
                .withTemplates(Map.of(Template.CHAIN, "${sample} then ${targets}",
                        Template.EXTRACTION_RULES, "LearnerBindings ${nothing}"));
        assertThat(Templates.problems(flow)).containsExactlyInAnyOrder(
                "The chain question template names ${targets}, which it does not have; it may use ${elements}, "
                + "${feedback}, ${headers}, ${sample}",
                "The extraction rules template names ${nothing}, which it does not have; it may use no variables");
        assertThat(Templates.problems(FlowExamples.flow(FlowExample.DIRECT))).isEmpty();
    }

    @Test
    void aValueThatLooksLikeASlotIsNotReadAsOne() {
        final Sample tricky = new Sample("price=${amount}\n", Map.of());
        assertThat(WORDS.render(new Chain(tricky, List.of("DSParser", "XSLTFilter"), List.of())))
                .contains("price=${amount}");
    }

    @Test
    void theWorkedExamplesInTheExtractionRulesRunAndTheHeaderOneNamesFieldsFromTheHeader() {
        // The extraction rules contain worked examples, and an example that does not run would teach the model
        // to fail. The second example reads the header into a variable and names every record's data from it,
        // which is the usual Stroom approach, so the field names come from the header and the header itself
        // is not a record.
        final String rules = Templates.builtIn(Template.EXTRACTION_RULES);
        final List<String> examples = new ArrayList<>();
        final Matcher fenced = Pattern.compile("```xml\\n(.*?)```", Pattern.DOTALL).matcher(rules);
        while (fenced.find()) {
            examples.add(fenced.group(1));
        }
        assertThat(examples).hasSize(2);
        final DataSplitterStep splitter = new DataSplitterStep(new NodeFixture().compiler());
        final String headed = "time,user,place,action\n2026-09-22T09:00:00Z,alice,lobby,logon\n"
                              + "2026-09-22T09:01:00Z,bob,lobby,logoff\n";
        final StepResult plain = splitter.run(examples.get(0), headed.lines().skip(1)
                .collect(Collectors.joining("\n", "", "\n")));
        assertThat(plain.passed()).describedAs(plain.diagnostics().toString()).isTrue();
        assertThat(Records.count(plain.output())).isEqualTo(2);
        final StepResult withHeader = splitter.run(examples.get(1), headed);
        assertThat(withHeader.passed()).describedAs(withHeader.diagnostics().toString()).isTrue();
        assertThat(Records.count(withHeader.output())).describedAs("the header is not a record").isEqualTo(2);
        assertThat(withHeader.output())
                .contains("<data name=\"user\" value=\"alice\"/>")
                .contains("<data name=\"action\" value=\"logoff\"/>");
        // The same example over reordered columns still names each field correctly.
        final StepResult reordered = splitter.run(examples.get(1),
                "user,time,action,place\nalice,2026-09-22T09:00:00Z,logon,lobby\n");
        assertThat(reordered.output()).contains("<data name=\"user\" value=\"alice\"/>")
                .contains("<data name=\"place\" value=\"lobby\"/>");
    }
}
