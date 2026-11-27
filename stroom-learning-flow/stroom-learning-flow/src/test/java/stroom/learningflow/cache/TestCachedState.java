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
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearnerStates;
import stroom.learningflow.state.InMemoryLearnerBindings;
import stroom.learningflow.state.InMemoryLearnerStates;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBatch;
import stroom.util.entityevent.EntityEventBus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the node-local caches in front of the learner binding and learner state tables.
///
/// A learner's bindings and state are read for every stream but written only when something is learned, so
/// they are cached. A write clears the cached copy on this node at once, and fires an event naming the
/// learner's pipeline so that every other node clears its copy too. The rolling score is not cached, because
/// it is counted across the whole cluster.
class TestCachedState {

    private static final String DOC = "doc-1";
    private static final Learner LEARNER = new Learner("pipeline-1", "learningFlow");
    private static final Learner NEIGHBOUR = new Learner("pipeline-2", "learningFlow");

    private final List<EntityEvent> fired = new ArrayList<>();
    private final EntityEventBus bus = new RecordingBus(fired);
    private final CacheManagerImpl cacheManager = new CacheManagerImpl();
    private final CountingBindings rows = new CountingBindings();
    private final CountingStates stateRows = new CountingStates();
    private final CachedLearnerBindings learnerBindings = new CachedLearnerBindings(rows, cacheManager, bus,
            LearningFlowConfig::new);
    private final CachedLearnerStates states = new CachedLearnerStates(stateRows, cacheManager, bus,
            LearningFlowConfig::new);

    @Test
    void aLearnersBindingsAreReadOnceAndHeldUntilSomethingWritesThem() {
        learnerBindings.append(LEARNER, DOC, Binding.builder().uuid("rule-1").build());
        final int afterWrite = rows.reads;

        learnerBindings.forLearner(LEARNER);
        learnerBindings.forLearner(LEARNER);
        learnerBindings.forLearner(LEARNER);

        assertThat(rows.reads).describedAs("one read of the rows, however many streams are served")
                .isEqualTo(afterWrite + 1);
        assertThat(learnerBindings.forLearner(LEARNER)).extracting(Binding::getUuid).containsExactly("rule-1");

        // A write on this node is visible on this node at once, and an event tells every other node.
        fired.clear();
        // A draft, because a learner may have only one live (non-draft) binding and a second would be refused
        learnerBindings.append(LEARNER, DOC, Binding.builder().uuid("rule-2").draft(true).build());

        assertThat(learnerBindings.forLearner(LEARNER)).extracting(Binding::getUuid)
                .describedAs("the node that wrote it is not reading its own stale copy")
                .containsExactly("rule-1", "rule-2");
        assertThat(fired).describedAs("and the others are told to let go of the learner's pipeline")
                .extracting(event -> event.getDocRef().getUuid())
                .containsExactly(LEARNER.pipelineUuid());
    }

    @Test
    void anotherNodesWriteReachesThisOneAsAnEvent() {
        learnerBindings.append(LEARNER, DOC, Binding.builder().uuid("rule-1").build());
        learnerBindings.append(NEIGHBOUR, DOC, Binding.builder().uuid("rule-n").build());
        assertThat(learnerBindings.forLearner(LEARNER)).hasSize(1);
        assertThat(learnerBindings.forLearner(NEIGHBOUR)).hasSize(1);
        // Simulate another node appending to the same learner by writing past the cache: this node only
        // learns of it through the event.
        rows.delegate.append(LEARNER, DOC, Binding.builder().uuid("rule-2").draft(true).build());
        final int beforeEvent = rows.reads;

        assertThat(learnerBindings.forLearner(LEARNER)).describedAs("still what it was told").hasSize(1);

        learnerBindings.onChange(new EntityEvent(new DocRef(Learner.EVENT_TYPE, LEARNER.pipelineUuid()),
                EntityAction.CLEAR_CACHE));

        assertThat(learnerBindings.forLearner(LEARNER)).describedAs("and reads again when told to").hasSize(2);
        assertThat(learnerBindings.forLearner(NEIGHBOUR)).describedAs("another pipeline's is left as it was")
                .hasSize(1);
        assertThat(rows.reads).describedAs("only the learner named was read again").isEqualTo(beforeEvent + 1);
    }

