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
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckSettings;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.RecordBoundary;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the permitted check, which statically checks what a candidate stylesheet uses against the list of
/// functions and instructions a learned stylesheet may use. It runs as an ordinary check, so a scorecard
/// refuses a candidate that uses something it may not, and the reason goes back to the model.
class TestPermittedScorer {

    private static final String ALLOWED = """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                    xmlns:stroom="stroom">
              <xsl:template match="record">
                <Event><xsl:value-of select="stroom:format-date(@when, 'MMM dd HH:mm:ss')"/></Event>
              </xsl:template>
            </xsl:stylesheet>""";

    private static final String REFUSED = """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                    xmlns:stroom="stroom">
              <xsl:template match="record">
                <Event><xsl:value-of select="stroom:lookup('USERS', @who)"/></Event>
              </xsl:template>
            </xsl:stylesheet>""";

    @Test
    void aStylesheetThatUsesOnlyWhatItMayScoresFull() {
        assertThat(new PermittedScorer().score(null, attempted(ALLOWED), null).orElseThrow().value())
                .isEqualTo(1.0);
    }

    @Test
    void aStylesheetThatReachesBeyondTheStreamScoresNothingAndSaysWhy() {
        final Score score = new PermittedScorer().score(null, attempted(REFUSED), null).orElseThrow();

        assertThat(score.value()).isEqualTo(0.0);
        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("stroom:lookup")
                                     && message.contains("beyond this stream"));
    }

    @Test
    void thereIsNothingToSayAboutAStepThatWroteNoStylesheet() {
        // A step choosing the chain has no configuration, and a parser's configuration is a Data Splitter,
        // not XSLT
        assertThat(new PermittedScorer().score(null, new Attempted(
                "XSLTFilter", false, "in", new StepResult("<Events/>", List.of()), null, null), null))
                .isEmpty();
        assertThat(new PermittedScorer().score(null, new Attempted(
                "DSParser", true, "in", new StepResult("<records/>", List.of()), null, "<dataSplitter/>"), null))
                .isEmpty();
    }

    @Test
    void aScorecardRefusesACandidateThatUsesSomethingItMayNot() {
        // The check end to end: as a gate a Learning Flow cannot turn off, it refuses the candidate
        final Scorecard scorecard = Scorecard.of(
                List.of(CheckSetting.of(Check.PERMITTED, CheckState.GATE)),
                List.of(new PermittedScorer()));

        assertThat(scorecard.judge(attempted(ALLOWED)).gatesPassed()).isTrue();
        assertThat(scorecard.judge(attempted(REFUSED)).gatesPassed()).isFalse();
    }

    @Test
    void aLearningFlowMayNotTurnTheCheckDown() {
        // It is a safety check, not a quality one, because on an automatic Learning Flow nobody reads a learned
        // stylesheet before it runs
        assertThat(CheckSettings.problems(List.of(CheckSetting.of(Check.PERMITTED, CheckState.ADVICE))))
                .contains("The permitted check is always a gate.");
        assertThat(CheckSettings.problems(List.of(CheckSetting.of(Check.PERMITTED, CheckState.OFF))))
                .contains("The permitted check is always a gate.");
        assertThat(CheckSettings.problems(CheckSettings.defaults())).isEmpty();
    }

    private static Attempted attempted(final String stylesheet) {
        return new Attempted("XSLTFilter", false, "<records/>",
                new StepResult("<Events/>", List.of()), (RecordBoundary) null, stylesheet);
    }
}
