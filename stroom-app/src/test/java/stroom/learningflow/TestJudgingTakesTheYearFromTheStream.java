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
import stroom.learningflow.fragment.FragmentOutput;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.FragmentTap;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.fragment.PipelineFragmentRunner;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.learningflow.transformation.StreamFacts;
import stroom.meta.shared.Meta;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.task.api.TaskContextFactory;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that, when a learning session judges a translation, a timestamp with no year takes its year from
/// the stream rather than from the clock.
///
/// Syslog writes timestamps such as `Jun 17 09:06:02` with no year. A translation that guesses the year
/// dates every event wrongly while passing every other check. The translation should use
/// `stroom:format-date`, which fills in the missing parts from the stream's own time, but only when the
/// stream's metadata is available to the run. The learning worker runs outside a processing task, and
/// without that metadata Stroom's `FormatDate` falls back to `Instant.now()`.
///
/// The test is here rather than in the Learning Flow module because only a running node has Stroom's
/// real function library. The module's scenario harness uses a stand-in (`StroomStandIns`), and the last
/// test checks that the stand-in fills the year in the same way. If they differed, the scenarios would
/// teach the model to work around a fault the real pipeline does not have.
class TestJudgingTakesTheYearFromTheStream extends AbstractProcessIntegrationTest {

    private static final DocPath FOLDER = DocPath.fromParts("Learning Flow", "PARTIAL-DATES");

    /// The stream's creation time, three years before the test runs. This is far enough back that a year
    /// taken from the clock is unmistakable, and is typical of a stream loaded from an archive.
    private static final Instant RECEIVED = Instant.now().minus(3 * 365, ChronoUnit.DAYS);
    /// Two years before [#RECEIVED], for the second stream run in the same pipeline scope.
    private static final Instant OLDER = RECEIVED.minus(2 * 365, ChronoUnit.DAYS);

    private static final String INPUT = "<records xmlns=\"records:2\"><record><data name=\"when\" "
                                        + "value=\"Jun 17 09:06:02\"/></record></records>";

    /// A translation that does what the prompt tells the model to do: take the parts the timestamp leaves
    /// out from the stream, rather than writing a year of its own.
    private static final String XSLT = """
            <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                    xmlns="event-logging:3" xmlns:stroom="stroom"
                    xpath-default-namespace="records:2">
              <xsl:template match="/records">
                <Events>
                  <xsl:apply-templates select="record"/>
                </Events>
              </xsl:template>
              <xsl:template match="record">
                <Event>
                  <When>
                    <xsl:value-of select="stroom:format-date(data[@name='when']/@value,
                        'MMM dd HH:mm:ss')"/>
                  </When>
                </Event>
              </xsl:template>
            </xsl:stylesheet>""";

    @Inject
    private FragmentWriter fragmentWriter;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private PipelineDataCache pipelineDataCache;
    @Inject
    private ElementRegistryFactory elementRegistryFactory;
    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<HeadlessCapture> headlessCaptureProvider;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private Provider<FragmentOutput> fragmentOutputProvider;
    @Inject
    private Provider<FragmentTap> fragmentTapProvider;
    @Inject
    private Provider<InjectedCode> injectedCodeProvider;
    @Inject
    private TaskContextFactory taskContextFactory;
    @Inject
    private Provider<MetaHolder> metaHolderProvider;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;

    @Test
    void theYearComesFromTheStreamAndNotFromTheClock() {
        final AtomicReference<String> written = new AtomicReference<>();
        pipelineScopeRunnable.scopeRunnable(() -> {
            final DocRef fragment = write("partial-dates-v1");
            metaHolderProvider.get().setMeta(stream(RECEIVED));
            written.set(when(run(fragment)));
        });

        assertThat(written.get())
                .describedAs("the year is the stream's, not this year")
                .startsWith(String.valueOf(RECEIVED.atZone(ZoneOffset.UTC).getYear()));
    }

    @Test
    void aSecondStreamInOneScopeDoesNotKeepTheFirstsYear() {
        // The worker runs a batch of attempts in one pipeline scope, and the meta holder is scoped to it.
        // An attempt that did not replace the previous attempt's meta would silently take the previous
        // stream's year.
        final AtomicReference<String> first = new AtomicReference<>();
        final AtomicReference<String> second = new AtomicReference<>();
        pipelineScopeRunnable.scopeRunnable(() -> {
            final DocRef fragment = write("partial-dates-v2");
            metaHolderProvider.get().setMeta(stream(OLDER));
            first.set(when(run(fragment)));
            metaHolderProvider.get().setMeta(stream(RECEIVED));
            second.set(when(run(fragment)));
        });

        assertThat(first.get()).startsWith(String.valueOf(OLDER.atZone(ZoneOffset.UTC).getYear()));
        assertThat(second.get()).startsWith(String.valueOf(RECEIVED.atZone(ZoneOffset.UTC).getYear()));
        assertThat(first.get())
                .describedAs("the second stream's date is its own, not the first's")
                .isNotEqualTo(second.get());
    }

    @Test
    void theModulesStandInFillsTheYearInTheSameWay() {
        // The scenario harness and a node must agree, or a translation that passes the scenarios would
        // behave differently in a real pipeline.
        final AtomicReference<String> real = new AtomicReference<>();
        pipelineScopeRunnable.scopeRunnable(() -> {
            final DocRef fragment = write("partial-dates-v3");
            metaHolderProvider.get().setMeta(stream(RECEIVED));
            real.set(when(run(fragment)));
        });

        assertThat(standIn())
                .describedAs("the stand-in and Stroom's own function fill the year in the same way")
                .isEqualTo(real.get());
    }

    private DocRef write(final String name) {
        return fragmentWriter.write(FOLDER, name, List.of(
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), null)), null);
    }

    private String run(final DocRef fragment) {
        final FragmentRunner runner = new PipelineFragmentRunner(pipelineStore, pipelineDataCache,
                elementRegistryFactory, pipelineFactoryProvider, headlessCaptureProvider,
                errorReceiverProvider, fragmentOutputProvider, fragmentTapProvider, injectedCodeProvider,
                taskContextFactory);
        final List<Attempted> steps = runner.run(fragment, INPUT, (RecordBoundary) null);
        return steps.get(steps.size() - 1).result().output();
    }

    /// Runs the same translation through the module's stand-in XSLT step, which the scenario harness uses,
    /// and returns what it wrote in `When`.
    private static String standIn() {
        return when(new StandInXsltStep(new StreamFacts(1L, "SYSLOG", "Raw Events", RECEIVED.toEpochMilli(),
                Map.of())).prepare(XSLT).run(INPUT).output());
    }

    /// @param output The XML output of a translation run.
    /// @return The text of the first `When` element in the output.
    private static String when(final String output) {
        assertThat(output).describedAs("the translation produced output").isNotNull();
        return output.replaceAll("(?s).*?<When[^>]*>", "").replaceAll("(?s)</When>.*", "").trim();
    }

    private static Meta stream(final Instant received) {
        return Meta.builder()
                .id(1L)
                .feedName("SYSLOG")
                .typeName("Raw Events")
                .createMs(received.toEpochMilli())
                .build();
    }
}
