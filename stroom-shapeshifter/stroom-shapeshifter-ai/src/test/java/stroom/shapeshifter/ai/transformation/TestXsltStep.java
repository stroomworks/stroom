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

package stroom.shapeshifter.ai.transformation;

import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stylesheet is model-authored and therefore untrusted (design §11). Every route out of the process
 * must be shut, and shut loudly: a candidate that reaches for one fails with a diagnostic, not silently
 * with an empty result.
 */
class TestXsltStep {

    private static final String INPUT = "<in>x</in>";

    @Test
    void aPreparedStylesheetIsCompiledOnceAndRunOverRecordAfterRecord() {
        // §12 item 25: the fragment gives its transform one record at a time, so a candidate is run once
        // per record of the stream. Compiling is the expensive half and does not depend on the input, so
        // it is paid for the candidate and not for every record of it.
        final String stylesheet = """
                <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
                  <xsl:template match="/"><out><xsl:value-of select="in"/></out></xsl:template>
                </xsl:stylesheet>""";

        final StepRunner.Prepared prepared = new XsltStep().prepare(stylesheet);

        assertThat(prepared.run("<in>one</in>").output()).contains("<out>one</out>");
        assertThat(prepared.run("<in>two</in>").output())
                .describedAs("the same compiled stylesheet, another record")
                .contains("<out>two</out>");
        assertThat(prepared.run("<in>three</in>").diagnostics())
                .describedAs("and one record's run tells another nothing")
                .isEmpty();
    }

    @Test
    void aStylesheetThatWillNotCompileFailsOnceForEveryRecordItWouldHaveHad() {
        // A stylesheet that will not compile fails the same way however many records it is given, and it
        // says so once rather than once per record.
        final StepRunner.Prepared prepared = new XsltStep().prepare("<xsl:stylesheet");

        final StepResult first = prepared.run("<in>one</in>");
        final StepResult second = prepared.run("<in>two</in>");

        assertThat(first.output()).isNull();
        assertThat(first.diagnostics()).isNotEmpty();
        assertThat(second.diagnostics()).describedAs("the same failure, not a second one")
                .isEqualTo(first.diagnostics());
    }

    @Test
    void transformsTheInput() {
        final StepResult result = new XsltStep().run(stylesheet("<out><xsl:value-of select='in'/></out>"), INPUT);

        assertThat(result.passed()).isTrue();
        assertThat(result.output()).contains("<out>x</out>");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void aStylesheetThatDoesNotCompileFailsWithSaxonsDiagnostic() {
        final StepResult result = new XsltStep().run(stylesheet("<out><xsl:value-of select='in)'/></out>"), INPUT);

        assertThat(result.passed()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.diagnostics()).extracting(StoredError::getSeverity).contains(Severity.FATAL_ERROR);
        assertThat(result.diagnostics().get(0).getLocation()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<out><xsl:copy-of select=\"document('FILE')\"/></out>",
            "<out><xsl:value-of select=\"unparsed-text('FILE')\"/></out>",
    })
    void aStylesheetMayNotReadOutsideItsInput(final String body) throws Exception {
        final Path secret = Files.createTempFile("xslt-step", ".txt");
        try {
            Files.writeString(secret, "<secret/>");
            final String candidate = stylesheet(body.replace("FILE", secret.toUri().toString()));

            final StepResult result = new XsltStep().run(candidate, INPUT);

            assertThat(result.passed()).isFalse();
            assertThat(String.valueOf(result.output())).doesNotContain("secret");
            assertThat(result.diagnostics()).extracting(StoredError::getMessage)
                    .anyMatch(message -> message.contains("may not read external resources"));
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    void aStylesheetMayNotIncludeAnotherOrExpandExternalEntities() throws Exception {
        final Path secret = Files.createTempFile("xslt-step", ".xsl");
        try {
            Files.writeString(secret, stylesheet(""));
            final String include = "<xsl:stylesheet xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='2.0'>"
                                   + "<xsl:include href='" + secret.toUri() + "'/></xsl:stylesheet>";
            assertThat(new XsltStep().run(include, INPUT).passed()).isFalse();

            final String entity = "<!DOCTYPE x [<!ENTITY e SYSTEM '" + secret.toUri() + "'>]>"
                                  + stylesheet("<out>&e;</out>");
            final StepResult result = new XsltStep().run(entity, INPUT);
            assertThat(result.passed()).isFalse();
            assertThat(String.valueOf(result.output())).doesNotContain("xsl:template");
            assertThat(result.diagnostics()).extracting(StoredError::getMessage)
                    .describedAs("a DOCTYPE is refused outright, so no entity can be declared")
                    .anyMatch(message -> message.contains("DOCTYPE"));
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    private static String stylesheet(final String body) {
        return "<xsl:stylesheet xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='2.0'>"
               + "<xsl:template match='/'>" + body + "</xsl:template></xsl:stylesheet>";
    }
}
