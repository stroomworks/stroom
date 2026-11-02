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
import stroom.pipeline.errorhandler.RecordErrorReceiver;
import stroom.pipeline.filter.TestFilter;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.task.api.SimpleTaskContext;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;

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
 * Design 01 §12 item 1: a pipeline runs an element with the configuration it was handed rather than the
 * document it references, without being a stepping session and without that configuration having been
 * written anywhere. Stepping has always been able to do this; until now only stepping could say so.
 * <p>
 * The pipeline here references no XSLT at all, which is the case that matters: a supervisor judging a
 * candidate has nothing to reference, and writing the document first is what design §7.3 forbids.
 */
class TestInjectedCode extends AbstractProcessIntegrationTest {

    private static final String INPUT = "<records><record>hello</record></records>";
    private static final String STYLESHEET = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="/"><out><xsl:value-of select="records/record"/></out></xsl:template>
            </xsl:stylesheet>""";

    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<InjectedCode> injectedCodeProvider;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private Provider<RecordErrorReceiver> recordErrorReceiverProvider;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private PipelineDataCache pipelineDataCache;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;

    @Test
    void anElementRunsTheCodeItWasGivenAlthoughItReferencesNoDocument() {
        final String output = run(STYLESHEET, "injected");

        assertThat(output)
                .describedAs("the candidate ran, and nothing was written to a store for it to run")
                .contains("<out>hello</out>");
    }

    @Test
    void withNothingInjectedTheSameTransformPassesTheInputThrough() {
        // The other half of the evidence: an XSLTFilter with no document and no code transforms nothing,
        // so the output above is the injected stylesheet's doing and not the pipeline's.
        final String output = run(null, "passed-through");

        assertThat(output).contains("<record>hello</record>").doesNotContain("<out>");
    }

    private String run(final String code, final String name) {
        final List<String> written = new ArrayList<>();
        pipelineScopeRunnable.scopeRunnable(() -> {
            errorReceiverProvider.get().setErrorReceiver(recordErrorReceiverProvider.get());
            if (code != null) {
                injectedCodeProvider.get().set(Map.of("xsltFilter", code));
            }

            final DocRef pipelineRef = pipelineStore.createDocument(name);
            final PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef)
                    .copy()
                    .pipelineData(chain())
                    .build();
            pipelineStore.writeDocument(pipelineDoc);

            final PipelineData pipelineData = pipelineDataCache.get(pipelineDoc);
            final Pipeline pipeline = pipelineFactoryProvider.get()
                    .create(pipelineData, new SimpleTaskContext());
            final TestFilter capture = pipeline.findFilters(TestFilter.class).getFirst();

            pipeline.process(new ByteArrayInputStream(INPUT.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8.name());
            written.addAll(capture.getOutputs());
        });
        return String.join("\n", written);
    }

    /// Source, an XML parser, a transform that references nothing, and somewhere to put what it wrote.
    private static PipelineData chain() {
        final PipelineDataBuilder builder = new PipelineDataBuilder();
        builder.addElement(PipelineDataUtil.createElement("Source", "Source", null, null));
        builder.addElement(PipelineDataUtil.createElement("xmlParser", "XMLParser", null, null));
        builder.addElement(PipelineDataUtil.createElement("readRecordCountFilter", "RecordCountFilter",
                null, null));
        builder.addElement(PipelineDataUtil.createElement("xsltFilter", "XSLTFilter", null, null));
        builder.addElement(PipelineDataUtil.createElement("testFilter", "TestFilter", null, null));
        builder.addLink(PipelineDataUtil.createLink("Source", "xmlParser"));
        builder.addLink(PipelineDataUtil.createLink("xmlParser", "readRecordCountFilter"));
        builder.addLink(PipelineDataUtil.createLink("readRecordCountFilter", "xsltFilter"));
        builder.addLink(PipelineDataUtil.createLink("xsltFilter", "testFilter"));
        return builder.build();
    }
}
