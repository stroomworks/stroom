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

package stroom.learningflow.impl.db;

import stroom.db.util.JooqUtil;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowDeletedPipeline;
import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckResult;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.Template;
import stroom.learningflow.shared.TurnScore;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Learner;
import stroom.util.json.JsonUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static stroom.learningflow.impl.db.jooq.tables.LearningFlowAttempt.LEARNING_FLOW_ATTEMPT;
import static stroom.learningflow.impl.db.jooq.tables.LearningFlowTurn.LEARNING_FLOW_TURN;

/// The database implementation of [Attempts], which stores learning sessions (attempts) and their turns.
///
/// There is one row per attempt and one row per turn, so that what the model was asked and how it answered
/// outlives the node that asked it, a session can be resumed by another node, and a person can read it back
/// in the Supervisor. Each turn is written as it happens rather than at the end, so an attempt that is still
/// running, or whose node died, shows how far it had got.
@Singleton
public class AttemptsDao implements Attempts {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AttemptsDao.class);

    /// The statuses in which an attempt is still learning, and so still holds its learner's claim. An
    /// attempt awaiting review is not one of them: it has already written its draft binding, and the
    /// learner's state reports that draft until a person decides.
    private static final List<String> OPEN = List.of(AttemptStatus.IN_PROGRESS.name(),
            AttemptStatus.AWAITING_MODEL.name(), AttemptStatus.PARKED.name());

    /// The value of the claim column on an open attempt. A closed attempt holds null there, and the unique
    /// key on the learner and its claim ignores nulls, so a learner can have any number of closed attempts
    /// but only one open one.
    private static final Byte CLAIMED = 1;

    /// The statuses in which an attempt is never pruned, however old, because it has not finished: it is
    /// still learning, waiting for the model, or waiting for a person to decide.
    private static final List<String> KEPT = List.of(AttemptStatus.IN_PROGRESS.name(),
            AttemptStatus.AWAITING_MODEL.name(), AttemptStatus.PARKED.name(), AttemptStatus.AWAITING_REVIEW.name());

    /// The largest page of attempts that [#found] returns, whatever page size is requested, so that a
    /// request for everything cannot read every attempt ever made.
    private static final int PAGE_LIMIT = 1000;

    /// The statuses in which an attempt's binding is, or was about to be, serving. These are the attempts
    /// that [#settled] updates when that binding is retracted.
    private static final List<String> BOUND = List.of(AttemptStatus.PROMOTED.name(),
            AttemptStatus.AWAITING_REVIEW.name());

    /// The most attempts one call to [#prune] removes. Deleting every old row at once would hold locks
    /// across the table; the job that prunes runs again, so the rest are removed later.
    private static final int PRUNE_BATCH = 1000;

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    AttemptsDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    /// Inserts a new open attempt, unless the learner already has one. The unique key on the learner and
    /// its claim allows only one open attempt per learner, so if two nodes try to open one at the same
    /// time, only one succeeds.
    @Override
    public Optional<Long> opened(final Attempt attempt, final long nowMs) {
        final long now = System.currentTimeMillis();
        // An open attempt whose lease has expired still holds its claim. Its node has stopped, and the
        // next worker to find it resumes it. It is not replaced here by a new attempt, which would have
        // to ask the model everything again.
        return JooqUtil.transactionResult(connProvider, context -> {
            try {
                // The database's unique key decides between two nodes opening an attempt at once, rather
                // than what each node read beforehand.
                return Optional.of(insert(context, attempt, now));
            } catch (final IntegrityConstraintViolationException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    public boolean claimed(final long attemptId, final String node, final long nowMs, final long expiryMs) {
        return JooqUtil.contextResult(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.NODE_NAME, node)
                .set(LEARNING_FLOW_ATTEMPT.STATUS, AttemptStatus.IN_PROGRESS.name())
                .set(LEARNING_FLOW_ATTEMPT.EXPIRY_MS, expiryMs)
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                // An attempt awaiting the model has no thread running it, so any node may claim it. One
                // in progress belongs to its own node until its lease expires. A parked attempt is waiting
                // for a person, so it is not claimed here.
                .and(LEARNING_FLOW_ATTEMPT.STATUS.eq(AttemptStatus.AWAITING_MODEL.name())
                        .or(LEARNING_FLOW_ATTEMPT.STATUS.eq(AttemptStatus.IN_PROGRESS.name())
                                .and(LEARNING_FLOW_ATTEMPT.NODE_NAME.eq(node)
                                        .or(LEARNING_FLOW_ATTEMPT.EXPIRY_MS.le(nowMs)))))
                .execute()) > 0;
    }

    /// Returns the attempts awaiting the model, oldest first, so that the attempt that has waited longest
    /// is advanced first and a busy document cannot starve the others.
    @Override
    public List<Recorded> awaiting(final int limit) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select()
                        .from(LEARNING_FLOW_ATTEMPT)
                        .where(LEARNING_FLOW_ATTEMPT.STATUS.eq(AttemptStatus.AWAITING_MODEL.name()))
                        .orderBy(LEARNING_FLOW_ATTEMPT.ID)
                        .limit(limit)
                        .fetch())
                .stream()
                .map(record -> recorded(record, turns(record.get(LEARNING_FLOW_ATTEMPT.ID))))
                .toList();
    }

    @Override
    public List<Recorded> lapsed(final long nowMs, final int limit) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select()
                        .from(LEARNING_FLOW_ATTEMPT)
                        .where(LEARNING_FLOW_ATTEMPT.STATUS.eq(AttemptStatus.IN_PROGRESS.name()))
                        .and(LEARNING_FLOW_ATTEMPT.EXPIRY_MS.le(nowMs))
                        .orderBy(LEARNING_FLOW_ATTEMPT.ID)
                        .limit(limit)
                        .fetch())
                .stream()
                .map(record -> recorded(record, turns(record.get(LEARNING_FLOW_ATTEMPT.ID))))
                .toList();
    }

    @Override
    public void amended(final long attemptId, final int turnNumber, final String answer,
                        final String answeredBy) {
        JooqUtil.transaction(connProvider, context -> {
            // The turns after this one followed from the answer that has now changed, so they are deleted
            // and the walk through the flow produces them again.
            context.deleteFrom(LEARNING_FLOW_TURN)
                    .where(LEARNING_FLOW_TURN.FK_ATTEMPT_ID.eq(attemptId))
                    .and(LEARNING_FLOW_TURN.TURN_NUMBER.gt(turnNumber))
                    .execute();
            final int amended = context.update(LEARNING_FLOW_TURN)
                    .set(LEARNING_FLOW_TURN.ANSWER, answer)
                    .set(LEARNING_FLOW_TURN.ANSWERED_BY, answeredBy)
                    .setNull(LEARNING_FLOW_TURN.OUTCOME)
                    .setNull(LEARNING_FLOW_TURN.SCORER)
                    .setNull(LEARNING_FLOW_TURN.SCORE)
                    .setNull(LEARNING_FLOW_TURN.SCORE_THRESHOLD)
                    .where(LEARNING_FLOW_TURN.FK_ATTEMPT_ID.eq(attemptId))
                    .and(LEARNING_FLOW_TURN.TURN_NUMBER.eq(turnNumber))
                    .execute();
            if (amended == 0) {
                throw new IllegalArgumentException("Attempt " + attemptId + " has no turn " + turnNumber);
            }
        });
    }

    /// Reopens an attempt so that the worker carries it on, for example after a person has answered it.
    /// The claim is taken back under the same unique key that [#opened] relies on, so the database refuses
    /// it if another attempt of the same learner is open.
    @Override
    public boolean reopened(final long attemptId, final long nowMs, final long expiryMs) {
        final long now = System.currentTimeMillis();
        return JooqUtil.transactionResult(connProvider, context -> {
            final Record row = context
                    .select(LEARNING_FLOW_ATTEMPT.STATUS,
                            LEARNING_FLOW_ATTEMPT.EXPIRY_MS)
                    .from(LEARNING_FLOW_ATTEMPT)
                    .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                    .fetchOne();
            if (row == null) {
                return false;
            }
            final Long expiry = row.get(LEARNING_FLOW_ATTEMPT.EXPIRY_MS);
            // A node is running this attempt now, so it is left to that node.
            if (AttemptStatus.IN_PROGRESS.name().equals(row.get(LEARNING_FLOW_ATTEMPT.STATUS))
                && expiry != null
                && expiry > nowMs) {
                return false;
            }
            // If another attempt of the learner holds the claim, it keeps it, even if its lease has
            // expired: an expired session is resumed, not displaced by an answer to an older one. The
            // unique key enforces this.
            try {
                // The claim is taken back with a fresh expiry, so that an attempt a person has just
                // answered is not displaced by the learner's next stream before the worker reaches it.
                return context.update(LEARNING_FLOW_ATTEMPT)
                        .set(LEARNING_FLOW_ATTEMPT.STATUS, AttemptStatus.AWAITING_MODEL.name())
                        .set(LEARNING_FLOW_ATTEMPT.CLAIMED, CLAIMED)
                        .set(LEARNING_FLOW_ATTEMPT.EXPIRY_MS, expiryMs)
                        .setNull(LEARNING_FLOW_ATTEMPT.DECISION)
                        .setNull(LEARNING_FLOW_ATTEMPT.REPORT)
                        .setNull(LEARNING_FLOW_ATTEMPT.BINDING_UUID)
                        .setNull(LEARNING_FLOW_ATTEMPT.SCORE)
                        .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                        .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, now)
                        .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                        .execute() > 0;
            } catch (final IntegrityConstraintViolationException e) {
                // Another attempt holds the claim. The caller tells the person, rather than letting two
                // attempts learn for the learner at once.
                return false;
            }
        });
    }

    @Override
    public void heartbeat(final long attemptId, final long expiryMs) {
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.EXPIRY_MS, expiryMs)
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                .and(LEARNING_FLOW_ATTEMPT.STATUS.in(OPEN))
                .execute());
    }

    @Override
    public Optional<Recorded> open(final Learner learner, final long nowMs) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select()
                        .from(LEARNING_FLOW_ATTEMPT)
                        .where(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(learner.pipelineUuid()))
                        .and(LEARNING_FLOW_ATTEMPT.ELEMENT_ID.eq(learner.elementId()))
                        .and(LEARNING_FLOW_ATTEMPT.STATUS.in(OPEN))
                        .orderBy(LEARNING_FLOW_ATTEMPT.ID.desc())
                        .limit(1)
                        .fetchOptional())
                .map(record -> recorded(record, turns(record.get(LEARNING_FLOW_ATTEMPT.ID))));
    }

    /// Sets an open attempt's status, typically to wait for the model, keeping its claim with the given
    /// expiry. A parked attempt is still learning, so it keeps its learner's claim.
    @Override
    public void parked(final long attemptId,
                       final AttemptStatus status,
                       final long expiryMs,
                       final long tokensSpent) {
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.STATUS, status.name())
                .set(LEARNING_FLOW_ATTEMPT.EXPIRY_MS, expiryMs)
                .set(LEARNING_FLOW_ATTEMPT.TOKENS_SPENT, LEARNING_FLOW_ATTEMPT.TOKENS_SPENT.plus(tokensSpent))
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                // A finished attempt is not brought back to life: it holds no claim and has nothing to ask.
                .and(LEARNING_FLOW_ATTEMPT.STATUS.in(OPEN))
                .execute());
    }

    private static long insert(final DSLContext context, final Attempt attempt, final long now) {
        return context
                .insertInto(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.CLAIMED, CLAIMED)
                .set(LEARNING_FLOW_ATTEMPT.VERSION, 1)
                .set(LEARNING_FLOW_ATTEMPT.CREATE_TIME_MS, now)
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, now)
                .set(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID, attempt.learner().pipelineUuid())
                .set(LEARNING_FLOW_ATTEMPT.ELEMENT_ID, attempt.learner().elementId())
                .set(LEARNING_FLOW_ATTEMPT.DOC_UUID, attempt.docUuid())
                .set(LEARNING_FLOW_ATTEMPT.FEED_NAME, attempt.feed())
                .set(LEARNING_FLOW_ATTEMPT.TYPE_NAME, attempt.type())
                .set(LEARNING_FLOW_ATTEMPT.INPUT_META_ID, attempt.inputId())
                .set(LEARNING_FLOW_ATTEMPT.NODE_NAME, attempt.node())
                .set(LEARNING_FLOW_ATTEMPT.PROMOTION_MODE, attempt.promotionMode().name())
                .set(LEARNING_FLOW_ATTEMPT.STATUS, AttemptStatus.IN_PROGRESS.name())
                .set(LEARNING_FLOW_ATTEMPT.EXPIRY_MS, attempt.expiryMs())
                // The prompt templates and instructions the attempt is asked with, so that a parked
                // attempt resumes with the same wording it began with, and a prompt can be rebuilt exactly
                // as it was sent.
                .set(LEARNING_FLOW_ATTEMPT.TEMPLATES, templates(attempt.askedWith()))
                .set(LEARNING_FLOW_ATTEMPT.BUILT_IN_VERSION, attempt.askedWith().known()
                        ? attempt.askedWith().builtInVersion()
                        : null)
                .set(LEARNING_FLOW_ATTEMPT.INSTRUCTIONS, attempt.askedWith().instructions())
                .set(LEARNING_FLOW_ATTEMPT.BUILD_VERSION, attempt.askedWith().build())
                // The binding a person asked to be improved, if this attempt is an Improve run.
                .set(LEARNING_FLOW_ATTEMPT.IMPROVING_BINDING_UUID, attempt.improving())
                .returning(LEARNING_FLOW_ATTEMPT.ID)
                .fetchOne(LEARNING_FLOW_ATTEMPT.ID);
    }

    /// Inserts or updates a turn, keyed by its number. A turn is written when it is asked and again when
    /// it is judged, and both writes go to the same row, so an attempt that is still running shows how far
    /// it has got.
    @Override
    public void turn(final long attemptId, final Turn turn) {
        // The score of the check that decided the turn's outcome, if a check did.
        final String check = NullSafe.get(turn.score(), TurnScore::getCheck, Check::name);
        final Double score = NullSafe.get(turn.score(), TurnScore::getScore);
        final Double threshold = NullSafe.get(turn.score(), TurnScore::getThreshold);
        JooqUtil.context(connProvider, context -> context
                .insertInto(LEARNING_FLOW_TURN)
                .set(LEARNING_FLOW_TURN.CREATE_TIME_MS, System.currentTimeMillis())
                .set(LEARNING_FLOW_TURN.FK_ATTEMPT_ID, attemptId)
                .set(LEARNING_FLOW_TURN.TURN_NUMBER, turn.number())
                .set(LEARNING_FLOW_TURN.STEP_ID, turn.stepId())
                .set(LEARNING_FLOW_TURN.CANDIDATE, turn.candidate())
                .set(LEARNING_FLOW_TURN.QUESTION_KIND, turn.kind().name())
                .set(LEARNING_FLOW_TURN.QUESTION, turn.question())
                .set(LEARNING_FLOW_TURN.ANSWER, turn.answer())
                .set(LEARNING_FLOW_TURN.ANSWERED_BY, turn.answeredBy())
                .set(LEARNING_FLOW_TURN.OUTCOME, turn.outcome() == null
                        ? null
                        : turn.outcome().name())
                .set(LEARNING_FLOW_TURN.SCORER, check)
                .set(LEARNING_FLOW_TURN.SCORE, score)
                .set(LEARNING_FLOW_TURN.SCORE_THRESHOLD, threshold)
                // The ids of the guidance this question carried, so that walking the attempt again
                // replays the guidance that was used, not guidance added since.
                .set(LEARNING_FLOW_TURN.CARRIED_GUIDANCE, carried(turn.carried()))
                .onDuplicateKeyUpdate()
                .set(LEARNING_FLOW_TURN.ANSWER, turn.answer())
                .set(LEARNING_FLOW_TURN.ANSWERED_BY, turn.answeredBy())
                .set(LEARNING_FLOW_TURN.OUTCOME, turn.outcome() == null
                        ? null
                        : turn.outcome().name())
                .set(LEARNING_FLOW_TURN.SCORER, check)
                .set(LEARNING_FLOW_TURN.SCORE, score)
                .set(LEARNING_FLOW_TURN.SCORE_THRESHOLD, threshold)
                // This is set on the update as well as the insert. A question that the attempt parked at
                // is written before it is asked, with no guidance, and answered when the attempt resumes.
                // Without this, the column would stay empty for the very turn that carried guidance.
                .set(LEARNING_FLOW_TURN.CARRIED_GUIDANCE, carried(turn.carried()))
                .execute());
    }

    @Override
    public void parked(final long attemptId, final String reason, final StopReport report, final long tokensSpent) {
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.STATUS, AttemptStatus.PARKED.name())
                .set(LEARNING_FLOW_ATTEMPT.DECISION, reason)
                .set(LEARNING_FLOW_ATTEMPT.REPORT, json(report))
                // A lease that never expires, so that only a person can take the claim from a parked
                // attempt.
                .set(LEARNING_FLOW_ATTEMPT.EXPIRY_MS, Long.MAX_VALUE)
                .set(LEARNING_FLOW_ATTEMPT.TOKENS_SPENT, LEARNING_FLOW_ATTEMPT.TOKENS_SPENT.plus(tokensSpent))
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                .and(LEARNING_FLOW_ATTEMPT.STATUS.in(OPEN))
                .execute());
    }

    @Override
    public void closed(final long attemptId,
                       final AttemptStatus status,
                       final String decision,
                       final String bindingUuid,
                       final Double score,
                       final StopReport report,
                       final long tokensSpent) {
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.STATUS, status.name())
                .set(LEARNING_FLOW_ATTEMPT.DECISION, decision)
                .set(LEARNING_FLOW_ATTEMPT.REPORT, json(report))
                .set(LEARNING_FLOW_ATTEMPT.BINDING_UUID, bindingUuid)
                .set(LEARNING_FLOW_ATTEMPT.SCORE, score)
                .set(LEARNING_FLOW_ATTEMPT.TOKENS_SPENT, LEARNING_FLOW_ATTEMPT.TOKENS_SPENT.plus(tokensSpent))
                // A request to go to the exhaustion step lasts only until the attempt closes.
                .set(LEARNING_FLOW_ATTEMPT.EXHAUST_NEXT, (byte) 0)
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                // Clearing the claim frees the learner for its next attempt.
                .setNull(LEARNING_FLOW_ATTEMPT.EXPIRY_MS)
                .setNull(LEARNING_FLOW_ATTEMPT.CLAIMED)
                .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                .execute());
    }

    @Override
    public void decided(final String docUuid,
                        final String bindingUuid,
                        final AttemptStatus status,
                        final String decision,
                        final String reviewedBy) {
        final long now = System.currentTimeMillis();
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.STATUS, status.name())
                .set(LEARNING_FLOW_ATTEMPT.DECISION, decision)
                .set(LEARNING_FLOW_ATTEMPT.REVIEWED_BY, reviewedBy)
                .set(LEARNING_FLOW_ATTEMPT.REVIEWED_TIME_MS, now)
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, now)
                .where(LEARNING_FLOW_ATTEMPT.DOC_UUID.eq(docUuid))
                .and(LEARNING_FLOW_ATTEMPT.BINDING_UUID.eq(bindingUuid))
                .and(LEARNING_FLOW_ATTEMPT.STATUS.eq(AttemptStatus.AWAITING_REVIEW.name()))
                .execute());
    }

    @Override
    public void evidence(final long attemptId, final List<CheckResult> evidence) {
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.EVIDENCE, JsonUtil.writeValueAsString(evidence, false))
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                .execute());
    }

    /// Updates the attempts that produced this binding, whether they were promoted or are still awaiting a
    /// review that will now never happen. This differs from [#decided], which only updates a draft
    /// awaiting review; this method is for a binding that was serving, such as one being retracted.
    @Override
    public void settled(final String docUuid, final String bindingUuid, final AttemptStatus status,
                        final String decision) {
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.STATUS, status.name())
                .set(LEARNING_FLOW_ATTEMPT.DECISION, decision)
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(LEARNING_FLOW_ATTEMPT.DOC_UUID.eq(docUuid))
                .and(LEARNING_FLOW_ATTEMPT.BINDING_UUID.eq(bindingUuid))
                .and(LEARNING_FLOW_ATTEMPT.STATUS.in(BOUND))
                .execute());
    }

    /// Deletes up to [#PRUNE_BATCH] finished attempts last updated before the given time. Their turns are
    /// deleted first, because the turn rows reference the attempt rows and the foreign key would refuse
    /// the other order. Both deletes are in one transaction, so a reader never sees an attempt with its
    /// turns removed.
    @Override
    public int prune(final long finishedBeforeMs) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final List<Long> old = context
                    .select(LEARNING_FLOW_ATTEMPT.ID)
                    .from(LEARNING_FLOW_ATTEMPT)
                    .where(LEARNING_FLOW_ATTEMPT.STATUS.notIn(KEPT))
                    .and(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS.lt(finishedBeforeMs))
                    .limit(PRUNE_BATCH)
                    .fetch(LEARNING_FLOW_ATTEMPT.ID);
            if (old.isEmpty()) {
                return 0;
            }
            context.deleteFrom(LEARNING_FLOW_TURN)
                    .where(LEARNING_FLOW_TURN.FK_ATTEMPT_ID.in(old))
                    .execute();
            return context.deleteFrom(LEARNING_FLOW_ATTEMPT)
                    .where(LEARNING_FLOW_ATTEMPT.ID.in(old))
                    .execute();
        });
    }

    @Override
    public Optional<Recorded> byId(final long attemptId) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select()
                        .from(LEARNING_FLOW_ATTEMPT)
                        .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                        .fetchOptional())
                .map(record -> recorded(record, turns(attemptId)));
    }

    @Override
    public List<Recorded> forDocument(final String docUuid, final int limit) {
        return JooqUtil.contextResult(connProvider, context -> {
            final List<? extends Record> rows = context
                    .select()
                    .from(LEARNING_FLOW_ATTEMPT)
                    .where(LEARNING_FLOW_ATTEMPT.DOC_UUID.eq(docUuid))
                    .orderBy(LEARNING_FLOW_ATTEMPT.ID.desc())
                    .limit(limit)
                    .fetch();
            final List<Long> ids = rows.stream().map(row -> row.get(LEARNING_FLOW_ATTEMPT.ID)).toList();
            // The turns of all these attempts are read in one query, rather than one query per attempt,
            // because the Supervisor reads these in pages.
            final Map<Long, List<Turn>> byAttempt = ids.isEmpty()
                    ? Map.of()
                    : context.select()
                            .from(LEARNING_FLOW_TURN)
                            .where(LEARNING_FLOW_TURN.FK_ATTEMPT_ID.in(ids))
                            .orderBy(LEARNING_FLOW_TURN.FK_ATTEMPT_ID, LEARNING_FLOW_TURN.TURN_NUMBER)
                            .fetch()
                            .stream()
                            .collect(Collectors.groupingBy(row -> row.get(LEARNING_FLOW_TURN.FK_ATTEMPT_ID),
                                    Collectors.mapping(AttemptsDao::turn, Collectors.toList())));
            return rows.stream()
                    .map(row -> recorded(row, byAttempt.getOrDefault(row.get(LEARNING_FLOW_ATTEMPT.ID), List.of())))
                    .toList();
        });
    }

    /// Returns a page of attempts matching the criteria, across the given documents, newest first. This is
    /// what the Supervisor lists. The turns are not read, as a list does not show them; the Supervisor
    /// reads them for one attempt with [#byId].
    @Override
    public Page found(final AttemptCriteria criteria, final Collection<String> docUuids) {
        if (docUuids.isEmpty()) {
            // The person may see no documents, so they get no attempts and no count, rather than an
            // unfiltered query.
            return new Page(List.of(), 0L);
        }
        final List<Condition> conditions = new ArrayList<>();
        conditions.add(LEARNING_FLOW_ATTEMPT.DOC_UUID.in(docUuids));
        if (criteria.getDocUuid() != null) {
            conditions.add(LEARNING_FLOW_ATTEMPT.DOC_UUID.eq(criteria.getDocUuid()));
        }
        if (criteria.getFeed() != null) {
            conditions.add(LEARNING_FLOW_ATTEMPT.FEED_NAME.eq(criteria.getFeed()));
        }
        if (criteria.getPromotionMode() != null) {
            conditions.add(LEARNING_FLOW_ATTEMPT.PROMOTION_MODE.eq(criteria.getPromotionMode().name()));
        }
        if (!criteria.getStatuses().isEmpty()) {
            conditions.add(LEARNING_FLOW_ATTEMPT.STATUS.in(criteria.getStatuses().stream()
                    .map(AttemptStatus::name)
                    .toList()));
        }
        // One learner's sessions, as listed beneath that learner's row in the Supervisor.
        if (criteria.getLearner() != null) {
            conditions.add(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(criteria.getLearner().getPipelineUuid()));
            conditions.add(LEARNING_FLOW_ATTEMPT.ELEMENT_ID.eq(criteria.getLearner().getElementId()));
        }
        // The attempts of a deleted pipeline are kept, but only shown when asked for.
        if (!criteria.isShowDeletedPipelines()) {
            conditions.add(DSL.notExists(DSL
                    .selectOne()
                    .from(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE)
                    .where(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID
                            .eq(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID))));
        }
        // A request without paging carries nulls, which the JooqUtil methods turn into defaults.
        final int offset = JooqUtil.getOffset(criteria.getPageRequest());
        final int length = Math.min(JooqUtil.getLimit(criteria.getPageRequest(), false, PAGE_LIMIT),
                PAGE_LIMIT);
        return JooqUtil.contextResult(connProvider, context -> {
            final List<Recorded> page = context
                    .select()
                    .from(LEARNING_FLOW_ATTEMPT)
                    .where(conditions)
                    .orderBy(LEARNING_FLOW_ATTEMPT.ID.desc())
                    .limit(offset, length)
                    .fetch()
                    .stream()
                    .map(record -> recorded(record, List.of()))
                    .toList();
            final long total = context
                    .selectCount()
                    .from(LEARNING_FLOW_ATTEMPT)
                    .where(conditions)
                    .fetchOptional(0, Long.class)
                    .orElse(0L);
            return new Page(page, total);
        });
    }

    private List<Turn> turns(final long attemptId) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(LEARNING_FLOW_TURN)
                .where(LEARNING_FLOW_TURN.FK_ATTEMPT_ID.eq(attemptId))
                .orderBy(LEARNING_FLOW_TURN.TURN_NUMBER)
                .fetch()
                .map(AttemptsDao::turn));
    }

    private static Recorded recorded(final Record record, final List<Turn> turns) {
        final Attempt attempt = new Attempt(
                new Learner(record.get(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID),
                        record.get(LEARNING_FLOW_ATTEMPT.ELEMENT_ID)),
                record.get(LEARNING_FLOW_ATTEMPT.DOC_UUID),
                record.get(LEARNING_FLOW_ATTEMPT.FEED_NAME),
                record.get(LEARNING_FLOW_ATTEMPT.TYPE_NAME),
                record.get(LEARNING_FLOW_ATTEMPT.INPUT_META_ID),
                record.get(LEARNING_FLOW_ATTEMPT.NODE_NAME),
                PromotionMode.valueOf(record.get(LEARNING_FLOW_ATTEMPT.PROMOTION_MODE)),
                record.get(LEARNING_FLOW_ATTEMPT.EXPIRY_MS) == null
                        ? 0L
                        : record.get(LEARNING_FLOW_ATTEMPT.EXPIRY_MS),
                askedWith(record),
                record.get(LEARNING_FLOW_ATTEMPT.IMPROVING_BINDING_UUID));
        return new Recorded(
                record.get(LEARNING_FLOW_ATTEMPT.ID),
                attempt,
                AttemptStatus.valueOf(record.get(LEARNING_FLOW_ATTEMPT.STATUS)),
                record.get(LEARNING_FLOW_ATTEMPT.DECISION),
                record.get(LEARNING_FLOW_ATTEMPT.BINDING_UUID),
                record.get(LEARNING_FLOW_ATTEMPT.SCORE),
                record.get(LEARNING_FLOW_ATTEMPT.TOKENS_SPENT),
                record.get(LEARNING_FLOW_ATTEMPT.CREATE_TIME_MS),
                record.get(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS),
                turns,
                new Grant(record.get(LEARNING_FLOW_ATTEMPT.GRANTED_TOKENS),
                        record.get(LEARNING_FLOW_ATTEMPT.GRANTED_MS)),
                record.get(LEARNING_FLOW_ATTEMPT.EXHAUST_NEXT) != 0,
                report(record.get(LEARNING_FLOW_ATTEMPT.REPORT)),
                new Review(evidence(record.get(LEARNING_FLOW_ATTEMPT.EVIDENCE)),
                        record.get(LEARNING_FLOW_ATTEMPT.REVIEWED_BY),
                        record.get(LEARNING_FLOW_ATTEMPT.REVIEWED_TIME_MS)));
    }

    // Reads the review evidence back from its JSON column; empty if there is none.
    private static List<CheckResult> evidence(final String json) {
        return NullSafe.isBlankString(json)
                ? List.of()
                : List.of(JsonUtil.readValue(json, CheckResult[].class));
    }

    /// Serialises the attempt's templates for their column, as JSON keyed by template name, or null if the
    /// templates are not known.
    private static String templates(final AskedWith askedWith) {
        return askedWith.known()
                ? JsonUtil.writeValueAsString(askedWith.templates(), false)
                : null;
    }

    /// Reads back the templates and instructions an attempt was asked with. A row written before these
    /// were recorded returns [AskedWith#UNKNOWN], rather than today's templates, which might not be the
    /// ones that were used.
    private static AskedWith askedWith(final Record record) {
        final String json = record.get(LEARNING_FLOW_ATTEMPT.TEMPLATES);
        if (NullSafe.isBlankString(json)) {
            return AskedWith.UNKNOWN;
        }
        final Map<Template, String> templates = new EnumMap<>(Template.class);
        final Map<?, ?> read = JsonUtil.readValue(json, Map.class);
        for (final Map.Entry<?, ?> entry : read.entrySet()) {
            final String name = String.valueOf(entry.getKey());
            // A template that this build does not have is dropped rather than failing the read, because an
            // old attempt may name a template that has since been removed.
            try {
                templates.put(Template.valueOf(name), String.valueOf(entry.getValue()));
            } catch (final IllegalArgumentException e) {
                LOGGER.debug(() -> LogUtil.message("Attempt was asked with template {}, which this build "
                                                   + "no longer has", name));
            }
        }
        final Integer version = record.get(LEARNING_FLOW_ATTEMPT.BUILT_IN_VERSION);
        return new AskedWith(version == null
                ? 0
                : version,
                templates,
                record.get(LEARNING_FLOW_ATTEMPT.INSTRUCTIONS),
                record.get(LEARNING_FLOW_ATTEMPT.BUILD_VERSION));
    }

    // Serialises a stop report as JSON for its column; null if there is none.
    private static String json(final StopReport report) {
        return report == null
                ? null
                : JsonUtil.writeValueAsString(report, false);
    }

    // Reads a stop report back from its JSON column; null if there is none.
    private static StopReport report(final String json) {
        return NullSafe.isBlankString(json)
                ? null
                : JsonUtil.readValue(json, StopReport.class);
    }

    @Override
    public void exhaustNext(final long attemptId) {
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.EXHAUST_NEXT, (byte) 1)
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                .execute());
    }

    @Override
    public void granted(final long attemptId, final long tokens, final long ms) {
        JooqUtil.context(connProvider, context -> context
                .update(LEARNING_FLOW_ATTEMPT)
                .set(LEARNING_FLOW_ATTEMPT.GRANTED_TOKENS, LEARNING_FLOW_ATTEMPT.GRANTED_TOKENS.plus(tokens))
                .set(LEARNING_FLOW_ATTEMPT.GRANTED_MS, LEARNING_FLOW_ATTEMPT.GRANTED_MS.plus(ms))
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(LEARNING_FLOW_ATTEMPT.ID.eq(attemptId))
                .execute());
    }

    /// Serialises the ids of the guidance a turn carried as a comma-separated list. The list is small and
    /// never queried, so it is kept in one column rather than in a table of its own.
    private static String carried(final List<Long> carried) {
        return NullSafe.isEmptyCollection(carried)
                ? null
                : carried.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Long> carried(final String written) {
        if (NullSafe.isBlankString(written)) {
            return List.of();
        }
        return Arrays.stream(written.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

    private static Turn turn(final Record record) {
        final String outcome = record.get(LEARNING_FLOW_TURN.OUTCOME);
        return new Turn(
                record.get(LEARNING_FLOW_TURN.TURN_NUMBER),
                record.get(LEARNING_FLOW_TURN.STEP_ID),
                record.get(LEARNING_FLOW_TURN.CANDIDATE),
                QuestionKind.valueOf(record.get(LEARNING_FLOW_TURN.QUESTION_KIND)),
                record.get(LEARNING_FLOW_TURN.QUESTION),
                record.get(LEARNING_FLOW_TURN.ANSWER),
                record.get(LEARNING_FLOW_TURN.ANSWERED_BY),
                outcome == null
                        ? null
                        : StepOutcome.valueOf(outcome),
                score(record),
                carried(record.get(LEARNING_FLOW_TURN.CARRIED_GUIDANCE)));
    }

    /// Reads the score of the check that decided a turn's outcome, or null if no check did.
    private static TurnScore score(final Record record) {
        // The column is called 'scorer', but it holds the name of a Check. Renaming the column needs
        // the jOOQ code to be regenerated, so it is left for the next set of table changes.
        final String check = record.get(LEARNING_FLOW_TURN.SCORER);
        return check == null
                ? null
                : new TurnScore(Check.valueOf(check), record.get(LEARNING_FLOW_TURN.SCORE),
                        record.get(LEARNING_FLOW_TURN.SCORE_THRESHOLD));
    }
}
