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
import stroom.learningflow.transformation.StreamFacts;
import stroom.learningflow.transformation.XsltStep;
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

/// A timestamp with no year takes its year from the stream, not from the clock (Track 1 task 3,
/// assumption AS6).
///
/// This is the fault the dev instance showed on 11 November: syslog writes `Jun 17 09:06:02` with no year,
/// and a translation that guesses one dates every event wrongly while passing every other check. The
/// answer is `stroom:format-date`, which fills in what the timestamp leaves out from the stream's own
/// time — but only where the run holds the stream. The worker runs outside a processing task, where
/// nothing held it, so Stroom's `FormatDate` fell back to `Instant.now()`.
///
/// The test is here rather than in the module because only a node runs Stroom's real function library.
/// The module's scenario harness stands in for it (`StroomStandIns`), and the last test here is what
/// holds the two together: a stand-in that had drifted would have the model correcting a fault the
/// pipeline does not have.
class TestJudgingTakesTheYearFromTheStream extends AbstractProcessIntegrationTest {

    private static final DocPath FOLDER = DocPath.fromParts("Learning Flow", "PARTIAL-DATES");

    /// Three years before this test runs: far enough back that a year taken from the clock is
    /// unmistakable, and the shape of a stream loaded from an archive.
    private static final Instant RECEIVED = Instant.now().minus(3 * 365, ChronoUnit.DAYS);
    /// Two years before that again, for the second stream of one pipeline scope.
    private static final Instant OLDER = RECEIVED.minus(2 * 365, ChronoUnit.DAYS);

    private static final String INPUT = "<records xmlns=\"records:2\"><record><data name=\"when\" "
                                        + "value=\"Jun 17 09:06:02\"/></record></records>";

    /// A translation that does what the prompt now tells the model to do: take what the timestamp leaves
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
        // The fault assumption AS6 named: the worker carries a batch of attempts in one pipeline scope,
        // and the holders are scoped to it, so an attempt that did not replace the previous one's meta
        // would take the previous stream's year without saying so
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
        // What Track 2 step 2 could not check until the stream's time was held when judging: the
        // scenario harness and a node must agree, or a translation that passes the suite behaves
        // differently in a pipeline
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
                new LearnedStep(new XsltStep(), XSLT, new StepResult("<Events/>", List.of()), null)), null);
    }

    private String run(final DocRef fragment) {
        final FragmentRunner runner = new PipelineFragmentRunner(pipelineStore, pipelineDataCache,
                elementRegistryFactory, pipelineFactoryProvider, headlessCaptureProvider,
                errorReceiverProvider, fragmentOutputProvider, fragmentTapProvider, injectedCodeProvider,
                taskContextFactory);
        final List<Attempted> steps = runner.run(fragment, INPUT, (RecordBoundary) null);
        return steps.get(steps.size() - 1).result().output();
    }

    /// The same date through the module's stand-in, which the scenario harness runs.
    private static String standIn() {
        return when(new XsltStep(new StreamFacts(1L, "SYSLOG", "Raw Events", RECEIVED.toEpochMilli(),
                Map.of())).prepare(XSLT).run(INPUT).output());
    }

    /// @param output A run's output.
    /// @return What it wrote in its first `When`.
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
