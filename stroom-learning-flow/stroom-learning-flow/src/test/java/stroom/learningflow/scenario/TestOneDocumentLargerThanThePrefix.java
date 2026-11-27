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

package stroom.learningflow.scenario;

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that a stream consisting of one XML or JSON document larger than the prefix read for learning is
/// refused, with a reason.
///
/// A truncated document does not parse, so there is nothing to find a record boundary in, and without a
/// boundary every later step would be judged on nothing. Reading the whole stream into memory instead is
/// what the prefix exists to prevent, so learning stops and reports what is wrong and what would make the
/// feed learnable.
class TestOneDocumentLargerThanThePrefix {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    @Test
    void oneXmlDocumentLargerThanThePrefixIsRefusedWithSomethingToDo() {
        final Scenarios scenarios = new Scenarios();
        final Script silent = Script.of();

        final StageRun run = scenarios.process(silent, doc(), streamed(oneHugeXmlDocument())).learned();

        // Depending on where it runs, the stage either gives the learner up (in a processing task) or parks
        // the session (in the worker). Either is acceptable here; what matters is that the run carries a
        // report explaining the refusal rather than failing silently.
        final StopReport report = reportOf(run);
        assertThat(report).isNotNull();
        assertThat(report.getError())
                .contains("single XML document larger than")
                .contains("bytes that are read to learn from");
        assertThat(report.getDetails())
                .anySatisfy(detail -> assertThat(detail).contains("A part of a document is not a document"))
                .anySatisfy(detail -> assertThat(detail).contains("a larger limit is not the answer"));
        assertThat(report.getSuggestions())
                .describedAs("and what would make the feed learnable")
                .anySatisfy(suggestion -> assertThat(suggestion.getText())
                        .contains("one document per record"));
        assertThat(silent.asked()).describedAs("nothing is put to the model: there is nothing to ask about")
                .isEmpty();
    }

    @Test
    void aStreamOfRecordsLongerThanThePrefixIsLearnedFromItsPrefixAsBefore() {
        // Guards against the refusal catching ordinary text feeds, which is why it checks whether the sample
        // looks like markup rather than only whether it parses. A very long CSV stream goes through the same
        // code, and its prefix is a perfectly good sample to learn from.
        final Scenarios scenarios = new Scenarios();
        final StringBuilder lines = new StringBuilder("dt,who,where,what\n");
        while (lines.length() < Input.PREFIX_BYTES * 2) {
            lines.append("2020-06-17T08:00:00.000Z,user0,office,logon\n");
        }
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, textDoc(), streamed(lines.toString())).learned();

        assertThat(reportOf(run))
                .describedAs("not refused for its size: " + run.decision())
                .satisfiesAnyOf(
                        report -> assertThat(report).isNull(),
                        report -> assertThat(report.getError()).doesNotContain("larger than"));
    }

    /// A document for a text feed, allowing a parser in front of the transform as a real CSV feed has.
    private static LearningFlowDoc textDoc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("big-text")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.DIRECT))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .build();
    }

    /// @param run The stage run to read the report from.
    /// @return The report carried by a parked or given-up decision, or null if the run carries none. Both
    ///         kinds are read, because the same refusal is a give-up in a task and a park in the worker.
    private static StopReport reportOf(final StageRun run) {
        if (run.decision() instanceof final Parked parked) {
            return parked.report();
        }
        if (run.decision() instanceof final GivenUp givenUp) {
            return givenUp.report();
        }
        return null;
    }

    /// A single XML document with no line breaks to cut at, longer than the prefix.
    private static String oneHugeXmlDocument() {
        final StringBuilder xml = new StringBuilder("<export>");
        while (xml.length() < Input.PREFIX_BYTES + 1_000) {
            xml.append("<record><id>").append(xml.length()).append("</id></record>");
        }
        return xml.append("</export>").toString();
    }

    /// Builds the stream as a processing task has it: a text prefix, and a supplier that opens the whole
    /// stream.
    private static Input streamed(final String data) {
        final String prefix = data.substring(0, Math.min(data.length(), Input.PREFIX_BYTES));
        return Input.streamed(1L, "BIG", "Raw Events", Map.of(), prefix, "pipeline-1",
                () -> new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("big")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("XSLTFilter"))
                .build();
    }
}
