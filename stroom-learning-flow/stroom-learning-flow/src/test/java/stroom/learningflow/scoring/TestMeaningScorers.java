/*
 * Copyright 2016-2026 Crown Copyright
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
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.XPathAssertion;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the edge cases of the scorers that judge meaning (extraction quality and business rules): what they
/// decline to judge, what configuration they refuse, and what output they refuse to read.
class TestMeaningScorers {

    private static final String EVENTS = """
            <Events xmlns="event-logging:3">
              <Event><EventDetail><TypeId>t</TypeId><Unknown/></EventDetail></Event>
            </Events>
            """;

    @Test
    void aParsersRecordsAreNotJudgedForMeaning() {
        // A parser over line-delimited XML fragments: its input is markup and its output is records shaped like
        // the input. Neither scorer of meaning applies to it; the stream-level scorers judge a parser instead.
        final Attempted parser = new Attempted("DSParser", true, "<a/>\n<a/>\n",
                new StepResult(EVENTS, List.of()));
        final Attempted transform = new Attempted("XSLTFilter", false, "<a/>",
                new StepResult(EVENTS, List.of()));
        final ExtractionQualityParameters quality = new ExtractionQualityParameters(false, List.of());

        assertThat(new ExtractionQualityScorer().score(quality, parser, null)).isEmpty();
        assertThat(new ExtractionQualityScorer().score(quality, transform, null)).isPresent();
        assertThat(new BusinessRulesScorer().score(new BusinessRulesParameters(List.of(), true), parser, null))
                .isEmpty();
    }

    @Test
    void aPathThatDoesNotCompileIsRefusedWhenTheScorecardIsBuilt() {
        final CheckSetting rule = new CheckSetting(Check.RULES, CheckState.ADVICE, 1.0,
                new BusinessRulesParameters(List.of(new XPathAssertion("broken", "EventDetail/[")), true));
        final CheckSetting field = new CheckSetting(Check.QUALITY, CheckState.GATE, 1.0,
                new ExtractionQualityParameters(false, List.of("EventSource/User/Id]")));

        assertThatThrownBy(() -> Scorecard.of(List.of(rule), List.of(new BusinessRulesScorer())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EventDetail/[");
        assertThatThrownBy(() -> Scorecard.of(List.of(field), List.of(new ExtractionQualityScorer())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EventSource/User/Id]");
    }

    // A schema that is not event-logging, with its own element called Data, which is meaningful there
    private static final TargetSchema SIGHTINGS = new TargetSchema(
            new DocRef(XmlSchemaDoc.TYPE, "schema-1", "sightings v2.4"), "sightings v2.4",
            "urn:sightings:2", "file://sightings-v2.4.xsd", "2.4", "Sightings", null);
    private static final String SIGHTINGS_OUTPUT = """
            <Sightings xmlns="urn:sightings:2">
              <Sighting><Species>heron</Species><Data>wet</Data></Sighting>
              <Sighting><Species>egret</Species><Data>dry</Data></Sighting>
            </Sightings>
            """;
    private static final String EVENTS_WITH_DATA = """
            <Events xmlns="event-logging:3">
              <Event><EventDetail><TypeId>t</TypeId><Authenticate><Data Name="x" Value="1"/></Authenticate>
              </EventDetail></Event>
            </Events>
            """;

    @Test
    void untypedDataAndUnknownAreEventLoggingsAndAreNotMeasuredForAnotherSchema() {
        // Data is event-logging's untyped element, and Unknown its branch for "no decision". A schema with its
        // own element called Data is not marked down for using it, and, with no fields required, extraction
        // quality has nothing to measure there, so it does not run
        final Attempted transform = new Attempted("XSLTFilter", false, "<a/>",
                new StepResult(SIGHTINGS_OUTPUT, List.of()));
        final ExtractionQualityParameters nothingRequired = new ExtractionQualityParameters(false, List.of());

        assertThat(new ExtractionQualityScorer().score(nothingRequired, transform, SIGHTINGS)).isEmpty();
    }

    @Test
    void aRequiredFieldIsStillMeasuredForAnotherSchema() {
        final Attempted transform = new Attempted("XSLTFilter", false, "<a/>",
                new StepResult(SIGHTINGS_OUTPUT.replace("<Species>egret</Species>", ""), List.of()));
        final ExtractionQualityParameters species = new ExtractionQualityParameters(false,
                List.of("*[local-name() = 'Species']"));

        final Score score = new ExtractionQualityScorer().score(species, transform, SIGHTINGS).orElseThrow();

        // Only the required field is measured: one record of two has it, and nothing is said about Data
        assertThat(score.value()).isEqualTo(0.5);
        assertThat(score.failingRecords()).containsExactly(1);
        assertThat(score.diagnostics()).noneMatch(error -> error.getMessage().contains("Data"));
    }

    @Test
    void untypedDataIsStillMeasuredForEventLoggingAndForNoTarget() {
        final Attempted transform = new Attempted("XSLTFilter", false, "<a/>",
                new StepResult(EVENTS_WITH_DATA, List.of()));
        final ExtractionQualityParameters quality = new ExtractionQualityParameters(false, List.of());
        final TargetSchema eventLogging = new TargetSchema(
                new DocRef(XmlSchemaDoc.TYPE, "schema-2", "event-logging v4.1.0"), "event-logging v4.1.0",
                TargetSchema.EVENT_LOGGING, "file://event-logging-v4.1.0.xsd", "4.1.0", "Events", null);

        for (final TargetSchema target : new TargetSchema[]{null, eventLogging}) {
            final Score score = new ExtractionQualityScorer().score(quality, transform, target).orElseThrow();
            assertThat(score.value()).isLessThan(1.0);
            assertThat(score.diagnostics()).anyMatch(error -> error.getMessage().contains("untyped Data"));
        }
    }

    @Test
    void outputWithADoctypeIsNotRead() {
        // A model's stylesheet can write a DOCTYPE into its output, so output with one is not read at all, and
        // what the DOCTYPE names is never fetched.
        final String withDoctype = "<!DOCTYPE Events SYSTEM \"http://127.0.0.1:1/never.dtd\">\n" + EVENTS;
        assertThat(OutputRecords.parse(withDoctype)).isEmpty();
        assertThat(OutputRecords.parse(EVENTS)).isPresent();
    }
}
