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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.ai.extraction.NodeFixture;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.util.shared.ErrorType;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// The bar is per record (design 04 §3, A55): each scorer that judges records one by one says which
/// fell short, zero-based in output order, and a verdict's failing records are the union over its gates.
class TestPerRecordScoring {

    private static final String EVENTS = """
            <Events xmlns="event-logging:3">
              <Event><EventDetail><TypeId>a</TypeId>
                <Authenticate><User><Id>jim</Id></User></Authenticate></EventDetail></Event>
              <Event><EventDetail><TypeId>b</TypeId><Unknown/></EventDetail></Event>
              <Event><EventDetail><TypeId>c</TypeId>
                <Authenticate><User><Id>bob</Id></User></Authenticate></EventDetail></Event>
              <Event><EventDetail><TypeId>d</TypeId><Authenticate/></EventDetail></Event>
            </Events>
            """;

    private static final Attempted TRANSFORM = new Attempted("XSLTFilter", false, "<a/>",
            new StepResult(EVENTS, List.of()));

    @Test
    void extractionQualityNamesTheRecordThatSaysUnknownAndTheOnesMissingARequiredField() {
        final ExtractionQualityParameters quality = new ExtractionQualityParameters(false,
                List.of("EventDetail/Authenticate/User/Id"));

        final Score score = new ExtractionQualityScorer().score(quality, TRANSFORM).orElseThrow();

        assertThat(score.failingRecords()).containsExactlyInAnyOrder(1, 3);
    }

    @Test
    void extractionQualityLaysNothingAtARecordWhereUnknownIsAllowedAndNothingIsRequired() {
        final ExtractionQualityParameters quality = new ExtractionQualityParameters(true, List.of());

        final Score score = new ExtractionQualityScorer().score(quality, TRANSFORM).orElseThrow();

        assertThat(score.failingRecords()).isEmpty();
    }

    @Test
    void businessRulesNameTheRecordsAnAssertionFailsOn() {
        final BusinessRulesParameters rules = new BusinessRulesParameters(List.of(
                new XPathAssertion("a user", "EventDetail/Authenticate/User/Id"),
                new XPathAssertion("a type", "EventDetail/TypeId")), false);

        final Score score = new BusinessRulesScorer().score(rules, TRANSFORM).orElseThrow();

        assertThat(score.failingRecords()).containsExactlyInAnyOrder(1, 3);
        assertThat(score.value()).isEqualTo(0.5);
    }

    @Test
    void aTransformsOwnMessagesCountAgainstTheScoreButNameNoRecord() {
        final Attempted noisy = new Attempted("XSLTFilter", false, "<a/>", new StepResult(EVENTS, List.of(
                new StoredError(Severity.WARNING, null, null, "no such field", ErrorType.CODE))));
        final BusinessRulesParameters rules = new BusinessRulesParameters(List.of(), true);

        final Score score = new BusinessRulesScorer().score(rules, noisy).orElseThrow();

        assertThat(score.value()).isEqualTo(0.75);
        assertThat(score.failingRecords()).isEmpty();
    }

    /// The union over the gates and no more: a scorer that is not a gate may fail a record without it
    /// falling short of the bar, which is a document choosing where its bar is.
    @Test
    void aVerdictsFailingRecordsAreTheUnionOverItsGates() {
        final Score gateA = new Score(ScorerType.SCHEMA_CONFORMANCE, 0.5, List.of(), Set.of(1));
        final Score gateB = new Score(ScorerType.EXTRACTION_QUALITY, 0.5, List.of(), Set.of(3));
        final Score ungated = new Score(ScorerType.BUSINESS_RULES, 0.5, List.of(), Set.of(0));
        final Verdict verdict = new Verdict(List.of(
                new Judgement(setting(ScorerType.SCHEMA_CONFORMANCE, true), gateA),
                new Judgement(setting(ScorerType.EXTRACTION_QUALITY, true), gateB),
                new Judgement(setting(ScorerType.BUSINESS_RULES, false), ungated)), 0.5);

        assertThat(verdict.failingRecords()).containsExactly(1, 3);
    }

    @Test
    void aScoreWithNoRecordLevelAccountNamesNoRecord() {
        assertThat(new Score(ScorerType.COMPILE, 1.0, List.of()).failingRecords()).isEmpty();
    }

    /// The validator judges one top-level element at a time, counting from one; the records it names are
    /// handed back counting from zero, as the output has them.
    @Test
    void schemaConformanceNamesTheRecordsThatDidNotValidate() {
        final String events = """
                <Events xmlns="event-logging:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
                  %1$s
                  <Event><EventDetail><TypeId>thin</TypeId></EventDetail></Event>
                  %1$s
                  <Event><EventDetail><TypeId>thin</TypeId></EventDetail></Event>
                </Events>
                """.formatted("""
                <Event>
                  <EventTime><TimeCreated>2020-06-17T08:00:00.000Z</TimeCreated></EventTime>
                  <EventSource><System><Name>Door</Name><Environment>Test</Environment></System>
                    <Generator>CSV</Generator><Device><Name>d</Name></Device><User><Id>jim</Id></User></EventSource>
                  <EventDetail><TypeId>logon</TypeId><Authenticate><Action>Logon</Action><User><Id>jim</Id></User>
                    </Authenticate></EventDetail>
                </Event>""");
        final Attempted transform = new Attempted("XSLTFilter", false, "<a/>", new StepResult(events, List.of()));

        final Score score = new NodeFixture().schemaConformanceScorer()
                .score(new SchemaConformanceParameters(NodeFixture.EVENTS_SCHEMA_GROUP), transform)
                .orElseThrow();

        assertThat(score.value()).isEqualTo(0.5);
        assertThat(score.failingRecords()).containsExactlyInAnyOrder(1, 3);
    }

    private static ScorerSetting setting(final ScorerType type, final boolean gate) {
        return new ScorerSetting(type, 1.0, 1.0, gate, null);
    }
}
