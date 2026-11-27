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

package stroom.learningflow.learning;

import stroom.learningflow.shared.FlowExit;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.StopReport;
import stroom.util.shared.StoredError;

import java.util.List;

/// How a learning attempt ended, as returned by [Conversation]: either with a chain whose every step
/// passed, or abandoned with the reason.
///
/// Either way the transcript is kept, because it is what the audit log records and what the next attempt
/// is told.
public sealed interface Outcome {

    /// @return Every turn of the attempt, oldest first.
    List<Exchange> transcript();

    /// The attempt learned a chain in which every step passed.
    ///
    /// @param chain      The learned steps in chain order.
    /// @param output     What the last step produced from the sample, which for a transform is the
    ///                   translation.
    /// @param targets    The validated target event for each kind of record. Empty for a flow with no
    ///                   target step. At promotion they become the expected outputs in the regression set.
    /// @param transcript Every turn of the attempt, oldest first.
    /// @param boundary   What one record is, as the split step settled it, for the binding to store and the
    ///                   stage to count records by. Null where the input is raw text or no split was asked.
    record Learned(List<LearnedStep> chain,
                   String output,
                   List<Target> targets,
                   List<Exchange> transcript,
                   RecordBoundary boundary) implements Outcome {

    }

    /// The attempt could not be completed. It records the reason, the diagnostics, the turns so far, and
    /// where the session exits the flow: parked for a person, or the learner given up and its streams
    /// errored. Giving up is the default for a parser step whose candidates are spent, and for any step
    /// whose flow transition says `goto give-up`.
    ///
    /// @param reason      Why the attempt stopped, in one line.
    /// @param diagnostics What the last failing step reported, if a step failed. This feedback was never
    ///                    sent to the model in a further question, so it is kept here.
    /// @param transcript  Every turn of the attempt, oldest first.
    /// @param exit        Where the session leaves the flow.
    /// @param report      Why it stopped, in parts ([StopReports]), or null where no report was written.
    record Abandoned(String reason,
                     List<StoredError> diagnostics,
                     List<Exchange> transcript,
                     FlowExit exit,
                     StopReport report) implements Outcome {

        public Abandoned(final String reason, final List<StoredError> diagnostics,
                         final List<Exchange> transcript) {
            this(reason, diagnostics, transcript, FlowExit.PARK);
        }

        public Abandoned(final String reason, final List<StoredError> diagnostics,
                         final List<Exchange> transcript, final FlowExit exit) {
            this(reason, diagnostics, transcript, exit, null);
        }

        /// An attempt stopped for the reason a report gives; its reason is the report's error line.
        public Abandoned(final StopReport report, final List<StoredError> diagnostics,
                         final List<Exchange> transcript, final FlowExit exit) {
            this(report.getError(), diagnostics, transcript, exit, report);
        }

        /// Whether the learner is to be given up and its streams errored.
        public boolean givesUp() {
            return exit == FlowExit.GIVE_UP;
        }
    }
}
