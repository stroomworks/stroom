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

/// What stops a runaway costing money without limit (ruling A24, the policy over A44's counter;
/// `simplification-options.md` §5, "a cost ceiling across sessions").
///
/// An attempt has budgets of its own — so many tries, so long, so many tokens — and they bound **one**
/// session. Nothing bounded a feed that keeps opening them: a learner that cannot be taught, a format
/// that changes every hour, a model that charges and fails. Until this, only the deferral age stood
/// between that and an unbounded bill, and the deferral age does not stop anything being spent.
///
/// Four ceilings, each over the same hour-long window the counter already keeps:
///
/// - the tokens and the questions one Learning Flow may spend in an hour, across every feed it serves;
/// - the tokens and the questions every Learning Flow on the cluster may spend between them.
///
/// Both are needed. A per-document ceiling cannot bound a cluster, because a hundred documents each
/// inside their own are a hundred times it; and a cluster-wide ceiling alone would let one runaway feed
/// starve every other.
///
/// **What it does when it trips.** No new session opens, and the streams that would have opened one wait
/// — a deferral, with the reason saying which ceiling and that it clears when the hour rolls. Nothing
/// errors, nothing is lost, and learning starts again by itself. A session already running is left to
/// finish: it is inside its own budgets, and killing it would waste what has already been paid for.
public final class SpendBreaker {

    /// The window the counter keeps, and the one the ceilings are read over (A44).
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    private final Spend spend;
    private final long tokensPerHour;
    private final int callsPerHour;

    /// @param spend         The counter.
    /// @param tokensPerHour The cluster's token ceiling; zero or less for none.
    /// @param callsPerHour  The cluster's call ceiling; zero or less for none.
    public SpendBreaker(final Spend spend, final long tokensPerHour, final int callsPerHour) {
        this.spend = spend;
        this.tokensPerHour = tokensPerHour;
        this.callsPerHour = callsPerHour;
    }

    /// Whether a new learning session may be opened for a document.
    ///
    /// The document's own ceilings are read first, because a feed that is spending its own Learning
    /// Flow's allowance should be told that rather than told the cluster is busy.
    ///
    /// @param doc The document.
    /// @return Why not, where it may not; empty where it may.
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

    /// Why nothing is being learned, said so that a person reading a waiting stream knows whose ceiling
    /// it is, what it is, and that it clears by itself.
    ///
    /// @param whose   What the ceiling belongs to.
    /// @param spent   What has been spent against it.
    /// @param ceiling The ceiling.
    /// @param unit    What is counted, singular.
    /// @return The reason.
    private static String reason(final String whose, final long spent, final long ceiling, final String unit) {
        return whose + " spent " + spent + " " + unit + "(s) in the last hour, which is its ceiling of "
               + ceiling + ". No new learning starts until the hour rolls; what is already learning "
               + "finishes.";
    }
}
