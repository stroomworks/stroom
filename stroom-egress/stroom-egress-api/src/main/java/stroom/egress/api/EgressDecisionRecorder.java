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

/// Receives every decision that is not a plain allow, so that blocked and would-be-blocked attempts can be shown.
public interface EgressDecisionRecorder {

    /// Records a decision.
    ///
    /// @param request  The request.
    /// @param decision The decision.
    void record(EgressRequest request, EgressDecision decision);

    /// Records that a permitted request used a rule, for the "last used" column.
    ///
    /// @param request  The request.
    /// @param decision The decision, which carries the rule id.
    default void recordUse(final EgressRequest request, final EgressDecision decision) {
    }
}
