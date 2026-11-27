/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.learningflow.impl.db;

import stroom.docref.DocRef;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings.Held;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that [LearnerBindingsDao] keeps the promises of the `LearnerBindings` interface against a real
/// database. A binding belongs to one learner, and its UUID identifies it across all learners. The database
/// outlives a test run, so every UUID and learner here is made unique to the run.
class TestLearnerBindingsDao {

    private static final String RUN = String.valueOf(System.nanoTime());
    private static final String DOC = "doc-" + RUN;
    private static final Learner LEARNER = new Learner("pipeline-" + RUN, "learningFlow");
    private static final Learner OTHER = new Learner("pipeline-" + RUN, "learningFlow2");

    @Inject
    private LearnerBindingsDao learnerBindings;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        for (final Learner learner : List.of(LEARNER, OTHER)) {
            learnerBindings.forLearner(learner).forEach(binding -> learnerBindings.remove(learner, binding.getUuid()));
        }
    }

    @Test
    void aBoundaryKeepsWhereItsRecordsSit() {
        // A binding's fragment splits the input at a depth, which is stored with the boundary because it
        // cannot be derived from it. A binding read back without it would produce a fragment that splits
        // in the wrong place, and equals() ignores the depth, so no other test would notice.
        learnerBindings.append(LEARNER, DOC, Binding.builder()
                .uuid(fresh("array"))
                .recordBoundary(RecordBoundary.ofArray("events").atDepth(3))
                .build());
        // A draft beside it: a learner has only one live binding, but a draft awaiting review can exist
        // alongside it.
        learnerBindings.append(LEARNER, DOC, Binding.builder()
                .uuid(fresh("element"))
                .draft(true)
                .recordBoundary(RecordBoundary.ofElement("Event"))
                .build());

        final List<Binding> read = learnerBindings.forLearner(LEARNER);

        assertThat(read.get(0).getRecordBoundary().getArray()).isEqualTo("events");
        assertThat(read.get(0).getRecordBoundary().splitDepth())
                .describedAs("where its records sit, kept with their name").hasValue(3);
        assertThat(read.get(1).getRecordBoundary().splitDepth())
                .describedAs("a binding stored before the depth was recorded has none, and is written "
                             + "without a filter until it is learned again")
                .isEmpty();
    }

    @Test
    void aBoundaryOfAContainersChildrenSurvivesTheRoundTrip() {
        // This form of boundary is what lets a feed whose records have different element names be learned
        // at all. It needs a column of its own; otherwise it would be written as all nulls and read back
        // as no boundary, and a node would lose it silently.
        learnerBindings.append(LEARNER, DOC, Binding.builder()
                .uuid(fresh("children"))
                .recordBoundary(RecordBoundary.ofChildrenOf("batch").atDepth(2))
                .build());

        final RecordBoundary read = learnerBindings.forLearner(LEARNER).get(0).getRecordBoundary();

        assertThat(read.isChildren()).isTrue();
        assertThat(read.getChildren()).isEqualTo("batch");
        assertThat(read.getElement()).describedAs("not the element of that name").isNull();
        assertThat(read.splitDepth()).hasValue(2);
        assertThat(read).isEqualTo(RecordBoundary.ofChildrenOf("batch"));
    }

    @Test
    void aBindingSurvivesTheRoundTripWholeAndSaysWhoseItIs() {
        final Binding learned = Binding.builder()
                .pipeline(new DocRef("Pipeline", "fragment-1", "door-v1"))
                .promotedTimeMs(1_700_000_000_000L)
                .score(0.97)
                .recordBoundary(RecordBoundary.ofArray("events"))
                .build();

        final Binding stored = learnerBindings.append(LEARNER, DOC, learned);

        assertThat(stored.getUuid()).describedAs("a binding without one is named on the way in").isNotNull();
        final List<Binding> read = learnerBindings.forLearner(LEARNER);
        assertThat(read).hasSize(1);
        assertThat(read.get(0)).isEqualTo(stored);
        assertThat(read.get(0).getPipeline()).isEqualTo(new DocRef("Pipeline", "fragment-1", "door-v1"));
        assertThat(read.get(0).getRecordBoundary()).isEqualTo(RecordBoundary.ofArray("events"));
        assertThat(read.get(0).getScore()).isEqualTo(0.97);
        assertThat(learnerBindings.byUuid(stored.getUuid())).contains(new Held(LEARNER, DOC, stored));
        assertThat(learnerBindings.byUuid(fresh("no-such-rule"))).isEmpty();
    }

    @Test
    void replacingABindingKeepsItAndPromotingOneThatWentPutsItBack() {
        // One live binding, and a draft beside it, since a second live binding would be refused.
        final String a = learnerBindings.append(LEARNER, DOC, named("a")).getUuid();
        final String b = learnerBindings.append(LEARNER, DOC,
                named("b").copy().draft(true).build()).getUuid();

        learnerBindings.replace(LEARNER, DOC, binding(a).copy().score(1.0).build());

        assertThat(uuids()).containsExactly(a, b);
        assertThat(binding(a).getScore()).isEqualTo(1.0);

        // A promotion must not be lost because its row was pruned between the run and the write.
        learnerBindings.remove(LEARNER, a);
        learnerBindings.replace(LEARNER, DOC, Binding.builder().uuid(a).score(0.95).build());
        assertThat(uuids()).containsExactly(b, a);
    }

    @Test
    void aSecondLiveBindingForOneLearnerIsRefusedAndTheFirstHandedBack() {
        // Without this guard, a node whose cache has not yet seen another node's promotion would write a
        // second live binding. The one with the lower id would then serve, and the other would be
        // orphaned: serving nothing, visible to nobody, and with a regression corpus of its own.
        final String first = learnerBindings.append(LEARNER, DOC, named("first")).getUuid();

        final Binding second = learnerBindings.append(LEARNER, DOC, named("second"));

        assertThat(second.getUuid())
                .describedAs("the promotion that is already there, not a rival to it")
                .isEqualTo(first);
        assertThat(uuids()).containsExactly(first);
    }

    @Test
    void aDraftSitsBesideTheLiveBinding() {
        // The guard is only on what serves a learner, and a draft awaiting review does not serve it.
        final String live = learnerBindings.append(LEARNER, DOC, named("live")).getUuid();

        final String draft = learnerBindings.append(LEARNER, DOC,
                named("draft").copy().draft(true).build()).getUuid();

        assertThat(uuids()).containsExactly(live, draft);
    }

    @Test
    void aReplaceFromACopyTheRowHasMovedPastIsRefused() {
        // A row lock alone is not enough: the second writer would write every field back from a copy taken
        // before the first writer's change, so a node writing a binding from its cache would revert another
        // node's change to the binding's pipeline and snapshot. The version check prevents this.
        final String uuid = learnerBindings.append(LEARNER, DOC, named("a")).getUuid();
        final Binding read = binding(uuid);

        assertThat(learnerBindings.replace(LEARNER, DOC,
                read.copy().pipeline(new DocRef("Pipeline", "fragment-rebound", "rebound")).build()))
                .describedAs("the first writer, from the copy it read")
                .isTrue();

        assertThat(learnerBindings.replace(LEARNER, DOC, read.copy().score(0.5).build()))
                .describedAs("the second, from the same copy, which the row has moved past")
                .isFalse();
        assertThat(binding(uuid).getPipeline().getUuid())
                .describedAs("and the rebind stands")
                .isEqualTo("fragment-rebound");
        assertThat(binding(uuid).getScore()).isNull();
    }

    @Test
    void aBindingBuiltRatherThanReadIsWrittenAsBefore() {
        // A binding with no version skips the version check, so that a promotion whose row was removed
        // between the run and the write is not lost because it cannot say which version it saw.
        final String uuid = learnerBindings.append(LEARNER, DOC, named("a")).getUuid();

        assertThat(learnerBindings.replace(LEARNER, DOC, Binding.builder().uuid(uuid).score(0.9).build()))
                .isTrue();
        assertThat(binding(uuid).getScore()).isEqualTo(0.9);
    }

    /// A row records the document the learner runs under, and a replacement updates it, so that a pipeline
    /// whose element is pointed at another document keeps what it learned, under the new document.
    @Test
    void aReplacementRecordsTheDocumentTheLearnerNowRunsUnder() {
        final String a = learnerBindings.append(LEARNER, DOC, named("a")).getUuid();

        learnerBindings.replace(LEARNER, "moved-" + RUN, binding(a).copy().score(0.9).build());

        assertThat(learnerBindings.byUuid(a)).map(Held::docUuid).contains("moved-" + RUN);
    }

    @Test
    void oneLearnersBindingsAreItsOwn() {
        final String mine = learnerBindings.append(LEARNER, DOC, named("mine")).getUuid();
        final String theirs = learnerBindings.append(OTHER, DOC, named("theirs")).getUuid();

        assertThat(uuids()).containsExactly(mine);
        assertThat(learnerBindings.byUuid(theirs)).map(Held::learner).contains(OTHER);
        learnerBindings.remove(LEARNER, theirs);
        assertThat(learnerBindings.forLearner(OTHER)).describedAs("another learner's binding is not ours to remove")
                .hasSize(1);
    }

    /// The snapshot a binding serves with survives being written and read back, and is updated by a
    /// replacement.
    @Test
    void aBindingKeepsItsSnapshotAndTakesTheNextOnReplacement() {
        learnerBindings.append(LEARNER, DOC, Binding.builder().uuid(fresh("snapshot")).snapshot("snap-1").build());

        assertThat(learnerBindings.forLearner(LEARNER).get(0).getSnapshot()).isEqualTo("snap-1");

        learnerBindings.replace(LEARNER, DOC, learnerBindings.forLearner(LEARNER).get(0).copy().snapshot("snap-2")
                .build());
        assertThat(learnerBindings.forLearner(LEARNER).get(0).getSnapshot()).isEqualTo("snap-2");
    }

    private List<String> uuids() {
        return learnerBindings.forLearner(LEARNER).stream().map(Binding::getUuid).toList();
    }

    private Binding binding(final String uuid) {
        final Optional<Held> held = learnerBindings.byUuid(uuid);
        return held.orElseThrow().binding();
    }

    private static String fresh(final String name) {
        return name + "-" + RUN;
    }

    private static Binding named(final String name) {
        return Binding.builder()
                .pipeline(new DocRef("Pipeline", "fragment-" + name, name))
                .build();
    }
}
