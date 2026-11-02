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

package stroom.shapeshifter.ai.extraction;

import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Running a step the way the fragment will run it (§12 item 25): one record at a time, compiled once for
 * the candidate, and reporting what fell short once rather than once per record.
 */
class TestPerRecord {

    private static final String TWO_RECORDS = "<records><record>a</record><record>b</record></records>";

    @Test
    void theConfigurationIsPreparedOnceAndRunOncePerRecord() {
        // The cost of compiling a candidate does not depend on the input, so it is paid for the candidate
        // and not for every record of the stream it is judged on: a stylesheet compiled per record is a
        // stylesheet compiled ten thousand times for a stream of ten thousand records.
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
        // A stylesheet that raises the same error for every record of a stream raises it ten thousand
        // times, and ten thousand copies of one sentence is a re-ask that says no more than one copy and
        // costs a budget (A44) to send.
        final Counting runner = new Counting(input -> new StepResult(null, List.of(error("no EventTime"))));

        final StepResult result = PerRecord.run(runner, "configuration", TWO_RECORDS,
                List.of("<record>a</record>", "<record>b</record>"));

        assertThat(result.diagnostics()).extracting(StoredError::getMessage).containsExactly("no EventTime");
    }

    @Test
    void aStreamCarryingOneRecordIsRunAsTheOneRecordItIs() {
        // "One record" and "no records" must not be the same answer. A stream whose array holds a single
        // item still has its SplitFilter written, so a candidate judged against the whole envelope would
        // be judged against something the pipeline will never hand it — and a stylesheet that reached
        // into the envelope would pass here and name nobody in production.
        final Counting runner = new Counting(input -> new StepResult("<out/>", List.of()));

        PerRecord.run(runner, "configuration", "<records><record>a</record></records>",
                List.of("<records><record>a</record></records>"));

        assertThat(runner.ran.get()).isEqualTo(1);
        assertThat(runner.wholeDocument.get()).describedAs("run as one record, not as a document").isZero();
    }

    @Test
    void aRunThatWroteNothingReadableSaysSoRatherThanFailingInSilence() {
        // Found by the live run of 2026-09-22: a stylesheet told to read records:2 over input that is in
        // no namespace matches nothing and writes the input's text with no elements. Saxon raises
        // nothing — it is a successful transform of a document into text — so joining it fails, the step
        // produces nothing, and the re-ask that follows carries no shortfall at all. Four of the five
        // attempts that run spent on the row were blind.
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
        // The only case in which there is nothing to run per record: the depth names no records at all.
        // A stream carrying a single record is run as the one record it is.
        final Counting runner = new Counting(input -> new StepResult("<out/>", List.of()));

        PerRecord.run(runner, "configuration", TWO_RECORDS, List.of());

        assertThat(runner.wholeDocument.get()).isEqualTo(1);
        assertThat(runner.prepared.get()).isZero();
    }

    private static StoredError error(final String message) {
        return new StoredError(Severity.ERROR, null, new ElementId("xsltFilter"), message);
    }


    // --------------------------------------------------------------------------------


    /// A runner that counts what was asked of it: how often a configuration was prepared, how often a
    /// prepared one was run, and how often the whole document went through the unprepared path.
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
