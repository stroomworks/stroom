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

import stroom.docref.DocRef;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.TimesParameters;
import stroom.learningflow.transformation.StreamFacts;
import stroom.util.date.DateUtil;
import stroom.util.shared.StoredError;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the time plausibility check, which reports an event dated nowhere near the stream it came from.
///
/// The fault it catches: a syslog date has no year, the translation guesses one, and the events come out dated
/// three years before their stream. Such events validate, carry real content and obey every rule, so without
/// this check they would score 1.0 everywhere.
class TestTimesScorer {

    private static final long RECEIVED = DateUtil.parseNormalDateTimeString("2026-11-11T09:18:19.000Z");
    private static final StreamFacts STREAM = new StreamFacts(1L, "NETFILTER", "Raw Events", RECEIVED, Map.of());
    // A schema that keeps its time in When, rather than event-logging's EventTime/TimeCreated
    private static final TargetSchema SIGHTINGS = new TargetSchema(
            new DocRef(XmlSchemaDoc.TYPE, "schema-1", "sightings v2.4"), "sightings v2.4",
            "urn:sightings:2", "file://sightings-v2.4.xsd", "2.4", "Sightings", null);

    @Test
    void anEventDatedNearItsStreamIsPlausible() {
        assertThat(score(events("2026-11-11T09:06:02.000Z", "2026-11-11T09:07:00.000Z")).value())
                .isEqualTo(1.0);
    }

