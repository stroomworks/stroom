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
import stroom.learningflow.transformation.StreamFacts;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Whether a translation has this stream's own data written into it (Track 1 task 3).
///
/// A translation is learned from one stream and serves every stream after it, so a value copied out of
/// the stream it was learned on is wrong for all the rest — and wrong quietly, since the translation
/// still compiles, still validates and still carries real content.
class TestLiteralsScorer {

    private static final StreamFacts STREAM = new StreamFacts(1L, "NETFILTER", "Raw Events", 0L,
            Map.of("Environment", "Production", "System", "Firewall"));

    @Test
    void aTranslationThatReadsEverythingFromTheRecordPasses() {
        assertThat(score("""
                <xsl:template match="record">
                  <Event>
                    <Feed><xsl:value-of select="stroom:feed-name()"/></Feed>
                    <When><xsl:value-of select="stroom:format-date(@when, 'MMM dd HH:mm:ss')"/></When>
                  </Event>
                </xsl:template>""").value()).isEqualTo(1.0);
    }

    @Test
    void aYearWrittenIntoTheTranslationIsReported() {
        // The dev instance's fault: a syslog date with no year, and a year written into the stylesheet
        final Score score = score("""
                <xsl:template match="record">
                  <When><xsl:value-of select="concat('2023', '-', @when)"/></When>
                </xsl:template>""");

        assertThat(score.value()).isEqualTo(0.0);
        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("2023")
                                     && message.contains("stroom:format-date"));
    }

    @Test
    void aWholeDateWrittenIntoTheTranslationIsReported() {
        final Score score = score("""
                <xsl:template match="record">
                  <When>2023-10-19T09:06:02.000Z</When>
                </xsl:template>""");

        assertThat(score.value()).isEqualTo(0.0);
        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("2023-10-19T09:06:02.000Z")
                                     && message.contains("every event it makes carries that date"));
    }

    @Test
    void thisStreamsOwnHeaderWrittenIntoTheOutputIsReported() {
        // The sharpest form of the fault: the value is right for the stream it was learned on and wrong
        // for every other
        final Score score = score("""
                <xsl:template match="record">
                  <Event><System><Environment>Production</Environment></System></Event>
                </xsl:template>""");

        assertThat(score.value()).isEqualTo(0.0);
        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("Production")
                                     && message.contains("this stream's Environment")
                                     && message.contains("stroom:meta('Environment')"));
    }

    @Test
    void theFeedNameIsPointedAtItsOwnFunction() {
        final Score score = score("""
                <xsl:template match="record">
                  <Event><Feed>NETFILTER</Feed></Event>
                </xsl:template>""");

        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("stroom:feed-name()"));
    }

    @Test
    void aTimeZoneWrittenIntoTheTranslationIsReported() {
        final Score score = score("""
                <xsl:template match="record">
                  <When><xsl:value-of select="stroom:format-date(@when, 'MMM dd', 'Europe/London')"/></When>
                </xsl:template>""");

        assertThat(score.diagnostics()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("Europe/London")
                                     && message.contains("ResolvedTimeZone"));
    }

    @Test
    void aDatePatternIsNotADate() {
        // 'yyyy-MM-dd' is how a date is read, not a date written in: refusing it would refuse the fix
        assertThat(score("""
                <xsl:template match="record">
                  <When><xsl:value-of select="stroom:format-date(@when, 'yyyy-MM-dd''T''HH:mm:ss.SSSXX')"/></When>
                </xsl:template>""").value()).isEqualTo(1.0);
    }

    @Test
    void anIsoInstantEndingInZIsNotATimeZone() {
        // A translation writing a proper instant is doing the right thing
        assertThat(score("""
                <xsl:template match="record">
                  <When><xsl:value-of select="concat(@when, 'Z')"/></When>
                </xsl:template>""").value()).isEqualTo(1.0);
    }

    @Test
    void shortValuesAreLeftAlone() {
        // Two characters match something by accident too easily to be worth saying
        assertThat(score("""
                <xsl:template match="record">
                  <Event><Id>OK</Id></Event>
                </xsl:template>""").value()).isEqualTo(1.0);
    }

    @Test
    void itIsAdviceUntilItHasRunOnRealFeeds() {
        // Stage 0b group 4, option C for literals: advice first, then a gate
        assertThat(setting(CheckPreset.STANDARD).getState()).isEqualTo(CheckState.ADVICE);
        assertThat(setting(CheckPreset.STRICT).getState()).isEqualTo(CheckState.GATE);
    }

    private static CheckSetting setting(final CheckPreset preset) {
        return preset.settings().stream().filter(s -> s.getCheck() == Check.LITERALS).findFirst()
                .orElseThrow();
    }

    private static Score score(final String body) {
        return new LiteralsScorer().score(null, new Attempted("XSLTFilter", false, "<records/>",
                new StepResult("<Events/>", List.of()), (RecordBoundary) null, wrapping(body), STREAM), null)
                .orElseThrow();
    }

    private static String wrapping(final String body) {
        return """
                <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:stroom="stroom">
                """ + body + "\n</xsl:stylesheet>";
    }
}
