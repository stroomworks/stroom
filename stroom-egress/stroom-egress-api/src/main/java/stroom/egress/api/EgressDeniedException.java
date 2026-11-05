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

/// Thrown when a request is denied by the egress policy.
public class EgressDeniedException extends RuntimeException {

    private final transient EgressDecision decision;

    /// Creates the exception.
    ///
    /// @param request  The request that was denied.
    /// @param decision The decision, which says why.
    public EgressDeniedException(final EgressRequest request, final EgressDecision decision) {
        super("Egress to " + request.kind() + " destination is not permitted ("
              + decision.reason() + ") by " + request.sink());
        this.decision = decision;
    }

    /// The decision that caused the exception.
    ///
    /// @return The decision.
    public EgressDecision getDecision() {
        return decision;
    }
}
