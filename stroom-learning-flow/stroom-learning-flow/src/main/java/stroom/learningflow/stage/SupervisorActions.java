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

/// The actions a person can take on a learning session or a learner from the Supervisor (the Learning
/// Status tab), other than approving or rejecting a draft, which [Promotion] handles. A person can grant a
/// parked session more budget, send it to the exhaustion step, answer a question instead of the model, send
/// a learner back to learn again, or give a learner up.
///
/// Each action only writes rows and leaves the actual work to the deferred learning job, so that a person's
/// request never waits on a model. Called by `SupervisorService`, which checks permissions first.
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

    /// @param attempts        The sessions and their turns.
    /// @param learnerBindings The bindings, whose drafts are discarded when a person changes an answer.
    /// @param states          Each learner's state.
    /// @param regressionSet   The corpus records each binding was accepted on.
    /// @param snapshots       The configuration snapshots each binding serves with.
    /// @param targetSchemas   Resolves a document's target schema, which says whether marked Unknowns
    ///                        are available as a fallback.
    /// @param wake            Wakes a pipeline's deferred tasks.
    /// @param clock           The source of the current time.
    /// @param lease           How long a claim on a learner lasts unless it is renewed by a heartbeat.
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

    /// Grants a parked session more budget: tokens and milliseconds beyond the document's budgets,
    /// recorded against this session alone. The session is then reopened for the worker to resume. Only a
    /// parked session can be granted more, because one still running still has budget and one that has
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

    /// Sends a parked session to the exhaustion step. The session is reopened for the worker, which
    /// resumes it from its recorded turns. At the first transform question it would put to the model, it
    /// instead takes the flow's transition for spent candidates, which leads to the `ADMIT` step if the flow
    /// has one. Only a parked session can be sent.
    public void exhaust(final LearningFlowDoc doc, final long attemptId) {
        final Recorded recorded = parked(doc, attemptId);
        // Marked Unknowns are event-logging events, so a document with another target schema has no
        // fallback to finish with
        final Optional<TargetSchema> target = targetSchemas.of(doc);
        if (target.isPresent() && !target.get().isEventLogging()) {
            throw new IllegalStateException("Finish with Unknowns writes event-logging's marked Unknown events, "
                                            + "and " + target.get().name() + " has none; answer the step, or "
                                            + "leave the AI a note, instead");
        }
        // The flag is set before the session is reopened, so that no worker can pick up the session in
        // between without it. On a parked row the flag does no harm until the session is reopened.
        attempts.exhaustNext(attemptId);
        carriedOn(recorded);
    }

    /// Records a person's answer in place of the model's for one turn. This serves both *answer instead*,
    /// for the question a session stopped at, and *edit and re-run from here*, for a question that was
    /// already answered. Either way the answer is written and the session is left for the worker to resume
    /// from it; nothing is run here, because a person's request must not wait on a model.
    ///
    /// The turns after the one answered are discarded, because they followed from an answer that has now
    /// changed; the worker derives them again when it replays the session. A session that had finished is
    /// reopened and takes back its learner's claim, which fails if another session holds the claim. A session
    /// that a node is running right now is also refused, because that node would overwrite the person's
    /// answer with the turn it is about to record.
    ///
    /// @param answeredBy Who answered. Recorded against the turn, and kept when the session is replayed.
    public void amend(final LearningFlowDoc doc,
                      final long attemptId,
                      final int turnNumber,
                      final String answer,
                      final String answeredBy) {
        final Recorded recorded = attemptOf(doc, attemptId);
        // Checked before anything is changed. Otherwise a person who mistyped a turn number would reopen a
        // finished session, wipe its result, and then be told their request failed.
        if (recorded.turns().stream().noneMatch(turn -> turn.number() == turnNumber)) {
            throw new IllegalArgumentException("Attempt " + attemptId + " has no turn " + turnNumber);
        }
        if (!attempts.reopened(attemptId, clock.millis(), claimUntil())) {
            throw new IllegalStateException("Attempt " + attemptId + " cannot be run again: it is being "
                                            + "carried on now, or another attempt is learning for "
                                            + recorded.attempt().learner());
        }
        attempts.amended(attemptId, turnNumber, answer, answeredBy);
        // Any draft the session wrote is discarded too, for the same reason the later turns are: it
        // followed from an answer that has changed. If it were kept, the reset below would clear the
        // pointer saying the learner awaits review of it, so approving or rejecting it would fail, every
        // stream of the learner would be held back by it, and the only way out would be deleting the
        // binding by hand.
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
        // A person re-running a session is saying the learner is not settled, so whatever the session
        // concluded about it (given up, marked to relearn, a draft awaiting review) is undone. Otherwise
        // the re-run would be refused because of the state its own first run left behind.
        states.reset(recorded.attempt().learner());
    }

    /// Sends a learner back to learn afresh. A person does this when the binding a session wrote is not
    /// what they want and no particular turn is to blame. The learner's next stream starts a relearning,
    /// and the serving binding goes on serving until the relearning finishes.
    ///
    /// @param learner The learner to send back.
    /// @param reason  Why, in the person's words; the learner's next session opens with it.
    public void relearn(final LearningFlowDoc doc, final Learner learner, final String reason) {
        if (states.reasonGivenUp(learner).isPresent()) {
            // Marking alone would have no effect on a given-up learner, because every path checks for a
            // give-up first and errors the stream before it looks at the mark. Sending a learner back
            // overrides the give-up, so the learner's whole state is reset, since it is starting afresh.
            states.reset(learner);
        }
        states.markForRelearning(learner, doc.getUuid(), reason);
    }

    /// Gives a learner up at a person's request. It learns no more, and streams that nothing serves error
    /// with the reason rather than wait; a binding that is serving goes on serving. Sending the learner back
    /// to learn again ([#relearn]) undoes this. Its pipeline is woken, so that the streams waiting on it are
    /// errored now rather than after the retry delay.
    ///
    /// @param learner The learner to give up.
    /// @param reason  Why, in the person's words; its streams error with this reason.
    /// @param by      Who gave it up.
    public void giveUp(final LearningFlowDoc doc, final Learner learner, final String reason, final String by) {
        // A draft awaiting review is checked before a give-up, so the learner's streams would go on waiting
        // for a review after it had been given up. Rejecting the draft gives the learner up and discards
        // the draft, so the person is told to do that instead.
        final Optional<String> draft = states.draftAwaiting(learner);
        if (draft.isPresent()) {
            throw new IllegalStateException("Draft binding " + draft.get() + " awaits review: reject it, which "
                                            + "gives the learner up with the reason");
        }
        states.giveUp(learner, doc.getUuid(), "Given up by " + by + ": " + reason);
        wake(learner);
    }

    /// Gets the session, which must belong to the given document.
    private Recorded attemptOf(final LearningFlowDoc doc, final long attemptId) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        if (!doc.getUuid().equals(recorded.attempt().docUuid())) {
            throw new IllegalArgumentException("Attempt " + attemptId + " belongs to document "
                                               + recorded.attempt().docUuid() + ", not " + doc.getUuid());
        }
        return recorded;
    }

    /// Gets the session, which must belong to the given document and be parked for a person.
    private Recorded parked(final LearningFlowDoc doc, final long attemptId) {
        final Recorded recorded = attemptOf(doc, attemptId);
        if (recorded.status() != AttemptStatus.PARKED) {
            throw new IllegalStateException("Attempt " + attemptId + " is not parked for a person: it is "
                                            + recorded.status().getDisplayValue());
        }
        return recorded;
    }

    /// Reopens the session for the worker to resume. Fails if another session holds its learner's claim.
    private void carriedOn(final Recorded recorded) {
        final long attemptId = recorded.id();
        if (!attempts.reopened(attemptId, clock.millis(), claimUntil())) {
            throw new IllegalStateException("Attempt " + attemptId + " cannot be carried on: another attempt is "
                                            + "learning for " + recorded.attempt().learner());
        }
    }

    /// The time at which a claim taken now expires if no heartbeat renews it.
    private long claimUntil() {
        return clock.millis() + lease.toMillis();
    }

    private void wake(final Learner learner) {
        wake.wake(learner.pipelineUuid());
    }
}
