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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the static check that a learned stylesheet uses only functions and instructions on the allow-list.
///
/// There is a test for each group of refused functions and instructions, because until the list is also
/// enforced when the stylesheet runs, this static check is the only guard.
class TestLearnedStylesheetCheck {

    private static final AiXsltAllowList LIST = AiXsltAllowList.hardCoded();

    @Test
    void everyStylesheetTheScenariosAlreadyUsePasses() throws IOException {
        // The check is no use if it refuses the translations Learning Flow already learns. These are the
        // stylesheets the scenario tests expect, so each must pass unchanged.
        final Path fixtures = Path.of("src/testFixtures/resources/stroom/learningflow/scenario");
        assertThat(fixtures).exists();
        final List<Path> stylesheets;
        try (Stream<Path> files = Files.list(fixtures)) {
            stylesheets = files.filter(p -> p.getFileName().toString().endsWith(".xsl")).sorted().toList();
        }
        assertThat(stylesheets).isNotEmpty();
        for (final Path stylesheet : stylesheets) {
            assertThat(LearnedStylesheetCheck.refusals(Files.readString(stylesheet), LIST))
                    .describedAs(stylesheet.getFileName().toString())
                    .isEmpty();
        }
    }

    @Test
    void aPlainTranslationPasses() {
        assertThat(LearnedStylesheetCheck.refusals(wrapping("""
                <xsl:template match="record">
                  <Event>
                    <xsl:value-of select="concat(normalize-space(data[@name='who']/@value), '!')"/>
                    <xsl:if test="matches(@id, '^[0-9]+$')">
                      <Id><xsl:value-of select="xs:integer(@id)"/></Id>
                    </xsl:if>
                  </Event>
                </xsl:template>"""), LIST)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "unparsed-text('file:///etc/passwd')",
            "unparsed-text-lines('file:///etc/passwd')",
            "doc('file:///etc/passwd')",
            "collection('file:///etc')",
            "uri-collection('file:///etc')",
            "json-doc('file:///etc/passwd')",
            "environment-variable('AWS_SECRET_ACCESS_KEY')",
            "available-environment-variables()",
            "current-dateTime()",
            "generate-id()"})
    void theStandardRoutesOffTheRecordAreRefused(final String expression) {
        assertThat(LearnedStylesheetCheck.refusals(
                wrapping("<xsl:template match=\"record\"><xsl:value-of select=\"" + expression
                         + "\"/></xsl:template>"), LIST))
                .describedAs(expression)
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "stroom:http-call('https://example.com', '', '', '', '')",
            "stroom:fetch-json('https://example.com')",
            "stroom:send-email('a', 'b', 'c', 'd')",
            "stroom:ask-ai('tell me')",
            "stroom:host-name('example.com')",
            "stroom:lookup('MAP', 'key')",
            "stroom:bitmap-lookup('MAP', 'key')",
            "stroom:dictionary('WORDS')",
            "stroom:meta-stream-for-id('1')",
            "stroom:log('ERROR', 'drop this record')",
            "stroom:put('k', 'v')",
            "stroom:get('k')",
            "stroom:random()",
            "stroom:current-time()"})
    void theStroomFunctionsBeyondThisStreamAreRefused(final String expression) {
        assertThat(LearnedStylesheetCheck.refusals(
                wrapping("<xsl:template match=\"record\"><xsl:value-of select=\"" + expression
                         + "\"/></xsl:template>"), LIST))
                .describedAs(expression)
                .isNotEmpty();
    }

    @Test
    void theStroomFunctionsOfThisStreamAreAllowed() {
        // Translations need these to take a missing year from the stream and to read the zone it was written in
        assertThat(LearnedStylesheetCheck.refusals(wrapping("""
                <xsl:template match="record">
                  <xsl:value-of select="stroom:format-date(@when, 'MMM dd HH:mm:ss')"/>
                  <xsl:value-of select="stroom:meta('ResolvedTimeZone')"/>
                  <xsl:value-of select="stroom:feed-name()"/>
                </xsl:template>"""), LIST)).isEmpty();
    }

    @Test
    void writingAFileIsRefused() {
        assertThat(LearnedStylesheetCheck.refusals(wrapping("""
                <xsl:template match="record">
                  <xsl:result-document href="file:///tmp/out.xml">
                    <Event/>
                  </xsl:result-document>
                </xsl:template>"""), LIST))
                .anyMatch(refusal -> refusal.contains("result-document") && refusal.contains("writes files"));
    }

    @Test
    void readingAnotherStylesheetIsRefused() {
        assertThat(LearnedStylesheetCheck.refusals(
                wrapping("<xsl:include href=\"file:///tmp/other.xsl\"/>"), LIST))
                .anyMatch(refusal -> refusal.contains("include"));
    }

    @Test
    void anXPathBuiltWhileItRunsIsRefused() {
        assertThat(LearnedStylesheetCheck.refusals(
                wrapping("<xsl:template match=\"record\"><xsl:evaluate xpath=\"$x\"/></xsl:template>"), LIST))
                .anyMatch(refusal -> refusal.contains("evaluate"));
    }

    @Test
    void referringToAFunctionByNameAndArityIsRefused() {
        // A function reference defeats a check that only looks for named calls, because the call never
        // appears as one
        assertThat(LearnedStylesheetCheck.refusals(wrapping("""
                <xsl:template match="record">
                  <xsl:variable name="f" select="stroom:http-call#5"/>
                  <xsl:value-of select="$f('https://example.com', '', '', '', '')"/>
                </xsl:template>"""), LIST))
                .anyMatch(refusal -> refusal.contains("name and arity"));
    }

    @Test
    void lookingUpAFunctionByNameIsRefused() {
        assertThat(LearnedStylesheetCheck.refusals(wrapping("""
                <xsl:template match="record">
                  <xsl:value-of select="function-lookup(QName('stroom', 'http-call'), 5)('a','b','c','d','e')"/>
                </xsl:template>"""), LIST))
                .anyMatch(refusal -> refusal.contains("function-lookup"));
    }

    @Test
    void aCallInAnAttributeValueTemplateIsChecked() {
        // An attribute value template on a literal result element is the easiest place to hide a call
        assertThat(LearnedStylesheetCheck.refusals(wrapping("""
                <xsl:template match="record">
                  <Event id="{unparsed-text('file:///etc/passwd')}"/>
                </xsl:template>"""), LIST))
                .anyMatch(refusal -> refusal.contains("unparsed-text"));
    }

    @Test
    void aNameInsideAStringLiteralIsNotACall() {
        // 'stroom:http-call(' inside a literal is text, not a call, and refusing it would be wrong
        assertThat(LearnedStylesheetCheck.refusals(wrapping("""
                <xsl:template match="record">
                  <xsl:value-of select="concat('stroom:http-call(', @id, ')')"/>
                </xsl:template>"""), LIST)).isEmpty();
    }

    @Test
    void anUndeclaredPrefixIsRefusedRatherThanPassed() {
        assertThat(LearnedStylesheetCheck.refusals(
                wrapping("<xsl:template match=\"record\"><xsl:value-of select=\"ext:go()\"/></xsl:template>"),
                LIST))
                .anyMatch(refusal -> refusal.contains("does not declare"));
    }

    @Test
    void aStylesheetThatDoesNotParseIsRefused() {
        assertThat(LearnedStylesheetCheck.refusals("<xsl:stylesheet><oops></xsl:stylesheet>", LIST))
                .hasSize(1)
                .allMatch(refusal -> refusal.contains("could not be read"));
        assertThat(LearnedStylesheetCheck.refusals("", LIST)).hasSize(1);
        assertThat(LearnedStylesheetCheck.refusals(null, LIST)).hasSize(1);
    }

    @Test
    void aFunctionTheStylesheetDeclaresItselfIsAllowed() {
        assertThat(LearnedStylesheetCheck.refusals("""
                <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:my="urn:mine">
                  <xsl:function name="my:tidy">
                    <xsl:param name="v"/>
                    <xsl:value-of select="normalize-space($v)"/>
                  </xsl:function>
                  <xsl:template match="record">
                    <xsl:value-of select="my:tidy(@value)"/>
                  </xsl:template>
                </xsl:stylesheet>""", LIST)).isEmpty();
    }

    @Test
    void theKeywordsAndKindTestsOfXPathAreNotReadAsCalls() {
        assertThat(LearnedStylesheetCheck.refusals(wrapping("""
                <xsl:template match="record">
                  <xsl:value-of select="if (@id) then 'y' else 'n'"/>
                  <xsl:value-of select="count(node()) + count(text()) + count(*/element())"/>
                  <xsl:value-of select="for $d in data return string($d)"/>
                </xsl:template>"""), LIST)).isEmpty();
    }

    @Test
    void literalsAreBlankedWithoutMovingAnythingElse() {
        assertThat(LearnedStylesheetCheck.withoutLiterals("concat('a(', $b)"))
                .hasSameSizeAs("concat('a(', $b)")
                .isEqualTo("concat('  ', $b)");
    }

    // A known gap found by a security review: an apostrophe inside an XPath comment opens a "literal" that
    // hides the real call after it from the check. Disabled until the check is fixed.
    @Test
    @Disabled("Security review finding 1: fails until Track 3 task 1 makes the check sound")
    void aCallHiddenBetweenCommentsHoldingQuotesIsRefused() {
        assertRefusesHttpCall(wrapping("""
                <xsl:template match="/">
                  <xsl:value-of select="(: ' :) stroom:http-call('http://x') (: ' :)"/>
                </xsl:template>"""));
    }

    // A known gap found by a security review: a declared function is matched on its lexical name only, so a
    // stylesheet that declares its own `stroom:http-call` makes the check pass the real one. Disabled until
    // the check is fixed.
    @Test
    @Disabled("Security review N1: fails until Track 3 task 1 keys declared functions by namespace")
    void aDeclaredFunctionDoesNotExcuseACallToTheRealOneOfTheSameName() {
        assertRefusesHttpCall(wrapping("""
                <xsl:function name="stroom:http-call">
                  <xsl:param name="u"/>
                  <xsl:sequence select="$u"/>
                </xsl:function>
                <xsl:template match="/">
                  <xsl:value-of select="stroom:http-call('http://x')"/>
                </xsl:template>"""));
    }

    // A known gap found by a security review: `for-each-source` holds XPath but is not scanned. Disabled
    // until the check is fixed.
    @Test
    @Disabled("Security review N1: fails until Track 3 task 1 scans every attribute that holds XPath")
    void aCallInAMergeSourceIsRefused() {
        assertRefusesHttpCall(wrapping("""
                <xsl:template match="/">
                  <xsl:merge>
                    <xsl:merge-source for-each-source="stroom:http-call('http://x')" select="item">
                      <xsl:merge-key select="@id"/>
                    </xsl:merge-source>
                    <xsl:merge-action><xsl:value-of select="1"/></xsl:merge-action>
                  </xsl:merge>
                </xsl:template>"""));
    }

    // A known gap found by a security review: `use-when` holds XPath that runs at compile time, and is not
    // scanned. Disabled until the check is fixed.
    @Test
    @Disabled("Security review N1: fails until Track 3 task 1 scans every attribute that holds XPath")
    void aCallInUseWhenIsRefused() {
        assertRefusesHttpCall(wrapping("""
                <xsl:template match="/">
                  <xsl:value-of use-when="stroom:http-call('http://x') = ''" select="1"/>
                </xsl:template>"""));
    }

    @Test
    void aPlainCallToABannedStroomFunctionIsRefused() {
        // The control for the four disabled tests above: without a trick, the same call is refused.
        assertRefusesHttpCall(wrapping("""
                <xsl:template match="/">
                  <xsl:value-of select="stroom:http-call('http://x')"/>
                </xsl:template>"""));
    }

    private static void assertRefusesHttpCall(final String stylesheet) {
        assertThat(LearnedStylesheetCheck.refusals(stylesheet, LIST))
                .describedAs("the check must refuse the call to stroom:http-call")
                .anyMatch(refusal -> refusal.contains("stroom:http-call"));
    }

    /// Wraps a fragment in a stylesheet that declares the prefixes the tests use.
    ///
    /// @param body The stylesheet fragment.
    /// @return A stylesheet holding the fragment.
    private static String wrapping(final String body) {
        return """
                <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:stroom="stroom">
                """ + body + "\n</xsl:stylesheet>";
    }
}
