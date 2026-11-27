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

package stroom.learningflow.state;

import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.CheckResult;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Learner;
import stroom.util.shared.PageRequest;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/// An in-memory [Attempts], for tests that run without a database. It behaves like `AttemptsDao`, except
/// that its records are not shared with other nodes and do not survive a restart.
public final class InMemoryAttempts implements Attempts {

    /// The statuses in which an attempt's binding is, or was about to be, serving. These are the attempts
    /// that [#settled] updates when that binding is retracted.
    private static final Set<AttemptStatus> BOUND = Set.of(AttemptStatus.PROMOTED,
            AttemptStatus.AWAITING_REVIEW);

    /// The statuses in which an attempt is still learning, and so still holds its learner's claim. An
    /// attempt awaiting review is not one of them: it has already written its draft binding, and the
    /// learner's state reports that draft until a person decides.
    private static final Set<AttemptStatus> OPEN = Set.of(AttemptStatus.IN_PROGRESS,
            AttemptStatus.AWAITING_MODEL, AttemptStatus.PARKED);

    private final Map<Long, Recorded> attempts = new LinkedHashMap<>();
    private final Map<Long, List<Turn>> turns = new LinkedHashMap<>();
    private final Map<Long, Grant> grants = new LinkedHashMap<>();
    /// Why each attempt stopped, in parts, for attempts that stopped with a report.
    private final Map<Long, StopReport> reports = new LinkedHashMap<>();
    private final Set<Long> exhaustNext = new HashSet<>();
    /// The evidence each attempt's draft was judged on, and who decided about it and when.
    private final Map<Long, Review> reviews = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    /// Which pipelines are deleted. A person sees their attempts only when they ask for them.
    private final DeletedPipelines deletedPipelines;

    /// Creates attempts for a scenario in which no pipeline is deleted.
    public InMemoryAttempts() {
        this(new InMemoryDeletedPipelines());
    }

    /// Creates attempts that consult the given record of deleted pipelines.
    ///
    /// @param deletedPipelines Which pipelines are deleted, so that [#found] can hide their attempts.
    @Inject
    public InMemoryAttempts(final DeletedPipelines deletedPipelines) {
        this.deletedPipelines = deletedPipelines;
    }

    /// Opens a new attempt, unless the learner already has an open one. An open attempt whose lease has
    /// expired still counts, because the next worker resumes it.
    @Override
    public synchronized Optional<Long> opened(final Attempt attempt, final long nowMs) {
        final long now = System.currentTimeMillis();
        if (open(attempt.learner(), nowMs).isPresent()) {
            return Optional.empty();
        }
        final long id = nextId.getAndIncrement();
        attempts.put(id, new Recorded(id, attempt, AttemptStatus.IN_PROGRESS, null, null, null, 0L, now, now,
                List.of()));
        turns.put(id, new ArrayList<>());
        return Optional.of(id);
    }

    /// Returns the learner's newest open attempt, as the database query does, so that an older attempt
    /// is never picked up in place of a later one.
    @Override
    public synchronized Optional<Recorded> open(final Learner learner, final long nowMs) {
        return attempts.values().stream()
                .filter(attempt -> attempt.attempt().learner().equals(learner)
                                   && OPEN.contains(attempt.status()))
                .max(Comparator.comparingLong(Recorded::id))
                .map(this::withTurns);
    }

    @Override
    public synchronized void parked(final long attemptId,
                                    final AttemptStatus status,
                                    final long expiryMs,
                                    final long tokensSpent) {
        final Recorded was = attempts.get(attemptId);
        // A finished attempt is not brought back to life: it holds no claim and has nothing to ask.
        if (was != null && OPEN.contains(was.status())) {
            attempts.put(attemptId, new Recorded(was.id(), claim(was, expiryMs), status, was.decision(),
                    was.bindingUuid(), was.score(), was.tokensSpent() + tokensSpent, was.createTimeMs(),
                    System.currentTimeMillis(), List.of()));
        }
    }

