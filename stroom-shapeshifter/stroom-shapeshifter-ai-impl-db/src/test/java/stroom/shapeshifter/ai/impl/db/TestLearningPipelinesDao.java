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
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Attempts.Attempt;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.LearnerStates;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.ai.state.InMemoryAttempts;
import stroom.shapeshifter.ai.state.InMemoryDeferrals;
import stroom.shapeshifter.ai.state.InMemoryDeletedPipelines;
import stroom.shapeshifter.ai.state.InMemoryGuidance;
import stroom.shapeshifter.ai.state.InMemoryLearnerBindings;
import stroom.shapeshifter.ai.state.InMemoryLearnerStates;
import stroom.shapeshifter.ai.state.InMemoryLearningPipelines;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.SupervisorLearner;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// The Supervisor's learning pipelines list (design 06 A115, A142) as the node reads it in one statement,
/// held to the in-memory list the scenarios read: the same learners in the same states in the same order,
/// filtered and paged the same way.
class TestLearningPipelinesDao {

    private final String run = Long.toString(System.nanoTime());
    private final String doc = "doc-" + run;
    private final String offDoc = "off-" + run;
    private final String unreadDoc = "unread-" + run;
    private final Learner serving = learner("serving");
    private final Learner learning = learner("learning");
    private final Learner review = learner("review");
    private final Learner givenUp = learner("given-up");
    private final Learner off = learner("off");
    private final Learner deleted = learner("deleted");
    private final Learner forgotten = learner("forgotten");
    private final Learner unread = learner("unread");
    /// Known by its state alone: given up, its streams long since errored, no binding or session left.
    private final Learner quiet = learner("quiet");
    private final List<String> readable = List.of(doc, offDoc);
    private final Set<String> learningOff = Set.of(offDoc);

    @Inject
    private LearnerBindingsDao learnerBindings;
    @Inject
    private LearnerStatesDao states;
    @Inject
    private AttemptsDao attempts;
    @Inject
    private DeferralsDao deferrals;
    @Inject
    private GuidanceDao guidance;
    @Inject
    private DeletedPipelinesDao deletedPipelines;
    @Inject
    private LearningPipelinesDao rows;

