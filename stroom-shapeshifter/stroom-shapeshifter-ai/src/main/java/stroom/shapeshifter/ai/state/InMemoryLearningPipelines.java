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

package stroom.shapeshifter.ai.state;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings.Held;
import stroom.shapeshifter.ai.stage.LearnerStates;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// The learning pipelines list over the in-memory stores: what a node's query does as joins, done here in
/// Java over every learner each store knows. An agreement test holds the two to one answer.
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
                    ShapeshifterAiDoc.buildDocRef().uuid(docUuid).build(),
                    state,
                    states.reasonGivenUp(learner).orElse(null),
                    serving.map(Binding::getUuid).orElse(null),
                    serving.map(Binding::getPipeline).orElse(null),
                    serving.map(Binding::isProvisional).orElse(false),
                    serving.map(Binding::getScore).orElse(null),
                    rolling.map(LearnerStates.Rolling::score).orElse(null),
                    rolling.map(LearnerStates.Rolling::records).orElse(0),
                    waits.size(),
                    waits.stream().map(Deferral::firstDeferredMs).min(Long::compare).orElse(null),
                    false,
                    sessions.stream().map(Recorded::createTimeMs).max(Long::compare).orElse(null),
                    guidance.standing(learner).size(),
                    deleted,
                    false));
        }
        // The same order as the node's query, ties broken the same way, so that a page turned twice reads
        // the same twice.
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

    /// The document the learner runs under where it has no state to say: what its bindings, then its
    /// waiting streams, record, which are brought to the document it runs under now; failing those, its
    /// latest session's, which keeps the document it ran under.
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
