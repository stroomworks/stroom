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

package stroom.learningflow.scoring;

import stroom.util.xml.XMLUtil;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests declared skips: the `Skipped` marker a translation writes in place of an event for a record it
/// deliberately did not translate.
///
/// A marker is counted separately from the events, is never confused with one, and is removed before anything
/// downstream can see it.
class TestDeclaredSkips {

    private static final String THREE_RECORDS_ONE_SKIPPED = """
            <Events xmlns="event-logging:3">
              <Event><Id>1</Id></Event>
              <Skipped xmlns="learningflow:skips" reason="header line"/>
              <Event><Id>2</Id></Event>
            </Events>""";

    @Test
    void aSkipIsNotAnEvent() {
        // The completeness check adds events and skips together, so they must be counted separately
        assertThat(Records.parsed(THREE_RECORDS_ONE_SKIPPED)).isEqualTo(2);
        assertThat(Records.skips(THREE_RECORDS_ONE_SKIPPED)).isEqualTo(1);
    }

    @Test
    void whyItWasSkippedIsKept() {
        // Without the reason a reviewer sees a bare number and cannot judge it. Showing the reasons is the main
        // safeguard, since there is deliberately no limit on how many records may be skipped
        assertThat(Records.skipReasons(THREE_RECORDS_ONE_SKIPPED)).containsExactly("header line");
    }

    @Test
    void aMarkerWithoutAReasonStillCounts() {
        assertThat(Records.skips("""
                <Events xmlns="event-logging:3">
                  <Skipped xmlns="learningflow:skips"/>
                </Events>""")).isEqualTo(1);
    }

    @Test
    void anElementOfThatNameInAnotherNamespaceIsAnEvent() {
        // The marker has a namespace of its own so that it cannot be confused with a target schema's
        // element, nor a target schema's element with it
        final String output = """
                <Events xmlns="event-logging:3">
                  <Skipped><Id>1</Id></Skipped>
                </Events>""";

        assertThat(Records.parsed(output)).isEqualTo(1);
        assertThat(Records.skips(output)).isZero();
    }

    @Test
    void theScorersOfMeaningNeverSeeAMarker() {
        final OutputRecords parsed = OutputRecords.parse(THREE_RECORDS_ONE_SKIPPED).orElseThrow();

        assertThat(parsed.records()).hasSize(2);
        assertThat(parsed.skips()).hasSize(1);
        assertThat(parsed.skipReasons()).containsExactly("header line");
    }

    @Test
    void strippingLeavesTheDocumentAsItWouldHaveBeen() throws Exception {
        // Stripping keeps markers away from the Schema-valid check. No schema knows the marker, so validating
        // it would fail every feed whose header lines are skipped
        assertThat(stripped(THREE_RECORDS_ONE_SKIPPED).replaceAll("\\s+", ""))
                .endsWith("<Eventsxmlns=\"event-logging:3\"><Event><Id>1</Id></Event>"
                          + "<Event><Id>2</Id></Event></Events>");
    }

    @Test
    void strippingTakesAnythingWrittenInsideAMarkerWithIt() throws Exception {
        // The marker is meant to be empty; a model that puts an event inside one must not have it
        // validated, counted or written
        final String output = """
                <Events xmlns="event-logging:3">
                  <Skipped xmlns="learningflow:skips" reason="junk"><Event><Id>9</Id></Event></Skipped>
                </Events>""";

        assertThat(stripped(output).replaceAll("\\s+", ""))
                .endsWith("<Eventsxmlns=\"event-logging:3\"></Events>");
    }

    @Test
    void theMarkersNamespaceIsNeverDeclaredOnWhatIsKept() throws Exception {
        // The namespace is declared on the root, as a stylesheet that uses it is likely to do. The output that
        // is written must not carry Learning Flow's own namespace
        final String output = """
                <Events xmlns="event-logging:3" xmlns:ss="learningflow:skips">
                  <ss:Skipped reason="heartbeat"/>
                  <Event><Id>1</Id></Event>
                </Events>""";

        assertThat(Records.skips(output)).isEqualTo(1);
        assertThat(stripped(output)).doesNotContain("learningflow:skips");
    }

    /// Removes the markers from a document, as the Schema-valid check and the writer see it.
    ///
    /// @param xml The translation's output.
    /// @return The output with every marker and its content removed.
    private static String stripped(final String xml) throws Exception {
        final TransformerHandler handler = XMLUtil.createTransformerHandler(false);
        final StringWriter writer = new StringWriter();
        handler.setResult(new StreamResult(writer));
        final XMLReader reader = ConfinedXml.reader();
        reader.setContentHandler(DeclaredSkips.stripping(handler));
        reader.parse(new InputSource(new StringReader(xml)));
        return writer.toString();
    }
}
