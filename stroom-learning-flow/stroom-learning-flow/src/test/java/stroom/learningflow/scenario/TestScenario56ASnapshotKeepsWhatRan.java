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
import stroom.learningflow.pack.DemoDocument;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Snapshots.Snapshot;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;
import stroom.meta.api.StandardHeaderArguments;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.processor.api.DeferTaskException;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests that each promotion keeps a snapshot of the configuration, and that relearning edits the existing
/// fragment in place.
///
/// A learner's binding points at a snapshot of its fragment's configuration. Relearning edits the same
/// fragment (the same pipeline document and stylesheet document, with the text replaced) and keeps a new
/// snapshot. Because the binding's snapshot changes, a stream deferred against the old snapshot is scored
/// again. Each output records the snapshot that produced it, so reprocessing "as processed" runs that
/// snapshot's code, not the fragment's current content. A stylesheet edited by hand is snapshotted the first
/// time the binding serves after the edit. Retracting a binding deletes its snapshots.
class TestScenario56ASnapshotKeepsWhatRan {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String PIPELINE = "pipeline-1";

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");

    @Test
    void aRelearningEditsTheFragmentInPlaceAndTheStampMovesWithTheSnapshot() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        final Binding learned = learn(scenarios, doc, LOGON_ONLY);
        final String first = learned.getSnapshot();
        assertThat(first).describedAs("a promotion keeps a snapshot (A61)").isNotNull();
        assertThat(scenarios.snapshots.forBinding(learned.getUuid()))
                .extracting(Snapshot::id)
                .containsExactly(first);
        assertThat(scenarios.snapshots.byId(first).orElseThrow().code().values())
                .describedAs("the code each element ran with")
                .anySatisfy(code -> assertThat(code).isEqualTo(LOGON_ONLY))
                .anySatisfy(code -> assertThat(code).isEqualTo(CSV.configuration()));

