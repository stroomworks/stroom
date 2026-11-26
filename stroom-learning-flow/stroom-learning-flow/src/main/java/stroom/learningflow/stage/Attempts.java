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

import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.CheckResult;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.TurnScore;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/// Storage for learning sessions (attempts) and their turns, as durable records that outlive the node that
/// created them. Each records its turns, what each turn was answered with and by whom, and the session's
/// outcome.
///
/// The record is the same whatever produced it (an automatic session differs from a deferred or reviewed
/// one only in who answered and when), so that one view can show them all, a person can see what was said,
/// and a resumed session can pick up where it stopped. The open session row is also the learner's claim:
/// only one session may be open per learner. Resuming is done by `Learning` and `Sessions`; this interface
/// only stores the record.
public interface Attempts {

    /// Opens a session for a learner. The row is the learner's claim: only one session may be open per
    /// learner, and a session waiting for the model or for a person is still open.
    ///
    /// An open session whose lease has expired still holds the claim. Its node has stopped, and the session
    /// is resumed from its recorded turns by the next worker to find it, rather than replaced.
    ///
    /// @param attempt The session to open.
    /// @param nowMs   The current time by the caller's clock, which the record keeps beside the claim's
    ///                expiry; both must come from the same clock.
    /// @return The session's id, or empty if another session is open for the learner, in which case the
    ///         caller has not taken the claim and must not learn.
    Optional<Long> opened(Attempt attempt, long nowMs);

    /// Gets the open session for a learner, if there is one, whether or not its lease has expired. A node
    /// that finds one does not take the claim, and the learner's streams wait on that session.
    Optional<Recorded> open(Learner learner, long nowMs);

    /// Leaves a session waiting until whoever will answer its next question (the model or a person) does.
    /// It keeps its learner's claim, since it is still learning, with its expiry set as a heartbeat would.
    /// The tokens spent are added to what it had spent before, because a resumed session's cost is its whole
    /// cost and not just its latest run.
    void parked(long attemptId, AttemptStatus status, long expiryMs, long tokensSpent);

    /// Parks a session for a person, because it stopped (e.g. ran out of budget) before its stream cleared
    /// the bar. It keeps its learner's claim with a lease that does not expire, the worker leaves it alone,
    /// and the reason is recorded as its decision for the Supervisor to show.
    ///
    /// @param attemptId   The session's id.
    /// @param reason      Why it stopped, in one line.
    /// @param report      The reason in parts (error, details, suggestions); null if there is none.
    /// @param tokensSpent What this run of the session cost.
    void parked(long attemptId, String reason, StopReport report, long tokensSpent);

    /// As [#parked(long, String, StopReport, long)], with no report.
    default void parked(final long attemptId, final String reason, final long tokensSpent) {
        parked(attemptId, reason, null, tokensSpent);
    }

    /// Takes up a session that stopped, for this node, and holds it while it runs. A session waiting for an
    /// answer has no thread behind it, so whichever worker reaches it first may take it. A running session
    /// belongs to its node until its claim expires, which is how another node takes over from a node that
    /// died mid-session.
    ///
    /// @param attemptId The session's id.
    /// @param node      This node's name.
    /// @param nowMs     The current time.
    /// @param expiryMs  When the claim expires unless renewed.
    /// @return Whether this node may resume it: false if it has finished, or if it is running on another
    ///         node whose claim has not expired. A session already running on *this* node is taken again,
    ///         since that is what a node re-entering its own session does. Two threads of the same node are
    ///         kept off one session because the job cannot run concurrently with itself, not by this
    ///         method.
    boolean claimed(long attemptId, String node, long nowMs, long expiryMs);

    /// Gets the sessions waiting for the model, oldest first, for the worker to advance. Whether a waiting
    /// session's claim has expired does not matter here: nobody is running it, so there is nothing to take
    /// it from, and a worker that was down while it expired must still pick it up, or the learner would
    /// wait for a stream that may never come. A waiting session that another session *has* replaced is no
    /// longer returned, because opening the replacement closed it.
    ///
    /// @param limit The maximum number to return.
    List<Recorded> awaiting(int limit);

    /// Marks a session to go to the exhaustion step: the worker resuming it puts no further transform
    /// question to the model and takes the flow's transition for spent candidates instead. The mark stays
    /// until the session is [#closed], so if the session parks on the way (e.g. for budget for the
    /// exhaustion step's own question) and is granted more, the run that follows still takes it.
    void exhaustNext(long attemptId);

    /// Grants a session more budget: tokens and milliseconds added to what the document allows it, recorded
    /// against this session alone and kept for as long as its row is.
    void granted(long attemptId, long tokens, long ms);

