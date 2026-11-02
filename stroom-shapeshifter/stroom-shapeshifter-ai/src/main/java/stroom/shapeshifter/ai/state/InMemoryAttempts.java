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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
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

/// The attempts of A28 in memory, for a harness with no database. One node's record is not the cluster's,
/// and it does not outlive the node, which is why the tables exist.
public final class InMemoryAttempts implements Attempts {

    /// The states in which an attempt's binding is one that was, or was about to be, serving: the ones a
    /// retraction is about.
    private static final Set<AttemptStatus> BOUND = Set.of(AttemptStatus.PROMOTED,
            AttemptStatus.PROVISIONAL, AttemptStatus.AWAITING_REVIEW);

    /// The states in which an attempt is still learning, and so still holds its learner's claim (A45). A
    /// draft awaiting review is not one of them: it has written its binding, and the binding is what the
    /// learner's state answers with until a person decides.
    private static final Set<AttemptStatus> OPEN = Set.of(AttemptStatus.IN_PROGRESS,
            AttemptStatus.AWAITING_MODEL, AttemptStatus.PARKED);

    private final Map<Long, Recorded> attempts = new LinkedHashMap<>();
    private final Map<Long, List<Turn>> turns = new LinkedHashMap<>();
    private final Map<Long, Grant> grants = new LinkedHashMap<>();
    private final Set<Long> exhaustNext = new HashSet<>();
    private final AtomicLong nextId = new AtomicLong(1);
    /// Which pipelines are deleted, whose attempts a person sees only when they ask (A137).
    private final DeletedPipelines deletedPipelines;

    /// Attempts in a scenario where no pipeline is deleted.
    public InMemoryAttempts() {
        this(new InMemoryDeletedPipelines());
    }

    /// @param deletedPipelines Which pipelines are deleted.
    @Inject
    public InMemoryAttempts(final DeletedPipelines deletedPipelines) {
        this.deletedPipelines = deletedPipelines;
    }

    /// One open attempt per learner (A45); an attempt whose expiry has passed has lapsed, and the next
    /// node resumes it.
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

    /// The newest, as the rows are read: an attempt superseded by a later one for the same learner is not
    /// what a node picks up.
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
        // A finished attempt is not parked back into life: it holds no claim and asks nothing.
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
        // A parked attempt is nobody's to carry; a running one is its own node's until it lapses.
        if (was.status() == AttemptStatus.IN_PROGRESS
            && !node.equals(was.attempt().node())
            && was.attempt().expiryMs() > nowMs) {
            return false;
        }
        final Attempt current = was.attempt();
        final Attempt taken = new Attempt(current.learner(), current.docUuid(), current.feed(),
                current.type(),
                current.inputId(), node, current.promotionMode(), expiryMs);
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

    /// By number: a turn written as it is asked and again when it is judged is one turn.
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
    public synchronized void parked(final long attemptId, final String reason, final long tokensSpent) {
        final Recorded was = attempts.get(attemptId);
        if (was != null && OPEN.contains(was.status())) {
            attempts.put(attemptId, new Recorded(was.id(), claim(was, Long.MAX_VALUE), AttemptStatus.PARKED, reason,
                    was.bindingUuid(), was.score(), was.tokensSpent() + tokensSpent, was.createTimeMs(),
                    System.currentTimeMillis(), List.of()));
        }
    }

    @Override
    public synchronized void closed(final long attemptId,
                                    final AttemptStatus status,
                                    final String decision,
                                    final String bindingUuid,
                                    final Double score,
                                    final long tokensSpent) {
        final Recorded was = attempts.get(attemptId);
        if (was != null) {
            // A claim that has ended holds nothing (A45), and frees the learner for the next attempt; what it
            // spent is added to what it had spent before it was parked.
            attempts.put(attemptId, new Recorded(was.id(), claim(was, 0L), status, decision, bindingUuid, score,
                    was.tokensSpent() + tokensSpent, was.createTimeMs(), System.currentTimeMillis(),
                    List.of()));
            // A sending to the exhaustion step stands until the attempt closes (A65).
            exhaustNext.remove(attemptId);
        }
    }

