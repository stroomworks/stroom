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
import stroom.learningflow.shared.CheckResult;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.SupervisorAttempt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/// What the Learning Status tab says about how a new translation was judged (Track 2 step 4, approval
/// evidence), for a person deciding whether to approve it: one line per check, the gates first, then the
/// advice; and who approved or rejected it, and when. A plain class, so the words are tested without a
/// browser.
public final class Evidence {

    private Evidence() {
        // Static utility
    }

    /// One line per check that judged the translation, the gates before the advice, each saying what it
    /// scored and whether that reached the check's threshold, e.g. "Compiles: 100%, a gate it passed".
    ///
    /// @param results How it did on each check.
    /// @return The lines; empty where there are no results.
    public static List<String> lines(final List<CheckResult> results) {
        final List<String> lines = new ArrayList<>();
        if (results == null) {
            return lines;
        }
        for (final CheckResult result : results) {
            if (result.getState() == CheckState.GATE) {
                lines.add(line(result));
            }
        }
        for (final CheckResult result : results) {
            if (result.getState() != CheckState.GATE) {
                lines.add(line(result));
            }
        }
        return lines;
    }

    /// Who decided about a run's translation, and when, e.g. "Approved by jo, 23 Nov 2026 10:12".
    ///
    /// @param run    The run.
    /// @param format Says when, as the user likes dates.
    /// @return The line, or null where nobody decided.
    public static String reviewed(final SupervisorAttempt run, final Function<Long, String> format) {
        if (run == null || run.getReviewedBy() == null) {
            return null;
        }
        final String decided = run.getStatus() == AttemptStatus.REJECTED
                ? "Rejected"
                : "Approved";
        return decided + " by " + run.getReviewedBy() + (run.getReviewedTimeMs() == null
                ? ""
                : ", " + format.apply(run.getReviewedTimeMs()));
    }

    private static String line(final CheckResult result) {
        final String scored = PlainWords.checkName(result.getCheck()) + ": "
                              + PlainWords.percent(result.getScore());
        if (result.getState() == CheckState.GATE) {
            return scored + (result.isMet()
                    ? ", a gate it passed"
                    : ", a gate it fell short of, needing " + PlainWords.percent(result.getThreshold()));
        }
        return scored + (result.isMet()
                ? ", advice it met"
                : ", advice it fell short of, which asks for " + PlainWords.percent(result.getThreshold()));
    }
}
