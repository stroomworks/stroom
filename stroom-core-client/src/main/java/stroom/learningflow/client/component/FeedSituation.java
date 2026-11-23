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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.Suggestion;
import stroom.learningflow.shared.SuggestionAction;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorTurn;
import stroom.learningflow.shared.TimeZoneSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/// Where a feed stands, said for a person (design 07, the Feeds tab): a chip, one sentence, a sentence of
/// detail, and the actions that fit it — the main ones as buttons, the rest in a menu. Where its learning
/// run stopped with a report ([StopReport]), the sentence is the report's error, and its details and
/// suggestions come with it; a suggestion the tab can act on, and that isn't already a button, is
/// [#canDo]. Worked out from what
/// the Supervisor already reads: the feed's learner row, its learning run (with its turns, where fetched)
/// and the document it runs under. A learner's actions and its run's are offered together, so that nobody
/// has to know which level an action belongs to.
///
/// The guards are the Supervisor's: nothing that would change a deleted pipeline's learning (A137), the
/// translation's actions only where one serves, stop only where it hasn't stopped and
/// no draft awaits review, approve and reject only on a draft, grant and the fallback only on a paused run.
public final class FeedSituation {

    // The chip of a Smart Pipeline that would learn but for a spend ceiling (A24)
    private static final String SPEND_LIMIT = "Spend limit";

    private final Tone tone;
    private final String chip;
    private final String headline;
    private final String detail;
    private final List<FeedAction> primary;
    private final List<FeedAction> more;
    private final List<String> details;
    private final List<Suggestion> suggestions;
    private final Set<SuggestionAction> doable;

    private FeedSituation(final Tone tone,
                          final String chip,
                          final String headline,
                          final String detail,
                          final List<FeedAction> primary,
                          final List<FeedAction> more,
                          final List<String> details,
                          final List<Suggestion> suggestions,
                          final Set<SuggestionAction> doable) {
        this.tone = tone;
        this.chip = chip;
        this.headline = headline;
        this.detail = detail;
        this.primary = Collections.unmodifiableList(primary);
        this.more = Collections.unmodifiableList(more);
        this.details = Collections.unmodifiableList(details);
        this.suggestions = Collections.unmodifiableList(suggestions);
        this.doable = Collections.unmodifiableSet(doable);
    }

