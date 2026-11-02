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

package stroom.shapeshifter.ai.scenario;

import stroom.docref.DocRef;
import stroom.pipeline.xml.event.EventList;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.RecordJudge;
import stroom.shapeshifter.ai.fragment.ServedStream;
import stroom.shapeshifter.ai.fragment.StandInFragmentRunner;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 01 §12 item 2: the run that is judged is the run that is served, and a [StageRun] carries the
/// events of *its own* run or none at all.
///
/// A fragment runner keeps one run's events and the next run replaces them, so the events have to be
/// taken in the call that made the run. A judgement made over the element runners — which is how a chain
/// is judged as it is learned, before it has been written as a fragment — made no run and must carry
/// nothing, or it takes whatever the runner happens to be holding: a variant tried and rejected earlier
/// in the same call, or an earlier stream of the same pipeline scope.
///
/// One stage over three streams, since a stage's runner is a stage's: a stream learned, one of the same
/// learner stepped, and then a second learner learning behind it.
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
        final ShapeshifterAiDoc doc = doc();

        final StageRun learned = scenarios.process(document -> script, scenarios.learnerBindings, runner, doc, stream(1,
                FEED)).learned();
        assertThat(learned.produced()).describedAs("the stream was learned and bound").isNotNull();
        assertThat(learned.events())
                .describedAs("nothing has run the fragment it wrote, so there is nothing to serve with")
                .isNull();

        // A bound stream's run, where one is still made and handed on: the stepper's (A30), which plays
        // the run's events on to what is stepped beneath it. A task streams a bound stream instead
        // (design 04 §3) and makes no run to carry.
        final StageRun served = stage.dryRun(doc, Scenarios.LEARNER, stream(2, FEED), false);
        assertThat(served.produced()).describedAs("the same learner, bound").isNotNull();
        assertThat(served.events())
                .describedAs("judged by running the fragment, and served by that same run")
                .isSameAs(runner.emitted.get(runner.emitted.size() - 1));

        // And now a second learner, behind a stage whose runner is holding the stream before it.
        final StageRun other = scenarios.process(document -> script, scenarios.learnerBindings, runner, doc, stream(3,
                OTHER_FEED)).learned();
        assertThat(other.produced()).describedAs("the second learner learned and was bound").isNotNull();
        assertThat(other.events())
                .describedAs("a chain judged as it was learned made no run, so it carries no events — "
                             + "least of all the previous stream's")
                .isNull();
    }

    private static ShapeshifterAiDoc doc() {
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


    /// A fragment runner that emits something distinguishable on every run, as a node's does and the
    /// stand-in does not. What it emits is never fired at anything — what is being asserted is *which*
    /// run's events a judgement ended up with, and identity says that better than content.
    private static final class Emitting implements FragmentRunner {

        private final List<EventList> emitted = new ArrayList<>();
        private final FragmentRunner runner;

        private Emitting(final FragmentRunner runner) {
            this.runner = runner;
        }

        /// Served as the runner it wraps serves: what is watched here is what a run is given.
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