    /// Gets the running sessions whose claim has expired (`IN_PROGRESS`, with an expiry at or before
    /// `nowMs`), oldest first. The node running each has stopped without finishing, and the session is
    /// resumed from its recorded turns by whichever worker finds it, rather than started again from nothing.
    ///
    /// @param nowMs The time to judge expiry against.
    /// @param limit The maximum number to return.
    List<Recorded> lapsed(long nowMs, int limit);

    /// Renews a running session's claim. This is the heartbeat, called as each question is asked and during
    /// a slow model call, so that the node does not lose the claim it is learning under.
    void heartbeat(long attemptId, long expiryMs);

    /// Writes a turn of a session, by its number, as it is answered and again when it is judged. A session
    /// that is still running, or whose node died, then shows how far it got, which is the transcript a
    /// person most needs.
    void turn(long attemptId, Turn turn);

    /// Closes a session with its outcome. The tokens are added to what it had spent before, so a resumed
    /// session records its whole cost.
    ///
    /// @param report The reason in parts, if it stopped without a translation; null otherwise.
    void closed(long attemptId, AttemptStatus status, String decision, String bindingUuid, Double score,
                StopReport report, long tokensSpent);

    /// As [#closed(long, AttemptStatus, String, String, Double, StopReport, long)], with no report.
    default void closed(final long attemptId,
                        final AttemptStatus status,
                        final String decision,
                        final String bindingUuid,
                        final Double score,
                        final long tokensSpent) {
        closed(attemptId, status, decision, bindingUuid, score, null, tokensSpent);
    }

    /// Records a person's answer in place of the model's for one turn: *answer instead*, for the question a
    /// session stopped at, or *edit and re-run from here*, for one already answered. The turns after it are
    /// discarded, because they followed from an answer that has changed and the replay will derive them
    /// again. This turn's outcome is cleared too, since the new answer has not been judged yet.
    void amended(long attemptId, int turnNumber, String answer, String answeredBy);

    /// Reopens a session so that it can be run again, e.g. from a turn a person has changed. It takes back
    /// its learner's claim, with a new expiry, whether it had finished, was parked, or was being run by a
    /// node that has since stopped.
    ///
    /// @return Whether it was reopened. False if another session holds the claim now (expired or not, since
    ///         an expired one is resumed rather than replaced), or if a node is running this session at this
    ///         moment, because that run would overwrite a person's answer with the next turn it records.
    ///         Either way the person is told, rather than their answer being lost.
    boolean reopened(long attemptId, long nowMs, long expiryMs);

    /// Records what a person decided about the draft a session wrote. The session awaiting review for this
    /// binding is closed with the decision, so that an approved draft no longer shows as waiting. Who decided
    /// is kept with the time, and shown beside what was learned.
    ///
    /// @param reviewedBy Who approved or rejected the draft.
    void decided(String docUuid, String bindingUuid, AttemptStatus status, String decision, String reviewedBy);

    /// Records how what a session learned scored on each check that ran, for a person deciding whether to
    /// approve it. Only numbers are kept, because what a check said about a record may quote the stream's
    /// data.
    ///
    /// @param attemptId The session's id.
    /// @param evidence  One result per check.
    void evidence(long attemptId, List<CheckResult> evidence);

    /// Records what has become of the binding a session bound, e.g. that it was retracted.
    ///
    /// This must be separate from [#decided], which records a decision about a draft *awaiting review* and
    /// only matches sessions in that state. This is about a binding that was **serving**, whose session
    /// closed as promoted long ago, so [#decided] would match nothing and the session would go on showing
    /// an outcome that is no longer true.
    ///
    /// Sessions that ended some other way are left alone, because they did not write this binding.
    void settled(String docUuid, String bindingUuid, AttemptStatus status, String decision);

    /// Deletes the records of sessions that finished before a given time, with their turns. A row per
    /// session and a row per turn is the fastest-growing data this feature writes, and a record kept for
    /// ever is a record nobody reads.
    ///
    /// A session that is still learning or still waiting is never removed, however old it is.
    ///
    /// @param finishedBeforeMs Sessions that finished before this time are deleted.
    /// @return How many sessions were removed.
    int prune(long finishedBeforeMs);

    /// Gets one session in full, with its turns in order.
    Optional<Recorded> byId(long attemptId);

    /// Gets the sessions of a document, newest first.
    List<Recorded> forDocument(String docUuid, int limit);