    /// Works out where a feed stands.
    ///
    /// @param learner The feed's learner row.
    /// @param run     The learning run shown with it, or null for none; its turns, where fetched, say what
    ///                step it is at.
    /// @param doc     The document it runs under, or null where not known: its pass mark, flag and default
    ///                tries.
    /// @param nowMs   The time now, for how long streams have waited.
    /// @return Where it stands.
    public static FeedSituation of(final SupervisorLearner learner,
                                   final SupervisorAttempt run,
                                   final LearningFlowDoc doc,
                                   final long nowMs) {
        final Set<FeedAction> available = available(learner, run);
        final String waiting = waiting(learner, nowMs);
        final AttemptStatus status = run == null || run.isPipelineDeleted()
                ? null
                : run.getStatus();

        if (learner.isPipelineDeleted()) {
            return build(Tone.IDLE, "Deleted",
                    "This feed's pipeline has been deleted",
                    "What it learned is kept to read. Nothing here can change it.",
                    available, List.of());
        }
        if (status == AttemptStatus.AWAITING_REVIEW) {
            final StringBuilder said = new StringBuilder();
            if (run.getScore() != null) {
                said.append("It scored ").append(PlainWords.percent(run.getScore())).append(". ");
            }
            said.append("Approving it puts it to use");
            if (learner.getWaiting() > 0) {
                said.append(" and tries the ").append(PlainWords.count(learner.getWaiting(), "waiting stream"))
                        .append(" again");
            }
            said.append('.');
            return build(Tone.WARNING, "Approve",
                    "A new translation is ready for you to check",
                    said.toString(),
                    available, List.of(FeedAction.APPROVE, FeedAction.REJECT));
        }
        if (status == AttemptStatus.PARKED) {
            final String error = error(run);
            final String reason = error == null || error.isEmpty()
                    ? "Paused for a person"
                    : "Paused: " + PlainWords.lowerFirst(error);
            final StringBuilder said = new StringBuilder("This run has used ")
                    .append(PlainWords.count(run.getTokensSpent(), "token"));
            final List<String> given = new ArrayList<>();
            if (run.getGrantedTokens() > 0) {
                given.add(PlainWords.number(run.getGrantedTokens()) + " more tokens");
            }
            if (run.getGrantedMs() > 0) {
                given.add(GrantText.duration(run.getGrantedMs()) + " more for each run");
            }
            if (!given.isEmpty()) {
                said.append(", and has been given ").append(PlainWords.list(given));
            }
            said.append(". ").append(waiting);
            if (run.isExhaustNext()) {
                said.append(" It will finish with Unknowns when the learning job next takes it up.");
            }
            return build(Tone.WARNING, "Paused", reason, said.toString(),
                    available, List.of(FeedAction.GRANT, FeedAction.EXHAUST, FeedAction.ANSWER))
                    .withReport(run.getReport(), available, doc);
        }
        if (learner.getState() == LearnerState.GIVEN_UP) {
            final String reason = learner.getGivenUpReason() == null || learner.getGivenUpReason().isEmpty()
                    ? "Stopped learning"
                    : "Stopped learning: " + PlainWords.lowerFirst(learner.getGivenUpReason());
            // The run that gave it up says why in parts; a later run, or none, doesn't
            final StopReport report = status == AttemptStatus.ABANDONED
                    ? run.getReport()
                    : null;
            return build(Tone.DANGER, "Stopped", reason,
                    "Streams that nothing translates are errored with this reason. Learn again to try once more "
                    + "from its next stream.",
                    available, List.of(FeedAction.RELEARN))
                    .withReport(report, available, doc);
        }
        if (status == AttemptStatus.ERROR) {
            // A run that failed leaves the learner as it was: its next stream opens another
            final String error = error(run);
            return build(Tone.DANGER, "Failed",
                    error == null || error.isEmpty()
                            ? "The learning run failed"
                            : "Failed: " + PlainWords.lowerFirst(error),
                    "Learning tries again from the feed's next stream. " + waiting,
                    available, List.of())
                    .withReport(run.getReport(), available, doc);
        }
        if (learner.getState() == LearnerState.LEARNING_OFF) {
            return build(Tone.IDLE, "Off",
                    "Learning is switched off in its Learning Flow",
                    "What it has learned goes on translating. Streams that nothing translates wait until learning "
                    + "is switched on.",
                    available, List.of(FeedAction.OPEN_SETTINGS));
        }
        if (status == AttemptStatus.IN_PROGRESS || status == AttemptStatus.AWAITING_MODEL) {
            return build(Tone.INFO, "Learning", "Learning: " + atStep(run, doc), waiting,
                    available, List.of());
        }
        if (learner.getState() == LearnerState.LEARNING && learner.getSpendPaused() != null) {
            // A spend ceiling (A24): nothing is wrong with the feed, and it starts again by itself
            return build(Tone.WARNING, SPEND_LIMIT,
                    "Not learning until a spend ceiling clears",
                    learner.getSpendPaused() + " " + waiting,
                    available, List.of());
        }
        if (learner.getState() == LearnerState.LEARNING) {
            return build(Tone.INFO, "Learning",
                    learner.getBindingUuid() == null
                            ? "Learning: waiting for a stream to learn from"
                            : "Learning again from its next stream",
                    waiting,
                    available, List.of());
        }
        if (learner.getState() == LearnerState.SERVING) {
            if (learner.isBelowThreshold()) {
                return build(Tone.WARNING, "Below par",
                        "Translating, but scoring below its Learning Flow's flag",
                        lately(learner) + (doc == null
                                ? ""
                                : " A translation is flagged below " + PlainWords.percent(doc.getRelearnThreshold())
                                  + ".") + " It goes on translating.",
                        available, List.of(FeedAction.IMPROVE));
            }
            return build(Tone.GOOD, "Translating", "Translating", lately(learner),
                    available, List.of(FeedAction.IMPROVE));
        }
        return build(Tone.WARNING, "Needs you",
                "Waiting for a person",
                "See its latest learning run for what it needs. " + waiting,
                available, List.of());
    }

    /// Which zone the pipeline reads timestamps in, and where that comes from (decision N6, Track 2 step 4),
    /// so that a surprising date can be traced to what set the zone.
    ///
    /// @param learner A feed's learner row, as its pipeline's Learning Status tab lists it.
    /// @return The sentence, or null where the row doesn't say.
    public static String timeZone(final SupervisorLearner learner) {
        if (learner == null || learner.getTimeZone() == null) {
            return null;
        }
        final String where = learner.getTimeZoneSource() == TimeZoneSource.PIPELINE
                ? "Timestamps with no offset are read in " + learner.getTimeZone() + ", this pipeline's time zone"
                : "Timestamps with no offset are read as " + learner.getTimeZone()
                  + ", as this pipeline sets no time zone";
        final String said = where + ", unless a stream's TimeZone header says otherwise.";
        return learner.getTimeZoneProblem() == null
                ? said
                : said + " " + learner.getTimeZoneProblem();
    }

