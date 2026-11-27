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

import stroom.learningflow.learning.StepResult;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.RecordBoundary;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the completeness check, which requires that records in = events out + declared skips.
///
/// Without it, a record that becomes no event is judged by nothing, so the stream is recorded as clean and
/// the loss is visible only to someone reading the Error stream.
class TestCompletenessScorer {

    private static final String THREE_RECORDS = """
            <records xmlns="records:2">
              <record><data name="a"/></record>
              <record><data name="b"/></record>
              <record><data name="c"/></record>
            </records>""";

    @Test
    void anEventForEveryRecordPasses() {
        assertThat(score(THREE_RECORDS, events(3)).value()).isEqualTo(1.0);
    }

    @Test
    void aRecordThatBecameNothingIsCaught() {
        // For example, the parser read a line it could not match, no event was made, and nothing else
        // judged the loss
        final Score score = score(THREE_RECORDS, events(2));

        assertThat(score.value()).isEqualTo(0.0);
        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("1 of 3 record(s) produced neither an event nor a "
                                                      + "declared skip"));
    }

    @Test
    void theMessageSaysHowToDeclareASkip() {
        // A model told only a count would have to guess whether to translate the record or skip it, so the
        // message explains how to declare a skip and when that is appropriate
        assertThat(score(THREE_RECORDS, events(2)).diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("learningflow:skips")
                                     && message.contains("header line"));
    }

    @Test
    void aDeclaredSkipMakesTheSumAddUp() {
        // A feed with a header line legitimately makes fewer events than records. Without declared skips the
        // check would fail it on every stream, and people would switch the check off
        final String output = """
                <Events xmlns="event-logging:3">
                  <Skipped xmlns="learningflow:skips" reason="header line"/>
                  <Event><Id>1</Id></Event>
                  <Event><Id>2</Id></Event>
                </Events>""";

        assertThat(score(THREE_RECORDS, output).value()).isEqualTo(1.0);
    }

    @Test
    void howManyWereSkippedIsSaidEvenWhenTheCheckPasses() {
        // There is deliberately no limit on how many records may be skipped, so this notice is the only thing
        // that shows the skip count to a person: a reviewer reads "2 events, 1 of 3 records declared skipped"
        final String output = """
                <Events xmlns="event-logging:3">
                  <Skipped xmlns="learningflow:skips" reason="header line"/>
                  <Event><Id>1</Id></Event>
                  <Event><Id>2</Id></Event>
                </Events>""";

        assertThat(score(THREE_RECORDS, output).diagnostics())
                .extracting(StoredError::getSeverity).containsExactly(Severity.INFO);
        assertThat(score(THREE_RECORDS, output).diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("2 event(s), 1 of 3 record(s) declared skipped"));
    }

    @Test
    void aSkipForEveryRecordStillPasses() {
        // This is a known gap, accepted deliberately and pinned by this test so that it is a choice rather
        // than a surprise: completeness cannot tell a wholly skipped stream from an honest one, and a person
        // judges it from the skip count
        final String output = """
                <Events xmlns="event-logging:3">
                  <Skipped xmlns="learningflow:skips" reason="cannot read"/>
                  <Skipped xmlns="learningflow:skips" reason="cannot read"/>
                  <Skipped xmlns="learningflow:skips" reason="cannot read"/>
                </Events>""";

        assertThat(score(THREE_RECORDS, output).value()).isEqualTo(1.0);
        assertThat(score(THREE_RECORDS, output).diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("0 event(s), 3 of 3 record(s) declared skipped"));
    }

    @Test
    void moreEventsThanRecordsIsNotItsBusiness() {
        // One record can legitimately make two events; too many events is for the yield check, not this one
        assertThat(score(THREE_RECORDS, events(5)).value()).isEqualTo(1.0);
    }

    @Test
    void aParserIsJudgedByInputCoverageInstead() {
        // A parser creates the records, so there is nothing to count them against
        assertThat(new CompletenessScorer().score(null, new Attempted("DSParser", true, "a\nb\nc\n",
                new StepResult(events(2), List.of())), null)).isEmpty();
    }

    @Test
    void textInIsNotCounted() {
        // A transform given raw text has no records to count, and a step is not failed by a check that
        // cannot apply to it
        assertThat(new CompletenessScorer().score(null, new Attempted("XSLTFilter", false, "a\nb\nc\n",
                new StepResult(events(2), List.of())), null)).isEmpty();
    }

    @Test
    void itIsAGateInEveryPreset() {
        // Unlike the literals check, which only advises in some presets, completeness is a gate in all of them,
        // because a record that silently became nothing is exactly the fault it exists to catch
        for (final CheckPreset preset : CheckPreset.values()) {
            assertThat(setting(preset).getState())
                    .describedAs(preset.getDisplayValue())
                    .isEqualTo(CheckState.GATE);
        }
    }

    private static CheckSetting setting(final CheckPreset preset) {
        return preset.settings().stream().filter(s -> s.getCheck() == Check.COMPLETENESS).findFirst()
                .orElseThrow();
    }

    /// Builds an event-logging output with a given number of events.
    ///
    /// @param count How many events.
    /// @return An `Events` document holding that many events.
    private static String events(final int count) {
        final StringBuilder output = new StringBuilder("<Events xmlns=\"event-logging:3\">");
        for (int i = 0; i < count; i++) {
            output.append("<Event><Id>").append(i).append("</Id></Event>");
        }
        return output.append("</Events>").toString();
    }

    private static Score score(final String input, final String output) {
        final Optional<Score> score = new CompletenessScorer().score(null,
                new Attempted("XSLTFilter", false, input, new StepResult(output, List.of()),
                        RecordBoundary.ofElement("record"), null), null);
        return score.orElseThrow();
    }
}
