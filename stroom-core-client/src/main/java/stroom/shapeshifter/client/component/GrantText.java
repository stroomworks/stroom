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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorAttempt;

/// What a grant to a parked session (A65, A77) is said as: what the grant dialog ([GrantEditorController])
/// tells a person before they grant, and what the sessions' list ([SessionList]) says has been granted
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
    public static String soFar(final SupervisorAttempt attempt, final ShapeshifterAiDoc doc) {
        final StringBuilder text = new StringBuilder("It has spent ").append(attempt.getTokensSpent())
                .append(" tokens");
        if (doc != null) {
            if (doc.getTokenBudget() == null || doc.getTokenBudget() == 0L) {
                text.append(", and its document sets no token budget");
            } else {
                text.append(" of its document's ").append(doc.getTokenBudget());
            }
        }
        if (attempt.getGrantedTokens() > 0L) {
            text.append(", with ").append(attempt.getGrantedTokens()).append(" more granted");
        }
        text.append(". Each run of it may take ").append(doc == null
                ? "its document's time budget"
                : duration(doc.getAttemptBudgetMs()) + " by its document");
        if (attempt.getGrantedMs() > 0L) {
            text.append(" and ").append(duration(attempt.getGrantedMs())).append(" more granted");
        }
        return text.append(". Tokens are counted over the session's life; time over each run, so a run "
                           + "carried on has the whole of it again.").toString();
    }

    /// What has been granted to a session, said as the grant dialog asks for it (design 06 A145): tokens
    /// over its life, and time for each run.
    ///
    /// @param attempt The session.
    /// @return E.g. `5000 tokens, 10 min each run`, or empty where nothing has been granted.
    public static String granted(final SupervisorAttempt attempt) {
        final String tokens = attempt.getGrantedTokens() > 0L
                ? attempt.getGrantedTokens() + " tokens"
                : "";
        final String time = attempt.getGrantedMs() > 0L
                ? duration(attempt.getGrantedMs()) + " each run"
                : "";
        return tokens.isEmpty() || time.isEmpty()
                ? tokens + time
                : tokens + ", " + time;
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