    /// @param learner A feed's learner row.
    /// @return The chip its row in the list shows, from the row alone.
    public static String listChip(final SupervisorLearner learner) {
        if (learner.isPipelineDeleted()) {
            return "Deleted";
        }
        final LearnerState state = learner.getState();
        if (state == null) {
            return "";
        }
        switch (state) {
            case AWAITING_PERSON:
                return "Needs you";
            case LEARNING:
                return learner.getSpendPaused() == null
                        ? "Learning"
                        : SPEND_LIMIT;
            case GIVEN_UP:
                return "Stopped";
            case LEARNING_OFF:
                return "Off";
            default:
                return learner.isBelowThreshold()
                        ? "Below par"
                        : "Translating";
        }
    }

    /// @param learner A feed's learner row.
    /// @return The tone of the chip its row in the list shows.
    public static Tone listTone(final SupervisorLearner learner) {
        if (learner.isPipelineDeleted() || learner.getState() == null) {
            return Tone.IDLE;
        }
        switch (learner.getState()) {
            case AWAITING_PERSON:
                return Tone.WARNING;
            case LEARNING:
                return learner.getSpendPaused() == null
                        ? Tone.INFO
                        : Tone.WARNING;
            case GIVEN_UP:
                return Tone.DANGER;
            case LEARNING_OFF:
                return Tone.IDLE;
            default:
                return learner.isBelowThreshold()
                        ? Tone.WARNING
                        : Tone.GOOD;
        }
    }

    /// @param learner A feed's learner row.
    /// @return The line under its name in the list: what it scores, why it stopped, or what waits.
    public static String listSummary(final SupervisorLearner learner) {
        final List<String> parts = new ArrayList<>();
        if (learner.getState() == LearnerState.GIVEN_UP && learner.getGivenUpReason() != null) {
            parts.add(learner.getGivenUpReason());
        } else if (learner.getBindingUuid() != null && learner.getRollingScore() != null) {
            parts.add("Scores " + PlainWords.percent(learner.getRollingScore()));
        }
        if (learner.getSkips() > 0) {
            // A record the translation said was deliberately not an event. Nothing caps how many it may
            // declare (ruling A148), so the count is what a person judges it by, and it belongs where
            // they already read the score rather than behind another click.
            parts.add(PlainWords.number(learner.getSkips()) + " of "
                      + PlainWords.number(learner.getRecords()) + " skipped");
        }
        if (learner.getWaiting() > 0) {
            parts.add(PlainWords.number(learner.getWaiting()) + " waiting" + (learner.isRaised()
                    ? ", overdue"
                    : ""));
        }
        return String.join(" · ", parts);
    }

    private static FeedSituation build(final Tone tone,
                                       final String chip,
                                       final String headline,
                                       final String detail,
                                       final Set<FeedAction> available,
                                       final List<FeedAction> wanted) {
        final List<FeedAction> primary = new ArrayList<>();
        for (final FeedAction action : wanted) {
            if (available.contains(action)) {
                primary.add(action);
            }
        }
        final List<FeedAction> more = new ArrayList<>();
        for (final FeedAction action : FeedAction.values()) {
            if (available.contains(action) && !primary.contains(action)) {
                more.add(action);
            }
        }
        return new FeedSituation(tone, chip, headline, detail, primary, more, List.of(), List.of(),
                EnumSet.noneOf(SuggestionAction.class));
    }

    /// This situation as a person who may only read the feed's pipeline sees it: nothing that would change its
    /// learning, which the server would refuse them; only what opens something to read, from a suggestion,
    /// the model's document (the learned translation has its own link). What it says is unchanged.
    ///
    /// @return The situation, read only.
    public FeedSituation readOnly() {
        final Set<SuggestionAction> readable = EnumSet.noneOf(SuggestionAction.class);
        if (doable.contains(SuggestionAction.OPEN_MODEL)) {
            readable.add(SuggestionAction.OPEN_MODEL);
        }
        return new FeedSituation(tone, chip, headline, detail, List.of(), List.of(), details, suggestions, readable);
    }