    @Override
    public synchronized void decided(final String docUuid,
                                     final String bindingUuid,
                                     final AttemptStatus status,
                                     final String decision) {
        attempts.values().stream()
                .filter(attempt -> attempt.attempt().docUuid().equals(docUuid)
                                   && bindingUuid.equals(attempt.bindingUuid())
                                   && attempt.status() == AttemptStatus.AWAITING_REVIEW)
                .toList()
                .forEach(attempt -> closed(attempt.id(), status, decision, attempt.bindingUuid(), attempt.score(),
                        0L));
    }

    /// The attempts that bound this binding, whatever state they ended in — promoted, provisional, or
    /// awaiting a review that will now never happen.
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
        // Found before anything is destroyed: a turn number that names nothing must leave the transcript
        // as it was, as it does in the table, where the two writes are one transaction.
        final int at = indexOf(written, turnNumber);
        if (at < 0) {
            throw new IllegalArgumentException("Attempt " + attemptId + " has no turn " + turnNumber);
        }
        final Turn was = written.get(at);
        // The guidance it was asked with is kept: answering instead replaces the answer, not the
        // question or what the question carried (A46). The table keeps it for the same reason.
        written.set(at, new Turn(was.number(), was.stepId(), was.candidate(), was.kind(), was.question(),
                answer, answeredBy, null, was.carried()));
        // What came after this turn is a consequence of the answer that has changed, and the walk will
        // derive it again.
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

    /// One open attempt per learner (A45), whether it is opened for the first time or opened again.
    @Override
    public synchronized boolean reopened(final long attemptId, final long nowMs, final long expiryMs) {
        final Recorded was = attempts.get(attemptId);
        if (was == null) {
            return false;
        }
        // A node is walking it at this moment: its answers are that walk's to give.
        if (was.status() == AttemptStatus.IN_PROGRESS && was.attempt().expiryMs() > nowMs) {
            return false;
        }
        // Whatever else holds the claim keeps it, lapsed or not: a lapsed session is resumed, not swept
        // aside for an answer to an older one.
        if (open(was.attempt().learner(), nowMs)
                .filter(other -> other.id() != attemptId)
                .isPresent()) {
            return false;
        }
        attempts.put(attemptId, new Recorded(was.id(), claim(was, expiryMs), AttemptStatus.AWAITING_MODEL,
                null, null, null, was.tokensSpent(), was.createTimeMs(), System.currentTimeMillis(),
                List.of()));
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
    public synchronized Page found(final AttemptCriteria criteria, final Collection<String> docUuids) {
        final List<Recorded> matching = attempts.values().stream()
                .filter(attempt -> docUuids.contains(attempt.attempt().docUuid()))
                .filter(attempt -> matches(attempt, criteria))
                .filter(attempt -> criteria.isShowDeletedPipelines()
                                   || !deletedPipelines.isDeleted(attempt.attempt().learner().pipelineUuid()))
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .toList();
        // Null offsets and lengths are what a request that says nothing about paging carries, and are
        // the caller's to survive rather than to be unboxed into.
        final PageRequest paging = criteria.getPageRequest();
        final long offset = paging == null || paging.getOffset() == null
                ? 0L
                : paging.getOffset();
        final int length = paging == null || paging.getLength() == null
                ? matching.size()
                : paging.getLength();
        // Without their turns, as the rows answer: a page of transcripts is a page nobody reads, and a
        // twin that hands them over hides the difference from every scenario.
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

    /// @return Every attempt, for the learning pipelines list.
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

    /// The same attempt with its claim expiring at a given time: the record is immutable, so a claim
    /// taken, extended or ended is a new one.
    private static Attempt claim(final Recorded was, final long expiryMs) {
        final Attempt attempt = was.attempt();
        return new Attempt(attempt.learner(), attempt.docUuid(), attempt.feed(), attempt.type(),
                attempt.inputId(), attempt.node(), attempt.promotionMode(), expiryMs);
    }

    private Recorded withTurns(final Recorded attempt) {
        return new Recorded(attempt.id(), attempt.attempt(), attempt.status(), attempt.decision(),
                attempt.bindingUuid(), attempt.score(), attempt.tokensSpent(), attempt.createTimeMs(),
                attempt.updateTimeMs(), List.copyOf(turns.getOrDefault(attempt.id(), List.of())),
                grants.getOrDefault(attempt.id(), Grant.NONE), exhaustNext.contains(attempt.id()));
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
