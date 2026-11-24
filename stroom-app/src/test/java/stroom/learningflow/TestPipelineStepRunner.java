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

package stroom.learningflow;

import stroom.learningflow.element.PipelineStepRunner;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.transformation.XsltStep;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.task.api.TaskContextFactory;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// On a node, a learned stylesheet is judged and served by the real pipeline: the real XSLTFilter and
/// the real XSLT pool, confined by `XsltConfig.secureMode` rather than by the module's own runner. These
/// tests hold that path to what the security review requires of it (Track 3 tasks 1 and 2).
///
/// They run with the default configuration, in which `secureMode` is on. What should happen when it is
/// off is decision S-D8.
class TestPipelineStepRunner extends AbstractProcessIntegrationTest {

    private static final String INPUT = "<in>x</in>";
    private static final String CANARY = "CANARY-LEARNING-FLOW";
    private static final String LOOPBACK = "127.0.0.1";

    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<HeadlessCapture> headlessCaptureProvider;
    @Inject
    private Provider<InjectedCode> injectedCodeProvider;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private TaskContextFactory taskContextFactory;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;

    @Test
    void aPlainStylesheetRunsOnTheNodePath() {
        // The control for the tests below: without it, a node path that failed for some other reason
        // would make every refusal look like a pass.
        final StepResult result = run(stylesheet("<out><xsl:value-of select=\"concat('read ', in)\"/></out>"));

        assertThat(result.output()).contains("<out>read x</out>");
    }

    // Security review finding 2 (23 November 2026): parse-xml() expanded an external entity, reading a
    // local file, on the node path as well as in the module's runner.
    @ParameterizedTest
    @ValueSource(strings = {
            "<!DOCTYPE d [<!ENTITY x SYSTEM 'FILE'>]><d>&x;</d>",
            "<!DOCTYPE d [<!ENTITY % p SYSTEM 'FILE'> %p;]><d/>",
            "<!DOCTYPE d SYSTEM 'FILE'><d/>",
    })
    void parseXmlMayNotReadAFileThroughADoctype(final String document) throws IOException {
        final Path secret = canary();
        try {
            final String parsed = escapedForAnAttribute(document.replace("FILE", secret.toUri().toString()));

            final StepResult result = run(stylesheet(
                    "<out><xsl:value-of select=\"parse-xml('" + parsed + "')\"/></out>"));

            assertThat(String.valueOf(result.output())).doesNotContain(CANARY);
            assertThat(result.diagnostics()).describedAs("and the model is told why").isNotEmpty();
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    // Security review finding 1 (23 November 2026): a stylesheet that got past the static check could
    // read any file the Stroom process can. In secure mode the pool refuses every such read.
    @ParameterizedTest
    @ValueSource(strings = {
            "unparsed-text('FILE')",
            "string(doc('FILE'))",
            "string(document('FILE'))",
            "string(json-doc('FILE'))",
            "string-join(unparsed-text-lines('FILE'))",
    })
    void aStylesheetMayNotReadAFile(final String expression) throws IOException {
        final Path secret = canary();
        try {
            final String select = expression.replace("FILE", secret.toUri().toString());

            final StepResult result = run(stylesheet("<out><xsl:value-of select=\"" + select + "\"/></out>"));

            assertThat(String.valueOf(result.output())).doesNotContain(CANARY);
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    // Security review finding 1 and N1 (24 November 2026): in the pool, every Java-registered stroom:
    // function is callable, including those the Smart Pipelines list refuses (http-call, send-email,
    // lookup ...). The static check is the only thing between a learned stylesheet and them, and it can
    // be bypassed. host-address() stands in for them here because it is refused by the list, harmless and
    // observable: given localhost, it answers with the loopback address.
    @Test
    @Disabled("Security review N1: fails until Track 3 task 1 compiles learned stylesheets with only the "
              + "allowed stroom: functions")
    void aStroomFunctionTheListRefusesCannotBeCalledOnANode() {
        final StepResult result = run(stylesheet(
                "<out xmlns:stroom='stroom'><xsl:value-of select=\"stroom:host-address('localhost')\"/></out>"));

        assertThat(String.valueOf(result.output())).doesNotContain(LOOPBACK);
        assertThat(result.diagnostics()).describedAs("and the model is told why").isNotEmpty();
    }

    private StepResult run(final String candidate) {
        final StepResult[] result = new StepResult[1];
        pipelineScopeRunnable.scopeRunnable(() -> {
            final StepRunner pipeline = new PipelineStepRunner(new XsltStep(), pipelineFactoryProvider,
                    headlessCaptureProvider, injectedCodeProvider, errorReceiverProvider,
                    taskContextFactory);
            result[0] = pipeline.run(candidate, INPUT);
        });
        return result[0];
    }

    private static Path canary() throws IOException {
        final Path secret = Files.createTempFile("pipeline-step-runner", ".txt");
        Files.writeString(secret, CANARY);
        return secret;
    }

    private static String stylesheet(final String body) {
        return "<xsl:stylesheet xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='3.0'>"
               + "<xsl:template match='/'>" + body + "</xsl:template></xsl:stylesheet>";
    }

    // A document written as an XPath string literal in a double-quoted attribute.
    private static String escapedForAnAttribute(final String document) {
        return document.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "''");
    }
}
