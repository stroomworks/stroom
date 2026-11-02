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

package stroom.shapeshifter.ai.impl.db;

import stroom.docref.DocRef;
import stroom.shapeshifter.ai.stage.Attempts.Attempt;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.CorpusIndex;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Produced;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.ai.stage.Snapshots.Snapshot;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// A deleted pipeline's learning as the table of marks and the retention job see it (design 06 A120,
/// A134–A136): marked once and only where something is kept, kept until forgotten, and forgotten whole —
/// every row its learners keep, under the pipeline's uuid or its bindings' — and nothing of any other
/// pipeline's.
class TestDeletedPipelinesDao {

    private static final String RUN = Long.toString(System.nanoTime());
    private static final String DOC = "doc-" + RUN;
    private static final Learner GONE = new Learner("gone-" + RUN, "shapeshifterAi");
    private static final Learner STAYING = new Learner("staying-" + RUN, "shapeshifterAi");
    private static final String GONE_BINDING = "gone-rule-" + RUN;
    private static final String STAYING_BINDING = "staying-rule-" + RUN;

    @Inject
    private DeletedPipelinesDao deletedPipelines;
    @Inject
    private LearnerBindingsDao learnerBindings;
    @Inject
    private LearnerStatesDao states;
    @Inject
    private GuidanceDao guidance;
    @Inject
    private DeferralsDao deferrals;
    @Inject
    private AttemptsDao attempts;
    @Inject
    private OutputsDao outputs;
    @Inject
    private CorpusIndexDao corpus;
    @Inject
    private SnapshotsDao snapshots;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
    }

    @Test
    void aPipelineThatKeepsNothingIsNotMarked() {
        assertThat(deletedPipelines.deleted("never-learned-" + RUN, 1_000L))
                .describedAs("most pipelines deleted in Stroom never learned anything")
                .isFalse();
        assertThat(deletedPipelines.isDeleted("never-learned-" + RUN)).isFalse();
    }

    @Test
    void aDeletedPipelinesLearningIsMarkedOnceAndForgottenWhole() {
        final long gonesAttempt = learned(GONE, GONE_BINDING);
        final long stayingsAttempt = learned(STAYING, STAYING_BINDING);

        assertThat(deletedPipelines.pipelines())
                .describedAs("both kept, neither marked")
                .contains(GONE.pipelineUuid(), STAYING.pipelineUuid());

        assertThat(deletedPipelines.deleted(GONE.pipelineUuid(), 1_000L)).isTrue();
        assertThat(deletedPipelines.deleted(GONE.pipelineUuid(), 5_000L))
                .describedAs("heard again, by another node or the retention job: the first mark stands")
                .isFalse();

        assertThat(deletedPipelines.isDeleted(GONE.pipelineUuid())).isTrue();
        assertThat(deletedPipelines.deletedOf(List.of(GONE.pipelineUuid(), STAYING.pipelineUuid())))
                .containsExactly(GONE.pipelineUuid());
        assertThat(deletedPipelines.pipelines())
                .describedAs("a marked pipeline is not looked for again")
                .doesNotContain(GONE.pipelineUuid())
                .contains(STAYING.pipelineUuid());
        assertThat(deletedPipelines.deletedBefore(1_000L)).doesNotContain(GONE.pipelineUuid());
        assertThat(deletedPipelines.deletedBefore(1_001L))
                .describedAs("aged from when it was first heard")
                .contains(GONE.pipelineUuid());

        // Back, as an import of it would bring it, and gone again: the mark is cleared and made afresh.
        assertThat(deletedPipelines.restored(GONE.pipelineUuid())).isTrue();
        assertThat(deletedPipelines.isDeleted(GONE.pipelineUuid())).isFalse();
        assertThat(deletedPipelines.restored(GONE.pipelineUuid())).describedAs("nothing left to clear").isFalse();
        assertThat(deletedPipelines.deleted(GONE.pipelineUuid(), 1_000L)).isTrue();

        deletedPipelines.forget(GONE.pipelineUuid());

        assertKeepsNothing(GONE, GONE_BINDING, gonesAttempt);
        assertThat(deletedPipelines.isDeleted(GONE.pipelineUuid())).describedAs("the mark goes too").isFalse();
        assertThat(deletedPipelines.deletedBefore(Long.MAX_VALUE)).doesNotContain(GONE.pipelineUuid());

        // And nothing of a pipeline that was not deleted.
        assertThat(learnerBindings.forLearner(STAYING)).hasSize(1);
        assertThat(states.reasonGivenUp(STAYING)).isPresent();
        assertThat(guidance.standing(STAYING)).hasSize(1);
        assertThat(deferrals.of(STAYING, 7L)).isPresent();
        assertThat(attempts.byId(stayingsAttempt)).isPresent();
        assertThat(corpus.rows(STAYING_BINDING)).hasSize(1);
        assertThat(snapshots.forBinding(STAYING_BINDING)).hasSize(1);
        assertThat(outputs.boundBy(STAYING_BINDING, "fragment-" + STAYING_BINDING)).hasSize(1);

        deletedPipelines.forget(STAYING.pipelineUuid());
        assertKeepsNothing(STAYING, STAYING_BINDING, stayingsAttempt);
    }

    /// A row in every table a learner keeps rows in, under its pipeline's uuid or its binding's.
    private long learned(final Learner learner, final String bindingUuid) {
        final DocRef fragment = new DocRef("Pipeline", "fragment-" + bindingUuid, bindingUuid);
        learnerBindings.append(learner, DOC, Binding.builder()
                .uuid(bindingUuid)
                .pipeline(fragment)
                .promotedTimeMs(1_000L)
                .build());
        states.giveUp(learner, DOC, "Gave up.");
        guidance.given(learner, DOC, "Times are local.", "jo");
        deferrals.deferred(learner, DOC, 7L, "DOOR-ACCESS", "Raw Events", null, "Waiting.", 0);
        final long attemptId = attempts.opened(new Attempt(learner, DOC, "DOOR-ACCESS", "Raw Events", 7L,
                "node-1", PromotionMode.AUTOMATIC, 60_000L), 1_000L).orElseThrow();
        attempts.turn(attemptId, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Which chain?", "DSParser",
                "model", null, List.of()));
        attempts.closed(attemptId, AttemptStatus.PROMOTED, "Promoted.", bindingUuid, 0.95, 10L);
        outputs.emitted(7L, learner.pipelineUuid(), new Produced(DOC, bindingUuid, fragment, null, false, 0.95));
        corpus.add(List.of(new CorpusIndex.Row(DOC, bindingUuid, 7L, 0, Kind.TRANSLATION, 0.95, attemptId,
                1_000L)));
        snapshots.keep(new Snapshot("snapshot-" + bindingUuid, DOC, bindingUuid, fragment.getUuid(), "{}",
                Map.of(), Map.of(), 1_000L));
        return attemptId;
    }

    private void assertKeepsNothing(final Learner learner, final String bindingUuid, final long attemptId) {
        assertThat(learnerBindings.forLearner(learner)).describedAs("its bindings").isEmpty();
        assertThat(states.reasonGivenUp(learner)).describedAs("its state").isEmpty();
        assertThat(guidance.standing(learner)).describedAs("what was said to it").isEmpty();
        assertThat(deferrals.of(learner, 7L)).describedAs("what waited on it").isEmpty();
        assertThat(attempts.byId(attemptId)).describedAs("its attempts and their turns").isEmpty();
        assertThat(corpus.rows(bindingUuid)).describedAs("its corpus").isEmpty();
        assertThat(snapshots.forBinding(bindingUuid)).describedAs("its snapshots").isEmpty();
        assertThat(outputs.boundBy(bindingUuid, "fragment-" + bindingUuid)).describedAs("its outputs").isEmpty();
    }
}
