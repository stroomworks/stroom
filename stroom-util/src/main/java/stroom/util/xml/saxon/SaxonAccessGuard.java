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

package stroom.util.xml.saxon;

/// Decides whether a stylesheet may use a route to reach a resource. This is the seam between the Saxon hooks, which
/// live in `stroom-util`, and the egress policy, which does not, so low-level modules need not depend on it.
@FunctionalInterface
public interface SaxonAccessGuard {

    /// Decides one attempt. Implementations record denied attempts.
    ///
    /// @param route The route used.
    /// @param uri   The resource, as an absolute URI where one could be formed.
    /// @return True if the stylesheet may go ahead.
    boolean permit(SaxonRoute route, String uri);
}
