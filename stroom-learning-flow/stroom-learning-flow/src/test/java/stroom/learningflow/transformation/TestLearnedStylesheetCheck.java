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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The static check of decision N1: a learned stylesheet may use only what is on the allow-list.
///
/// There is a test here for each refused group of N1's table, because the list is the only guard until
/// Track 2 step 6 adds run-time enforcement.
class TestLearnedStylesheetCheck {

    private static final AiXsltAllowList LIST = AiXsltAllowList.hardCoded();

    @Test
    void everyStylesheetTheScenariosAlreadyUsePasses() throws IOException {
        // The check is no use if it refuses the translations the module already learns: these are the
        // stylesheets the scenario suite holds the engine to, so each must pass as it stands.
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
        // What Track 1 task 3 needs: the missing year from the stream, and the zone it was received in
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
        // The route that defeats a check of named calls: the call never appears as one
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
        // A literal result element's attributes are the easiest place to hide a call
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

    /// Puts a fragment in a stylesheet that declares the prefixes the tests use.
    ///
    /// @param body The fragment.
    /// @return A stylesheet holding it.
    private static String wrapping(final String body) {
        return """
                <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:stroom="stroom">
                """ + body + "\n</xsl:stylesheet>";
    }
}
