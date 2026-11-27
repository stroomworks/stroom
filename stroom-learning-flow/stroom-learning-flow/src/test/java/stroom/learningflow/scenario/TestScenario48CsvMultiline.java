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

import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests learning a CSV audit export whose last field is quoted and may hold commas, doubled quotes and line
/// breaks, so that one record can span several lines.
///
/// A split of one record per line cuts such a record in two. It consumes every character, so coverage does
/// not catch it, and it loses none, so wholeness (a share of characters) does not catch it either. What
/// catches it is the yield check, against the number of lines per record that the document states. The split
/// that respects the quoting passes, targets are proposed for whole records, and the expected output keeps
/// the note's line break and reads each doubled quote as one.
class TestScenario48CsvMultiline {

    private static final String WHOLE = Scenarios.resource("csv-multiline-split.ds3.xml");
    private static final String FIELDS = Scenarios.resource("csv-multiline.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-multiline.xsl");
    private static final String CSV = Scenarios.resource("csv-multiline.csv");
    private static final String EVENTS = Scenarios.resource("csv-multiline.events.xml");

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("document-store-audit")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.GATE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.GATE, 0.9,
                                new YieldParameters(0.77, YieldBasis.LINES)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
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
        // The expected output keeps the line break and reads a doubled quote as one.
        assertThat(run.output())
                .contains("<Description>Opened from the \"Finance\" share;\nread only</Description>")
                .contains("<Description>Removed after review, superseded\nby the v2 draft</Description>");
        // The line split consumed everything and lost nothing, so neither coverage nor wholeness refused it.
        // Yield against the lines per record did, before any target was asked for.
        final List<Exchange> turns = run.transcript();
        assertThat(turns.get(1).question()).isInstanceOf(Split.class);
        assertThat(turns.get(1).outcome()).isEqualTo(StepOutcome.YIELD_SHORT);
        assertThat(turns.get(1).question().feedback()).isEmpty();
        assertThat(turns.get(2).question().feedback()).extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Input coverage") || message.startsWith("The split emitted"));
        assertThat(turns.get(2).outcome()).isEqualTo(StepOutcome.PASSED);
        // Targets were proposed for whole records: each starts with a timestamp, and at least one spans two
        // lines.
        final List<TargetFor> targets = script.asked().stream()
                .filter(TargetFor.class::isInstance).map(TargetFor.class::cast).toList();
        assertThat(targets).isNotEmpty();
        assertThat(targets).anyMatch(target -> target.record().contains("\n"));
        assertThat(targets).allMatch(target -> target.record().startsWith("2026-09-21T"));
        assertThat(turns.stream().filter(turn -> turn.question() instanceof TargetFor).findFirst().orElseThrow()
                .candidate()).isEqualTo(1);
    }
}