    /// This situation with a run's report: its details and suggestions, and which suggestions the tab can
    /// act on. Those whose action is already a button here are said, not offered twice.
    private FeedSituation withReport(final StopReport report,
                                     final Set<FeedAction> available,
                                     final LearningFlowDoc doc) {
        if (report == null) {
            return this;
        }
        final Set<SuggestionAction> can = EnumSet.noneOf(SuggestionAction.class);
        for (final Suggestion suggestion : report.getSuggestions()) {
            final SuggestionAction action = suggestion.getAction();
            if (action == null) {
                continue;
            }
            final FeedAction feedAction = feedAction(action);
            final boolean doableHere = switch (action) {
                case LEAVE_NOTE, CHANGE_LIMITS, CHANGE_STEPS, CHANGE_MODEL -> true;
                case OPEN_MODEL -> doc != null && doc.getModel() != null;
                default -> feedAction != null && available.contains(feedAction) && !primary.contains(feedAction);
            };
            if (doableHere) {
                can.add(action);
            }
        }
        return new FeedSituation(tone, chip, headline, detail, primary, more, report.getDetails(),
                report.getSuggestions(), can);
    }

    /// @param action What a suggestion can be acted on with.
    /// @return The feed's action that does it, or null where it is done elsewhere (a note, the Setup tab, the
    ///         model's document).
    public static FeedAction feedAction(final SuggestionAction action) {
        return switch (action) {
            case GIVE_MORE -> FeedAction.GRANT;
            case ANSWER -> FeedAction.ANSWER;
            case FINISH_WITH_UNKNOWNS -> FeedAction.EXHAUST;
            case LEARN_AGAIN -> FeedAction.RELEARN;
            default -> null;
        };
    }

    // What a run stopped on, in one line: its report's error, else its decision
    private static String error(final SupervisorAttempt run) {
        return run.getReport() != null
                ? run.getReport().getError()
                : run.getDecision();
    }

    // What may be done now, by the Supervisor's own guards
    private static Set<FeedAction> available(final SupervisorLearner learner, final SupervisorAttempt run) {
        final Set<FeedAction> available = EnumSet.noneOf(FeedAction.class);
        final boolean live = !learner.isPipelineDeleted();
        final boolean serving = live && learner.getBindingUuid() != null;
        final AttemptStatus status = run == null || run.isPipelineDeleted()
                ? null
                : run.getStatus();
        if (live && status == AttemptStatus.AWAITING_REVIEW) {
            available.add(FeedAction.APPROVE);
            available.add(FeedAction.REJECT);
        }
        if (live && status == AttemptStatus.PARKED) {
            available.add(FeedAction.GRANT);
            // Marked Unknowns are event-logging's: a target with none has no fallback to finish with
            if (!run.isExhaustNext() && !learner.isFallbackUnavailable()) {
                available.add(FeedAction.EXHAUST);
            }
            if (unanswered(run) != null) {
                available.add(FeedAction.ANSWER);
            }
        }
        if (serving) {
            available.add(FeedAction.IMPROVE);
        }
        if (live) {
            available.add(FeedAction.RELEARN);
        }
        // Refused by the server while a draft awaits review: rejecting it is the way to give up
        if (live && learner.getState() != LearnerState.GIVEN_UP && status != AttemptStatus.AWAITING_REVIEW) {
            available.add(FeedAction.GIVE_UP);
        }
        if (serving) {
            available.add(FeedAction.RETRACT);
        }
        if (live && learner.getState() == LearnerState.LEARNING_OFF) {
            available.add(FeedAction.OPEN_SETTINGS);
        }
        return available;
    }

    /// Whether a feed's Learning history should be open of its own accord: while a run is going or waits for
    /// someone, or streams wait for the feed. Otherwise, e.g. while it is just translating, it is folded, as a
    /// Data Provider needs only where it stands.
    ///
    /// @param learner The feed's learner row.
    /// @param run     Its learning run shown, or null for none.
    /// @return Whether its Learning history should be open.
    public static boolean needsHistory(final SupervisorLearner learner, final SupervisorAttempt run) {
        if (learner.isPipelineDeleted()) {
            return false;
        }
        if (learner.getWaiting() > 0) {
            return true;
        }
        if (run == null || run.isPipelineDeleted()) {
            return false;
        }
        final AttemptStatus status = run.getStatus();
        return status == AttemptStatus.IN_PROGRESS
               || status == AttemptStatus.AWAITING_MODEL
               || status == AttemptStatus.PARKED
               || status == AttemptStatus.AWAITING_REVIEW;
    }

    /// @param run A learning run, with its turns.
    /// @return The question it stopped at, which nobody has answered, or null for none.
    public static SupervisorTurn unanswered(final SupervisorAttempt run) {
        if (run == null || run.getTurns() == null || run.getTurns().isEmpty()) {
            return null;
        }
        final SupervisorTurn last = run.getTurns().get(run.getTurns().size() - 1);
        return last.getAnswer() == null
                ? last
                : null;
    }

