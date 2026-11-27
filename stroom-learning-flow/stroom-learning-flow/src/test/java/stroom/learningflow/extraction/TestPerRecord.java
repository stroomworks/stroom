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

package stroom.learningflow.extraction;

import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests `PerRecord`, which runs a learning step the way the learned fragment will run it in production.
///
/// The candidate configuration is compiled once and run over one record at a time, and a shortfall shared by
/// every record is reported once rather than once per record.
class TestPerRecord {

    private static final String TWO_RECORDS = "<records><record>a</record><record>b</record></records>";

    @Test
    void theConfigurationIsPreparedOnceAndRunOncePerRecord() {
        // Compiling a candidate costs the same whatever the input, so it is done once per candidate, not once
        // per record. Otherwise a stream of ten thousand records would compile the stylesheet ten thousand
        // times.
        final Counting runner = new Counting(input -> new StepResult("<out>" + input + "</out>", List.of()));

        PerRecord.run(runner, "configuration", TWO_RECORDS, List.of("<record>a</record>", "<record>b</record>"));

        assertThat(runner.prepared.get()).describedAs("compiled once for the candidate").isEqualTo(1);
        assertThat(runner.ran.get()).describedAs("run once for each record").isEqualTo(2);
        assertThat(runner.wholeDocument.get()).describedAs("and never over the whole document").isZero();
        assertThat(runner.inputs).describedAs("each run sees one record and not the stream")
                .containsExactly("<record>a</record>", "<record>b</record>");
    }

    @Test
    void whatEachRecordWroteIsJoinedIntoOneDocument() {
        final Counting runner = new Counting(input -> new StepResult(
                "<Events><Event>" + input.replaceAll("<[^>]*>", "") + "</Event></Events>", List.of()));

        final StepResult result = PerRecord.run(runner, "configuration", TWO_RECORDS,
                List.of("<record>a</record>", "<record>b</record>"));

        assertThat(result.output()).contains("<Event>a</Event>").contains("<Event>b</Event>");
    }

    @Test
    void aShortfallEveryRecordSharesIsToldOnce() {
        // A stylesheet that raises the same error for every record of a ten-thousand-record stream raises it
        // ten thousand times. Sending all those copies back to the model would tell it no more than one copy
        // does, and would cost tokens to send.
        final Counting runner = new Counting(input -> new StepResult(null, List.of(error("no EventTime"))));

        final StepResult result = PerRecord.run(runner, "configuration", TWO_RECORDS,
                List.of("<record>a</record>", "<record>b</record>"));

        assertThat(result.diagnostics()).extracting(StoredError::getMessage).containsExactly("no EventTime");
    }

    @Test
    void aStreamCarryingOneRecordIsRunAsTheOneRecordItIs() {
        // "One record" and "no records" must be treated differently. A stream whose array holds a single item
        // still has a SplitFilter in its fragment, so the pipeline hands the transform one record, never the
        // whole envelope. Judging a candidate against the envelope would let a stylesheet that reached into the
        // envelope pass here and then produce nothing useful in production.
        final Counting runner = new Counting(input -> new StepResult("<out/>", List.of()));

        PerRecord.run(runner, "configuration", "<records><record>a</record></records>",
                List.of("<records><record>a</record></records>"));

        assertThat(runner.ran.get()).isEqualTo(1);
        assertThat(runner.wholeDocument.get()).describedAs("run as one record, not as a document").isZero();
    }

    @Test
    void aRunThatWroteNothingReadableSaysSoRatherThanFailingInSilence() {
        // A stylesheet that matches records:2 elements over input in no namespace matches nothing, and writes
        // the input's text with no elements. Saxon raises no error, because that is a successful transform into
        // text, so joining the outputs fails and the step produces nothing. Without a diagnostic, the next
        // question to the model would carry no reason at all. This was seen in a live run with a real model.
        final Counting runner = new Counting(input -> new StepResult("just some text", List.of()));

        final StepResult result = PerRecord.run(runner, "configuration", TWO_RECORDS,
                List.of("<record>a</record>", "<record>b</record>"));

        assertThat(result.output()).isNull();
        assertThat(result.diagnostics()).describedAs("a failure the next candidate can act on").hasSize(1);
        assertThat(result.diagnostics().get(0).getMessage())
                .contains("not an XML document")
                .contains("2 records");
    }

    @Test
    void aRunThatWroteNothingAtAllSaysThatInstead() {
        final Counting runner = new Counting(input -> new StepResult(null, List.of()));

        final StepResult result = PerRecord.run(runner, "configuration", TWO_RECORDS,
                List.of("<record>a</record>", "<record>b</record>"));

        assertThat(result.diagnostics()).extracting(StoredError::getMessage)
                .containsExactly("Nothing was written for any of the 2 records of the stream");
    }

    @Test
    void aStepWithNoRecordsToRunOverIsRunOverTheWholeDocument() {
        // When no records were found at the record depth, there is nothing to run per record, so the step is
        // run once over the whole document. (A stream with a single record is still run per record.)
        final Counting runner = new Counting(input -> new StepResult("<out/>", List.of()));

        PerRecord.run(runner, "configuration", TWO_RECORDS, List.of());

        assertThat(runner.wholeDocument.get()).isEqualTo(1);
        assertThat(runner.prepared.get()).isZero();
    }

    private static StoredError error(final String message) {
        return new StoredError(Severity.ERROR, null, new ElementId("xsltFilter"), message);
    }


    // --------------------------------------------------------------------------------


    /// A step runner that counts how often a configuration was prepared, how often a prepared one was run, and
    /// how often the whole document went through the unprepared path.
    private static final class Counting implements StepRunner {

        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicInteger ran = new AtomicInteger();
        private final AtomicInteger wholeDocument = new AtomicInteger();
        private final List<String> inputs = new ArrayList<>();
        private final Prepared behaviour;

        private Counting(final Prepared behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public String elementType() {
            return "XSLTFilter";
        }

        @Override
        public String elementId() {
            return "xsltFilter";
        }

        @Override
        public Optional<Configured> configured() {
            return Optional.empty();
        }

        @Override
        public StepResult run(final String configuration, final String input) {
            wholeDocument.incrementAndGet();
            return behaviour.run(input);
        }

        @Override
        public Prepared prepare(final String configuration) {
            prepared.incrementAndGet();
            return input -> {
                ran.incrementAndGet();
                inputs.add(input);
                return behaviour.run(input);
            };
        }
    }
}
