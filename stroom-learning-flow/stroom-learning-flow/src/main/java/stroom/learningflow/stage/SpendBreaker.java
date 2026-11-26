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

import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Spend.Spent;

import java.util.Optional;

/// Stops runaway learning from spending money without limit, by refusing to open new learning sessions once
/// a spend ceiling is reached. It reads the cluster-wide counts kept by [Spend].
///
/// A session has its own budgets (a number of tries, a time limit, a number of tokens), but they limit only
/// **one** session. They do not limit a feed that keeps opening new sessions: a learner that cannot be
/// taught, a format that changes every hour, or a model that charges and then fails. The maximum deferral
/// age does not help, because it does not stop anything being spent.
///
/// There are four ceilings, each over the same hour-long window that the counter keeps:
///
/// - the tokens and the questions one Learning Flow may spend in an hour, across every feed it serves;
/// - the tokens and the questions every Learning Flow on the cluster may spend between them.
///
/// Both are needed. A per-document ceiling cannot limit a cluster, because a hundred documents, each within
/// its own ceiling, can spend a hundred times it; and a cluster-wide ceiling alone would let one runaway
/// feed starve every other.
///
/// **What happens when it trips.** No new session opens, and the streams that would have opened one are
/// deferred, with a reason saying which ceiling was reached and that it clears when the hour is up. Nothing
/// errors, nothing is lost, and learning restarts by itself. A session already running is left to finish,
/// because it is within its own budgets and stopping it would waste what has already been paid for.
public final class SpendBreaker {

    /// The length of the window the counter keeps and the ceilings apply to: one hour.
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    private final Spend spend;
    private final long tokensPerHour;
    private final int callsPerHour;

    /// @param spend         The cluster-wide spend counter.
    /// @param tokensPerHour The cluster's token ceiling per hour; zero or less for none.
    /// @param callsPerHour  The cluster's ceiling on questions to the model per hour; zero or less for none.
    public SpendBreaker(final Spend spend, final long tokensPerHour, final int callsPerHour) {
        this.spend = spend;
        this.tokensPerHour = tokensPerHour;
        this.callsPerHour = callsPerHour;
    }

    /// Checks whether a new learning session may be opened for a document.
    ///
    /// The document's own ceilings are checked first, so that a feed that has used up its own Learning
    /// Flow's allowance is told that, rather than told that the cluster is busy.
    ///
    /// @param doc The document.
    /// @return Why not, if a ceiling has been reached; empty if a session may be opened.
    public Optional<String> tripped(final LearningFlowDoc doc) {
        final long docTokens = doc.getSpendTokensPerHour() == null
                ? 0L
                : doc.getSpendTokensPerHour();
        final int docCalls = doc.getSpendCallsPerHour() == null
                ? 0
                : doc.getSpendCallsPerHour();
        if (docTokens > 0 || docCalls > 0) {
            final Spent own = spend.spent(doc.getUuid(), WINDOW_MS);
            if (docTokens > 0 && own.tokens() >= docTokens) {
                return Optional.of(reason("This Learning Flow", own.tokens(), docTokens, "token"));
            }
            if (docCalls > 0 && own.calls() >= docCalls) {
                return Optional.of(reason("This Learning Flow", own.calls(), docCalls, "question"));
            }
        }
        if (tokensPerHour <= 0 && callsPerHour <= 0) {
            return Optional.empty();
        }
        final Spent all = spend.spentAll(WINDOW_MS);
        if (tokensPerHour > 0 && all.tokens() >= tokensPerHour) {
            return Optional.of(reason("Smart Pipelines on this cluster", all.tokens(), tokensPerHour, "token"));
        }
        if (callsPerHour > 0 && all.calls() >= callsPerHour) {
            return Optional.of(reason("Smart Pipelines on this cluster", all.calls(), callsPerHour, "question"));
        }
        return Optional.empty();
    }

    /// Words the reason nothing is being learned, so that a person reading a waiting stream knows whose
    /// ceiling was reached, what it is, and that it clears by itself.
    ///
    /// @param whose   What the ceiling belongs to.
    /// @param spent   How much has been spent against it.
    /// @param ceiling The ceiling.
    /// @param unit    What is counted, in the singular.
    /// @return The reason.
    private static String reason(final String whose, final long spent, final long ceiling, final String unit) {
        return whose + " spent " + spent + " " + unit + "(s) in the last hour, which is its ceiling of "
               + ceiling + ". No new learning starts until the hour rolls; what is already learning "
               + "finishes.";
    }
}
