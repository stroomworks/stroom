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

import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMode;

/// The one place that decides whether data may leave Stroom. Every sink calls it before sending anything.
public interface EgressGuard {

    /// Decides a request. The guard fails closed in enforce mode: an exception, an unknown kind or an unavailable
    /// registry denies. In report-only mode the same conditions let the request proceed and are recorded.
    ///
    /// @param request The request.
    /// @return The decision. Never null and never thrown.
    EgressDecision check(EgressRequest request);

    /// Denies a request that no rule may ever permit, in every mode, and records the attempt. Used for the
    /// standard XSLT routes `unparsed-text`, `collection` and `xsl:result-document`.
    ///
    /// @param request The request.
    /// @return A denied decision.
    EgressDecision denyOutright(EgressRequest request);

    /// The mode of a kind, so a caller can behave differently in enforce mode, for example by stripping credentials
    /// an XSLT supplied.
    ///
    /// @param kind The kind.
    /// @return The mode.
    EgressMode getMode(EgressKind kind);

    /// Checks a request and throws if it is denied.
    ///
    /// @param request The request.
    /// @throws EgressDeniedException If the request must not proceed.
    default void require(final EgressRequest request) {
        final EgressDecision decision = check(request);
        if (!decision.isAllowed()) {
            throw new EgressDeniedException(request, decision);
        }
    }
}