    private final InMemoryDeletedPipelines memoryDeleted = new InMemoryDeletedPipelines();
    private final InMemoryLearnerStates memoryStates = new InMemoryLearnerStates();
    private final InMemoryLearnerBindings memoryBindings = new InMemoryLearnerBindings();
    private final InMemoryAttempts memoryAttempts = new InMemoryAttempts(memoryDeleted);
    private final InMemoryDeferrals memoryDeferrals = new InMemoryDeferrals();
    private final InMemoryGuidance memoryGuidance = new InMemoryGuidance();
    private final LearningPipelines memory = new InMemoryLearningPipelines(memoryStates, memoryBindings,
            memoryAttempts, memoryDeferrals, memoryGuidance, memoryDeleted);

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        given(learnerBindings, states, attempts, deferrals, guidance, deletedPipelines);
        given(memoryBindings, memoryStates, memoryAttempts, memoryDeferrals, memoryGuidance, memoryDeleted);
    }

    @Test
    void eachLearnerIsListedInTheStateItIsInMostWaitingFirst() {
        final List<SupervisorLearner> listed = rows.learners(readable, learningOff, List.of(), false, 0L, 100)
                .learners();

        assertThat(listed)
                .extracting(row -> row.getLearner().getPipelineUuid(), SupervisorLearner::getState,
                        SupervisorLearner::getWaiting)
                .describedAs("most waiting first; the deleted pipeline and the unreadable document left out")
                .containsExactly(
                        tuple(givenUp.pipelineUuid(), LearnerState.GIVEN_UP, 3),
                        tuple(learning.pipelineUuid(), LearnerState.LEARNING, 2),
                        tuple(off.pipelineUuid(), LearnerState.LEARNING_OFF, 1),
                        tuple(quiet.pipelineUuid(), LearnerState.GIVEN_UP, 0),
                        tuple(review.pipelineUuid(), LearnerState.AWAITING_PERSON,
                                0),
                        tuple(serving.pipelineUuid(), LearnerState.SERVING, 0));
        final SupervisorLearner servingRow = listed.getLast();
        assertThat(listed.get(3).getDoc().getUuid())
                .describedAs("a learner known by its state alone is listed under the document its state names")
                .isEqualTo(doc);
        assertThat(servingRow.getBindingUuid()).isEqualTo("binding-serving-" + run);
        assertThat(servingRow.getFragment().getUuid()).isEqualTo("fragment-serving-" + run);
        assertThat(servingRow.getPromotedScore()).isEqualTo(0.93);
        assertThat(servingRow.getRollingScore()).isEqualTo(0.9);
        assertThat(servingRow.getRecords()).isEqualTo(10);
        assertThat(servingRow.getGuidance()).isEqualTo(1);
        assertThat(servingRow.getDoc().getUuid()).isEqualTo(doc);
        assertThat(listed.getFirst().getGivenUpReason()).isEqualTo("Unlearnable");
        assertThat(listed.get(1).getOldestWaitMs()).isNotNull();
        assertThat(listed.get(1).getLastSessionMs()).isNotNull();
    }

    @Test
    void theListInRowsAgreesWithTheListInMemory() {
        for (final boolean showDeleted : List.of(false, true)) {
            assertThat(summarise(rows.learners(readable, learningOff, List.of(), showDeleted, 0L, 100).learners()))
                    .describedAs("showing deleted " + showDeleted)
                    .isEqualTo(summarise(memory.learners(readable, learningOff, List.of(), showDeleted, 0L, 100)
                            .learners()));
        }
        assertThat(summarise(rows.learners(readable, learningOff, List.of(), false, 1L, 2).learners()))
                .describedAs("a page from the middle")
                .isEqualTo(summarise(memory.learners(readable, learningOff, List.of(), false, 1L, 2).learners()));
    }

    @Test
    void aStateFilterNarrowsThePageAndItsTotal() {
        final LearningPipelines.Page page = rows.learners(readable, learningOff,
                List.of(LearnerState.SERVING, LearnerState.AWAITING_PERSON), false, 0L, 1);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.learners()).singleElement()
                .satisfies(row -> assertThat(row.getState()).isEqualTo(LearnerState.AWAITING_PERSON));
        assertThat(memory.learners(readable, learningOff,
                List.of(LearnerState.SERVING, LearnerState.AWAITING_PERSON), false, 0L, 1).total())
                .isEqualTo(2);
    }

    @Test
    void aDeletedPipelinesLearnerIsShownWhenAskedForAndSaysSo() {
        assertThat(rows.learners(readable, learningOff, List.of(), true, 0L, 100).learners())
                .filteredOn(row -> row.getLearner().getPipelineUuid().equals(deleted.pipelineUuid()))
                .singleElement()
                .satisfies(row -> assertThat(row.isPipelineDeleted()).isTrue());
        assertThat(rows.learners(List.of(), learningOff, List.of(), true, 0L, 100).total())
                .describedAs("no readable document is nothing, not everything")
                .isZero();
    }

    /// A learner's document is the one it is listed under, in both forms; for a deleted pipeline's learner
    /// that never bound anything too, which its sessions alone name, so that it can be read (A137).
    @Test
    void aLearnersDocumentIsTheOneItIsListedUnder() {
        for (final LearningPipelines pipelines : List.of(rows, memory)) {
            assertThat(pipelines.learners(readable, learningOff, List.of(), true, 0L, 100).learners())
                    .allSatisfy(row -> assertThat(pipelines.documentOf(new Learner(
                            row.getLearner().getPipelineUuid(), row.getLearner().getElementId())))
                            .contains(row.getDoc().getUuid()));
            assertThat(pipelines.documentOf(forgotten)).contains(doc);
            assertThat(pipelines.documentOf(unread)).contains(unreadDoc);
            assertThat(pipelines.documentOf(learner("unknown"))).isEmpty();
        }
    }

    /// The same learners, in whichever form: written through the seams, as the stage writes them.
    private void given(final LearnerBindings bindings,
                              final LearnerStates learnerStates,
                              final Attempts sessions,
                              final Deferrals waits,
                              final Guidance said,
                              final DeletedPipelines marks) {
        bindings.append(serving, doc, binding("serving", false, 0.93));
        learnerStates.scored(serving, doc, 0.9, 10, 10);
        said.given(serving, doc, "Times are local.", "jo");

        sessions.opened(new Attempt(learning, doc, "DOOR", "Raw Events", 1L, "node-1",
                PromotionMode.AUTOMATIC, Long.MAX_VALUE), 1_000L);
        waits.deferred(learning, doc, 1L, "DOOR", "Raw Events", null, "Learning.", 0);
        waits.deferred(learning, doc, 2L, "DOOR", "Raw Events", null, "Learning.", 0);

        bindings.append(review, doc, binding("review", true, 0.91));
        learnerStates.awaitReview(review, doc, "binding-review-" + run);

        learnerStates.giveUp(givenUp, doc, "Unlearnable");
        for (long id = 1; id <= 3; id++) {
            waits.deferred(givenUp, doc, id, "SYSLOG", "Raw Events", null, "Given up.", 0);
        }

        bindings.append(off, offDoc, binding("off", false, 0.9));
        waits.deferred(off, offDoc, 1L, "LIFT", "Raw Events", null, "Learning off.", 0);

        bindings.append(deleted, doc, binding("deleted", false, 0.9));
        marks.deleted(deleted.pipelineUuid(), 1_000L);

        // A deleted pipeline that only ever had a session: nothing but the session names its document.
        sessions.opened(new Attempt(forgotten, doc, "GATE", "Raw Events", 3L, "node-1",
                PromotionMode.AUTOMATIC, Long.MAX_VALUE), 500L);
        marks.deleted(forgotten.pipelineUuid(), 1_000L);

        bindings.append(unread, unreadDoc, binding("unread", false, 0.9));

        learnerStates.giveUp(quiet, doc, "Retired");
    }

    private Binding binding(final String name, final boolean draft, final double score) {
        return Binding.builder()
                .uuid("binding-" + name + "-" + run)
                .pipeline(new DocRef("Pipeline", "fragment-" + name + "-" + run, name))
                .draft(draft)
                .score(score)
                .promotedTimeMs(1_000L)
                .build();
    }

    private List<String> summarise(final List<SupervisorLearner> learners) {
        return learners.stream()
                .map(row -> row.getLearner() + " " + row.getDoc().getUuid() + " " + row.getState()
                            + " " + row.getBindingUuid() + " waiting " + row.getWaiting()
                            + " rolling " + row.getRollingScore() + " said " + row.getGuidance()
                            + " deleted " + row.isPipelineDeleted())
                .toList();
    }

    private Learner learner(final String name) {
        return new Learner(name + "-" + run, "shapeshifterAi");
    }
}
