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

package stroom.learningflow.scenario;

import stroom.docref.DocRef;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.RecordJudge;
import stroom.learningflow.fragment.ServedStream;
import stroom.learningflow.fragment.StandInFragmentRunner;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;
import stroom.pipeline.xml.event.EventList;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that a [StageRun] carries the events of its own fragment run or none at all, so that the run that
/// is judged is the run that is served.
///
/// A fragment runner keeps the events of its last run, and the next run replaces them, so the events have to
/// be taken in the call that made the run. A judgement made over the element runners (which is how a chain is
/// judged while it is learned, before it is written as a fragment) makes no fragment run and must carry
/// nothing. Otherwise it would pick up whatever the runner happens to hold, such as a candidate tried and
/// rejected earlier in the same call, or an earlier stream of the same pipeline.
///
/// The test uses one stage over three streams, because the runner belongs to the stage: a stream learned, a
/// stream of the same learner stepped, and then a stream of another feed processed after it.
class TestServedRunEvents {

    private static final String FEED = "DOOR-ACCESS";
    private static final String OTHER_FEED = "TURNSTILE";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void aRunCarriesTheEventsOfItsOwnRunOrNone() {
        final Scenarios scenarios = new Scenarios();
        final Emitting runner = new Emitting(new StandInFragmentRunner(scenarios.stores.pipelines,
                scenarios.stores.stackLoader, scenarios.stores.textConverters, scenarios.stores.xslts,
                scenarios.runners()));
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain().withKey("Feed", FEED)).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT))
                .expect(QuestionMatcher.chain().withKey("Feed", OTHER_FEED)).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final Stage stage = scenarios.stage(document -> script, scenarios.learnerBindings, runner);
        final LearningFlowDoc doc = doc();

        final StageRun learned = scenarios.process(document -> script, scenarios.learnerBindings, runner, doc, stream(1,
                FEED)).learned();
        assertThat(learned.produced()).describedAs("the stream was learned and bound").isNotNull();
        assertThat(learned.events())
                .describedAs("nothing has run the fragment it wrote, so there is nothing to serve with")
                .isNull();

        // The stepper still makes a run for a bound stream and passes the run's events on to the elements
        // stepped after it. A processing task streams a bound stream instead and makes no run to carry.
        final StageRun served = stage.dryRun(doc, Scenarios.LEARNER, stream(2, FEED), false);
        assertThat(served.produced()).describedAs("the same learner, bound").isNotNull();
        assertThat(served.events())
                .describedAs("judged by running the fragment, and served by that same run")
                .isSameAs(runner.emitted.get(runner.emitted.size() - 1));

        // Then a stream of another feed, through a stage whose runner still holds the previous stream's run.
        final StageRun other = scenarios.process(document -> script, scenarios.learnerBindings, runner, doc, stream(3,
                OTHER_FEED)).learned();
        assertThat(other.produced()).describedAs("the second learner learned and was bound").isNotNull();
        assertThat(other.events())
                .describedAs("a chain judged as it was learned made no run, so it carries no events — "
                             + "least of all the previous stream's")
                .isNull();
    }

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .build();
    }

    private static Input stream(final long id, final String feed) {
        return new Input(id, feed, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }


    // --------------------------------------------------------------------------------


    /// A fragment runner that records a distinct event list on every run, as a node's runner does and the
    /// stand-in does not. The events are never played to anything: the test asserts which run's events a
    /// judgement ended up with, and object identity shows that better than content.
    private static final class Emitting implements FragmentRunner {

        private final List<EventList> emitted = new ArrayList<>();
        private final FragmentRunner runner;

        private Emitting(final FragmentRunner runner) {
            this.runner = runner;
        }

        /// Serves through the wrapped runner without recording anything; only calls to `run` record events.
        @Override
        public ServedStream serve(final DocRef fragment,
                                  final InputStream input,
                                  final RecordBoundary boundary,
                                  final RecordJudge judge,
                                  final ContentHandler downstream) {
            return runner.serve(fragment, input, boundary, judge, downstream);
        }

        @Override
        public List<Attempted> run(final DocRef fragment, final String input, final RecordBoundary boundary) {
            return run(fragment, input, boundary, Map.of());
        }

        @Override
        public List<Attempted> run(final DocRef fragment,
                                   final String input,
                                   final RecordBoundary boundary,
                                   final Map<String, String> code) {
            final List<Attempted> attempted = runner.run(fragment, input, boundary, code);
            emitted.add(handler -> {
            });
            return attempted;
        }

        @Override
        public Optional<EventList> lastOutput() {
            return emitted.isEmpty()
                    ? Optional.empty()
                    : Optional.of(emitted.get(emitted.size() - 1));
        }
    }
}
