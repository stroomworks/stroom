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

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.factory.PipelineStackLoader;
import stroom.pipeline.shared.stepping.NestedElementData;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.fragment.FragmentOutput;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.FragmentTap;
import stroom.shapeshifter.ai.fragment.FragmentWriter;
import stroom.shapeshifter.ai.fragment.PipelineFragmentRunner;
import stroom.shapeshifter.ai.fragment.StandInFragmentRunner;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.task.api.TaskContextFactory;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 01 §12 item 2: a node judges and serves a fragment by running it as a pipeline, and the Tier 1
 * scenarios run the same fragment with the module's step runners instead (design 02 §2). They are meant
 * to agree, and this is what holds them to it.
 * <p>
 * A stand-in that has drifted from the thing it stands in for is worse than no stand-in: every scenario
 * would still pass while the pipeline refused what they promoted. So the same fragment, written the way
 * a promotion writes it, is run both ways over the same stream and the events compared.
 */
class TestFragmentRunnersAgree extends AbstractProcessIntegrationTest {

    private static final DocPath FOLDER = DocPath.fromParts("Shapeshifter", "AGREEMENT");
    private static final String DOCUMENT = Scenarios.resource("records.json");
    private static final String XSLT = Scenarios.resource("records.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// Counts what it is given, so that a run over the whole document and a run over one record at a
    /// time cannot produce the same answer.
    private static final String COUNTS = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                            xpath-default-namespace="records:2" version="2.0">
              <xsl:template match="/"><counted><xsl:value-of select="count(//record)"/></counted>
              </xsl:template>
            </xsl:stylesheet>""";
    /// Will not compile, so that a candidate's failure is scored rather than thrown.
    private static final String BROKEN = "<xsl:stylesheet";
    private static final Verdict UNSCORED = new Verdict(List.of(), 1.0);

    @Inject
    private FragmentWriter fragmentWriter;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private PipelineStackLoader pipelineStackLoader;
    @Inject
    private TextConverterStore textConverterStore;
    @Inject
    private XsltStore xsltStore;
    @Inject
    private PipelineDataCache pipelineDataCache;
    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<HeadlessCapture> headlessCaptureProvider;
    @Inject
    private Provider<InjectedCode> injectedCodeProvider;
    @Inject
    private TaskContextFactory taskContextFactory;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private Provider<DataSplitterCompiler> dataSplitterCompilerProvider;
    @Inject
    private Provider<FragmentOutput> fragmentOutputProvider;
    @Inject
    private Provider<FragmentTap> fragmentTapProvider;
    @Inject
    private ElementRegistryFactory elementRegistryFactory;

    @Test
    void theStandInAndThePipelineMakeTheSameEventsOfTheSameFragment() {
        final List<StepRunner> runners = List.of(new JsonStep(), new XsltStep());
        final RecordBoundary boundary = RecordBoundary.ofArray("events").atDepth(3);
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), UNSCORED),
                new LearnedStep(new XsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final List<Attempted> stood = new ArrayList<>();
        final List<Attempted> ran = new ArrayList<>();
        pipelineScopeRunnable.scopeRunnable(() -> {
            final DocRef fragment = fragmentWriter.write(FOLDER, "agreement-v1", chain, boundary);

            final FragmentRunner standIn = new StandInFragmentRunner(pipelineStore, pipelineStackLoader,
                    textConverterStore, xsltStore, runners);
            final FragmentRunner pipeline = new PipelineFragmentRunner(pipelineStore, pipelineDataCache,
                    elementRegistryFactory, pipelineFactoryProvider, headlessCaptureProvider, errorReceiverProvider,
                    fragmentOutputProvider, fragmentTapProvider, injectedCodeProvider, taskContextFactory);

            stood.addAll(standIn.run(fragment, DOCUMENT, boundary));
            ran.addAll(pipeline.run(fragment, DOCUMENT, boundary));
        });

        assertThat(ran).extracting(Attempted::elementType)
                .describedAs("the same chain, the SplitFilter being shape and not a step")
                .containsExactlyElementsOf(stood.stream().map(Attempted::elementType).toList());
        assertThat(ran).extracting(Attempted::parser)
                .containsExactlyElementsOf(stood.stream().map(Attempted::parser).toList());
        assertThat(canonical(last(ran)))
                .describedAs("and the same events of the same stream")
                .isEqualTo(canonical(last(stood)));
        assertThat(canonical(last(ran))).contains("<Event>");
    }

    /**
     * The chain's capture is <b>taken</b> from the runner, not read: after one ask the runner is left
     * holding nothing.
     * <p>
     * That is what keeps a stage that ran no chain from showing the last one that did. A stage decides
     * many things without running a fragment at all — a reserved binding matches, a draft awaits review,
     * the learner has been given up — and a caller that merely read the last capture would be handed the
     * previous stream's chain and hang it in the stepping tree beneath a stage that says nothing was
     * bound. The same shape of fault as serving a rejected candidate's events, met once already.
     */
    @Test
    void theChainsCaptureIsTakenAndNotLeftBehind() {
        final List<StepRunner> runners = List.of(new JsonStep(), new XsltStep());
        final RecordBoundary boundary = RecordBoundary.ofArray("events").atDepth(3);
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), UNSCORED),
                new LearnedStep(new XsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        pipelineScopeRunnable.scopeRunnable(() -> {
            final DocRef fragment = fragmentWriter.write(FOLDER, "taken-v1", chain, boundary);
            final FragmentRunner runner = new PipelineFragmentRunner(pipelineStore, pipelineDataCache,
                    elementRegistryFactory, pipelineFactoryProvider, headlessCaptureProvider,
                    errorReceiverProvider, fragmentOutputProvider, fragmentTapProvider, injectedCodeProvider,
                    taskContextFactory);

            assertThat(runner.takeRecords())
                    .describedAs("nothing has run, so there is nothing to take")
                    .isEmpty();

            runner.run(fragment, DOCUMENT, boundary);

            final List<FragmentRunner.FragmentRecord> taken = runner.takeRecords();
            assertThat(taken).describedAs("what the chain's elements made of the stream").isNotEmpty();
            assertThat(taken.get(0).elements())
                    .extracting(NestedElementData::getType)
                    .describedAs("the fragment as it was learned, and not the SplitFilter's scaffolding")
                    .contains("JSONParser", "XSLTFilter");

            assertThat(runner.takeRecords())
                    .describedAs("and taken means taken: a second ask finds nothing, which is the truth "
                                 + "for any decision made without running a chain at all")
                    .isEmpty();
        });
    }

    @Test
    void aChainWithNoRecordBoundaryIsRunWholeBothWays() {
        // A fragment for raw text carries no SplitFilter: the Data Splitter cuts the records and the
        // transform is given the parsed document whole, which is what the pipeline does in production.
        // A capture that inserted a split of its own would hand the transform one record instead and
        // judge the candidate on a difference the pipeline will never show it.
        final List<Attempted> stood = new ArrayList<>();
        final List<Attempted> ran = new ArrayList<>();
        run(() -> {
            final DataSplitterStep splitter = new DataSplitterStep(dataSplitterCompilerProvider.get());
            return List.of(
                    new LearnedStep(splitter, CSV.configuration(), new StepResult("<records/>", List.of()),
                            UNSCORED),
                    new LearnedStep(new XsltStep(), COUNTS, new StepResult("<counted/>", List.of()),
                            UNSCORED));
        }, () -> List.of(new DataSplitterStep(dataSplitterCompilerProvider.get()), new XsltStep()),
                null, CSV.input(), stood, ran);

        assertThat(canonical(last(ran)))
                .describedAs("one run over the whole document both ways, so one count of every record")
                .isEqualTo(canonical(last(stood)));
        assertThat(canonical(last(ran)))
                .describedAs("and the count is the stream's, not one per record")
                .contains("<counted>6</counted>");
    }

    @Test
    void aCandidateThatWillNotCompileIsScoredRatherThanThrown() {
        // Without an error receiver in place the first thing a failing element says is a
        // NullPointerException, and the attempt dies instead of being judged.
        final List<StepRunner> runners = List.of(new JsonStep(), new XsltStep());
        final RecordBoundary boundary = RecordBoundary.ofArray("events").atDepth(3);
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), UNSCORED),
                new LearnedStep(new XsltStep(), BROKEN, new StepResult("<Events/>", List.of()), UNSCORED));

        final List<Attempted> stood = new ArrayList<>();
        final List<Attempted> ran = new ArrayList<>();
        run(() -> chain, () -> runners, boundary, DOCUMENT, stood, ran);

        final Attempted transform = ran.get(ran.size() - 1);
        assertThat(transform.result().output()).describedAs("nothing was written").isNull();
        assertThat(transform.result().diagnostics())
                .describedAs("and the next candidate is told why")
                .isNotEmpty();
    }

    private void run(final Supplier<List<LearnedStep>> chain,
                     final Supplier<List<StepRunner>> runners,
                     final RecordBoundary boundary,
                     final String input,
                     final List<Attempted> stood,
                     final List<Attempted> ran) {
        pipelineScopeRunnable.scopeRunnable(() -> {
            final List<StepRunner> steps = runners.get();
            final DocRef fragment = fragmentWriter.write(FOLDER, "agreement-" + System.nanoTime(),
                    chain.get(), boundary);
            final FragmentRunner standIn = new StandInFragmentRunner(pipelineStore, pipelineStackLoader,
                    textConverterStore, xsltStore, steps);
            final FragmentRunner pipeline = new PipelineFragmentRunner(pipelineStore, pipelineDataCache,
                    elementRegistryFactory, pipelineFactoryProvider, headlessCaptureProvider, errorReceiverProvider,
                    fragmentOutputProvider, fragmentTapProvider, injectedCodeProvider, taskContextFactory);
            stood.addAll(standIn.run(fragment, input, boundary));
            ran.addAll(pipeline.run(fragment, input, boundary));
        });
    }

    private static String last(final List<Attempted> steps) {
        return steps.get(steps.size() - 1).result().output();
    }

    /// The events, not the spacing between them: the two runs serialise their joins differently.
    private static String canonical(final String xml) {
        return Scenarios.canonical(xml);
    }
}
