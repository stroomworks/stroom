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

package stroom.egress;

import stroom.docref.DocRef;
import stroom.egress.api.EgressRule;
import stroom.egress.impl.AggregatingDecisionRecorder;
import stroom.egress.impl.AggregatingDecisionRecorder.Attempt;
import stroom.egress.impl.InMemoryEgressRegistry;
import stroom.egress.impl.SaxonEgressAccessGuard;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressMode;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.PipelineTestUtil;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.state.RecordCount;
import stroom.pipeline.xslt.XsltStore;
import stroom.task.api.SimpleTaskContext;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.test.common.StroomPipelineTestFileUtil;
import stroom.util.io.FileUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.Severity;
import stroom.util.xml.saxon.SaxonConfinement;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Runs real pipelines through the real pipeline factory and XSLT pool, with the real guard (reading its policy from
/// memory, see [stroom.test.MockEgressModule]) and a stylesheet that tries to read outside the pipeline, to show that
/// the policy applies there and not only to the stylesheet pool in isolation.
///
/// The application's start-up tasks are not run by the test framework, so the test does what they do and installs the
/// Saxon guard.
class TestEgressRealNode extends AbstractProcessIntegrationTest {

    private static final String DIR = "TestXMLTransformer/";
    private static final String INPUT = DIR + "XML-EVENTS.nxml";
    private static final String PIPELINE = DIR + "XMLTransformer.Pipeline.json";
    private static final String XSLT_PATH = DIR + "DATA_SPLITTER-EVENTS_no-ref.xsl";
    private static final String SECRET = "value-from-an-outside-file";

    private Path outsideDir;

    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private Provider<RecordCount> recordCountProvider;
    @Inject
    private XsltStore xsltStore;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private PipelineDataCache pipelineDataCache;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;
    @Inject
    private InMemoryEgressRegistry registry;
    @Inject
    private SaxonEgressAccessGuard saxonGuard;
    @Inject
    private AggregatingDecisionRecorder recorder;

    @BeforeEach
    void setUpPolicy() {
        resetPolicy();
        SaxonConfinement.install(saxonGuard);
        recorder.clear();
    }

    @AfterEach
    void tearDownPolicy() {
        resetPolicy();
        recorder.clear();
    }

    @Test
    void testUnparsedTextIsRefusedInEveryMode() throws IOException {
        final Path outside = writeOutsideFile();
        final String xslt = withDescription("unparsed-text('" + outside.toUri() + "')");

        // Report-only is the default, and unparsed-text is still refused: it is denied outright.
        final Result result = run(xslt);

        assertThat(result.output).doesNotContain(SECRET);
        assertThat(result.errors).isGreaterThan(0);
        assertThat(attempts()).anyMatch(attempt -> attempt.key().sink().contains("unparsed-text")
                                                    && attempt.key().outcome().name().equals("DENIED"));
    }

    @Test
    void testDocumentIsReportedAndAllowedInReportOnlyMode() throws IOException {
        final Path outside = writeOutsideXml();
        final String xslt = withDescription("doc('" + outside.toUri() + "')/*/@v");

        final Result result = run(xslt);

        assertThat(result.output).as(result.detail + " attempts: " + attempts()).contains(SECRET);
        assertThat(attempts()).anyMatch(attempt -> attempt.key().sink().contains("doc")
                                                    && attempt.key().outcome().name().equals("WOULD_DENY"));
    }

    @Test
    void testDocumentIsRefusedInEnforceMode() throws IOException {
        final Path outside = writeOutsideXml();
        final String xslt = withDescription("doc('" + outside.toUri() + "')/*/@v");
        registry.setMode(EgressKind.FILE, EgressMode.ENFORCE);
        permitPipelineOutput();

        final Result result = run(xslt);

        // A refused document() stops the translation with an error, and nothing from the file is written
        assertThat(result.errors).as(result.detail).isGreaterThan(0);
        assertThat(result.output).doesNotContain(SECRET);
        assertThat(attempts()).anyMatch(attempt -> attempt.key().sink().contains("doc")
                                                    && attempt.key().outcome().name().equals("DENIED"));
    }

    @Test
    void testDocumentIsAllowedInEnforceModeByARule() throws IOException {
        final Path outside = writeOutsideXml();
        final String xslt = withDescription("doc('" + outside.toUri() + "')/*/@v");
        registry.setMode(EgressKind.FILE, EgressMode.ENFORCE);
        permitPipelineOutput();
        registry.saveRule(new EgressRule(
                null, EgressKind.FILE, outside.getParent().toRealPath().toString(), EgressMatch.PREFIX,
                "Test rule for the outside directory", true));

        final Result result = run(xslt);

        assertThat(result.output).as(result.detail).contains(SECRET);
        assertThat(attempts()).as(attempts().toString()).isEmpty();
    }

    private void resetPolicy() {
        for (final EgressRule rule : registry.listRules()) {
            registry.deleteRule(rule.id());
        }
        for (final EgressKind kind : EgressKind.values()) {
            registry.setMode(kind, EgressMode.REPORT_ONLY);
            registry.setKilled(kind, false);
        }
    }

    private List<Attempt> attempts() {
        return recorder.getAttempts();
    }

    // A directory of its own, apart from the one the pipeline writes its output to
    private Path outsideDir() throws IOException {
        if (outsideDir == null) {
            outsideDir = Files.createTempDirectory("egress-outside");
        }
        return outsideDir;
    }

    // The pipeline's own file appender is governed by the policy too, so an enforcing test has to permit its output
    private void permitPipelineOutput() throws IOException {
        registry.saveRule(new EgressRule(
                null, EgressKind.FILE, getCurrentTestDir().toRealPath().toString(), EgressMatch.PREFIX,
                "The pipeline's output directory", true));
    }

    private Path writeOutsideFile() throws IOException {
        final Path file = outsideDir().resolve("outside.txt");
        Files.writeString(file, SECRET, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeOutsideXml() throws IOException {
        final Path file = outsideDir().resolve("outside.xml");
        Files.writeString(file, "<outside v=\"" + SECRET + "\"/>", StandardCharsets.UTF_8);
        return file;
    }

    // The pipeline validates its output against the event-logging schema, so the stylesheet is the one the transformer
    // test uses, with the description of each event replaced by the expression under test.
    private static String withDescription(final String expression) {
        final String original = "<Description><xsl:value-of select=\"data[@name='Message']/@value\"/></Description>";
        final String xslt = StroomPipelineTestFileUtil.getString(XSLT_PATH);
        assertThat(xslt).contains(original);
        return xslt.replace(original, "<Description><xsl:value-of select=\"" + expression + "\"/></Description>");
    }

    private Result run(final String xslt) {
        final DocRef xsltRef = xsltStore.createDocument("Egress real node XSLT");
        final XsltDoc xsltDoc = xsltStore.readDocument(xsltRef).copy().data(xslt).build();
        xsltStore.writeDocument(xsltDoc);

        final String data = StroomPipelineTestFileUtil.getString(PIPELINE);
        final DocRef pipelineRef = PipelineTestUtil.createTestPipeline(pipelineStore, data);
        PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef);
        final PipelineDataBuilder builder = new PipelineDataBuilder(pipelineDoc.getPipelineData());
        builder.addProperty(PipelineDataUtil.createProperty("translationFilter", "xslt", xsltRef));
        pipelineDoc = pipelineDoc.copy().pipelineData(builder.build()).build();
        pipelineStore.writeDocument(pipelineDoc);

        final Result[] holder = new Result[1];
        pipelineScopeRunnable.scopeRunnable(() -> {
            try (final InputStream inputStream = StroomPipelineTestFileUtil.getInputStream(INPUT)) {
                final Path outputFile = getCurrentTestDir().resolve("TestXMLTransformer.xml");
                FileUtil.deleteFile(outputFile);
                FileUtil.deleteFile(getCurrentTestDir().resolve("TestXMLTransformer.xml.lock"));

                final LoggingErrorReceiver errorReceiver = new LoggingErrorReceiver();
                errorReceiverProvider.get().setErrorReceiver(errorReceiver);

                final PipelineData pipelineData = pipelineDataCache.get(pipelineStore.readDocument(pipelineRef));
                final Pipeline pipeline = pipelineFactoryProvider.get().create(pipelineData, new SimpleTaskContext());
                try {
                    pipeline.startProcessing();
                    pipeline.process(inputStream, null);
                    pipeline.endProcessing();
                } catch (final RuntimeException e) {
                    // A refused stylesheet may stop the pipeline; what matters is what was written and recorded.
                }
                final long errors = errorReceiver.getRecords(Severity.ERROR)
                                    + errorReceiver.getRecords(Severity.FATAL_ERROR);
                final String output = Files.exists(outputFile)
                        ? Files.readString(outputFile, StandardCharsets.UTF_8)
                        : "";
                holder[0] = new Result(output, errors, errorReceiver.toString() + " output file exists: " + Files.exists(outputFile)
                                                                  + " in " + outputFile);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return holder[0];
    }


    // --------------------------------------------------------------------------------


    private record Result(String output, long errors, String detail) {

    }
}
