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

package stroom.learningflow.transformation;

import stroom.learningflow.learning.StepResult;
import stroom.util.date.DateUtil;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the stand-ins for `stroom:` functions that let the stand-in XSLT runner run a learned stylesheet the way
/// a node would: the `stroom:` functions the allow-list permits work here too, and the ones it refuses do not.
///
/// Without the stand-ins, every `stroom:` call would fail in tests and succeed on a node, so a stylesheet could
/// pass the scenario tests and behave differently in a pipeline, or fail them for using something it is allowed
/// to use.
class TestStroomStandIns {

    /// The stream's received time, 17 June 2020 08:00 UTC, from which a date with no year takes its year.
    private static final long RECEIVED = DateUtil.parseNormalDateTimeString("2020-06-17T08:00:00.000Z");

    private static final StreamFacts FACTS = new StreamFacts(42L, "DOOR-ACCESS", "Raw Events", RECEIVED,
            Map.of("Environment", "dev", "ResolvedTimeZone", "UTC"));

    @Test
    void theStreamsOwnMetadataIsThere() {
        assertThat(run("""
                <xsl:template match="/records">
                  <Event>
                    <Feed><xsl:value-of select="stroom:feed-name()"/></Feed>
                    <Id><xsl:value-of select="stroom:stream-id()"/></Id>
                    <Env><xsl:value-of select="stroom:meta('Environment')"/></Env>
                    <Zone><xsl:value-of select="stroom:meta('ResolvedTimeZone')"/></Zone>
                  </Event>
                </xsl:template>"""))
                .contains(">DOOR-ACCESS<")
                .contains(">42<")
                .contains(">dev<")
                .contains(">UTC<");
    }

    @Test
    void aDateWithNoYearTakesItFromTheStream() {
        // This is why format-date is on the list at all: a syslog date carries no year, and taking the year
        // from the current time would make the judgement while learning disagree with what is served
        assertThat(run("""
                <xsl:template match="/records">
                  <Event><xsl:value-of select="stroom:format-date('Jun 17 09:06:02', 'MMM dd HH:mm:ss')"/></Event>
                </xsl:template>"""))
                .contains(">2020-06-17T09:06:02.000Z<");
    }

    @Test
    void aDateIsWrittenInThePatternItIsAskedFor() {
        assertThat(run("""
                <xsl:template match="/records">
                  <Event><xsl:value-of select="stroom:format-date('2020-06-17T09:06:02.000Z',
                      'yyyy-MM-dd''T''HH:mm:ss.SSSXX', 'UTC', 'yyyy/MM/dd', 'UTC')"/></Event>
                </xsl:template>"""))
                .contains(">2020/06/17<");
    }

    @Test
    void millisecondsAloneAreWrittenInTheStandardForm() {
        assertThat(run("""
                <xsl:template match="/records">
                  <Event><xsl:value-of select="stroom:format-date('1592380800000')"/></Event>
                </xsl:template>"""))
                .contains(">2020-06-17T08:00:00.000Z<");
    }

    @Test
    void aFunctionTheListRefusesIsNotThereToCall() {
        // The runner agrees with the static check: a refused function is an unknown function here
        final StepResult result = new StandInXsltStep(FACTS).prepare(wrapping("""
                <xsl:template match="/records">
                  <Event><xsl:value-of select="stroom:lookup('USERS', 'bob')"/></Event>
                </xsl:template>""")).run("<records/>");

        assertThat(result.output()).isNull();
        assertThat(result.diagnostics()).isNotEmpty();
    }

    @Test
    void readingAFileIsStillRefusedHoweverTheCheckWent() {
        // The runner does not rely on the static check having run, so the ways out of the process are blocked
        // here too
        final StepResult result = new StandInXsltStep(FACTS).prepare(wrapping("""
                <xsl:template match="/records">
                  <Event><xsl:value-of select="unparsed-text('file:///etc/passwd')"/></Event>
                </xsl:template>""")).run("<records/>");

        assertThat(result.output()).isNull();
    }

    @Test
    void withoutTheStreamAPartialDateFailsRatherThanReadingTheClock() {
        // A year taken from the current time would differ between judging and serving, so a run that does
        // not know its stream fails with a diagnostic instead
        final StepResult result = new StandInXsltStep().prepare(wrapping("""
                <xsl:template match="/records">
                  <Event><xsl:value-of select="stroom:format-date('Jun 17 09:06:02', 'MMM dd HH:mm:ss')"/></Event>
                </xsl:template>""")).run("<records/>");

        assertThat(result.output()).isNull();
        assertThat(result.diagnostics()).isNotEmpty();
    }

    @Test
    void theGapBetweenTheListAndTheHarnessIsNamedRatherThanLeftToBeFound() {
        // These pass the static check but then fail in the stand-in runner, because no stand-in has been
        // written for them. Nothing uses them yet. The test lists them so that the gap is closed deliberately,
        // rather than found by a scenario test that fails for no obvious reason.
        assertThat(StroomStandIns.missing(AiXsltAllowList.hardCoded()))
                .containsExactly("cidr-to-numeric-ip-range", "col-from", "col-to", "dec-to-hex",
                        "decode-url", "encode-url", "hash", "hex-to-dec", "hex-to-string", "ip-in-cidr",
                        "json-to-xml", "line-from", "line-to", "manifest", "meta-attribute", "meta-keys",
                        "meta-stream", "numeric-ip", "parent-id", "parse-date", "parse-uri",
                        "shannon-entropy", "source", "to-date", "to-dateTime");
    }

    private static String run(final String body) {
        final StepResult result = new StandInXsltStep(FACTS).prepare(wrapping(body)).run("<records/>");
        assertThat(result.diagnostics()).describedAs("diagnostics").isEmpty();
        assertThat(result.output()).describedAs("output").isNotNull();
        return result.output();
    }

    private static String wrapping(final String body) {
        return """
                <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:stroom="stroom">
                """ + body + "\n</xsl:stylesheet>";
    }
}
