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

package stroom.learningflow.stage;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Attempts.Recorded;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/// What a person does to a learning run or a learner from its Learning Status tab, beyond approving or
/// rejecting what it learned ([Promotion]): give a paused run more to spend, send it to its fallback,
/// answer a question instead of the model, send a learner back to learn, or give it up (A28, A46, A65,
/// A77). Each writes rows and leaves the work to the learning job: a person's request never waits on a
/// model. Part of simplification option iii.
public final class SupervisorActions {

    private final Attempts attempts;
    private final LearnerBindings learnerBindings;
    private final LearnerStates states;
    private final RegressionSet regressionSet;
    private final Snapshots snapshots;
    private final TargetSchemas targetSchemas;
    private final Wake wake;
    private final Clock clock;
    private final Duration lease;

    /// @param attempts        The runs.
    /// @param learnerBindings The bindings, whose drafts a changed answer discards.
    /// @param states          Each learner's state.
    /// @param regressionSet   The records each binding was accepted on.
    /// @param snapshots       The configuration each binding serves with.
    /// @param targetSchemas   Resolves a document's target schema, which says whether it has a fallback.
    /// @param wake            Wakes a learner's pipeline.
    /// @param clock           The time.
    /// @param lease           How long a claim lasts unless renewed (A45).
    public SupervisorActions(final Attempts attempts,
                             final LearnerBindings learnerBindings,
                             final LearnerStates states,
                             final RegressionSet regressionSet,
                             final Snapshots snapshots,
                             final TargetSchemas targetSchemas,
                             final Wake wake,
                             final Clock clock,
                             final Duration lease) {
        this.attempts = attempts;
        this.learnerBindings = learnerBindings;
        this.states = states;
        this.regressionSet = regressionSet;
        this.snapshots = snapshots;
        this.targetSchemas = targetSchemas;
        this.wake = wake;
        this.clock = clock;
        this.lease = lease;
    }

    /// Give a parked session more to spend (A65, A77): tokens and milliseconds beyond the document's
    /// budgets, recorded against the attempt alone, and the attempt opened again for the worker to carry
    /// on with them. Only a parked attempt takes a grant: one still running has budget, and one that
    /// finished has nothing to spend it on.
    ///
    /// @param tokens Tokens to add; zero for none.
    /// @param ms     Milliseconds to add; zero for none.
    public void grant(final LearningFlowDoc doc, final long attemptId, final long tokens, final long ms) {
        final Recorded recorded = parked(doc, attemptId);
        if (tokens < 0 || ms < 0 || (tokens == 0 && ms == 0)) {
            throw new IllegalArgumentException("A grant is tokens, milliseconds or both, and not less than nothing");
        }
        attempts.granted(attemptId, tokens, ms);
        carriedOn(recorded);
    }

    /// Send a parked session to the exhaustion step (A65, A76): the attempt is opened again for the
    /// worker, which carries it on from its recorded turns and, at the first transform question it would
    /// put to the model, takes the flow's spent transition instead — to `ADMIT`, where the flow has one.
    /// Only a parked attempt is sent.
    public void exhaust(final LearningFlowDoc doc, final long attemptId) {
        final Recorded recorded = parked(doc, attemptId);
        // Marked Unknowns are event-logging's: another target has no fallback to finish with (task 7, W1-3)
        final Optional<TargetSchema> target = targetSchemas.of(doc);
        if (target.isPresent() && !target.get().isEventLogging()) {
            throw new IllegalStateException("Finish with Unknowns writes event-logging's marked Unknown events, "
                                            + "and " + target.get().name() + " has none; answer the step, or "
                                            + "leave the AI a note, instead");
        }
        // The sending first, so that no worker takes the attempt between the reopening and it; on a
        // parked row it stands harmlessly until the attempt is reopened and closed.
        attempts.exhaustNext(attemptId);
        carriedOn(recorded);
    }

