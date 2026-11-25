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

/// The attempts of A28 as rows: one per attempt, one per turn, so that what was asked and answered
/// outlives the node that asked it and a person can read it back. A turn is inserted as it is answered
/// rather than at the end, so an attempt still running — or one whose node died — shows what it had got
/// to.
@Singleton
public class AttemptsDao implements Attempts {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AttemptsDao.class);

    /// The states in which an attempt is still learning, and so still holds its learner's claim (A45). A
    /// draft awaiting review is not one of them: it has written its binding, and the binding is what the
    /// learner's state answers with until a person decides.
    private static final List<String> OPEN = List.of(AttemptStatus.IN_PROGRESS.name(),
            AttemptStatus.AWAITING_MODEL.name(), AttemptStatus.PARKED.name());

    /// What an open attempt's claim column holds. A closed one holds null, which the unique key on the
    /// learner and its claim does not count, so a learner has any number of closed attempts and one open.
    private static final Byte CLAIMED = 1;

    /// The states an attempt is never pruned in, however old: it is still learning, still waiting for
    /// the model, or waiting for a person to decide, and none of those is over.
    private static final List<String> KEPT = List.of(AttemptStatus.IN_PROGRESS.name(),
            AttemptStatus.AWAITING_MODEL.name(), AttemptStatus.PARKED.name(), AttemptStatus.AWAITING_REVIEW.name());

    /// The most attempts one page may ask for, whatever it asks for: a view that asked for everything
    /// would read every attempt every document has ever made.
    private static final int PAGE_LIMIT = 1000;

    /// The states in which an attempt's binding is one that was, or was about to be, serving: the ones a
    /// retraction is about.
    private static final List<String> BOUND = List.of(AttemptStatus.PROMOTED.name(),
            AttemptStatus.AWAITING_REVIEW.name());

    /// How many attempts one pass removes: a delete of every old row at once would hold locks across the
    /// table, and the job runs again.
    private static final int PRUNE_BATCH = 1000;

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    AttemptsDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    /// One open attempt per learner (A45), which the unique key on the learner and its claim admits: two
    /// nodes meeting a learner's streams at once do not both take it. An attempt whose expiry has passed
    /// has lapsed, and the next worker resumes it.
    @Override
    public Optional<Long> opened(final Attempt attempt, final long nowMs) {
        final long now = System.currentTimeMillis();
        // An attempt whose lease has lapsed still holds its claim: the node behind it has stopped, and the
        // session is resumed by the next worker to find it (design 04 §4), not abandoned here for a new one
        // that would ask the model everything again.
        return JooqUtil.transactionResult(connProvider, context -> {
            try {
                // The unique key on the learner and its claim is what admits one open attempt per learner:
                // two nodes meeting it at once are decided by the database, not by what each read.
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
                // A parked attempt is nobody's to carry, since no thread is behind it; a running one is
                // its own node's until it lapses.
                .and(LEARNING_FLOW_ATTEMPT.STATUS.eq(AttemptStatus.AWAITING_MODEL.name())
                        .or(LEARNING_FLOW_ATTEMPT.STATUS.eq(AttemptStatus.IN_PROGRESS.name())
                                .and(LEARNING_FLOW_ATTEMPT.NODE_NAME.eq(node)
                                        .or(LEARNING_FLOW_ATTEMPT.EXPIRY_MS.le(nowMs)))))
                .execute()) > 0;
    }

    /// Oldest first: an attempt that has waited longest is advanced first, so a busy document cannot
    /// starve one behind it.
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
            // What came after this turn is a consequence of the answer that has changed, and the walk will
            // derive it again.
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

    /// One open attempt per learner (A45), whether it is opened for the first time or opened again: the
    /// claim is taken back under the same unique key, and the database refuses it where another attempt
    /// of the learner holds it.
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
            // A node is walking it at this moment: its answers are that walk's to give.
            if (AttemptStatus.IN_PROGRESS.name().equals(row.get(LEARNING_FLOW_ATTEMPT.STATUS))
                && expiry != null
                && expiry > nowMs) {
                return false;
            }
            // Whatever else holds the claim keeps it, lapsed or not: a lapsed session is resumed, not
            // swept aside for an answer to an older one, and the unique key below says so.
            try {
                // Its claim taken back, and pushed out: an attempt a person has just answered must not be
                // swept away by the learner's next stream before the worker reaches it.
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
                // Another attempt holds the claim: a person is told rather than two learning it at once.
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

    /// A parked attempt keeps its claim: it is still learning, and its expiry is pushed out by the same
    /// heartbeat that would have extended it (A45).
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
                // A finished attempt is not parked back into life: it holds no claim and asks nothing.
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
                // The words it is asked with (N9), so that a parked run resumes in the words it began
                // in and a prompt can be rebuilt as it was sent.
                .set(LEARNING_FLOW_ATTEMPT.TEMPLATES, templates(attempt.askedWith()))
                .set(LEARNING_FLOW_ATTEMPT.BUILT_IN_VERSION, attempt.askedWith().known()
                        ? attempt.askedWith().builtInVersion()
                        : null)
                .set(LEARNING_FLOW_ATTEMPT.INSTRUCTIONS, attempt.askedWith().instructions())
                .set(LEARNING_FLOW_ATTEMPT.BUILD_VERSION, attempt.askedWith().build())
                // The binding a person asked to be improved (A46), where that is what this run is.
                .set(LEARNING_FLOW_ATTEMPT.IMPROVING_BINDING_UUID, attempt.improving())
                .returning(LEARNING_FLOW_ATTEMPT.ID)
                .fetchOne(LEARNING_FLOW_ATTEMPT.ID);
    }

    /// By number: a turn written as it is asked and again when it is judged is one row, so an attempt
    /// still running shows what it had got to.
    @Override
    public void turn(final long attemptId, final Turn turn) {
        // The score of the check that decided it (design 06 A144), where one did.
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
                // Which guidance this question carried (A46), so that a re-walk replays what was used
                // rather than what has since been added.
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
                // On the update as well as the insert: a question the attempt parked at is written
                // before it is asked, with nothing carried, and answered on the pass that resumes it.
                // Left off here the column would stay empty for exactly the turn that carried something.
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
                // A lease that does not lapse: nothing takes the claim from a parked attempt but a person.
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
                // A sending to the exhaustion step stands until the attempt closes (A65).
                .set(LEARNING_FLOW_ATTEMPT.EXHAUST_NEXT, (byte) 0)
                .set(LEARNING_FLOW_ATTEMPT.VERSION, LEARNING_FLOW_ATTEMPT.VERSION.plus(1))
                .set(LEARNING_FLOW_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                // A claim that has ended holds nothing (A45), and frees the learner for the next attempt.
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

    /// The attempts that bound this binding, whatever state they ended in — promoted, or
    /// awaiting a review that will now never happen. Not [#decided]'s filter, which is A25's decision
    /// about a draft *awaiting review*: these are about a binding that was serving.
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

    /// Their turns first, then the attempts: the turn rows point at the attempt rows, and a delete in the
    /// other order is a delete the foreign key refuses. In one transaction, so a pruned attempt never
    /// reads as an attempt with no turns.
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
            // One query for every attempt's turns, not one each: the Supervisor view pages these.
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

    /// Across every document, newest first (A28): what the Supervisor view lists. Without their turns —
    /// a page of transcripts is a page nobody reads — which the detail reads for one attempt.
    @Override
    public Page found(final AttemptCriteria criteria, final Collection<String> docUuids) {
        if (docUuids.isEmpty()) {
            // Nothing this person may see: not every attempt, and not a count of them either.
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
        // One learner's sessions, the list beneath its row (design 06 A143).
        if (criteria.getLearner() != null) {
            conditions.add(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(criteria.getLearner().getPipelineUuid()));
            conditions.add(LEARNING_FLOW_ATTEMPT.ELEMENT_ID.eq(criteria.getLearner().getElementId()));
        }
        // A deleted pipeline's attempts are kept to be read, and shown only when asked for (A137).
        if (!criteria.isShowDeletedPipelines()) {
            conditions.add(DSL.notExists(DSL
                    .selectOne()
                    .from(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE)
                    .where(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID
                            .eq(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID))));
        }
        // A request that says nothing about paging carries nulls, which are the caller's to survive.
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

    // The evidence read back from its column; empty for none
    private static List<CheckResult> evidence(final String json) {
        return NullSafe.isBlankString(json)
                ? List.of()
                : List.of(JsonUtil.readValue(json, CheckResult[].class));
    }

    /// The template set as its column holds it, JSON keyed by template name; null where the run's words
    /// were not recorded.
    private static String templates(final AskedWith askedWith) {
        return askedWith.known()
                ? JsonUtil.writeValueAsString(askedWith.templates(), false)
                : null;
    }

    /// The words a run was asked with, read back from its columns (N9). A row written before they were
    /// kept answers [AskedWith#UNKNOWN], which says so rather than offering today's words as though they
    /// were the ones used.
    private static AskedWith askedWith(final Record record) {
        final String json = record.get(LEARNING_FLOW_ATTEMPT.TEMPLATES);
        if (NullSafe.isBlankString(json)) {
            return AskedWith.UNKNOWN;
        }
        final Map<Template, String> templates = new EnumMap<>(Template.class);
        final Map<?, ?> read = JsonUtil.readValue(json, Map.class);
        for (final Map.Entry<?, ?> entry : read.entrySet()) {
            final String name = String.valueOf(entry.getKey());
            // A template this build no longer has is dropped rather than failing the read: an attempt is
            // history, and history may name something since removed.
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

    // A stop report as its column holds it, JSON; null for none
    private static String json(final StopReport report) {
        return report == null
                ? null
                : JsonUtil.writeValueAsString(report, false);
    }

    // A stop report read back from its column; null for none
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

    /// The guidance a turn carried, as a list of ids: small, fixed-width and never queried on, so a
    /// column of its own would be a table of its own for nothing.
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

    /// The score of the check that decided a turn, where one did (design 06 A144).
    private static TurnScore score(final Record record) {
        // The column is still called 'scorer'; what it holds is a Check's name since the one model of
        // checks. Renaming the column needs a jOOQ regeneration, so it goes with the rest of the table
        // changes rather than here.
        final String check = record.get(LEARNING_FLOW_TURN.SCORER);
        return check == null
                ? null
                : new TurnScore(Check.valueOf(check), record.get(LEARNING_FLOW_TURN.SCORE),
                        record.get(LEARNING_FLOW_TURN.SCORE_THRESHOLD));
    }
}
