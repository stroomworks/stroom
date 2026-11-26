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

package stroom.learningflow.state;

import stroom.docref.DocRef;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings.Held;
import stroom.learningflow.stage.LearnerStates;
import stroom.learningflow.stage.LearningPipelines;
import stroom.pipeline.shared.PipelineDoc;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// An in-memory [LearningPipelines], which builds the Supervisor's list of learning pipelines from the
/// in-memory stores. It does in Java, over every learner any store knows, what `LearningPipelinesDao`
/// does with joins in one query. `TestLearningPipelinesDao` checks that the two give the same answer.
public final class InMemoryLearningPipelines implements LearningPipelines {

    private static final Set<AttemptStatus> OPEN = Set.of(AttemptStatus.IN_PROGRESS, AttemptStatus.AWAITING_MODEL);
    private static final Set<AttemptStatus> FOR_A_PERSON = Set.of(AttemptStatus.PARKED,
            AttemptStatus.AWAITING_REVIEW);

    private final InMemoryLearnerStates states;
    private final InMemoryLearnerBindings bindings;
    private final InMemoryAttempts attempts;
    private final InMemoryDeferrals deferrals;
    private final Guidance guidance;
    private final DeletedPipelines deletedPipelines;

    @Inject
    public InMemoryLearningPipelines(final InMemoryLearnerStates states,
                                     final InMemoryLearnerBindings bindings,
                                     final InMemoryAttempts attempts,
                                     final InMemoryDeferrals deferrals,
                                     final Guidance guidance,
                                     final DeletedPipelines deletedPipelines) {
        this.states = states;
        this.bindings = bindings;
        this.attempts = attempts;
        this.deferrals = deferrals;
        this.guidance = guidance;
        this.deletedPipelines = deletedPipelines;
    }

    @Override
    public Page learners(final Collection<String> docUuids,
                         final Set<String> learningOff,
                         final Collection<LearnerState> wanted,
                         final boolean showDeleted,
                         final String pipelineUuid,
                         final long offset,
                         final int limit) {
        if (NullSafe.isEmptyCollection(docUuids)) {
            return new Page(List.of(), 0L);
        }
        final List<Held> held = bindings.all();
        final List<Recorded> recorded = attempts.all();
        final List<Deferral> waiting = deferrals.all();
        final Set<Learner> learners = new LinkedHashSet<>(states.learners());
        held.forEach(row -> learners.add(row.learner()));
        recorded.forEach(attempt -> learners.add(attempt.attempt().learner()));
        waiting.forEach(row -> learners.add(row.learner()));

        final List<SupervisorLearner> matched = new ArrayList<>();
        for (final Learner learner : learners) {
            if (pipelineUuid != null && !pipelineUuid.equals(learner.pipelineUuid())) {
                continue;
            }
            final List<Held> mine = held.stream().filter(row -> row.learner().equals(learner)).toList();
            final List<Recorded> sessions = recorded.stream()
                    .filter(attempt -> attempt.attempt().learner().equals(learner))
                    .toList();
            final List<Deferral> waits = waiting.stream().filter(row -> row.learner().equals(learner)).toList();
            final String docUuid = states.documentOf(learner).orElseGet(() -> documentOf(mine, waits, sessions));
            final boolean deleted = deletedPipelines.isDeleted(learner.pipelineUuid());
            if (docUuid == null || !docUuids.contains(docUuid) || (deleted && !showDeleted)) {
                continue;
            }
            final Optional<Binding> serving = mine.stream()
                    .map(Held::binding)
                    .filter(binding -> !binding.isDraft())
                    .findFirst();
            final LearnerState state = stateOf(learner, docUuid, learningOff, serving, sessions);
            if (!wanted.isEmpty() && !wanted.contains(state)) {
                continue;
            }
            final Optional<LearnerStates.Rolling> rolling = states.rolling(learner);
            matched.add(new SupervisorLearner(
                    new LearnerRef(learner.pipelineUuid(), learner.elementId()),
                    new DocRef(PipelineDoc.TYPE, learner.pipelineUuid()),
                    LearningFlowDoc.buildDocRef().uuid(docUuid).build(),
                    state,
                    states.reasonGivenUp(learner).orElse(null),
                    serving.map(Binding::getUuid).orElse(null),
                    serving.map(Binding::getPipeline).orElse(null),
                    serving.map(Binding::getScore).orElse(null),
                    rolling.map(LearnerStates.Rolling::score).orElse(null),
                    rolling.map(LearnerStates.Rolling::records).orElse(0),
                    rolling.map(LearnerStates.Rolling::skips).orElse(0),
                    waits.size(),
                    waits.stream().map(Deferral::firstDeferredMs).min(Long::compare).orElse(null),
                    false,
                    sessions.stream().map(Recorded::createTimeMs).max(Long::compare).orElse(null),
                    guidance.standing(learner).size(),
                    deleted,
                    false));
        }
        // The same order as the database query, with ties broken the same way, so that the order is
        // stable from page to page.
        matched.sort(Comparator.comparingInt(SupervisorLearner::getWaiting).reversed()
                .thenComparing(SupervisorLearner::getLastSessionMs, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> row.getLearner().getPipelineUuid())
                .thenComparing(row -> row.getLearner().getElementId()));
        return new Page(matched.stream()
                .skip(Math.max(0L, offset))
                .limit(Math.max(0, limit))
                .toList(), matched.size());
    }

    @Override
    public Optional<String> documentOf(final Learner learner) {
        return states.documentOf(learner).or(() -> Optional.ofNullable(documentOf(
                bindings.all().stream().filter(row -> row.learner().equals(learner)).toList(),
                deferrals.all().stream().filter(row -> row.learner().equals(learner)).toList(),
                attempts.all().stream().filter(attempt -> attempt.attempt().learner().equals(learner)).toList())));
    }

    /// Returns the document of a learner that has no state row: the one its bindings record, failing that
    /// its waiting streams (both are kept up to date with the document it runs under), and failing those
    /// its latest session's, which records the document it ran under.
    private static String documentOf(final List<Held> held,
                                     final List<Deferral> waits,
                                     final List<Recorded> sessions) {
        if (!held.isEmpty()) {
            return held.getFirst().docUuid();
        }
        if (!waits.isEmpty()) {
            return waits.getFirst().docUuid();
        }
        return sessions.stream()
                .max(Comparator.comparingLong(Recorded::createTimeMs))
                .map(attempt -> attempt.attempt().docUuid())
                .orElse(null);
    }

    private LearnerState stateOf(final Learner learner,
                                 final String docUuid,
                                 final Set<String> learningOff,
                                 final Optional<Binding> serving,
                                 final List<Recorded> sessions) {
        if (states.reasonGivenUp(learner).isPresent()) {
            return LearnerState.GIVEN_UP;
        }
        if (learningOff.contains(docUuid)) {
            return LearnerState.LEARNING_OFF;
        }
        if (states.awaitsReview(learner)
            || sessions.stream().anyMatch(attempt -> FOR_A_PERSON.contains(attempt.status()))) {
            return LearnerState.AWAITING_PERSON;
        }
        if (serving.isEmpty() || sessions.stream().anyMatch(attempt -> OPEN.contains(attempt.status()))) {
            return LearnerState.LEARNING;
        }
        return LearnerState.SERVING;
    }
}