    /**
     * A person's answer in place of a turn's (A28): *answer instead*, for the question an attempt stopped
     * at, and *edit and re-run from here*, for one it had already been answered. Either way the attempt
     * is left waiting, for the worker to carry on from what it has now been told — the answer is written
     * and nothing is run here, since a person's request must not wait on a model.
     * <p>
     * The turns after the one answered are discarded: what they were is a consequence of an answer that
     * has changed, and re-walking derives them again (A45). An attempt that had finished is opened again
     * and takes its learner's claim back, which fails where another attempt holds it — and an attempt a node
     * is walking right now is refused, since its answers are that walk's to give and a person's answer
     * would be overwritten by the turn it is about to record.
     *
     * @param answeredBy Who answered: recorded against the turn, and not overwritten when the attempt is
     *                   re-walked (A28).
     */
    public void amend(final LearningFlowDoc doc,
                      final long attemptId,
                      final int turnNumber,
                      final String answer,
                      final String answeredBy) {
        final Recorded recorded = attemptOf(doc, attemptId);
        // Before anything is changed: an attempt is not opened again for a turn it has not got, or a
        // person who mistyped a number would reopen a finished attempt, wipe what it came to and be told
        // their request failed.
        if (recorded.turns().stream().noneMatch(turn -> turn.number() == turnNumber)) {
            throw new IllegalArgumentException("Attempt " + attemptId + " has no turn " + turnNumber);
        }
        if (!attempts.reopened(attemptId, clock.millis(), claimUntil())) {
            throw new IllegalStateException("Attempt " + attemptId + " cannot be run again: it is being "
                                            + "carried on now, or another attempt is learning for "
                                            + recorded.attempt().learner());
        }
        attempts.amended(attemptId, turnNumber, answer, answeredBy);
        // What the attempt drafted goes with the answer that produced it. The turns after the one
        // answered are discarded for exactly this reason — they are a consequence of an answer that has
        // changed — and a draft binding is the last of those consequences. Left in place it would be a
        // draft nothing awaits: the reset below clears the pointer that says the learner is waiting for
        // it, so approving or rejecting it would throw, every stream of the learner would be sentinelled
        // by it, and the only way out would be deleting the binding by hand.
        if (recorded.bindingUuid() != null) {
            learnerBindings.byUuid(recorded.bindingUuid())
                    .map(LearnerBindings.Held::binding)
                    .filter(Binding::isDraft)
                    .ifPresent(draft -> {
                        learnerBindings.remove(recorded.attempt().learner(), draft.getUuid());
                        regressionSet.discard(draft.getUuid());
                        snapshots.discard(draft.getUuid());
                    });
        }
        // A person re-running an attempt is saying the learner is not settled: whatever this attempt
        // concluded about it — given up, marked, a draft awaiting someone — is undone, or the re-run would
        // be refused by the state its own first run left behind (A28).
        states.reset(recorded.attempt().learner());
    }

    /**
     * Send a learner back to learn afresh (A28): what a person does to an attempt they are reading when
     * the binding it wrote is not the binding they want and no particular turn is to blame. The learner's next
     * stream relearns, with the incumbent serving until the relearning settles (A29).
     *
     * @param learner The learner.
     * @param reason  Why, in the words a person gave: the model opens its next attempt with it.
     */
    public void relearn(final LearningFlowDoc doc, final Learner learner, final String reason) {
        if (states.reasonGivenUp(learner).isPresent()) {
            // A learner that gave up stays given up until something says otherwise, and a mark alone
            // says nothing: every path reads the give-up first and sentinels the stream before it ever
            // looks at the mark. Sending a learner back *is* saying otherwise, so the give-up goes — and
            // with it the rest of what was concluded, since it is being started afresh.
            states.reset(learner);
        }
        states.markForRelearning(learner, doc.getUuid(), reason);
    }

    /// A person gives a learner up (design 06 A115): it learns no more, its streams with nothing serving
    /// them error with the reason rather than wait, and an open session is not carried on. What serves it
    /// goes on serving. Sending it back to learn again undoes it ([#relearn]). Its pipeline is woken, so
    /// that the streams waiting on it come to their end now rather than at the retry delay.
    ///
    /// @param learner The learner.
    /// @param reason  Why, in the words a person gave: its streams error with it.
    /// @param by      Who gave it up.
    public void giveUp(final LearningFlowDoc doc, final Learner learner, final String reason, final String by) {
        // A draft is decided before a give-up is read, so its streams would go on waiting for a review
        // after the learner had been given up. Rejecting it is giving the learner up, and discards the draft.
        final Optional<String> draft = states.draftAwaiting(learner);
        if (draft.isPresent()) {
            throw new IllegalStateException("Draft binding " + draft.get() + " awaits review: reject it, which "
                                            + "gives the learner up with the reason");
        }
        states.giveUp(learner, doc.getUuid(), "Given up by " + by + ": " + reason);
        wake(learner);
    }

    /// The attempt, which must be the document's.
    private Recorded attemptOf(final LearningFlowDoc doc, final long attemptId) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        if (!doc.getUuid().equals(recorded.attempt().docUuid())) {
            throw new IllegalArgumentException("Attempt " + attemptId + " belongs to document "
                                               + recorded.attempt().docUuid() + ", not " + doc.getUuid());
        }
        return recorded;
    }

    /// The attempt, which must be the document's and parked for a person.
    private Recorded parked(final LearningFlowDoc doc, final long attemptId) {
        final Recorded recorded = attemptOf(doc, attemptId);
        if (recorded.status() != AttemptStatus.PARKED) {
            throw new IllegalStateException("Attempt " + attemptId + " is not parked for a person: it is "
                                            + recorded.status().getDisplayValue());
        }
        return recorded;
    }

    /// The attempt opened again for the worker to carry on, which fails where another attempt holds its
    /// learner's claim.
    private void carriedOn(final Recorded recorded) {
        final long attemptId = recorded.id();
        if (!attempts.reopened(attemptId, clock.millis(), claimUntil())) {
            throw new IllegalStateException("Attempt " + attemptId + " cannot be carried on: another attempt is "
                                            + "learning for " + recorded.attempt().learner());
        }
    }

    /// When a claim taken now lapses if nothing renews it (A45).
    private long claimUntil() {
        return clock.millis() + lease.toMillis();
    }

    private void wake(final Learner learner) {
        wake.wake(learner.pipelineUuid());
    }
}
