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

package stroom.learningflow.stage;

import stroom.learningflow.learning.Advisors;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// The deferred learning worker, run by the Learning Flow Deferred Learning job, which resumes the sessions
/// that are waiting for the model.
///
/// When a stream arrives for a learner that nothing serves, its task is deferred without any model call: a
/// session is opened and left waiting at its first question. This worker then resumes the session, outside
/// the processing task, at whatever pace the model answers. A promotion it reaches wakes the learner's
/// pipeline as any other promotion does, so the waiting streams are tried again.
///
/// A waiting session belongs to no node until a worker picks it up, so any node's worker may do so. Picking
/// it up means taking its learner's claim, and the session then belongs to that node until it finishes or
/// the claim's lease expires. The worker does not loop over a learner's streams: one pass advances each
/// waiting session once, and a session left waiting again is advanced by the next pass.
public final class DeferredWorker {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DeferredWorker.class);

    private final Stage stage;
    private final Attempts attempts;
    private final Documents documents;
    private final Inputs inputs;
    private final StreamHeld streamHeld;
    private final DeletedPipelines deletedPipelines;
    private final Advisors advisors;
    private final Clock clock;

    /// @param streamHeld Puts each session's stream into the pipeline's state before the session is resumed,
    ///                   so that the Stroom functions used by a judged run read the stream being judged.
    public DeferredWorker(final Stage stage,
                          final Attempts attempts,
                          final Documents documents,
                          final Inputs inputs,
                          final StreamHeld streamHeld,
                          final DeletedPipelines deletedPipelines,
                          final Advisors advisors,
                          final Clock clock) {
        this.stage = stage;
        this.attempts = attempts;
        this.documents = documents;
        this.inputs = inputs;
        this.streamHeld = streamHeld;
        this.deletedPipelines = deletedPipelines;
        this.advisors = advisors;
        this.clock = clock;
    }

    /// Advances up to `limit` sessions, oldest first. These are the sessions waiting for the model, and the
    /// sessions whose node's claim has expired. A session whose node died is resumed by this node from the
    /// turns it recorded, rather than started again from nothing.
    ///
    /// A failure affects only its own session. A deleted pipeline or document, a stream removed by
    /// retention, or a model error closes or skips that session and the pass moves on to the next, because a
    /// job that stopped at the first bad session would never reach the good ones behind it.
    ///
    /// @param limit The maximum number of sessions to advance in this pass.
    /// @return How many sessions this pass advanced. A session another node took first, or one that is no
    ///         longer needed for its learner, is not counted, because nothing was learned for it here.
    public int advance(final int limit) {
        // Oldest first across both kinds, because a session with an expired claim has streams waiting on it
        // too, and a cluster that always has a full batch of waiting sessions must not leave it behind for
        // ever.
        final List<Recorded> waiting = new ArrayList<>(attempts.awaiting(limit));
        waiting.addAll(attempts.lapsed(clock.millis(), limit));
        waiting.sort(Comparator.comparingLong(Recorded::id));
        if (waiting.size() > limit) {
            waiting.subList(limit, waiting.size()).clear();
        }
        int advanced = 0;
        for (final Recorded attempt : waiting) {
            if (carry(attempt)) {
                advanced++;
            }
        }
        final int carried = advanced;
        LOGGER.debug(() -> LogUtil.message("Carried on {} of {} attempts awaiting the model or lapsed",
                carried, waiting.size()));
        wakeSuperseded();
        return advanced;
    }

    /// Wakes the streams that are waiting against a transform that has since been replaced, which no
    /// promotion's wake could reach (see [Stage#wakeSuperseded]). It runs after the sessions, so that
    /// anything this pass promoted is in force.
    private void wakeSuperseded() {
        try {
            stage.wakeSuperseded();
        } catch (final RuntimeException e) {
            // A failed sweep is tried again by the next pass; the sessions advanced before it are not
            // affected.
            LOGGER.error(() -> LogUtil.message("Streams waiting on a replaced transform could not be woken: {}",
                    e.getMessage()), e);
        }
    }

    private boolean carry(final Recorded attempt) {
        // A deleted pipeline's learning stops at once, because resuming its session would spend money on
        // the model for a pipeline that will never run what it learns.
        if (deletedPipelines.isDeleted(attempt.attempt().learner().pipelineUuid())) {
            return abandon(attempt, "The pipeline this learner is in has been deleted");
        }
        final Optional<LearningFlowDoc> doc = documents.byUuid(attempt.attempt().docUuid());
        if (doc.isEmpty()) {
            return abandon(attempt, "The Learning Flow has been deleted");
        }
        final Long inputId = attempt.attempt().inputId();
        // An improvement a person asked for has no stream behind it. Its sample is the corpus records its
        // binding was accepted on, reassembled by the stage.
        final Optional<Input> input = attempt.attempt().isImprovement()
                ? stage.improvementInput(attempt.attempt())
                : Optional.ofNullable(inputId).flatMap(inputs::byId);
        if (input.isEmpty()) {
            // The conversation is replayed over the sample, and the sample is the stream, so without it
            // there is nothing to resume from, and waiting longer will not bring it back.
            return abandon(attempt, attempt.attempt().isImprovement()
                    ? "The records the binding this was improving was accepted on are no longer held"
                    : "The stream this attempt was learning from is no longer held");
        }
        try {
            // Done for every session, not once per pass: the batch runs in one pipeline scope, and a session
            // that left the previous session's meta in place would silently use the previous stream's year
            if (!attempt.attempt().isImprovement()) {
                streamHeld.hold(inputId, attempt.attempt().learner());
            }
            // A sentinel means the session was not this pass's to advance: another node had taken it, a draft
            // awaits review, the learner gave up, or learning was disabled while it waited. The stage has
            // recorded this against the session; this pass simply did not advance it.
            // The advisor uses the question text the session was started with, not the Learning Flow's text
            // today, because a session is a conversation and the model is shown its own history as it
            // resumes.
            final StageRun run = stage.resume(doc.get(), attempt.id(), input.get(),
                    advisors.of(doc.get(), attempt.attempt().askedWith()));
            return !(run.decision() instanceof Sentinel);
        } catch (final RuntimeException e) {
            // The stage has already recorded the outcome against the session; the pass goes on.
            LOGGER.error(() -> LogUtil.message("Attempt {} could not be carried on: {}",
                    attempt.id(), e.getMessage()), e);
            return false;
        }
    }

    private boolean abandon(final Recorded attempt, final String reason) {
        LOGGER.warn(() -> LogUtil.message("Attempt {} abandoned: {}", attempt.id(), reason));
        attempts.closed(attempt.id(), AttemptStatus.ABANDONED, reason, null, null, 0L);
        return false;
    }
}
