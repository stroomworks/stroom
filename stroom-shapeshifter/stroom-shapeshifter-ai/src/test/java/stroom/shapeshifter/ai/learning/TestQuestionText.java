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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.NodeFixture;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.scoring.Records;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;
import stroom.shapeshifter.shared.XPathAssertion;
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

/**
 * The prompt contract of design 01 §10, as text: each question carries what the section says it must.
 */
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
        // §12 item 25: the fragment gives this element one record at a time, so it is asked for a
        // configuration that handles one — and told so, because a stylesheet written for the whole
        // stream is written for something that will never arrive.
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
        // A47: the configuration is run over every record, so a question that shows only the first kind
        // invites a configuration that handles only the first kind.
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
        // The representatives are capped, and a model told it has seen every shape when it has seen
        // three of five writes a configuration that drops the other two — the failure A47 exists to
        // prevent, with an assurance attached.
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
        // The transformation rules describe the usual case and state records:2, but a chain with no
        // parser hands the transform the feed's XML as it arrived. A stylesheet that sets
        // xpath-default-namespace="records:2" over it matches nothing and writes no elements, failing
        // without Saxon raising anything — which the live run of 2026-09-22 spent seven attempts on.
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
        // Both live runs put the reader's location under Device/Location, never told that Device/Name was
        // required until the score had already been taken.
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder()
                .uuid("doc-1")
                .name("door-access")
                .instructions("Badge readers.")
                .scorers(List.of(
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false,
                                        List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 0.5, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "interactive events name the user",
                                        "not(EventDetail/Authenticate) or EventDetail/Authenticate/User/Id")),
                                        true))))
                .build();
        assertThat(QuestionText.of(doc).system())
                .startsWith(QuestionText.builtIn("Badge readers.").system())
                .contains("Events are scored on carrying a value in each of: EventSource/User/Id, "
                          + "EventSource/Device/Name. Fill each wherever the record has a value for it")
                .contains("- interactive events name the user: not(EventDetail/Authenticate) or ");
        assertThat(QuestionText.of(ShapeshifterAiDoc.builder().uuid("doc-2").name("bare").build()).system())
                .isEqualTo(WORDS.system());
    }

    @Test
    void aTemplateOverrideIsRenderedWithItsBlocksAndTheRestFollowsTheBuiltIn() {
        // Scenario 40: the chain question in the document's own words; every other question as built in.
        final LearningFlow flow = LearningFlow.of(FlowExample.TARGET_FIRST)
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
        final LearningFlow flow = LearningFlow.of(FlowExample.DIRECT)
                .withTemplates(Map.of(Template.CHAIN, "${sample} then ${targets}",
                        Template.EXTRACTION_RULES, "LearnerBindings ${nothing}"));
        assertThat(Templates.problems(flow)).containsExactlyInAnyOrder(
                "The chain question template names ${targets}, which it does not have; it may use ${elements}, "
                + "${feedback}, ${headers}, ${sample}",
                "The extraction rules template names ${nothing}, which it does not have; it may use no variables");
        assertThat(Templates.problems(LearningFlow.of(FlowExample.DIRECT))).isEmpty();
    }

    @Test
    void aValueThatLooksLikeASlotIsNotReadAsOne() {
        final Sample tricky = new Sample("price=${amount}\n", Map.of());
        assertThat(WORDS.render(new Chain(tricky, List.of("DSParser", "XSLTFilter"), List.of())))
                .contains("price=${amount}");
    }

    @Test
    void theWorkedExamplesInTheExtractionRulesRunAndTheHeaderOneNamesFieldsFromTheHeader() {
        // The rules are the words the live runs taught; a worked example that does not run teaches the
        // model to fail. The second reads the header into a variable and names every record's data from
        // it, Stroom's idiom, so that the names travel with the record and the header is not one.
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
        // The same example over the columns reordered still names each field rightly.
        final StepResult reordered = splitter.run(examples.get(1),
                "user,time,action,place\nalice,2026-09-22T09:00:00Z,logon,lobby\n");
        assertThat(reordered.output()).contains("<data name=\"user\" value=\"alice\"/>")
                .contains("<data name=\"place\" value=\"lobby\"/>");
    }
}
