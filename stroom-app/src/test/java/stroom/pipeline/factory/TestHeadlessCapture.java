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

package stroom.pipeline.factory;

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.task.api.SimpleTaskContext;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.Indicators;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 01 §12 item 2: capture without stepping. A pipeline is run over an input and what each element
 * made of each record is handed back, with no session, no step request and no durable store — which is
 * what something judging a configuration needs, and until now only a stepping session could get.
 * <p>
 * Run with item 1 (the injected configuration), this is the whole of what the supervisor does by hand
 * today: a candidate stylesheet, nothing written to any store, run once per record, and its output read
 * back to be scored.
 */
class TestHeadlessCapture extends AbstractProcessIntegrationTest {

    private static final String INPUT = """
            <records><record>alpha</record><record>beta</record></records>""";
    private static final String THREE = """
            <records><record>alpha</record><record>beta</record><record>gamma</record></records>""";
    /// Says something for one record alone, so that what it said must not be reported against the rest.
    private static final String COMPLAINS_ABOUT_BETA = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="/">
                <xsl:if test="records/record = 'beta'"><xsl:message>beta is trouble</xsl:message></xsl:if>
                <out><xsl:value-of select="records/record"/></out>
              </xsl:template>
            </xsl:stylesheet>""";
    /// Writes nothing at all for one record, so that what is captured must keep its place.
    private static final String SKIPS_BETA = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="/">
                <xsl:if test="records/record != 'beta'">
                  <out><xsl:value-of select="records/record"/></out>
                </xsl:if>
              </xsl:template>
            </xsl:stylesheet>""";
    private static final String STYLESHEET = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="/"><out><xsl:value-of select="records/record"/></out></xsl:template>
            </xsl:stylesheet>""";

    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<InjectedCode> injectedCodeProvider;
    @Inject
    private Provider<HeadlessCapture> headlessCaptureProvider;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private PipelineDataCache pipelineDataCache;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;

    @Test
    void everyElementsInputAndOutputIsCapturedForEveryRecord() {
        final List<HeadlessCapture.Record> records = new ArrayList<>();
        final List<String> written = new ArrayList<>();
        pipelineScopeRunnable.scopeRunnable(() -> {
            errorReceiverProvider.get().setErrorReceiver(new LoggingErrorReceiver());
            // The candidate, which is written nowhere (§12 item 1).
            injectedCodeProvider.get().set(Map.of("xsltFilter", STYLESHEET));

            final HeadlessCapture capture = headlessCaptureProvider.get();
            final DocRef pipelineRef = pipelineStore.createDocument("headless");
            final PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef)
                    .copy()
                    .pipelineData(chain())
                    .build();
            pipelineStore.writeDocument(pipelineDoc);

            final PipelineData pipelineData = pipelineDataCache.get(pipelineDoc);
            final Pipeline pipeline = pipelineFactoryProvider.get()
                    .create(pipelineData, new SimpleTaskContext(), capture);
            pipeline.process(new ByteArrayInputStream(INPUT.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8.name());

            records.addAll(capture.getRecords());
            written.addAll(capture.outputOf("xsltFilter"));
        });

        assertThat(records).describedAs("one entry per record of the stream").hasSize(2);
        assertThat(written).describedAs("what the candidate wrote for each record, in order").hasSize(2);
        assertThat(written.get(0)).contains("<out>alpha</out>");
        assertThat(written.get(1)).contains("<out>beta</out>");
        assertThat(records.get(0).byElement()).describedAs("and what each element was given")
                .containsKey("xsltFilter");
        assertThat(records.get(0).byElement().get("xsltFilter").input())
                .contains("<record>alpha</record>");
    }

    @Test
    void whatAnElementSaidBelongsToTheRecordItSaidItFor() {
        // Indicators accumulate on the error receiver for the life of the receiver, so a capture that
        // does not clear them reports one record's complaint against every record after it.
        final List<HeadlessCapture.Record> records = capture(COMPLAINS_ABOUT_BETA, THREE, Integer.MAX_VALUE)
                .getRecords();

        assertThat(records).hasSize(3);
        assertThat(indicatorsOf(records.get(0))).describedAs("alpha was fine").isNull();
        assertThat(indicatorsOf(records.get(1))).describedAs("beta was complained about").isNotNull();
        assertThat(indicatorsOf(records.get(2))).describedAs("and gamma is not beta").isNull();
    }

    @Test
    void aRecordThatProducedNothingStillKeepsItsPlace() {
        // A scorer pairing an input with an output has to be able to trust the position: a list that
        // silently drops the records an element wrote nothing for is off by one with no error.
        final HeadlessCapture capture = capture(SKIPS_BETA, THREE, Integer.MAX_VALUE);

        assertThat(capture.getRecords()).extracting(HeadlessCapture.Record::sequence)
                .describedAs("counted across the capture, never repeated")
                .containsExactly(0L, 1L, 2L);
        assertThat(capture.outputOf("xsltFilter"))
                .describedAs("one entry per record, in record order, whatever each record produced")
                .hasSize(3);
        assertThat(capture.outputOf("xsltFilter").get(0)).contains("<out>alpha</out>");
        assertThat(capture.outputOf("xsltFilter").get(2))
                .describedAs("gamma is the third record, whatever the second one did")
                .contains("<out>gamma</out>");
        assertThat(capture.getRecords().get(1).byElement())
                .describedAs("and the element is still there for the record it wrote nothing for")
                .containsKey("xsltFilter");
    }

    @Test
    void aCaptureHoldsWhatItWasToldToAndSaysWhenThereWasMore() {
        // There is no store behind this: a capture is for a sample, and the cap is how a caller says so.
        final HeadlessCapture capture = capture(STYLESHEET, THREE, 2);

        assertThat(capture.getRecordCount()).isEqualTo(2);
        assertThat(capture.isTruncated()).isTrue();
        assertThat(capture.outputOf("xsltFilter").getFirst()).contains("<out>alpha</out>");
    }

    private static Indicators indicatorsOf(final HeadlessCapture.Record record) {
        return record.byElement().get("xsltFilter").indicators();
    }

    private HeadlessCapture capture(final String stylesheet, final String input, final int maxRecords) {
        final HeadlessCapture[] held = new HeadlessCapture[1];
        pipelineScopeRunnable.scopeRunnable(() -> {
            errorReceiverProvider.get().setErrorReceiver(new LoggingErrorReceiver());
            injectedCodeProvider.get().set(Map.of("xsltFilter", stylesheet));
            final HeadlessCapture capture = headlessCaptureProvider.get();
            capture.setMaxRecords(maxRecords);
            held[0] = capture;

            final DocRef pipelineRef = pipelineStore.createDocument("headless-" + System.nanoTime());
            final PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef)
                    .copy()
                    .pipelineData(chain())
                    .build();
            pipelineStore.writeDocument(pipelineDoc);

            pipelineFactoryProvider.get()
                    .create(pipelineDataCache.get(pipelineDoc), new SimpleTaskContext(), capture)
                    .process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8.name());
        });
        return held[0];
    }

    /// Source, a parser, a split so that a record is a record, and the transform being judged.
    private static PipelineData chain() {
        final PipelineDataBuilder builder = new PipelineDataBuilder();
        builder.addElement(PipelineDataUtil.createElement("Source", "Source", null, null));
        builder.addElement(PipelineDataUtil.createElement("xmlParser", "XMLParser", null, null));
        builder.addElement(PipelineDataUtil.createElement("splitFilter", "SplitFilter", null, null));
        builder.addElement(PipelineDataUtil.createElement("xsltFilter", "XSLTFilter", null, null));
        builder.addLink(PipelineDataUtil.createLink("Source", "xmlParser"));
        builder.addLink(PipelineDataUtil.createLink("xmlParser", "splitFilter"));
        builder.addLink(PipelineDataUtil.createLink("splitFilter", "xsltFilter"));
        builder.addProperty(new PipelineProperty("splitFilter", "splitDepth", new PipelinePropertyValue(1)));
        builder.addProperty(new PipelineProperty("splitFilter", "splitCount", new PipelinePropertyValue(1)));
        return builder.build();
    }
}