    @Test
    void anEventDatedYearsBeforeItsStreamIsNot() {
        final Score score = score(events("2023-10-19T09:06:02.000Z"));

        assertThat(score.value()).isEqualTo(0.0);
        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("2023-10-19T09:06:02.000Z")
                                     && message.contains("about 3 years before")
                                     && message.contains("stroom:format-date"));
    }

    @Test
    void theScoreIsTheShareOfRecordsThatArePlausible() {
        assertThat(score(events("2026-11-11T09:06:02.000Z", "2023-10-19T09:06:02.000Z")).value())
                .isEqualTo(0.5);
    }

    @Test
    void aBackLoadedStreamIsNotPunishedForBeingOld() {
        // Loading old data from an archive is normal, so the default window reaches a year back
        assertThat(score(events("2026-06-11T09:06:02.000Z")).value()).isEqualTo(1.0);
    }

    @Test
    void aClockSlightlyAheadIsAllowedFor() {
        assertThat(score(events("2026-11-11T15:00:00.000Z")).value()).isEqualTo(1.0);
    }

    @Test
    void theWindowIsTheLearningFlowsToSet() {
        // A feed whose data is never more than a week old can say so and catch more
        final TimesParameters week = new TimesParameters(7, 1);
        assertThat(new TimesScorer().score(week, attempted(events("2026-06-11T09:06:02.000Z")), null)
                .orElseThrow().value()).isEqualTo(0.0);
    }

    @Test
    void onlyAFewRecordsAreNamedHoweverManyAreWrong() {
        // A translation that gets the year wrong gets it wrong for every record, and twenty copies of one
        // fault tell the model nothing that three do not
        final String[] wrong = new String[20];
        java.util.Arrays.fill(wrong, "2023-10-19T09:06:02.000Z");

        assertThat(score(events(wrong)).diagnostics()).hasSize(3);
    }

    @Test
    void aRunThatDoesNotKnowItsStreamSaysNothing() {
        // It says nothing rather than measuring against the current time, which would make the judgement
        // while learning disagree with the judgement while serving
        assertThat(new TimesScorer().score(null, new Attempted("XSLTFilter", false, "<records/>",
                new StepResult(events("2023-10-19T09:06:02.000Z"), List.of()), (RecordBoundary) null, null,
                StreamFacts.none()), null)).isEmpty();
    }

    @Test
    void aParsersRecordsAreNotJudgedOnTheirTimes() {
        // A parser's records are shaped like the input, not like the target
        assertThat(new TimesScorer().score(null, new Attempted("DSParser", true, "in",
                new StepResult(events("2023-10-19T09:06:02.000Z"), List.of()), (RecordBoundary) null, null,
                STREAM), null)).isEmpty();
    }

    @Test
    void itIsAdviceUntilAFlowMakesItAGate() {
        // As advice it is shown beside a draft and sent back to the model, but it does not refuse a candidate
        // until it has run on real feeds without false failures
        assertThat(setting(CheckPreset.STANDARD).getState()).isEqualTo(CheckState.ADVICE);
        assertThat(setting(CheckPreset.LENIENT).getState()).isEqualTo(CheckState.ADVICE);
        assertThat(setting(CheckPreset.STRICT).getState()).isEqualTo(CheckState.GATE);
    }

    private static CheckSetting setting(final CheckPreset preset) {
        return preset.settings().stream().filter(s -> s.getCheck() == Check.TIMES).findFirst().orElseThrow();
    }

    @Test
    void anotherSchemaIsNotJudgedUntilTheLearningFlowNamesItsTime() {
        // EventTime/TimeCreated is event-logging's. A schema that keeps its time elsewhere is neither judged
        // on a path it does not have nor refused for lacking it
        assertThat(new TimesScorer().score(null, attempted(sightings("2023-10-19T09:06:02.000Z")), SIGHTINGS))
                .isEmpty();
    }

    @Test
    void aNamedTimeFieldIsJudgedForAnotherSchema() {
        final TimesParameters when = new TimesParameters(null, null, "*[local-name() = 'When']");

        final Score score = new TimesScorer()
                .score(when, attempted(sightings("2026-11-11T09:06:02.000Z", "2023-10-19T09:06:02.000Z")),
                        SIGHTINGS)
                .orElseThrow();

        assertThat(score.value()).isEqualTo(0.5);
        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.startsWith("Record 2's *[local-name() = 'When'] is 2023-10-19"));
    }

    @Test
    void eventLoggingIsJudgedOnItsOwnTimeWithNoFieldNamed() {
        final TargetSchema eventLogging = new TargetSchema(
                new DocRef(XmlSchemaDoc.TYPE, "schema-2", "event-logging v4.1.0"), "event-logging v4.1.0",
                TargetSchema.EVENT_LOGGING, "file://event-logging-v4.1.0.xsd", "4.1.0", "Events", null);

        assertThat(new TimesScorer().score(null, attempted(events("2023-10-19T09:06:02.000Z")), eventLogging)
                .orElseThrow().value()).isEqualTo(0.0);
    }

    @Test
    void aTimeFieldThatIsNotAnXPathIsRefusedWhenTheScorecardIsBuilt() {
        final CheckSetting broken = new CheckSetting(Check.TIMES, CheckState.ADVICE, null,
                new TimesParameters(null, null, "When]"));

        assertThatThrownBy(() -> Scorecard.of(List.of(broken), List.of(new TimesScorer())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("When]");
    }

    private static String sightings(final String... times) {
        final StringBuilder xml = new StringBuilder("<Sightings xmlns=\"urn:sightings:2\">");
        for (final String time : times) {
            xml.append("<Sighting><When>").append(time).append("</When></Sighting>");
        }
        return xml.append("</Sightings>").toString();
    }

    private static Score score(final String events) {
        return new TimesScorer().score(null, attempted(events), null).orElseThrow();
    }

    private static Attempted attempted(final String events) {
        return new Attempted("XSLTFilter", false, "<records/>", new StepResult(events, List.of()),
                (RecordBoundary) null, null, STREAM);
    }

    private static String events(final String... times) {
        final StringBuilder xml = new StringBuilder("<Events xmlns=\"event-logging:3\">");
        for (final String time : times) {
            xml.append("<Event><EventTime><TimeCreated>").append(time)
                    .append("</TimeCreated></EventTime></Event>");
        }
        return xml.append("</Events>").toString();
    }
}
