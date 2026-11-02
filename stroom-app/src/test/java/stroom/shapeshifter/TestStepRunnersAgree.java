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

package stroom.shapeshifter;

import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.shapeshifter.ai.element.PipelineStepRunner;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.extraction.XmlFragmentStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.task.api.TaskContextFactory;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.StoredError;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 01 §12 item 2: a node judges a candidate with the element that will run it, and the Tier 1
 * scenarios judge with the module's own runner instead (design 02 §2). They are meant to agree, and a
 * stand-in that has drifted is worse than no stand-in — the model would be told to correct a fault the
 * pipeline does not have, and a candidate the pipeline is happy with would be thrown away.
 * <p>
 * One case per kind of element, because each stands in for something different: Saxon's configuration
 * for a stylesheet, the DS3 factory for a splitter, the JSON reader, the fragment wrapper.
 */
class TestStepRunnersAgree extends AbstractProcessIntegrationTest {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("records.xsl");
    private static final String RECORDS = Scenarios.resource("records.json");
    private static final String FRAGMENTS = Scenarios.resource("events-fragments.xml");

    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<HeadlessCapture> headlessCaptureProvider;
    @Inject
    private Provider<InjectedCode> injectedCodeProvider;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private Provider<DataSplitterCompiler> dataSplitterCompilerProvider;
    @Inject
    private TaskContextFactory taskContextFactory;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;

    @Test
    void aStylesheetWritesTheSameEventsEitherWay() {
        agree(XsltStep::new, XSLT, jsonRecords());
    }

    @Test
    void aSplitterCutsTheSameRecordsEitherWay() {
        agree(() -> new DataSplitterStep(dataSplitterCompilerProvider.get()), CSV.configuration(),
                CSV.input());
    }

    @Test
    void theJsonParserReadsTheSameDocumentEitherWay() {
        agree(JsonStep::new, null, RECORDS);
    }

    @Test
    void theFragmentParserWrapsTheSameStreamEitherWay() {
        // Nothing is asked for its wrapper, so nothing passes one: both sides have to find their own.
        agree(XmlFragmentStep::new, null, FRAGMENTS);
    }

    @Test
    void aStylesheetThatWillNotCompileIsRefusedWithSomethingToGoOn() {
        // A candidate that refuses must come back as a step that produced nothing and said why: a
        // re-ask carrying no shortfall spends an attempt for nothing (§10, A44).
        pipelineScopeRunnable.scopeRunnable(() -> {
            final StepRunner pipeline = new PipelineStepRunner(new XsltStep(), pipelineFactoryProvider,
                    headlessCaptureProvider, injectedCodeProvider, errorReceiverProvider,
                    taskContextFactory);

            final StepResult result = pipeline.run("<xsl:stylesheet", jsonRecords());

            assertThat(result.output()).isNull();
            assertThat(result.diagnostics()).describedAs("and the model is told why").isNotEmpty();
        });
    }

    @Test
    void inputThatWillNotParseIsRefusedByWhateverRefusedItRatherThanInSilence() {
        // The element under test is not always the one that objects: a transform is given text, and
        // text that is not well formed is refused by the parser standing in front of it.
        pipelineScopeRunnable.scopeRunnable(() -> {
            final StepRunner pipeline = new PipelineStepRunner(new XsltStep(), pipelineFactoryProvider,
                    headlessCaptureProvider, injectedCodeProvider, errorReceiverProvider,
                    taskContextFactory);

            final StepResult result = pipeline.run(Scenarios.resource("records.xsl"), "<not><well>formed");

            assertThat(result.output()).isNull();
            assertThat(result.diagnostics())
                    .describedAs("what the parser in front of it said, rather than nothing at all")
                    .isNotEmpty();
        });
    }

    @Test
    void aPreparedCandidateIsBuiltOnceAndGivenRecordAfterRecord() {
        // Building the pipeline is what compiles the candidate, and compiling does not depend on the
        // input: a stylesheet judged over a stream's records is compiled for the candidate, not for
        // each record of it (§12 item 25).
        pipelineScopeRunnable.scopeRunnable(() -> {
            final StepRunner pipeline = new PipelineStepRunner(new XsltStep(), pipelineFactoryProvider,
                    headlessCaptureProvider, injectedCodeProvider, errorReceiverProvider,
                    taskContextFactory);
            final String records = jsonRecords();

            try (StepRunner.Prepared prepared = pipeline.prepare(Scenarios.resource("records.xsl"))) {
                final StepResult first = prepared.run(records);
                final StepResult second = prepared.run(records);

                assertThat(first.output()).isNotNull();
                assertThat(Scenarios.canonical(second.output()))
                        .describedAs("the same pipeline, another record")
                        .isEqualTo(Scenarios.canonical(first.output()));
                assertThat(second.diagnostics())
                        .describedAs("and one record's run tells another nothing")
                        .isEmpty();
            }
        });
    }

    /// The parser's output, which is what a transform is given while it is being written.
    private String jsonRecords() {
        return new JsonStep().run(null, RECORDS).output();
    }

    private void agree(final Supplier<StepRunner> runner,
                       final String configuration,
                       final String input) {
        pipelineScopeRunnable.scopeRunnable(() -> {
            final StepRunner standIn = runner.get();
            final StepRunner pipeline = new PipelineStepRunner(standIn,
                    pipelineFactoryProvider, headlessCaptureProvider, injectedCodeProvider,
                    errorReceiverProvider, taskContextFactory);

            final StepResult stood = standIn.run(configuration, input);
            final StepResult ran = pipeline.run(configuration, input);

            assertThat(ran.output()).describedAs("the pipeline wrote something").isNotNull();
            assertThat(Scenarios.canonical(ran.output()))
                    .describedAs("and the same thing the stand-in wrote")
                    .isEqualTo(Scenarios.canonical(stood.output()));
            assertThat(ran.diagnostics()).extracting(StoredError::getSeverity)
                    .describedAs("and had as little to complain about")
                    .containsExactlyElementsOf(stood.diagnostics().stream()
                            .map(StoredError::getSeverity)
                            .toList());
        });
    }
}
