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

package stroom.egress.api;

/// The guard's answer to a request.
///
/// @param outcome    What was decided.
/// @param reason     A short explanation, suitable for a log or the screen.
/// @param normalised The normalised destination, or null if it could not be normalised.
/// @param ruleId     The rule that permitted the request, or null.
public record EgressDecision(Outcome outcome, String reason, String normalised, Long ruleId) {

    /// Whether the caller may go ahead. True for allowed and would-be-denied requests.
    ///
    /// @return True if the request may proceed.
    public boolean isAllowed() {
        return outcome != Outcome.DENIED;
    }

    /// The possible outcomes.
    public enum Outcome {
        /// An enabled rule matched.
        ALLOWED,
        /// No rule matched, the kind is in report-only mode, and the request proceeds.
        WOULD_DENY,
        /// The request must not proceed.
        DENIED
    }
}