    @Override
    public synchronized boolean claimed(final long attemptId, final String node, final long nowMs,
                                        final long expiryMs) {
        final Recorded was = attempts.get(attemptId);
        if (was == null || !OPEN.contains(was.status())) {
            return false;
        }
        // An attempt in progress belongs to its own node until its lease expires. Any other open attempt
        // may be claimed by any node.
        if (was.status() == AttemptStatus.IN_PROGRESS
            && !node.equals(was.attempt().node())
            && was.attempt().expiryMs() > nowMs) {
            return false;
        }
        final Attempt current = was.attempt();
        final Attempt taken = new Attempt(current.learner(), current.docUuid(), current.feed(),
                current.type(),
                current.inputId(), node, current.promotionMode(), expiryMs, current.askedWith(),
                current.improving());
        attempts.put(attemptId, new Recorded(was.id(), taken, AttemptStatus.IN_PROGRESS, was.decision(),
                was.bindingUuid(), was.score(), was.tokensSpent(), was.createTimeMs(),
                System.currentTimeMillis(), List.of()));
        return true;
    }

    @Override
    public synchronized void heartbeat(final long attemptId, final long expiryMs) {
        final Recorded was = attempts.get(attemptId);
        if (was != null && OPEN.contains(was.status())) {
            attempts.put(attemptId, new Recorded(was.id(), claim(was, expiryMs), was.status(), was.decision(),
                    was.bindingUuid(), was.score(), was.tokensSpent(), was.createTimeMs(),
                    System.currentTimeMillis(), List.of()));
        }
    }

    /// Adds or replaces a turn, keyed by its number. A turn is written when it is asked and again when it
    /// is judged, and both writes are the same turn.
    @Override
    public synchronized void turn(final long attemptId, final Turn turn) {
        final List<Turn> written = turns.computeIfAbsent(attemptId, key -> new ArrayList<>());
        for (int i = 0; i < written.size(); i++) {
            if (written.get(i).number() == turn.number()) {
                written.set(i, turn);
                return;
            }
        }
        written.add(turn);
    }

    @Override
    public synchronized void parked(final long attemptId,
                                    final String reason,
                                    final StopReport report,
                                    final long tokensSpent) {
        final Recorded was = attempts.get(attemptId);
        if (was != null && OPEN.contains(was.status())) {
            attempts.put(attemptId, new Recorded(was.id(), claim(was, Long.MAX_VALUE), AttemptStatus.PARKED, reason,
                    was.bindingUuid(), was.score(), was.tokensSpent() + tokensSpent, was.createTimeMs(),
                    System.currentTimeMillis(), List.of()));
            report(attemptId, report);
        }
    }

    @Override
    public synchronized void closed(final long attemptId,
                                    final AttemptStatus status,
                                    final String decision,
                                    final String bindingUuid,
                                    final Double score,
                                    final StopReport report,
                                    final long tokensSpent) {
        final Recorded was = attempts.get(attemptId);
        if (was != null) {
            report(attemptId, report);
            // Ending the claim frees the learner for its next attempt. What this run spent is added to what
            // the attempt had spent before it was parked.
            attempts.put(attemptId, new Recorded(was.id(), claim(was, 0L), status, decision, bindingUuid, score,
                    was.tokensSpent() + tokensSpent, was.createTimeMs(), System.currentTimeMillis(),
                    List.of()));
            // A request to go to the exhaustion step lasts only until the attempt closes.
            exhaustNext.remove(attemptId);
        }
    }

    @Override
    public synchronized void decided(final String docUuid,
                                     final String bindingUuid,
                                     final AttemptStatus status,
                                     final String decision,
                                     final String reviewedBy) {
        final long now = System.currentTimeMillis();
        attempts.values().stream()
                .filter(attempt -> attempt.attempt().docUuid().equals(docUuid)
                                   && bindingUuid.equals(attempt.bindingUuid())
                                   && attempt.status() == AttemptStatus.AWAITING_REVIEW)
                .toList()
                .forEach(attempt -> {
                    closed(attempt.id(), status, decision, attempt.bindingUuid(), attempt.score(), 0L);
                    final Review was = reviews.getOrDefault(attempt.id(), Review.NONE);
                    reviews.put(attempt.id(), new Review(was.evidence(), reviewedBy, now));
                });
    }

    @Override
    public synchronized void evidence(final long attemptId, final List<CheckResult> evidence) {
        final Review was = reviews.getOrDefault(attemptId, Review.NONE);
        reviews.put(attemptId, new Review(evidence, was.reviewedBy(), was.reviewedTimeMs()));
    }

