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
import stroom.learningflow.pack.DemoDocument;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Drafted;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;
import stroom.processor.api.DeferTaskException;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/// Tests the decision a processing task makes for each stream: serve it, defer it, or error it.
///
/// A task does only cheap work (find its learner, check what is known, then serve, defer or error) and
/// never learns. A stream of a learner that has learned nothing is deferred, with a session opened on it
/// and left for the worker, and a second stream of that learner waits on the same session. Neither is
/// errored to make it wait. The worker runs the session, the promotion wakes the waiting streams, and the
/// next decision on each serves it. A stream with a record short of the bar under the current binding is
/// also deferred, recording the snapshot it failed against, and is not scored again until that binding
/// changes; meanwhile, a stream of the same learner whose records all clear is served. Only a stream that
/// will never be learned is errored, which means a learner that has been given up.
class TestScenario54TheTaskDecision {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");

    /// A stream of a new learner is deferred, the worker learns from it, the promotion wakes it, and it is
    /// then served.
    @Test
    void aStreamOfANewLearnerWaitsOnASessionAndIsServedWhenItPromotes() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        final Script silent = Script.of();

        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(1L, MIXED));
        scenarios.inputs.put(stream(2L, MIXED));
        final StageRun first = task(scenarios, silent, null).decide(doc, Scenarios.LEARNER, stream(1L, MIXED));

        assertThat(first.decision()).describedAs(first.decision().toString()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) first.decision()).reason()).contains("Awaiting the model");
        assertThat(silent.asked()).describedAs("no model call in the task").isEmpty();
        assertThat(first.output()).isNull();
        final List<Recorded> attempts = scenarios.attempts.forDocument(DOC, 10);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(attempts.get(0).attempt().inputId()).isEqualTo(1L);
        assertThat(scenarios.deferrals.forDocument(DOC))
                .extracting(Deferral::inputId, Deferral::failedAgainst)
                .containsExactly(tuple(1L, null));

        // A second stream of the learner while the session is open waits on it and opens no new session.
        final StageRun second = task(scenarios, silent, null).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));
        assertThat(second.decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).describedAs("one session for one learner").hasSize(1);
        assertThat(scenarios.deferrals.forDocument(DOC)).extracting(Deferral::inputId).containsExactly(1L, 2L);

        // The worker runs the session on the stream that opened it, and the promotion wakes both streams.
        final Script script = script(scenarios, TRANSLATES_BOTH);
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();
        assertThat(scenarios.attempts.byId(attempts.get(0).id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.PROMOTED);
        // Each is woken twice: by the promotion, and by the same pass's sweep for streams a wake could not
        // reach. A deferral is removed when its stream is served, not when it is woken, so a row the wake
        // did reach is swept again, which updates no rows.
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .containsExactly(1L, 2L, 1L, 2L);

        // When tried again, each stream is bound and served as it is read, and its deferral row is removed.
        for (final long id : List.of(1L, 2L)) {
            final StageRun bound = task(scenarios, silent, null).decide(doc, Scenarios.LEARNER, stream(id, MIXED));
            assertThat(bound.decision()).describedAs(bound.decision().toString()).isInstanceOf(Bound.class);
            final TextSink sink = new TextSink();
            final ServedStream served = task(scenarios, silent, null)
                    .stream(doc, Scenarios.LEARNER, ((Bound) bound.decision()).binding(), stream(id, MIXED), sink);
            assertThat(served.clear()).isTrue();
            assertThat(served.records()).isEqualTo(6);
            assertThat(sink.text()).contains("<Action>Logoff</Action>");
        }
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
        assertThat(silent.asked()).isEmpty();
    }

    /// A record short of the bar defers the stream against the current binding's snapshot, a stream whose
    /// records all clear is served meanwhile, and the deferred stream is scored only once per binding.
    @Test
    void aStreamWithARecordShortOfTheBarWaitsAndIsNotScoredAgainUntilTheTransformMoves() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        final Binding learned = learn(scenarios, doc, LOGON_ONLY);
        final AtomicInteger runs = new AtomicInteger();
        final FragmentRunner counting = counting(scenarios, runs);
        final Script silent = Script.of();

        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        final StageRun bound = task(scenarios, silent, counting).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));
        assertThat(bound.decision()).describedAs("nothing is known until it is read").isInstanceOf(Bound.class);
        final TextSink sink = new TextSink();
        assertThatThrownBy(() -> task(scenarios, silent, counting).stream(doc, Scenarios.LEARNER, learned, stream(2L,
                MIXED), sink))
                .isInstanceOf(DeferTaskException.class)
                .hasMessageStartingWith("Record 3 fell short of the bar");
        assertThat(sink.records())
                .describedAs("the two records before it were handed on; nothing after")
                .isEqualTo(2);
        assertThat(runs.get()).describedAs("read once").isEqualTo(1);
        final Deferral row = scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow();
        assertThat(row.failedAgainst())
                .describedAs("stamped with the snapshot in force (A61)")
                .isEqualTo(learned.getSnapshot());
        assertThat(row.failingRecords())
                .describedAs("the one the run stopped at; nothing after it was read")
                .isEqualTo(1);
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isPresent();
        assertThat(scenarios.attempts.forDocument(DOC, 10))
                .describedAs("a relearning session, opened and parked, beside the one that learned the binding")
                .extracting(Recorded::status)
                .containsExactlyInAnyOrder(AttemptStatus.AWAITING_MODEL, AttemptStatus.PROMOTED);

        // Tried again before the binding changes, it is deferred because of the recorded snapshot, without
        // being run.
        final StageRun again = task(scenarios, silent, counting).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));
        assertThat(again.decision()).isInstanceOf(Deferred.class);
        assertThat(runs.get()).describedAs("not read again (§8.3)").isEqualTo(1);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow().attempts()).isEqualTo(2);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).describedAs("no second session").hasSize(2);

        // A stream of the learner whose records all clear is served while the session runs.
        final StageRun flowing = task(scenarios, silent, counting).decide(doc, Scenarios.LEARNER, stream(3L,
                CSV.input()));
        assertThat(flowing.decision()).describedAs(flowing.decision().toString()).isInstanceOf(Bound.class);
        assertThat(task(scenarios, silent, counting).stream(doc, Scenarios.LEARNER, learned, stream(3L, CSV.input()),
                new TextSink())
                .clear()).isTrue();
        assertThat(scenarios.deferrals.forDocument(DOC)).extracting(Deferral::inputId).containsExactly(2L);

        // The worker relearns on the held stream, the rebinding wakes it, and it is served by the new
        // transform.
        final Script script = script(scenarios, TRANSLATES_BOTH);
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .describedAs("the stream the binding was first learned on, woken by its promotion; and now the "
                             + "held stream, by the rebinding, and again by the pass's sweep of what waits "
                             + "against a replaced transform — nothing in a scenario has run its task, where "
                             + "in a node the task is no longer deferred and the second wake finds nothing")
                .containsExactly(1L, 2L, 2L);
        final StageRun served = task(scenarios, silent, counting).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));
        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        final Binding rebound = ((Bound) served.decision()).binding();
        assertThat(task(scenarios, silent, counting).stream(doc, Scenarios.LEARNER, rebound, stream(2L, MIXED),
                new TextSink()).clear())
                .isTrue();
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void whatNothingWillLearnErrorsRatherThanWaits() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.states.giveUp(Scenarios.LEARNER, DOC, "the parse could not be learned");

        final StageRun errored = scenarios.stage(document -> Script.of(), scenarios.learnerBindings)
                .decide(doc, Scenarios.LEARNER, stream(1L, MIXED));

        assertThat(errored.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) errored.decision()).reason()).contains("Given up");
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).isEmpty();
    }

    /// Builds the stage as a task uses it, with a silent script, since a task never asks the model.
    private static Stage task(final Scenarios scenarios, final Script silent, final FragmentRunner runner) {
        return scenarios.stage(document -> silent, scenarios.learnerBindings, runner);
    }

    /// A draft awaiting review holds its learner's streams waiting, not erroring, and Approve wakes them.
    @Test
    void aStreamBehindADraftWaitsForTheReviewAndApproveWakesIt() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc().copy().promotionMode(PromotionMode.REVIEW).build();
        final Script script = script(scenarios, TRANSLATES_BOTH);
        final StageRun drafted = scenarios.process(script, doc, stream(1L, MIXED)).learned();
        script.verifyExhausted();
        assertThat(drafted.decision()).describedAs(drafted.decision().toString()).isInstanceOf(Drafted.class);
        final Binding draft = ((Drafted) drafted.decision()).binding();
        final Script silent = Script.of();

        final StageRun waiting = task(scenarios, silent, null).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));

        assertThat(waiting.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) waiting.decision()).reason()).contains("Awaiting review");
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L)).isPresent();

        scenarios.promotion().approve(doc, draft.getUuid(), null);

        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .describedAs("the stream the draft was learned on waited behind it too")
                .containsExactly(1L, 2L);
        final StageRun served = task(scenarios, silent, null).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));
        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        task(scenarios, silent, null)
                .stream(doc, Scenarios.LEARNER, ((Bound) served.decision()).binding(), stream(2L, MIXED),
                        new TextSink());
        task(scenarios, silent, null)
                .stream(doc, Scenarios.LEARNER, ((Bound) served.decision()).binding(), stream(1L, MIXED),
                        new TextSink());
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
    }

    /// A stream tried again while its session is still open says so in its decision, but its deferral row
    /// keeps only the reason it waits.
    @Test
    void aStreamTriedAgainWhileItsSessionIsOpenKeepsOneReason() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        learn(scenarios, doc, LOGON_ONLY);
        final Script silent = Script.of();
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThatThrownBy(() -> task(scenarios, silent, null).stream(doc, Scenarios.LEARNER, binding, stream(2L,
                MIXED), new TextSink()))
                .isInstanceOf(DeferTaskException.class);

        final StageRun again = task(scenarios, silent, null).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));

        assertThat(((Deferred) again.decision()).reason())
                .startsWith("Record 3 fell short of the bar: ")
                .contains("Waiting for learning run ");
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow().reason())
                .describedAs("the row keeps why, not what each try found")
                .startsWith("Record 3 fell short of the bar: ")
                .doesNotContain("learning for this pipeline");
    }

    private static Binding learn(final Scenarios scenarios, final LearningFlowDoc doc, final String xslt) {
        final Script script = script(scenarios, xslt);
        final StageRun run = scenarios.process(script, doc, stream(1L, CSV.input())).learned();
        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        return ((Promoted) run.decision()).binding();
    }

    private static Script script(final Scenarios scenarios, final String xslt) {
        return scenarios.script(CSV.configuration(), xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
    }

    /// Wraps the test harness's fragment runner to count how many times a fragment is run over a stream.
    private static FragmentRunner counting(final Scenarios scenarios, final AtomicInteger runs) {
        final FragmentRunner standIn = new StandInFragmentRunner(scenarios.stores.pipelines,
                scenarios.stores.stackLoader, scenarios.stores.textConverters, scenarios.stores.xslts,
                scenarios.runners());
        return new FragmentRunner() {
            @Override
            public List<Attempted> run(final DocRef fragment, final String input, final RecordBoundary boundary) {
                runs.incrementAndGet();
                return standIn.run(fragment, input, boundary);
            }

            @Override
            public ServedStream serve(final DocRef fragment,
                                      final InputStream input,
                                      final RecordBoundary boundary,
                                      final RecordJudge judge,
                                      final ContentHandler downstream) {
                runs.incrementAndGet();
                return standIn.serve(fragment, input, boundary, judge, downstream);
            }
        };
    }

    private static LearningFlowDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").heldOutFraction(0.0).build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of(), data, "pipeline-1");
    }
}
