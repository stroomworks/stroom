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

package stroom.learningflow.fragment;

import stroom.learningflow.scoring.ConfinedXml;
import stroom.learningflow.scoring.Verdict;
import stroom.util.xml.XMLUtil;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests how the record gate treats a declared skip in a stream being served.
///
/// A translation may write a `Skipped` marker in place of an event for a record it deliberately did not
/// translate. The gate counts the marker, does not judge it against the bar, and removes it before anything
/// downstream, including the writer, can see it.
class TestRecordGateSkips {

    private static final Verdict CLEARS = new Verdict(List.of(), 1.0);

    private static final String SERVED = """
            <Events xmlns="event-logging:3" xmlns:ss="learningflow:skips">
              <Event><Id>1</Id></Event>
              <ss:Skipped reason="header line"/>
              <Event><Id>2</Id></Event>
              <ss:Skipped reason="heartbeat"/>
            </Events>""";

    @Test
    void aSkipIsCountedButNotJudged() throws Exception {
        final Run run = serve(SERVED);

        assertThat(run.records).describedAs("the events, and only the events").isEqualTo(2);
        assertThat(run.skips).isEqualTo(2);
        assertThat(run.reasons).containsExactly("header line", "heartbeat");
        assertThat(run.judged).describedAs("the bar is never put to a marker").hasSize(2);
    }

    @Test
    void theDownstreamNeverSeesAMarker() throws Exception {
        // The marker is never passed to the writer, because a skip is Learning Flow's own bookkeeping and not
        // one of the feed's events
        final String written = serve(SERVED).written;

        assertThat(written).doesNotContain("Skipped");
        assertThat(written).doesNotContain("learningflow:skips");
        assertThat(written.replaceAll("\\s+", ""))
                .contains("<Event><Id>1</Id></Event><Event><Id>2</Id></Event>");
    }

    @Test
    void aStreamOfNothingButSkipsHandsOnNothing() throws Exception {
        // Nothing is passed downstream, just as for a run whose every record fell short, because there are no
        // events to write
        final Run run = serve("""
                <Events xmlns="event-logging:3">
                  <Skipped xmlns="learningflow:skips" reason="cannot read"/>
                </Events>""");

        assertThat(run.records).isZero();
        assertThat(run.skips).isEqualTo(1);
        assertThat(run.written).isEmpty();
    }

    /// Runs the record gate over a chain's output, as a serving run does.
    ///
    /// @param output What the chain produced.
    /// @return What the gate counted and judged, and what it passed downstream.
    private static Run serve(final String output) throws Exception {
        final TransformerHandler downstream = XMLUtil.createTransformerHandler(false);
        final StringWriter writer = new StringWriter();
        downstream.setResult(new StreamResult(writer));
        final List<String> judged = new ArrayList<>();
        final RecordGate gate = new RecordGate((index, record, parsed) -> {
            judged.add(record);
            return CLEARS;
        }, downstream, index -> Optional.empty());

        final XMLReader reader = ConfinedXml.reader();
        reader.setContentHandler(gate);
        reader.parse(new InputSource(new StringReader(output)));
        gate.finish();

        return new Run(gate.records(), gate.skips(), gate.skipReasons(), judged, writer.toString());
    }


    // --------------------------------------------------------------------------------


    /// The result of one serving run through the record gate.
    ///
    /// @param records How many events were judged.
    /// @param skips   How many records the translation declared it skipped.
    /// @param reasons Why it said it skipped them.
    /// @param judged  Each record as the bar was shown it.
    /// @param written What the downstream was given.
    private record Run(int records, int skips, List<String> reasons, List<String> judged, String written) {

    }
}
