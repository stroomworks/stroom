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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// The worker (A5, A28): the job that advances the attempts waiting for the model. A stream of a
/// learner nothing serves costs its task a deferral and no model call — the attempt
/// is opened and parked at its first question — and this is what carries it on, outside the task, at
/// whatever pace the model answers. The promotion it reaches wakes the learner's pipeline as any other
/// does, so the streams that waited are tried again.
///
/// A parked attempt is nobody's until a worker takes it (A45), so any node's may; taking it is taking
/// its learner's claim, and the attempt is that node's until it finishes or lapses. Nothing here is a loop over a
/// learner's streams: one pass advances each waiting attempt once, and an attempt that parks again is advanced by
/// the next pass.
public final class DeferredWorker {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DeferredWorker.class);

    private final Stage stage;
    private final Attempts attempts;
    private final Documents documents;
    private final Inputs inputs;
    private final DeletedPipelines deletedPipelines;
    private final Advisors advisors;
    private final Clock clock;

    public DeferredWorker(final Stage stage,
                          final Attempts attempts,
                          final Documents documents,
                          final Inputs inputs,
                          final DeletedPipelines deletedPipelines,
                          final Advisors advisors,
                          final Clock clock) {
        this.stage = stage;
        this.attempts = attempts;
        this.documents = documents;
        this.inputs = inputs;
        this.deletedPipelines = deletedPipelines;
        this.advisors = advisors;
        this.clock = clock;
    }

    /// Advance up to `limit` attempts, oldest first: those waiting for the model, and then those a node
    /// was carrying whose lease has lapsed (design 04 §4) — a session whose node died is carried on from
    /// the turns it recorded, by this node, and not opened again from nothing.
    ///
    /// One attempt's failure is its own: a pipeline or document deleted, a stream aged off or a model that fell over
    /// closes that attempt and the pass goes on to the next, since a job that stops at the first bad
    /// attempt would never reach the good ones behind it.
    ///
    /// @return How many attempts this pass carried to an end. An attempt another node took first, or one
    /// the learner no longer wants, is not one of them: nothing was learned for it here.
    public int advance(final int limit) {
        // Oldest first across both kinds: a lapsed session has streams waiting on it as a parked attempt
        // has, and a cluster with a full batch of parked attempts must not leave it behind for ever.
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

    /// After the attempts, so that what this pass promoted is in force: the streams that wait against a
    /// transform since replaced, which no promotion's wake could reach (see [Stage#wakeSuperseded]).
    private void wakeSuperseded() {
        try {
            stage.wakeSuperseded();
        } catch (final RuntimeException e) {
            // A sweep that failed is tried again by the next pass; the attempts it follows are carried.
            LOGGER.error(() -> LogUtil.message("Streams waiting on a replaced transform could not be woken: {}",
                    e.getMessage()), e);
        }
    }

    private boolean carry(final Recorded attempt) {
        // A deleted pipeline's learning stops at once (design 06 A135): a session carried on for it would
        // spend on the model for a pipeline that will never run what it learns.
        if (deletedPipelines.isDeleted(attempt.attempt().learner().pipelineUuid())) {
            return abandon(attempt, "The pipeline this learner is in has been deleted");
        }
        final Optional<ShapeshifterAiDoc> doc = documents.byUuid(attempt.attempt().docUuid());
        if (doc.isEmpty()) {
            return abandon(attempt, "The Shapeshifter AI document has been deleted");
        }
        final Long inputId = attempt.attempt().inputId();
        final Optional<Input> input = inputId == null
                ? Optional.empty()
                : inputs.byId(inputId);
        if (input.isEmpty()) {
            // The conversation is re-walked over the sample, and the sample is the stream (A45): without it
            // there is nothing to carry on from, and waiting longer will not bring it back.
            return abandon(attempt, "The stream this attempt was learning from is no longer held");
        }
        try {
            // A sentinel means the attempt was not this pass's to carry: another node had taken it, or
            // a draft awaits review, the learner gave up, or learning was turned off while it waited. The stage has
            // said so against the attempt; this pass simply did not advance it.
            final StageRun run = stage.resume(doc.get(), attempt.id(), input.get(), advisors.of(doc.get()));
            return !(run.decision() instanceof Sentinel);
        } catch (final RuntimeException e) {
            // The stage has already recorded what this came to against the attempt; the pass goes on.
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
