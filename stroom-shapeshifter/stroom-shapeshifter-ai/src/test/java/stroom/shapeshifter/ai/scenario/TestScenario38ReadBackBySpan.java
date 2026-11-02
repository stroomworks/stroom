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

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Inputs;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.TextRange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 38 (design 01 §10.1, §12 item 21): a fault found at an event is relearned
/// with the record that made it, and the record is read back from the stream by the span the parser
/// recorded — not by running the parser over the stream again, which at thirty gigabytes is not a thing
/// anyone can do per fault.
///
/// This is the read-back itself: the spans are kept with the bindings as the stream is served, and one
/// record's own text comes back out of the stream by its span.
class TestScenario38ReadBackBySpan {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), data, "pipeline-1");
    }

    @Test
    void scenario38ARecordsOwnTextComesBackFromTheStreamByItsSpan() {
        final Scenarios scenarios = new Scenarios();
        final StageRun run = scenarios.process(scenarios.script(CSV.configuration(), XSLT)
                        .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("DSParser"))
                        .reply(Scenarios.fenced(CSV.configuration()))
                        .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT)),
                doc(), stream(1, CSV.input())).learned();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);

        // The spans were kept as the stream was served, one per record the parser cut.
        final Optional<TextRange> second = scenarios.outputs.span(DOC, 1L, "pipeline-1", 1);
        assertThat(second).describedAs("the second record's span").isPresent();

        // And the record's own text comes back out of the stream by it, without the parser running.
        final Inputs inputs = metaId -> Optional.of(stream(metaId, CSV.input()));
        final String text = inputs.textOf(1L, second.get()).orElseThrow();

        final List<String> lines = CSV.input().lines().toList();
        assertThat(text)
                .describedAs("the second record of the stream, which the header line is not")
                .isEqualTo(lines.get(2));
        assertThat(scenarios.outputs.span(DOC, 1L, "pipeline-1", 0).flatMap(span -> inputs.textOf(1L, span)))
                .contains(lines.get(1));
    }

    @Test
    void anAsProcessedReprocessDoesNotEraseTheSpansTheRunThatProducedItRecorded() {
        // The stream being reprocessed as it was processed is the one somebody is investigating, and a
        // reprocess has no parser to ask: it runs the written fragment. Knowing nothing about where the
        // records began must not erase what the run that produced the output knew.
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(Script.of());
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT)),
                doc, stream(1, CSV.input())).learned();
        final TextRange before = scenarios.outputs.span(DOC, 1L, "pipeline-1", 1).orElseThrow();

        stage.reprocess(doc, Scenarios.LEARNER, stream(1, CSV.input()));

        assertThat(scenarios.outputs.span(DOC, 1L, "pipeline-1", 1))
                .describedAs("still there, and the same span")
                .contains(before);
    }

    @Test
    void aRecordNothingRecordedHasNoSpanAndSaysSoRatherThanGuessing() {
        final Scenarios scenarios = new Scenarios();

        assertThat(scenarios.outputs.span(DOC, 99L, "pipeline-1", 0)).isEmpty();
    }

    @Test
    void aSpanThatNamesSomewhereTheStreamDoesNotReachReadsBackNothing() {
        final Inputs inputs = metaId -> Optional.of(stream(metaId, "one\ntwo\n"));

        assertThat(inputs.textOf(1L, new TextRange(DefaultLocation.of(9, 1), DefaultLocation.of(9, 3))))
                .describedAs("a line the stream has not got")
                .isEmpty();
        assertThat(inputs.textOf(1L, new TextRange(DefaultLocation.of(1, 1), DefaultLocation.of(2, 3))))
                .describedAs("and a span across two lines is both of them")
                .contains("one\ntwo");
    }
}
