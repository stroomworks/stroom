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
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.fragment.FragmentOutput;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.FragmentTap;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.fragment.PipelineFragmentRunner;
import stroom.learningflow.fragment.StandInFragmentRunner;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.pipeline.xml.event.EventList;
import stroom.task.api.TaskContextFactory;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.DocPath;
import stroom.util.xml.XMLUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the run of a fragment that is judged also keeps the events it emitted, so that the Learning
/// Flow element can serve those events without transforming the stream a second time.
///
/// The stand-in fragment runner used by the module's own tests has no pipeline under it, so it keeps no
/// events and returns none rather than making some up. Those tests check what each step wrote instead.
class TestTheJudgedRunIsTheServedRun extends AbstractProcessIntegrationTest {

    private static final DocPath FOLDER = DocPath.fromParts("Learning Flow", "ONE-RUN");
    private static final String DOCUMENT = Scenarios.resource("records.json");
    private static final String XSLT = Scenarios.resource("records.xsl");

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
    private PipelineScopeRunnable pipelineScopeRunnable;

    @Test
    void theRunThatIsJudgedKeepsTheEventsThatWouldBeServed() {
        final List<StepRunner> runners = List.of(new JsonStep(), new StandInXsltStep());
        final RecordBoundary boundary = RecordBoundary.ofArray("events").atDepth(3);
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), null),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), null));

        final AtomicReference<String> judged = new AtomicReference<>();
        final AtomicReference<String> served = new AtomicReference<>();
        final AtomicReference<Optional<EventList>> stoodIn = new AtomicReference<>();
        pipelineScopeRunnable.scopeRunnable(() -> {
            final DocRef fragment = fragmentWriter.write(FOLDER, "one-run-v1", chain, boundary);

            final FragmentRunner runner = new PipelineFragmentRunner(pipelineStore, pipelineDataCache,
                    elementRegistryFactory, pipelineFactoryProvider, headlessCaptureProvider, errorReceiverProvider,
                    fragmentOutputProvider, fragmentTapProvider, injectedCodeProvider, taskContextFactory);
            final List<Attempted> steps = runner.run(fragment, DOCUMENT, boundary);
            judged.set(steps.get(steps.size() - 1).result().output());
            served.set(written(runner.lastOutput().orElseThrow()));

            stoodIn.set(new StandInFragmentRunner(pipelineStore, null, null, null, runners).lastOutput());
        });

        assertThat(judged.get()).describedAs("the run produced an output to judge").isNotBlank();
        assertThat(events(served.get()))
                .describedAs("and the events it kept are that same output, so nothing need run again")
                .isNotEmpty()
                .isEqualTo(events(judged.get()));
        assertThat(stoodIn.get())
                .describedAs("the stand-in has no pipeline under it and keeps no events")
                .isEmpty();
    }

    /// Extracts each event, in order. The outputs are compared event by event rather than as a whole
    /// because a fragment that splits emits one document per record, as any pipeline serving records does,
    /// whereas what is judged is those documents joined (see `RecordJoin`). The only difference is the
    /// repeated root element, and the events are what matter.
    private static List<String> events(final String xml) {
        return Pattern.compile("<Event>.*?</Event>", Pattern.DOTALL).matcher(canonical(xml))
                .results()
                .map(MatchResult::group)
                .toList();
    }

    /// Serialises the kept events to XML, so that they can be compared with what was judged.
    private static String written(final EventList events) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final TransformerHandler handler = XMLUtil.createTransformerHandler(false);
            handler.setResult(new StreamResult(out));
            events.fire(handler);
            return out.toString(StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /// Normalises XML for comparison, because the two serialisers indent differently and only one writes an
    /// XML declaration.
    private static String canonical(final String xml) {
        return xml.replaceAll("<\\?xml[^>]*\\?>", "").replaceAll(">\\s+<", "><").strip();
    }
}
