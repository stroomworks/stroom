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

package stroom.learningflow.scoring;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ScorerParameters;
import stroom.learningflow.stage.RecordSkeleton;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Optional;

/// The completeness scorer, a gate by default. It checks that **records in = events out + declared
/// skips**.
///
/// It catches translations that silently lose records. A record the translation cannot handle produces
/// no event, so no other check judges it, and the stream would be recorded as clean, with the loss
/// visible only to someone reading the error stream. Every other check judges what came out; this is the
/// only one that asks whether anything went missing on the way.
///
/// A record that is deliberately not an event (e.g. a header line, a column banner or a heartbeat) is
/// declared with a marker ([DeclaredSkips]), which counts towards the sum without being an event. Without
/// that, the check would fail every feed that has such lines, and people would switch it off.
///
/// It applies to a step that turned records into records. A parser's records simply follow the input's
/// structure, so there is nothing to count them against; the input coverage check judges that step instead.
public final class CompletenessScorer implements Scorer {

    private static final ElementId COMPLETENESS = new ElementId("Completeness");

    @Override
    public Check type() {
        return Check.COMPLETENESS;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        if (step.parser()) {
            return Optional.empty();
        }
        final String output = step.result().output();
        if (output == null || !RecordSkeleton.isMarkup(step.input()) || !RecordSkeleton.isMarkup(output)) {
            return Optional.empty();
        }
        final int in = Records.parsed(step.input(), step.boundary());
        if (in <= 0) {
            // Nothing went in, or the records in the input cannot be counted, so there is nothing to
            // compare and the step is not failed for it.
            return Optional.empty();
        }
        final int out = Math.max(0, Records.parsed(output, step.boundary()));
        final int skipped = Records.skips(output);
        if (out + skipped >= in) {
            // More out than in is for the yield check to judge; for completeness, nothing was lost.
            return Optional.of(new Score(type(), 1.0, skipped == 0
                    ? List.of()
                    : List.of(new StoredError(Severity.INFO, null, COMPLETENESS, declared(out, skipped, in)))));
        }
        return Optional.of(new Score(type(), 0.0, List.of(
                new StoredError(Severity.ERROR, null, COMPLETENESS, shortfall(in, out, skipped)))));
    }

    /// The notice for a passing run that declared skips: how much of the stream the translation said it
    /// skipped rather than translated. There is deliberately no limit on skips, so this notice is the only
    /// thing that brings them to a person's attention.
    ///
    /// @param out     The events that came out.
    /// @param skipped The records declared skipped.
    /// @param in      The records that went in.
    /// @return The message.
    private static String declared(final int out, final int skipped, final int in) {
        return out + " event(s), " + skipped + " of " + in
               + " record(s) declared skipped by the translation.";
    }

    /// The failure message: what is missing, and what to do about it. The message names the skip marker,
    /// because the two correct responses are to translate the record or to declare why it is not an event,
    /// and a model that is only told a count would have to guess which.
    ///
    /// @param in      The records that went in.
    /// @param out     The events that came out.
    /// @param skipped The records declared skipped.
    /// @return The message.
    private static String shortfall(final int in, final int out, final int skipped) {
        final int missing = in - out - skipped;
        final StringBuilder message = new StringBuilder()
                .append(missing)
                .append(" of ")
                .append(in)
                .append(" record(s) produced neither an event nor a declared skip: ")
                .append(out)
                .append(" event(s)");
        if (skipped > 0) {
            message.append(" and ").append(skipped).append(" declared skip(s)");
        }
        message.append(". Every record must be accounted for. Translate the records that are missing, or, "
                       + "where a record is deliberately not an event — a header line, a column banner, a "
                       + "heartbeat — write ")
                .append(DeclaredSkips.MARKUP)
                .append(" in place of the event, saying why.");
        return message.toString();
    }
}