        // A stream falls short under the binding and is deferred against that snapshot.
        final Script silent = Script.of();
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, learned, stream(2L, MIXED),
                new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow().failedAgainst()).isEqualTo(first);

        // The worker relearns, editing the same fragment in place and keeping a new snapshot.
        final Script relearning = script(scenarios, TRANSLATES_BOTH);
        assertThat(scenarios.worker(relearning).advance(10)).isEqualTo(1);
        relearning.verifyExhausted();
        final Binding rebound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(rebound.getUuid()).isEqualTo(learned.getUuid());
        assertThat(rebound.getPipeline()).describedAs("the same fragment").isEqualTo(learned.getPipeline());
        assertThat(rebound.getSnapshot()).describedAs("a new snapshot").isNotEqualTo(first);
        assertThat(scenarios.stores.pipelines.list()).describedAs("no new fragment was written").hasSize(1);
        assertThat(scenarios.stores.xslts.list()).describedAs("nor a new stylesheet").hasSize(1);
        assertThat(stylesheet(scenarios, rebound.getPipeline())).isEqualTo(TRANSLATES_BOTH);
        assertThat(scenarios.snapshots.forBinding(learned.getUuid())).extracting(Snapshot::id)
                .containsExactly(first, rebound.getSnapshot());

        // The binding's snapshot changed, so the deferred stream is scored again, and now clears.
        final StageRun decided = task(scenarios, silent).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));
        assertThat(decided.decision()).describedAs(decided.decision().toString()).isInstanceOf(Bound.class);
        final TextSink sink = new TextSink();
        assertThat(task(scenarios, silent).stream(doc, Scenarios.LEARNER, ((Bound) decided.decision()).binding(),
                stream(2L, MIXED), sink)
                .clear()).isTrue();
        assertThat(sink.text()).contains("<Action>Logoff</Action>");
    }

    /// Covers a task that finds a record short of the bar while judging against a snapshot that a relearning
    /// has just replaced. It neither marks the learner nor opens a session, which would relearn what has just
    /// been learned and ask the model again. Instead it waits against the old snapshot. Because the
    /// promotion's wake came before its deferral row was written, it is the learning job's next pass that
    /// wakes it, to be scored against the new snapshot.
    @Test
    void aStreamJudgedAgainstAReplacedSnapshotIsTriedAgainRatherThanRelearned() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        scenarios.inputs.put(stream(3L, MIXED));
        final Binding learned = learn(scenarios, doc, LOGON_ONLY);
        final Script silent = Script.of();
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, learned, stream(2L, MIXED),
                new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        final int attempts = scenarios.attempts.forDocument(DOC, 10).size();

        // A task serves stream 3 under the current snapshot. While it runs, after the records before the
        // logoff have been passed on, the worker relearns and promotes. The logoff then falls short under the
        // binding as it was when the run began.
        final Script relearning = script(scenarios, TRANSLATES_BOTH);
        final ContentHandler relearnedMidRun = new DefaultHandler() {
            private boolean relearned;

            @Override
            public void startElement(final String uri, final String localName, final String qName,
                                     final Attributes attributes) {
                if (!relearned) {
                    relearned = true;
                    assertThat(scenarios.worker(relearning).advance(10)).isEqualTo(1);
                }
            }
        };
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, learned, stream(3L, MIXED),
                relearnedMidRun))
                .isInstanceOf(DeferTaskException.class)
                .hasMessageContaining("since replaced");
        relearning.verifyExhausted();
        final Binding rebound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(rebound.getSnapshot()).isNotEqualTo(learned.getSnapshot());
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER))
                .describedAs("the learner is not marked on a stale judgement")
                .isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10))
                .describedAs("no session opened beyond the relearning")
                .hasSize(attempts);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 3L).orElseThrow().failedAgainst())
                .isEqualTo(learned.getSnapshot());
        assertThat(scenarios.wake.woken())
                .describedAs("the promotion woke what waited before the row was written")
                .extracting(Deferral::inputId)
                .doesNotContain(3L);

        // The learning job's pass wakes it, and it clears under the current snapshot.
        assertThat(scenarios.worker(Script.of()).advance(10)).isZero();
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(3L);
        final StageRun decided = task(scenarios, silent).decide(doc, Scenarios.LEARNER, stream(3L, MIXED));
        assertThat(decided.decision()).describedAs(decided.decision().toString()).isInstanceOf(Bound.class);
        assertThat(task(scenarios, silent).stream(doc, Scenarios.LEARNER, ((Bound) decided.decision()).binding(),
                stream(3L, MIXED), new TextSink()).clear()).isTrue();
    }

    @Test
    void anAsProcessedReprocessRunsTheSnapshotThatProducedTheOutput() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        final Binding learned = learn(scenarios, doc, LOGON_ONLY);
        final Script silent = Script.of();
        // The first stream was served under the first snapshot by the session that learned from it. A second
        // stream falls short, and the binding is relearned in place.
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, learned, stream(2L, MIXED),
                new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        final Script relearning = script(scenarios, TRANSLATES_BOTH);
        scenarios.worker(relearning).advance(10);
        relearning.verifyExhausted();
        assertThat(stylesheet(scenarios, learned.getPipeline())).isEqualTo(TRANSLATES_BOTH);

        // Reprocessed as processed, the first stream goes back through the code that produced its output (the
        // logon-only stylesheet), even though the fragment now translates both.
        final StageRun again = scenarios.stage(document -> silent, scenarios.learnerBindings)
                .reprocess(doc, Scenarios.LEARNER, stream(1L, CSV.input()));
        assertThat(again.decision()).describedAs(again.decision().toString()).isInstanceOf(Bound.class);
        assertThat(again.produced().snapshotId()).isEqualTo(learned.getSnapshot());
        assertThat(again.output()).doesNotContain("Logoff");
    }

    @Test
    void aStylesheetEditedByHandIsSnapshottedTheFirstTimeTheBindingServes() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        final Binding learned = learn(scenarios, doc, TRANSLATES_BOTH);
        final Script silent = Script.of();

        // The stylesheet is edited by hand so that it translates only logons.
        edit(scenarios, learned.getPipeline(), LOGON_ONLY);
        final StageRun decided = task(scenarios, silent).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));

        final Binding fresh = ((Bound) decided.decision()).binding();
        assertThat(fresh.getSnapshot()).describedAs("a promotion by hand (A72)").isNotEqualTo(learned.getSnapshot());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getSnapshot())
                .isEqualTo(fresh.getSnapshot());
        assertThat(scenarios.snapshots.byId(fresh.getSnapshot()).orElseThrow().code().values())
                .anySatisfy(code -> assertThat(code).isEqualTo(LOGON_ONLY));
        // The edited stylesheet is what serves: the logoffs fall short, and the stream is deferred against
        // the new snapshot.
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, fresh, stream(2L, MIXED),
                new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER,
                2L).orElseThrow().failedAgainst()).isEqualTo(fresh.getSnapshot());
        // Serving again before anything else changes takes no third snapshot.
        task(scenarios, silent).decide(doc, Scenarios.LEARNER, stream(3L, CSV.input()));
        assertThat(scenarios.snapshots.forBinding(learned.getUuid())).hasSize(2);
    }

    // There is no test of a fragment shared between learners: a learner is one element in one pipeline, so
    // no two learners can share a fragment.

    @Test
    void aRetractedBindingTakesItsSnapshotsWithIt() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        final Binding learned = learn(scenarios, doc, TRANSLATES_BOTH);
        assertThat(scenarios.snapshots.forBinding(learned.getUuid())).hasSize(1);

        scenarios.promotion().retract(doc, learned.getUuid(), "wrong",
                "jo");

        assertThat(scenarios.snapshots.forBinding(learned.getUuid())).isEmpty();
    }

    private static Binding learn(final Scenarios scenarios, final LearningFlowDoc doc, final String xslt) {
        final Script script = script(scenarios, xslt);
        final StageRun run = scenarios.process(script, doc, stream(1L, CSV.input())).learned();
        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        return ((Promoted) run.decision()).binding();
    }

    private static Stage task(final Scenarios scenarios, final Script silent) {
        return scenarios.stage(document -> silent, scenarios.learnerBindings);
    }

    private static Script script(final Scenarios scenarios, final String xslt) {
        return scenarios.script(CSV.configuration(), xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
    }

    /// Finds the fragment's stylesheet document.
    private static DocRef stylesheetRef(final Scenarios scenarios, final DocRef fragment) {
        return scenarios.stores.pipelines.readDocument(fragment).getPipelineData().getAddedProperties().stream()
                .map(PipelineProperty::getValue)
                .filter(value -> value != null && value.getEntity() != null
                                 && XsltDoc.TYPE.equals(value.getEntity().getType()))
                .map(value -> value.getEntity())
                .findFirst()
                .orElseThrow();
    }

    private static String stylesheet(final Scenarios scenarios, final DocRef fragment) {
        return scenarios.stores.xslts.readDocument(stylesheetRef(scenarios, fragment)).getData();
    }

    private static void edit(final Scenarios scenarios, final DocRef fragment, final String xslt) {
        final DocRef ref = stylesheetRef(scenarios, fragment);
        scenarios.stores.xslts.writeDocument(scenarios.stores.xslts.readDocument(ref).copy().data(xslt).build());
    }

    private static LearningFlowDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").heldOutFraction(0.0).build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of(), data, PIPELINE);
    }

    private static Input stream(final long id, final String data, final String format) {
        return new Input(id, FEED, "Raw Events", Map.of(StandardHeaderArguments.FORMAT, format), data, PIPELINE);
    }
}
