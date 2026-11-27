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
import stroom.learningflow.extraction.DataSplitterCompiler;
import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.extraction.XmlFragmentStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.learningflow.transformation.XsltStep;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.task.api.TaskContextFactory;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.StoredError;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the module's stand-in step runners give the same results as the pipeline step runner that a
/// node uses to judge a candidate with the real element.
///
/// The module's own scenarios judge candidates with the stand-ins. If a stand-in drifted from the real
/// element, the model would be told to fix faults the pipeline does not have, and candidates the pipeline
/// would accept would be thrown away.
///
/// There is one case per kind of element, because each stand-in replaces something different: Saxon's
/// configuration for a stylesheet, the DS3 parser factory for a Data Splitter, the JSON reader, and the
/// XML fragment wrapper.
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
        agree(StandInXsltStep::new, XSLT, jsonRecords());
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
        // The model is never asked for a wrapper, so no configuration is passed and both runners must
        // find their own.
        agree(XmlFragmentStep::new, null, FRAGMENTS);
    }

    @Test
    void aStylesheetThatWillNotCompileIsRefusedWithSomethingToGoOn() {
        // A candidate that fails must come back as a step that produced nothing and says why. Asking the
        // model again without saying what was wrong would waste a candidate.
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
        // The element under test is not always the one that reports the error. A transform is given text,
        // and text that is not well formed is rejected by the parser in front of it.
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
        // Building the pipeline compiles the candidate, and compiling does not depend on the input. A
        // stylesheet judged over a stream's records is therefore compiled once per candidate, not once
        // per record.
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

    /// Returns the JSON parser's output for [#RECORDS], which is what a transform is given as input while
    /// it is being learned.
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