    @Test
    void whatALearnerSettledIntoIsHeldAndTheRollingScoreIsNot() {
        assertThat(states.reasonGivenUp(LEARNER)).isEmpty();
        assertThat(states.relearnReason(LEARNER)).isEmpty();
        assertThat(states.draftAwaiting(LEARNER)).isEmpty();
        final int afterFirst = stateRows.reads;

        assertThat(states.reasonGivenUp(LEARNER)).isEmpty();
        assertThat(states.relearnReason(LEARNER)).isEmpty();

        assertThat(stateRows.reads).describedAs("all three answers were read together, once")
                .isEqualTo(afterFirst);

        // The rolling score is updated for every served stream. It is not cached, and recording a score does
        // not read or disturb the cached state.
        states.scored(LEARNER, DOC, 1.0, 6, 10);
        states.scored(LEARNER, DOC, 1.0, 6, 10);
        assertThat(stateRows.scores).isEqualTo(2);
        assertThat(stateRows.reads).describedAs("and reads nothing back through the cache")
                .isEqualTo(afterFirst);

        // Giving up is a write, so this node sees it at once and every other node is sent an event.
        fired.clear();
        states.giveUp(LEARNER, DOC, "Rejected");

        assertThat(states.reasonGivenUp(LEARNER)).contains("Rejected");
        assertThat(states.reasonGivenUp(NEIGHBOUR)).describedAs("another learner's state is its own")
                .isEmpty();
        assertThat(fired).extracting(event -> event.getDocRef().getUuid()).containsExactly(LEARNER.pipelineUuid());
    }

    @Test
    void anEventIsOfTheLearnersOwnTypeAndOnlySaysToLetGoOfACopy() {
        // The event uses the learner's own type rather than the pipeline's, because an event on the pipeline
        // would make every node drop its cached merged pipeline data too. The action is CLEAR_CACHE because
        // nothing a person authored has changed.
        learnerBindings.append(LEARNER, DOC, Binding.builder().uuid("rule-1").build());
        states.giveUp(LEARNER, DOC, "Rejected");

        assertThat(fired).extracting(EntityEvent::getAction)
                .containsExactly(EntityAction.CLEAR_CACHE, EntityAction.CLEAR_CACHE);
        assertThat(fired).extracting(event -> event.getDocRef().getType())
                .containsOnly(Learner.EVENT_TYPE);
    }

    @Test
    void whatTheCacheHandsOutIsNotTheRowsOwnList() {
        // The cached list is shared by every serving thread on the node until it is invalidated, so a caller
        // that sorted or added to it would corrupt what every other stream is served with.
        learnerBindings.append(LEARNER, DOC, Binding.builder().uuid("rule-1").build());

        final List<Binding> bindings = learnerBindings.forLearner(LEARNER);

        assertThatThrownBy(() -> bindings.add(Binding.builder().uuid("rule-2").build()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(learnerBindings.forLearner(LEARNER)).hasSize(1);
    }


    // --------------------------------------------------------------------------------


    private static final class RecordingBus implements EntityEventBus {

        private final List<EntityEvent> fired;

        private RecordingBus(final List<EntityEvent> fired) {
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


    // --------------------------------------------------------------------------------


    /// An in-memory binding table that counts its reads, so tests can show the cache keeps the number of reads
    /// from growing with the number of streams served.
    private static final class CountingBindings implements LearnerBindings {

        private final InMemoryLearnerBindings delegate = new InMemoryLearnerBindings();
        private int reads;

        @Override
        public List<Binding> forLearner(final Learner learner) {
            reads++;
            return delegate.forLearner(learner);
        }

        @Override
        public Optional<Held> byUuid(final String bindingUuid) {
            return delegate.byUuid(bindingUuid);
        }

        @Override
        public Binding append(final Learner learner, final String docUuid, final Binding binding) {
            return delegate.append(learner, docUuid, binding);
        }

        @Override
        public boolean replace(final Learner learner, final String docUuid, final Binding binding) {
            return delegate.replace(learner, docUuid, binding);
        }

        @Override
        public void remove(final Learner learner, final String bindingUuid) {
            delegate.remove(learner, bindingUuid);
        }
    }


    // --------------------------------------------------------------------------------


    private static final class CountingStates implements LearnerStates {

        private final InMemoryLearnerStates delegate = new InMemoryLearnerStates();
        private int reads;
        private int scores;

        @Override
        public Optional<String> reasonGivenUp(final Learner learner) {
            reads++;
            return delegate.reasonGivenUp(learner);
        }

        @Override
        public OptionalDouble scored(final Learner learner, final String docUuid, final double score,
                                     final int records, final int skipped, final int memory) {
            scores++;
            return delegate.scored(learner, docUuid, score, records, skipped, memory);
        }

        @Override
        public void giveUp(final Learner learner, final String docUuid, final String reason) {
            delegate.giveUp(learner, docUuid, reason);
        }

        @Override
        public Optional<String> relearnReason(final Learner learner) {
            return delegate.relearnReason(learner);
        }

        @Override
        public void markForRelearning(final Learner learner, final String docUuid, final String reason) {
            delegate.markForRelearning(learner, docUuid, reason);
        }

        @Override
        public void awaitReview(final Learner learner, final String docUuid, final String bindingUuid) {
            delegate.awaitReview(learner, docUuid, bindingUuid);
        }

        @Override
        public Optional<String> draftAwaiting(final Learner learner) {
            return delegate.draftAwaiting(learner);
        }

        @Override
        public void reset(final Learner learner) {
            delegate.reset(learner);
        }

        @Override
        public Optional<Rolling> rolling(final Learner learner) {
            return delegate.rolling(learner);
        }
    }
}