    /// Updates the attempts that produced this binding, whether they were promoted or are still awaiting a
    /// review that will now never happen.
    @Override
    public synchronized void settled(final String docUuid, final String bindingUuid,
                                     final AttemptStatus status, final String decision) {
        attempts.values().stream()
                .filter(attempt -> attempt.attempt().docUuid().equals(docUuid)
                                   && bindingUuid.equals(attempt.bindingUuid())
                                   && BOUND.contains(attempt.status()))
                .toList()
                .forEach(attempt -> closed(attempt.id(), status, decision,
                        attempt.bindingUuid(), attempt.score(), 0L));
    }

    @Override
    public synchronized void amended(final long attemptId, final int turnNumber, final String answer,
                                     final String answeredBy) {
        final List<Turn> written = turns.computeIfAbsent(attemptId, key -> new ArrayList<>());
        // The turn is found before anything is changed, so that a turn number that does not exist leaves
        // the transcript as it was, as the database does, where the two writes are one transaction.
        final int at = indexOf(written, turnNumber);
        if (at < 0) {
            throw new IllegalArgumentException("Attempt " + attemptId + " has no turn " + turnNumber);
        }
        final Turn was = written.get(at);
        // The guidance the question carried is kept: a person answering instead replaces the answer, not
        // the question or what it carried. The database keeps it too.
        written.set(at, new Turn(was.number(), was.stepId(), was.candidate(), was.kind(), was.question(),
                answer, answeredBy, null, was.carried()));
        // The turns after this one followed from the answer that has now changed, so they are removed and
        // the walk through the flow produces them again.
        written.removeIf(turn -> turn.number() > turnNumber);
    }

    private static int indexOf(final List<Turn> written, final int turnNumber) {
        for (int i = 0; i < written.size(); i++) {
            if (written.get(i).number() == turnNumber) {
                return i;
            }
        }
        return -1;
    }

    /// Reopens an attempt so that the worker carries it on, for example after a person has answered it.
    /// It is refused if another attempt of the same learner is open, as for [#opened].
    @Override
    public synchronized boolean reopened(final long attemptId, final long nowMs, final long expiryMs) {
        final Recorded was = attempts.get(attemptId);
        if (was == null) {
            return false;
        }
        // A node is running this attempt now, so it is left to that node.
        if (was.status() == AttemptStatus.IN_PROGRESS && was.attempt().expiryMs() > nowMs) {
            return false;
        }
        // If another attempt of the learner holds the claim, it keeps it, even if its lease has expired:
        // an expired session is resumed, not displaced by an answer to an older one.
        if (open(was.attempt().learner(), nowMs)
                .filter(other -> other.id() != attemptId)
                .isPresent()) {
            return false;
        }
        attempts.put(attemptId, new Recorded(was.id(), claim(was, expiryMs), AttemptStatus.AWAITING_MODEL,
                null, null, null, was.tokensSpent(), was.createTimeMs(), System.currentTimeMillis(),
                List.of()));
        reports.remove(attemptId);
        return true;
    }

    @Override
    public synchronized List<Recorded> awaiting(final int limit) {
        return attempts.values().stream()
                .filter(attempt -> attempt.status() == AttemptStatus.AWAITING_MODEL)
                .sorted(Comparator.comparingLong(Recorded::id))
                .limit(limit)
                .map(this::withTurns)
                .toList();
    }

    @Override
    public synchronized List<Recorded> lapsed(final long nowMs, final int limit) {
        return attempts.values().stream()
                .filter(attempt -> attempt.status() == AttemptStatus.IN_PROGRESS
                                   && attempt.attempt().expiryMs() <= nowMs)
                .sorted(Comparator.comparingLong(Recorded::id))
                .limit(limit)
                .map(this::withTurns)
                .toList();
    }

    @Override
    public synchronized int prune(final long finishedBeforeMs) {
        final List<Long> old = attempts.values().stream()
                .filter(attempt -> !OPEN.contains(attempt.status())
                                   && attempt.status() != AttemptStatus.AWAITING_REVIEW
                                   && attempt.updateTimeMs() < finishedBeforeMs)
                .map(Recorded::id)
                .toList();
        old.forEach(id -> {
            attempts.remove(id);
            turns.remove(id);
        });
        return old.size();
    }