    // e.g. "writing the parser, try 2 of 3", from the run's last turn and the document's flow
    private static String atStep(final SupervisorAttempt run, final LearningFlowDoc doc) {
        final List<SupervisorTurn> turns = run.getTurns();
        if (turns == null || turns.isEmpty()) {
            return "waiting for the learning job to take it up";
        }
        final SupervisorTurn last = turns.get(turns.size() - 1);
        final FlowStep step = step(doc, last.getStepId());
        final String doing = step != null
                ? PlainWords.doing(step.getKind(), step.getRole())
                : PlainWords.doing(last.getKind(), null);
        final Integer tries = step == null || doc == null
                ? null
                : step.getCandidates() == null
                        ? Integer.valueOf(doc.getMaxAttempts())
                        : step.getCandidates();
        return tries == null
                ? doing + ", try " + last.getCandidate()
                : doing + ", try " + last.getCandidate() + " of " + tries;
    }

    /// @param doc    The document, or null.
    /// @param stepId A step's id as a turn records it, or null.
    /// @return The document's step of that id, or null where there is none.
    public static FlowStep step(final LearningFlowDoc doc, final String stepId) {
        if (doc == null || stepId == null || doc.getFlow() == null) {
            return null;
        }
        for (final FlowStep step : doc.getFlow().getSteps()) {
            if (step.effectiveId().equalsIgnoreCase(stepId)) {
                return step;
            }
        }
        return null;
    }

    private static String waiting(final SupervisorLearner learner, final long nowMs) {
        if (learner.getWaiting() == 0) {
            return "No streams are waiting.";
        }
        final StringBuilder said = new StringBuilder(PlainWords.count(learner.getWaiting(), "stream"))
                .append(learner.getWaiting() == 1
                        ? " is waiting"
                        : " are waiting");
        if (learner.getOldestWaitMs() != null && nowMs > learner.getOldestWaitMs()) {
            said.append(learner.getWaiting() == 1
                    ? ", for "
                    : ", the oldest for ").append(PlainWords.duration(nowMs - learner.getOldestWaitMs()));
        }
        said.append('.');
        if (learner.isRaised()) {
            said.append(" Some have waited longer than its Learning Flow allows.");
        }
        return said.toString();
    }

    // e.g. "It scores 98% lately, over 1,204 records. It scored 95% when it was put to use."
    private static String lately(final SupervisorLearner learner) {
        final StringBuilder said = new StringBuilder();
        if (learner.getRollingScore() != null) {
            said.append("It scores ").append(PlainWords.percent(learner.getRollingScore())).append(" lately, over ")
                    .append(PlainWords.count(learner.getRecords(), "record")).append('.');
        }
        if (learner.getPromotedScore() != null) {
            if (said.length() > 0) {
                said.append(' ');
            }
            said.append("It scored ").append(PlainWords.percent(learner.getPromotedScore()))
                    .append(" when it was put to use.");
        }
        return said.length() == 0
                ? "Its translation is in use."
                : said.toString();
    }

    /// @return The tone of its chip.
    public Tone getTone() {
        return tone;
    }

    /// @return Its chip's word, e.g. "Paused".
    public String getChip() {
        return chip;
    }

    /// @return The one sentence that says where it stands.
    public String getHeadline() {
        return headline;
    }

    /// @return The sentence of detail beneath it.
    public String getDetail() {
        return detail;
    }

    /// @return The main actions, as buttons, in order.
    public List<FeedAction> getPrimary() {
        return primary;
    }

    /// @return The other actions that may be done now, for a menu.
    public List<FeedAction> getMore() {
        return more;
    }

    /// @return The evidence of why its run stopped, a line each; empty where there is no report.
    public List<String> getDetails() {
        return details;
    }

    /// @return What a person can do about its run stopping; empty where there is no report.
    public List<Suggestion> getSuggestions() {
        return suggestions;
    }

    /// @param suggestion One of [#getSuggestions()].
    /// @return Whether the tab can do it, as a link: it has an action, the action may be taken now, and it
    ///         isn't one of the buttons already shown.
    public boolean canDo(final Suggestion suggestion) {
        return suggestion.getAction() != null && doable.contains(suggestion.getAction());
    }


    // --------------------------------------------------------------------------------


    /// How a chip is coloured: each is a status colour of the theme, never the accent.
    public enum Tone {
        GOOD,
        WARNING,
        DANGER,
        INFO,
        IDLE
    }
}
