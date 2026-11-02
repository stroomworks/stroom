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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 48 (design 03 §3): a document store's audit export as CSV whose last field is
/// quoted and may hold commas, doubled quotes and line breaks. A split of one record per line cuts such a
/// record in two: it consumes every character, so coverage says nothing, and it loses none, so wholeness — a
/// character share — says nothing either; what catches it is yield against the lines a record takes, which
/// the document states. The split that honours the quoting passes; targets are proposed for whole records;
/// the golden holds the note with its line break intact and its doubled quotes as one.
class TestScenario48CsvMultiline {

    private static final String WHOLE = Scenarios.resource("csv-multiline-split.ds3.xml");
    private static final String FIELDS = Scenarios.resource("csv-multiline.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-multiline.xsl");
    private static final String CSV = Scenarios.resource("csv-multiline.csv");
    private static final String EVENTS = Scenarios.resource("csv-multiline.events.xml");

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("document-store-audit")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        // Twenty records over twenty-six lines: the expected yield per line says a record may
                        // span lines, and a split of one record per line scores 0.77 of it.
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.9, false,
                                new YieldParameters(0.77, YieldBasis.LINES)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id")))))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOCUMENT-STORE", "Raw Events", Map.of("Format", "CSV"), data);
    }

    @Test
    void scenario48AQuotedFieldSpanningLinesIsOneRecord() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply(Scenarios.fenced(Structure.LINE_SPLIT))
                .expect(QuestionMatcher.split()
                        .withFeedbackMentioning("Yield scored")
                        .withFeedbackMentioning("per unit against an expected 0.77"))
                .reply(Scenarios.fenced(WHOLE))
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, CSV)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(run.output()).isEqualTo(EVENTS);
        // The golden keeps the line break and reads a doubled quote as one.
        assertThat(run.output())
                .contains("<Description>Opened from the \"Finance\" share;\nread only</Description>")
                .contains("<Description>Removed after review, superseded\nby the v2 draft</Description>");
        // The line split consumed everything and lost nothing: neither coverage nor wholeness refused it.
        // Yield against the lines a record takes did, before any target was asked.
        final List<Exchange> turns = run.transcript();
        assertThat(turns.get(1).question()).isInstanceOf(Split.class);
        assertThat(turns.get(1).outcome()).isEqualTo(StepOutcome.YIELD_SHORT);
        assertThat(turns.get(1).question().feedback()).isEmpty();
        assertThat(turns.get(2).question().feedback()).extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Input coverage") || message.startsWith("The split emitted"));
        assertThat(turns.get(2).outcome()).isEqualTo(StepOutcome.PASSED);
        // Targets were proposed for whole records: one of them spans two lines and carries the doubled quote.
        final List<TargetFor> targets = script.asked().stream()
                .filter(TargetFor.class::isInstance).map(TargetFor.class::cast).toList();
        assertThat(targets).isNotEmpty();
        assertThat(targets).anyMatch(target -> target.record().contains("\n"));
        assertThat(targets).allMatch(target -> target.record().startsWith("2026-09-21T"));
        assertThat(turns.stream().filter(turn -> turn.question() instanceof TargetFor).findFirst().orElseThrow()
                .candidate()).isEqualTo(1);
    }
}