    @Override
    public synchronized Page found(final AttemptCriteria criteria,
                                   final Collection<String> docUuids,
                                   final Collection<String> pipelineUuids) {
        final List<Recorded> matching = attempts.values().stream()
                .filter(attempt -> docUuids.contains(attempt.attempt().docUuid()))
                .filter(attempt -> pipelineUuids == null
                                   || pipelineUuids.contains(attempt.attempt().learner().pipelineUuid()))
                .filter(attempt -> matches(attempt, criteria))
                .filter(attempt -> criteria.isShowDeletedPipelines()
                                   || !deletedPipelines.isDeleted(attempt.attempt().learner().pipelineUuid()))
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .toList();
        // A request without paging carries a null offset and length, which must be handled here rather
        // than unboxed.
        final PageRequest paging = criteria.getPageRequest();
        final long offset = paging == null || paging.getOffset() == null
                ? 0L
                : paging.getOffset();
        final int length = paging == null || paging.getLength() == null
                ? matching.size()
                : paging.getLength();
        // The attempts are returned without their turns, as the database implementation returns them.
        // Returning the turns here would hide that difference from every scenario.
        return new Page(matching.stream()
                .skip(offset)
                .limit(length)
                .toList(), matching.size());
    }

    private static boolean matches(final Recorded attempt, final AttemptCriteria criteria) {
        final Attempt was = attempt.attempt();
        return (criteria.getDocUuid() == null || criteria.getDocUuid().equals(was.docUuid()))
               && (criteria.getFeed() == null || criteria.getFeed().equals(was.feed()))
               && (criteria.getPromotionMode() == null || criteria.getPromotionMode() == was.promotionMode())
               && (criteria.getStatuses().isEmpty() || criteria.getStatuses().contains(attempt.status()))
               && (criteria.getLearner() == null
                   || (criteria.getLearner().getPipelineUuid().equals(was.learner().pipelineUuid())
                       && criteria.getLearner().getElementId().equals(was.learner().elementId())));
    }

    /// Returns every attempt, for the in-memory learning pipelines list.
    ///
    /// @return Every attempt.
    public synchronized List<Recorded> all() {
        return List.copyOf(attempts.values());
    }

    @Override
    public synchronized Optional<Recorded> byId(final long attemptId) {
        return Optional.ofNullable(attempts.get(attemptId)).map(this::withTurns);
    }

    @Override
    public synchronized List<Recorded> forDocument(final String docUuid, final int limit) {
        return attempts.values().stream()
                .filter(attempt -> attempt.attempt().docUuid().equals(docUuid))
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .limit(limit)
                .map(this::withTurns)
                .toList();
    }

    /// Returns a copy of the attempt with its claim expiring at the given time. The record is immutable,
    /// so taking, extending or ending a claim makes a new one.
    private static Attempt claim(final Recorded was, final long expiryMs) {
        final Attempt attempt = was.attempt();
        return new Attempt(attempt.learner(), attempt.docUuid(), attempt.feed(), attempt.type(),
                attempt.inputId(), attempt.node(), attempt.promotionMode(), expiryMs, attempt.askedWith(),
                attempt.improving());
    }

    private Recorded withTurns(final Recorded attempt) {
        return new Recorded(attempt.id(), attempt.attempt(), attempt.status(), attempt.decision(),
                attempt.bindingUuid(), attempt.score(), attempt.tokensSpent(), attempt.createTimeMs(),
                attempt.updateTimeMs(), List.copyOf(turns.getOrDefault(attempt.id(), List.of())),
                grants.getOrDefault(attempt.id(), Grant.NONE), exhaustNext.contains(attempt.id()),
                reports.get(attempt.id()), reviews.getOrDefault(attempt.id(), Review.NONE));
    }

    private void report(final long attemptId, final StopReport report) {
        if (report == null) {
            reports.remove(attemptId);
        } else {
            reports.put(attemptId, report);
        }
    }

    @Override
    public synchronized void exhaustNext(final long attemptId) {
        if (attempts.containsKey(attemptId)) {
            exhaustNext.add(attemptId);
        }
    }

    @Override
    public synchronized void granted(final long attemptId, final long tokens, final long ms) {
        if (attempts.containsKey(attemptId)) {
            grants.merge(attemptId, new Grant(tokens, ms), (was, more) -> was.plus(more.tokens(), more.ms()));
        }
    }
}
