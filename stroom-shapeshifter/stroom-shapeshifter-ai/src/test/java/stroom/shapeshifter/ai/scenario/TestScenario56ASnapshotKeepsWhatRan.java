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
import stroom.meta.api.StandardHeaderArguments;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.processor.api.DeferTaskException;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Snapshots.Snapshot;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Design 02 §5, scenario 56: **a promotion keeps a snapshot, and a session edits in place** (A58, A61,
/// A70–A72; design 04 §8 scenario 11).
///
/// A learner's binding points at a snapshot of its fragment's configuration. A relearning edits
/// the same fragment — the same pipeline document, the same stylesheet document, the text replaced —
/// and keeps a new snapshot; the deferral stamp moves with it, so a stream held against the old one is
/// scored again. An output records the snapshot that produced it, and an as-processed reprocess runs
/// that snapshot's code, not what the fragment says today. A stylesheet edited by hand is snapshotted the
/// first time the binding serves after the edit. A retracted binding takes its snapshots with it.
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
        final ShapeshifterAiDoc doc = doc();
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

        // A stream falls short under it and is held against that snapshot.
        final Script silent = Script.of();
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, learned, stream(2L, MIXED),
                new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow().failedAgainst()).isEqualTo(first);

        // The worker relearns: the same fragment, edited in place (A58), under a new snapshot.
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

        // The stamp moved, so the held stream is scored again, and now clears.
        final StageRun decided = task(scenarios, silent).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));
        assertThat(decided.decision()).describedAs(decided.decision().toString()).isInstanceOf(Bound.class);
        final TextSink sink = new TextSink();
        assertThat(task(scenarios, silent).stream(doc, Scenarios.LEARNER, ((Bound) decided.decision()).binding(),
                stream(2L, MIXED), sink)
                .clear()).isTrue();
        assertThat(sink.text()).contains("<Action>Logoff</Action>");
    }

    /// A task that judged its stream against the snapshot a relearning has just replaced finds its record
    /// short of a bar the learner no longer stands at. It neither marks the learner nor opens a session — that
    /// would relearn what has just been learned, and ask the model for it — but waits against the old
    /// stamp; and since the promotion's wake came before its row was written, the learning job's pass is
    /// what wakes it, to be scored against the new snapshot.
    @Test
    void aStreamJudgedAgainstAReplacedSnapshotIsTriedAgainRatherThanRelearned() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        scenarios.inputs.put(stream(3L, MIXED));
        final Binding learned = learn(scenarios, doc, LOGON_ONLY);
        final Script silent = Script.of();
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, learned, stream(2L, MIXED),
                new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        final int attempts = scenarios.attempts.forDocument(DOC, 10).size();

        // A task serves stream 3 under the snapshot in force, and while it runs — the records before the
        // logoff already handed on — the worker relearns and promotes. The logoff then falls short of the
        // bar the learner stood at when the run began.
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

        // The learning job's pass wakes it, and it clears under the snapshot in force.
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
        final ShapeshifterAiDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        final Binding learned = learn(scenarios, doc, LOGON_ONLY);
        final Script silent = Script.of();
        // The first stream was emitted under the first snapshot by the session that learned it; a second
        // stream falls short and the binding is relearned in place.
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, learned, stream(2L, MIXED),
                new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        final Script relearning = script(scenarios, TRANSLATES_BOTH);
        scenarios.worker(relearning).advance(10);
        relearning.verifyExhausted();
        assertThat(stylesheet(scenarios, learned.getPipeline())).isEqualTo(TRANSLATES_BOTH);

        // As processed: the first stream goes back through the code that produced its output — the
        // logon-only stylesheet — though the fragment now translates both.
        final StageRun again = scenarios.stage(document -> silent, scenarios.learnerBindings)
                .reprocess(doc, Scenarios.LEARNER, stream(1L, CSV.input()));
        assertThat(again.decision()).describedAs(again.decision().toString()).isInstanceOf(Bound.class);
        assertThat(again.produced().snapshotId()).isEqualTo(learned.getSnapshot());
        assertThat(again.output()).doesNotContain("Logoff");
    }

    @Test
    void aStylesheetEditedByHandIsSnapshottedTheFirstTimeTheBindingServes() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final Binding learned = learn(scenarios, doc, TRANSLATES_BOTH);
        final Script silent = Script.of();

        // Edited by hand: the stylesheet now admits nothing but a logon.
        edit(scenarios, learned.getPipeline(), LOGON_ONLY);
        final StageRun decided = task(scenarios, silent).decide(doc, Scenarios.LEARNER, stream(2L, MIXED));

        final Binding fresh = ((Bound) decided.decision()).binding();
        assertThat(fresh.getSnapshot()).describedAs("a promotion by hand (A72)").isNotEqualTo(learned.getSnapshot());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getSnapshot())
                .isEqualTo(fresh.getSnapshot());
        assertThat(scenarios.snapshots.byId(fresh.getSnapshot()).orElseThrow().code().values())
                .anySatisfy(code -> assertThat(code).isEqualTo(LOGON_ONLY));
        // And the edit is what serves: the logoffs fall short, against the new stamp.
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, fresh, stream(2L, MIXED),
                new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER,
                2L).orElseThrow().failedAgainst()).isEqualTo(fresh.getSnapshot());
        // Served again before anything else changes: no third snapshot.
        task(scenarios, silent).decide(doc, Scenarios.LEARNER, stream(3L, CSV.input()));
        assertThat(scenarios.snapshots.forBinding(learned.getUuid())).hasSize(2);
    }

    // A fragment shared with a neighbouring learner is retired with binding by looking (design 06 phase J
    // slice 2a): a learner is one element in one pipeline, so no second learner can come to share the
    // first's fragment.

    @Test
    void aRetractedBindingTakesItsSnapshotsWithIt() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final Binding learned = learn(scenarios, doc, TRANSLATES_BOTH);
        assertThat(scenarios.snapshots.forBinding(learned.getUuid())).hasSize(1);

        scenarios.stage(document -> Script.of(), scenarios.learnerBindings).retract(doc, learned.getUuid(), "wrong",
                "jo");

        assertThat(scenarios.snapshots.forBinding(learned.getUuid())).isEmpty();
    }

    private static Binding learn(final Scenarios scenarios, final ShapeshifterAiDoc doc, final String xslt) {
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

    /// The fragment's stylesheet document.
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

    private static ShapeshifterAiDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").heldOutFraction(0.0).build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of(), data, PIPELINE);
    }

    private static Input stream(final long id, final String data, final String format) {
        return new Input(id, FEED, "Raw Events", Map.of(StandardHeaderArguments.FORMAT, format), data, PIPELINE);
    }
}
