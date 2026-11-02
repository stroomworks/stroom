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

import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.XPathAssertion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The edges of the scorers that judge meaning: what they refuse to judge, what they refuse to be
 * configured with, and what they refuse to read.
 */
class TestMeaningScorers {

    private static final String EVENTS = """
            <Events xmlns="event-logging:3">
              <Event><EventDetail><TypeId>t</TypeId><Unknown/></EventDetail></Event>
            </Events>
            """;

    @Test
    void aParsersRecordsAreNotJudgedForMeaning() {
        // A parser over line-delimited XML fragments: its input is markup and its output is records of the
        // input's shape. Neither scorer of meaning applies; the stream-level scorers judge the parser.
        final Attempted parser = new Attempted("DSParser", true, "<a/>\n<a/>\n",
                new StepResult(EVENTS, List.of()));
        final Attempted transform = new Attempted("XSLTFilter", false, "<a/>",
                new StepResult(EVENTS, List.of()));
        final ExtractionQualityParameters quality = new ExtractionQualityParameters(false, List.of());

        assertThat(new ExtractionQualityScorer().score(quality, parser)).isEmpty();
        assertThat(new ExtractionQualityScorer().score(quality, transform)).isPresent();
        assertThat(new BusinessRulesScorer().score(new BusinessRulesParameters(List.of(), true), parser)).isEmpty();
    }

    @Test
    void aPathThatDoesNotCompileIsRefusedWhenTheScorecardIsBuilt() {
        final ScorerSetting rule = new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                new BusinessRulesParameters(List.of(new XPathAssertion("broken", "EventDetail/[")), true));
        final ScorerSetting field = new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 1.0, true,
                new ExtractionQualityParameters(false, List.of("EventSource/User/Id]")));

        assertThatThrownBy(() -> new Scorecard(List.of(rule), List.of(new BusinessRulesScorer())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EventDetail/[");
        assertThatThrownBy(() -> new Scorecard(List.of(field), List.of(new ExtractionQualityScorer())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EventSource/User/Id]");
    }

    @Test
    void outputWithADoctypeIsNotRead() {
        // A model's stylesheet can write a DOCTYPE into its output; the harness never fetches what it names.
        final String withDoctype = "<!DOCTYPE Events SYSTEM \"http://127.0.0.1:1/never.dtd\">\n" + EVENTS;
        assertThat(OutputRecords.parse(withDoctype)).isEmpty();
        assertThat(OutputRecords.parse(EVENTS)).isPresent();
    }
}