    /// Finds sessions across documents, newest first, for the Supervisor, filtered by the criteria. Their
    /// turns are not included; the detail view reads those.
    ///
    /// @param criteria The filters, and which page to return.
    /// @param docUuids The documents to search: those the user may see, so that the total and the pages
    ///                 reflect what they may read rather than everything that exists. Empty means none, and
    ///                 returns nothing.
    /// @return The requested page, and how many sessions match in total.
    Page found(AttemptCriteria criteria, Collection<String> docUuids);


    // --------------------------------------------------------------------------------


    /// A learning session, as opened.
    ///
    /// @param learner       The learner the session is for, whose claim it holds.
    /// @param docUuid       The UUID of the document it learns under.
    /// @param feed          The feed of the stream it opened on.
    /// @param type          The type of the stream it opened on.
    /// @param inputId       The meta id of the stream that opened the session, so a person can see what it
    ///                      was learning from.
    /// @param node          Which node opened it.
    /// @param promotionMode The promotion mode it opened under.
    /// @param expiryMs      When the claim expires if no heartbeat renews it.
    /// @param askedWith     The question templates and texts the session used, so that a resumed session
    ///                      continues with the same wording and a person with *View Data* can be shown the
    ///                      prompt as it was sent.
    /// @param improving     The UUID of the binding being improved, for an improvement session; otherwise
    ///                      null.
    record Attempt(Learner learner,
                   String docUuid,
                   String feed,
                   String type,
                   Long inputId,
                   String node,
                   PromotionMode promotionMode,
                   long expiryMs,
                   AskedWith askedWith,
                   String improving) {

        public Attempt {
            askedWith = askedWith == null
                    ? AskedWith.UNKNOWN
                    : askedWith;
        }

        /// Creates a session that learns from a stream rather than improving a binding, which is every
        /// session except those a person asks for with *Improve*.
        ///
        /// @param learner       The learner the session is for.
        /// @param docUuid       The UUID of the document it learns under.
        /// @param feed          The feed of the stream it opened on.
        /// @param type          The type of the stream it opened on.
        /// @param inputId       The meta id of the stream that opened it.
        /// @param node          Which node opened it.
        /// @param promotionMode The promotion mode it opened under.
        /// @param expiryMs      When its claim expires.
        /// @param askedWith     The question templates and texts it uses.
        public Attempt(final Learner learner,
                       final String docUuid,
                       final String feed,
                       final String type,
                       final Long inputId,
                       final String node,
                       final PromotionMode promotionMode,
                       final long expiryMs,
                       final AskedWith askedWith) {
            this(learner, docUuid, feed, type, inputId, node, promotionMode, expiryMs, askedWith, null);
        }

        /// Whether this session is an improvement a person asked for, rather than learning started by a
        /// stream. An improvement has no stream behind it: its sample is the corpus records the binding was
        /// accepted on, reassembled, so the worker reads it from the regression corpus rather than the
        /// stream store.
        ///
        /// @return True if it is improving a binding.
        public boolean isImprovement() {
            return improving != null;
        }

        /// Creates a session whose question wording was not recorded: one opened before the wording was
        /// recorded, or a test that does not care about it.
        ///
        /// @param learner       The learner the session is for.
        /// @param docUuid       The UUID of the document it learns under.
        /// @param feed          The feed of the stream it opened on.
        /// @param type          The type of the stream it opened on.
        /// @param inputId       The meta id of the stream that opened it.
        /// @param node          Which node opened it.
        /// @param promotionMode The promotion mode it opened under.
        /// @param expiryMs      When its claim expires.
        public Attempt(final Learner learner,
                       final String docUuid,
                       final String feed,
                       final String type,
                       final Long inputId,
                       final String node,
                       final PromotionMode promotionMode,
                       final long expiryMs) {
            this(learner, docUuid, feed, type, inputId, node, promotionMode, expiryMs, AskedWith.UNKNOWN, null);
        }
    }

    /// One turn of a session: a question put to the model (or answered by a person instead) and its outcome.
    ///
    /// @param answer     The answer, or null for a turn a session stopped at that nobody has answered yet.
    /// @param answeredBy The model the document names, or the person who answered instead; null while
    ///                   nobody has answered.
    /// @param outcome    How the answer was judged, once it has been; null while it is being asked.
    /// @param score      The score of the check that decided the outcome; null if no scorer decided it.
    /// @param carried    The ids of the guidance the question included.
    record Turn(int number,
                String stepId,
                int candidate,
                QuestionKind kind,
                String question,
                String answer,
                String answeredBy,
                StepOutcome outcome,
                TurnScore score,
                List<Long> carried) {

        public Turn {
            carried = List.copyOf(carried);
        }

        /// Creates a turn that included no guidance, which is every turn until a person leaves some.
        public Turn(final int number,
                    final String stepId,
                    final int candidate,
                    final QuestionKind kind,
                    final String question,
                    final String answer,
                    final String answeredBy,
                    final StepOutcome outcome) {
            this(number, stepId, candidate, kind, question, answer, answeredBy, outcome, null, List.of());
        }

        /// Creates a turn that no scorer decided, with the guidance it included.
        public Turn(final int number,
                    final String stepId,
                    final int candidate,
                    final QuestionKind kind,
                    final String question,
                    final String answer,
                    final String answeredBy,
                    final StepOutcome outcome,
                    final List<Long> carried) {
            this(number, stepId, candidate, kind, question, answer, answeredBy, outcome, null, carried);
        }
    }

