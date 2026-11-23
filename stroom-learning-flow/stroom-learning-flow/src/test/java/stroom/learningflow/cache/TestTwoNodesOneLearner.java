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

/// Track 1 task 8: **two nodes, one learner** — the harness the audit's W7 findings asked for.
///
/// Every other test of the cache is one node, so a node's own write clears its own entry and everything
/// agrees. What the audit found is what happens when the event that tells the *other* node is lost or
/// late: node B goes on serving what it last read, and the question is what it does with the write it
/// then makes from that stale copy.
///
/// Two `CachedLearnerBindings` over one store, each with its own cache and its own event bus, is as
/// close as a unit test gets to two nodes. The bus of one is deliberately not wired to the other, which
/// is the lost event.
class TestTwoNodesOneLearner {

    private static final String DOC = "doc-1";
    private static final Learner LEARNER = new Learner("pipeline-1", "learningFlow");

    /// The database both nodes read and write.
    private final InMemoryLearnerBindings shared = new InMemoryLearnerBindings();

    private final List<EntityEvent> firedByA = new ArrayList<>();
    private final List<EntityEvent> firedByB = new ArrayList<>();
    // A cache manager apiece, as two nodes have: one manager cannot hold two caches of one name, which
    // is itself a reminder that these are meant to be separate processes.
    private final CachedLearnerBindings nodeA = node(firedByA);
    private final CachedLearnerBindings nodeB = node(firedByB);

    @Test
    void aNodeThatMissesTheEventGoesOnServingWhatItLastRead() {
        // Not a fault, and the premise of the three that follow: without the event there is nothing to
        // tell node B that the world moved.
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
        // Audit finding W7-2. A node whose cache predates another's promotion would otherwise write a
        // second live binding, after which the first by id wins and the second is orphaned — serving
        // nothing, visible to nobody, and with a regression corpus of its own.
        nodeA.append(LEARNER, DOC, Binding.builder().uuid("promoted-by-a").build());
        // B has never seen it and believes it is learning this feed for the first time.
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
        // Audit finding W7-3. The copy B holds was read before A rebound the learner; writing every
        // field back from it would put A's pipeline and snapshot back as they were.
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
        // Losing the race is how B finds out it is stale, so it must not go on holding the copy that
        // lost: the next read is from the rows.
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

    /// A node: its own cache over the shared store, with a bus that reaches nobody else.
    ///
    /// @param fired Where its events go.
    /// @return The node's view.
    private CachedLearnerBindings node(final List<EntityEvent> fired) {
        return new CachedLearnerBindings(shared, new CacheManagerImpl(), new ToItself(fired),
                LearningFlowConfig::new);
    }


    // --------------------------------------------------------------------------------


    /// A bus that reaches nobody else: the lost event the whole of this class is about.
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
