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

import stroom.docref.DocRef;
import stroom.learningflow.extraction.DataSplitterCompiler;
import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.fragment.FragmentOutput;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.FragmentTap;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.fragment.InputRead;
import stroom.learningflow.fragment.PipelineFragmentRunner;
import stroom.learningflow.fragment.StandInFragmentRunner;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.transformation.StandInXsltStep;
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
import stroom.task.api.TaskContextFactory;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the stand-in fragment runner used by the unit-level scenarios produces the same results as the
/// real pipeline fragment runner that a node uses to judge and serve a fragment.
///
/// If the stand-in drifted from the real runner, the unit-level scenarios would keep passing while a real
/// pipeline rejected what they promoted. So each test writes a fragment as a promotion writes it, runs it
/// both ways over the same input, and compares the results.
class TestFragmentRunnersAgree extends AbstractProcessIntegrationTest {

    private static final DocPath FOLDER = DocPath.fromParts("Learning Flow", "AGREEMENT");
    private static final String DOCUMENT = Scenarios.resource("records.json");
    private static final String XSLT = Scenarios.resource("records.xsl");

    /// A headerless CSV of exactly four fields; any other line is unmatched content.
    private static final String FIELDS = Scenarios.resource("csv-fields.ds3.xml");

    /// Six lines, of which the last two use a different delimiter that [#FIELDS] does not match.
    private static final String PART_DRIFTED = """
            2020-06-17T08:00:00.000Z,user0,office,logon
            2020-06-17T08:01:00.000Z,user1,office,logon
            2020-06-17T08:02:00.000Z,user2,office,logon
            2020-06-17T08:03:00.000Z,user3,office,logon
            2020-06-17T08:04:00.000Z|user4|office|logon
            2020-06-17T08:05:00.000Z|user5|office|logon
            """;
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// A stylesheet that counts the records it is given, so that a run over the whole document and a run
    /// over one record at a time give different answers.
    private static final String COUNTS = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                            xpath-default-namespace="records:2" version="2.0">
              <xsl:template match="/"><counted><xsl:value-of select="count(//record)"/></counted>
              </xsl:template>
            </xsl:stylesheet>""";
    /// A stylesheet that does not compile, to check that a compile failure is reported rather than thrown.
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
        final List<StepRunner> runners = List.of(new JsonStep(), new StandInXsltStep());
        final RecordBoundary boundary = RecordBoundary.ofArray("events").atDepth(3);
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), UNSCORED),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

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

    /// Checks that `takeRecords()` clears the runner's captured records, so a second call returns nothing.
    ///
    /// The Learning Flow element often decides what to do with a stream without running a fragment, for
    /// example when a draft awaits review or the learner has been given up. If the capture were only read
    /// and not cleared, the stepper would show the previous stream's chain beneath an element that ran
    /// nothing for this stream.
    @Test
    void theChainsCaptureIsTakenAndNotLeftBehind() {
        final List<StepRunner> runners = List.of(new JsonStep(), new StandInXsltStep());
        final RecordBoundary boundary = RecordBoundary.ofArray("events").atDepth(3);
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), UNSCORED),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

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
        // A fragment for raw text has no SplitFilter. The Data Splitter divides the input into records and
        // the transform is given the whole parsed document, as in production. If a runner added its own
        // split, the transform would see one record at a time, and the candidate would be judged on
        // output the production pipeline never produces.
        final List<Attempted> stood = new ArrayList<>();
        final List<Attempted> ran = new ArrayList<>();
        run(() -> {
            final DataSplitterStep splitter = new DataSplitterStep(dataSplitterCompilerProvider.get());
            return List.of(
                    new LearnedStep(splitter, CSV.configuration(), new StepResult("<records/>", List.of()),
                            UNSCORED),
                    new LearnedStep(new StandInXsltStep(), COUNTS, new StepResult("<counted/>", List.of()),
                            UNSCORED));
        }, () -> List.of(new DataSplitterStep(dataSplitterCompilerProvider.get()), new StandInXsltStep()),
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
        // Without an error receiver in place, a failing element throws a NullPointerException and the
        // attempt fails instead of being judged.
        final List<StepRunner> runners = List.of(new JsonStep(), new StandInXsltStep());
        final RecordBoundary boundary = RecordBoundary.ofArray("events").atDepth(3);
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), UNSCORED),
                new LearnedStep(new StandInXsltStep(), BROKEN, new StepResult("<Events/>", List.of()), UNSCORED));

        final List<Attempted> stood = new ArrayList<>();
        final List<Attempted> ran = new ArrayList<>();
        run(() -> chain, () -> runners, boundary, DOCUMENT, stood, ran);

        final Attempted transform = ran.get(ran.size() - 1);
        assertThat(transform.result().output()).describedAs("nothing was written").isNull();
        assertThat(transform.result().diagnostics())
                .describedAs("and the next candidate is told why")
                .isNotEmpty();
    }

    /// Checks that both runners count the parser's view of the input in the same way. A serving run reports
    /// how many records the parser read and how many it could not, and a unit test of the stand-in only
    /// says something about production if the real pipeline counts the same. Of the six input lines, two
    /// do not match the splitter, so both runners must report four records read, two errors, and a
    /// non-empty stream.
    @Test
    void bothRunnersCountWhatTheParserMadeOfTheInputTheSameWay() {
        final List<InputRead> read = new ArrayList<>();
        pipelineScopeRunnable.scopeRunnable(() -> {
            final List<LearnedStep> chain = List.of(
                    new LearnedStep(new DataSplitterStep(dataSplitterCompilerProvider.get()), FIELDS,
                            new StepResult("<records/>", List.of()), UNSCORED),
                    new LearnedStep(new StandInXsltStep(), COUNTS, new StepResult("<counted/>", List.of()), UNSCORED));
            final DocRef fragment = fragmentWriter.write(FOLDER, "input-read-" + System.nanoTime(), chain, null);
            final FragmentRunner standIn = new StandInFragmentRunner(pipelineStore, pipelineStackLoader,
                    textConverterStore, xsltStore,
                    List.of(new DataSplitterStep(dataSplitterCompilerProvider.get()), new StandInXsltStep()));
            final FragmentRunner pipeline = new PipelineFragmentRunner(pipelineStore, pipelineDataCache,
                    elementRegistryFactory, pipelineFactoryProvider, headlessCaptureProvider, errorReceiverProvider,
                    fragmentOutputProvider, fragmentTapProvider, injectedCodeProvider, taskContextFactory);
            read.add(served(standIn, fragment));
            read.add(served(pipeline, fragment));
        });

        assertThat(read.get(1).records())
                .describedAs("the records the parser cut: the pipeline's tap and the stand-in's split agree")
                .isEqualTo(read.get(0).records())
                .isEqualTo(4);
        assertThat(read.get(1).errors())
                .describedAs("and the records it reported it could not read")
                .isEqualTo(read.get(0).errors())
                .isEqualTo(2);
        assertThat(read.get(1).empty()).isEqualTo(read.get(0).empty()).isFalse();
    }

    /// @param runner   The fragment runner under test.
    /// @param fragment The fragment to serve.
    /// @return The runner's count of what the parser made of [#PART_DRIFTED].
    private static InputRead served(final FragmentRunner runner, final DocRef fragment) {
        return runner.serve(fragment, new ByteArrayInputStream(PART_DRIFTED.getBytes(StandardCharsets.UTF_8)),
                null, (index, record, parsed) -> UNSCORED, null).input();
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

    /// Normalises the XML so that only the events are compared, because the two runners lay out the
    /// whitespace between them differently.
    private static String canonical(final String xml) {
        return Scenarios.canonical(xml);
    }
}