    /// A page of sessions, and how many there are in total.
    record Page(List<Recorded> attempts, long total) {

    }

    /// A session as it is read back: what it was, its outcome, and all its turns.
    ///
    /// @param grant       What a person granted the session beyond the document's budgets.
    /// @param exhaustNext Whether a person sent the session to the exhaustion step: the next transform
    ///                    question is not put to the model, and the flow's transition for spent candidates
    ///                    is taken instead. It stays set until the session closes, through any park and
    ///                    grant in between.
    /// @param report      The reason in parts, if it stopped without a translation; null otherwise.
    /// @param review      The approval evidence and review decision for its draft, if any.
    record Recorded(long id,
                    Attempt attempt,
                    AttemptStatus status,
                    String decision,
                    String bindingUuid,
                    Double score,
                    long tokensSpent,
                    long createTimeMs,
                    long updateTimeMs,
                    List<Turn> turns,
                    Grant grant,
                    boolean exhaustNext,
                    StopReport report,
                    Review review) {

        public Recorded {
            review = review == null
                    ? Review.NONE
                    : review;
        }

        public Recorded(final long id, final Attempt attempt, final AttemptStatus status, final String decision,
                        final String bindingUuid, final Double score, final long tokensSpent, final long createTimeMs,
                        final long updateTimeMs, final List<Turn> turns, final Grant grant,
                        final boolean exhaustNext, final StopReport report) {
            this(id, attempt, status, decision, bindingUuid, score, tokensSpent, createTimeMs, updateTimeMs, turns,
                    grant, exhaustNext, report, Review.NONE);
        }

        public Recorded(final long id, final Attempt attempt, final AttemptStatus status, final String decision,
                        final String bindingUuid, final Double score, final long tokensSpent, final long createTimeMs,
                        final long updateTimeMs, final List<Turn> turns, final Grant grant,
                        final boolean exhaustNext) {
            this(id, attempt, status, decision, bindingUuid, score, tokensSpent, createTimeMs, updateTimeMs, turns,
                    grant, exhaustNext, null);
        }

        public Recorded(final long id, final Attempt attempt, final AttemptStatus status, final String decision,
                        final String bindingUuid, final Double score, final long tokensSpent, final long createTimeMs,
                        final long updateTimeMs, final List<Turn> turns) {
            this(id, attempt, status, decision, bindingUuid, score, tokensSpent, createTimeMs, updateTimeMs, turns,
                    Grant.NONE, false);
        }

        public Recorded(final long id, final Attempt attempt, final AttemptStatus status, final String decision,
                        final String bindingUuid, final Double score, final long tokensSpent, final long createTimeMs,
                        final long updateTimeMs, final List<Turn> turns, final Grant grant) {
            this(id, attempt, status, decision, bindingUuid, score, tokensSpent, createTimeMs, updateTimeMs, turns,
                    grant, false);
        }
    }

    /// What a person deciding about a session's draft is shown, and what they decided.
    ///
    /// @param evidence       How what it learned scored on each check that ran; empty if it learned nothing.
    /// @param reviewedBy     Who approved or rejected it; null if nobody has.
    /// @param reviewedTimeMs When it was approved or rejected; null if nobody has.
    record Review(List<CheckResult> evidence, String reviewedBy, Long reviewedTimeMs) {

        public static final Review NONE = new Review(List.of(), null, null);

        public Review {
            evidence = evidence == null
                    ? List.of()
                    : List.copyOf(evidence);
        }
    }

    /// What a person granted a session beyond the document's budgets: added to them for that session alone,
    /// and shown on its row.
    record Grant(long tokens, long ms) {

        public static final Grant NONE = new Grant(0L, 0L);

        /// Returns this grant with more added.
        public Grant plus(final long moreTokens, final long moreMs) {
            return new Grant(tokens + moreTokens, ms + moreMs);
        }
    }
}
