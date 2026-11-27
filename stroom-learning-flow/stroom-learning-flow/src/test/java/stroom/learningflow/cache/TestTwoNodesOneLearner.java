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

package stroom.learningflow.cache;

import stroom.cache.impl.CacheManagerImpl;
import stroom.docref.DocRef;
import stroom.learningflow.LearningFlowConfig;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.state.InMemoryLearnerBindings;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBatch;
import stroom.util.entityevent.EntityEventBus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests how the learner binding cache behaves when two nodes share one learner and one node misses the
/// other's cache-clearing event.
///
/// The other cache tests use one node, where a node's own write clears its own entry. These tests cover the
/// case where the event that should tell the *other* node is lost or late: node B goes on serving what it last
/// read, and must not overwrite node A's work with a write based on that stale copy.
///
/// Two `CachedLearnerBindings` over one store, each with its own cache and event bus, stand in for two
/// nodes. Neither bus is wired to the other node, which simulates the lost event.
class TestTwoNodesOneLearner {

    private static final String DOC = "doc-1";
    private static final Learner LEARNER = new Learner("pipeline-1", "learningFlow");

    /// The database both nodes read and write.
    private final InMemoryLearnerBindings shared = new InMemoryLearnerBindings();

    private final List<EntityEvent> firedByA = new ArrayList<>();
    private final List<EntityEvent> firedByB = new ArrayList<>();
    // Each node has its own cache manager, as real nodes do. One manager cannot hold two caches with the same
    // name anyway.
    private final CachedLearnerBindings nodeA = node(firedByA);
    private final CachedLearnerBindings nodeB = node(firedByB);

    @Test
    void aNodeThatMissesTheEventGoesOnServingWhatItLastRead() {
        // This is expected rather than a fault, and is the premise of the tests that follow: without the
        // event, nothing tells node B that the stored binding has changed.
        nodeA.append(LEARNER, DOC, Binding.builder().uuid("first").build());
        assertThat(nodeB.forLearner(LEARNER)).extracting(Binding::getUuid).containsExactly("first");

        shared.replace(LEARNER, DOC, shared.forLearner(LEARNER).get(0).copy()
                .pipeline(new DocRef("Pipeline", "fragment-2", "rebound"))
                .build());

        assertThat(nodeB.forLearner(LEARNER).get(0).getPipeline())
                .describedAs("B is still holding the copy it read")
                .isNull();
    }

    @Test
    void aStaleNodeCannotAppendASecondLiveBinding() {
        // A node whose cache predates another node's promotion could otherwise write a second live binding.
        // The first by id would then win, and the second would be orphaned: serving nothing, shown nowhere,
        // and with a regression corpus of its own.
        nodeA.append(LEARNER, DOC, Binding.builder().uuid("promoted-by-a").build());
        // B has never read A's binding, so believes it is binding this learner for the first time.
        final Binding written = nodeB.append(LEARNER, DOC, Binding.builder().uuid("promoted-by-b").build());

        assertThat(written.getUuid())
                .describedAs("B is handed A's promotion rather than adding a rival to it")
                .isEqualTo("promoted-by-a");
        assertThat(shared.forLearner(LEARNER)).extracting(Binding::getUuid)
                .describedAs("one live binding for one learner")
                .containsExactly("promoted-by-a");
    }

    @Test
    void aStaleNodesReplaceDoesNotRevertTheOtherNodesWork() {
        // The copy B holds was read before A rebound the learner, so writing every field back from it would
        // revert A's pipeline and snapshot.
        nodeA.append(LEARNER, DOC, Binding.builder().uuid("one").build());
        final Binding asBSawIt = nodeB.forLearner(LEARNER).get(0);

        nodeA.replace(LEARNER, DOC, nodeA.forLearner(LEARNER).get(0).copy()
                .pipeline(new DocRef("Pipeline", "fragment-2", "rebound"))
                .snapshot("snapshot-2")
                .build());
        final boolean written = nodeB.replace(LEARNER, DOC, asBSawIt.copy().score(0.9).build());

        assertThat(written).describedAs("B's write is refused, not applied").isFalse();
        assertThat(shared.forLearner(LEARNER).get(0).getPipeline().getUuid())
                .describedAs("and A's rebind stands")
                .isEqualTo("fragment-2");
        assertThat(shared.forLearner(LEARNER).get(0).getSnapshot()).isEqualTo("snapshot-2");
    }

    @Test
    void aRefusedWriteClearsTheStaleNodesCache() {
        // A refused write is how B finds out its copy is stale, so it must drop that copy and read the
        // table again next time.
        nodeA.append(LEARNER, DOC, Binding.builder().uuid("one").build());
        final Binding asBSawIt = nodeB.forLearner(LEARNER).get(0);
        nodeA.replace(LEARNER, DOC, nodeA.forLearner(LEARNER).get(0).copy()
                .pipeline(new DocRef("Pipeline", "fragment-2", "rebound"))
                .build());

        nodeB.replace(LEARNER, DOC, asBSawIt.copy().score(0.9).build());

        assertThat(nodeB.forLearner(LEARNER).get(0).getPipeline().getUuid())
                .describedAs("B reads the rows again and sees A's work")
                .isEqualTo("fragment-2");
    }

    /// Creates one simulated node: its own cache over the shared store, with an event bus that reaches no
    /// other node.
    ///
    /// @param fired Records the events this node fires.
    /// @return The node's cached view of the shared bindings.
    private CachedLearnerBindings node(final List<EntityEvent> fired) {
        return new CachedLearnerBindings(shared, new CacheManagerImpl(), new ToItself(fired),
                LearningFlowConfig::new);
    }


    // --------------------------------------------------------------------------------


    /// An event bus that only records events and delivers them to no other node, simulating a lost event.
    private static final class ToItself implements EntityEventBus {

        private final List<EntityEvent> fired;

        private ToItself(final List<EntityEvent> fired) {
            this.fired = fired;
        }

        @Override
        public void fire(final EntityEvent event) {
            fired.add(event);
        }

        @Override
        public void fire(final EntityEventBatch events) {
            fired.addAll(events.getEntityEvents());
        }
    }
}
