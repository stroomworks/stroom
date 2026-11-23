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

import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.SupervisorAttempt;

/// What a grant to a parked session (A65, A77) is said as: what the grant dialog ([GrantEditorController])
/// tells a person before they grant, and what Learning Status says a paused run has been given
/// (design 06 A145). Plain logic, so that it is tested without a browser.
public final class GrantText {

    private GrantText() {
        // Static utility
    }

    /// What the session has spent and been granted, against what its document allows.
    ///
    /// @param attempt The parked session.
    /// @param doc     Its document, for its budgets, or null where it has not been read.
    /// @return The sentence the grant dialog shows above its fields.
    public static String soFar(final SupervisorAttempt attempt, final LearningFlowDoc doc) {
        final StringBuilder text = new StringBuilder("It has spent ").append(attempt.getTokensSpent())
                .append(" tokens");
        if (doc != null) {
            if (doc.getTokenBudget() == null || doc.getTokenBudget() == 0L) {
                text.append(", and its Learning Flow sets no token budget");
            } else {
                text.append(" of its Learning Flow's ").append(doc.getTokenBudget());
            }
        }
        if (attempt.getGrantedTokens() > 0L) {
            text.append(", with ").append(attempt.getGrantedTokens()).append(" more granted");
        }
        text.append(". Each run of it may take ").append(doc == null
                ? "its Learning Flow's time budget"
                : duration(doc.getAttemptBudgetMs()) + " by its Learning Flow");
        if (attempt.getGrantedMs() > 0L) {
            text.append(" and ").append(duration(attempt.getGrantedMs())).append(" more granted");
        }
        return text.append(". Tokens are counted over the session's life; time over each run, so a run "
                           + "carried on has the whole of it again.").toString();
    }

    /// Minutes where the time is whole minutes, seconds otherwise.
    ///
    /// @param ms The time in milliseconds.
    /// @return E.g. `5 min` or `90 s`.
    public static String duration(final long ms) {
        return ms % 60_000L == 0L
                ? (ms / 60_000L) + " min"
                : (ms / 1_000L) + " s";
    }
}
