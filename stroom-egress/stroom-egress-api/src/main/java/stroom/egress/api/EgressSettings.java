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

/// The mode and kill switch of each kind, read by the guard on every request.
public interface EgressSettings {

    /// The mode for a kind.
    ///
    /// @param kind The kind.
    /// @return The mode.
    EgressMode getMode(EgressKind kind);

    /// Whether the kill switch for a kind is on. When it is, every request of that kind is denied, whatever the
    /// mode and whatever the rules say.
    ///
    /// @param kind The kind.
    /// @return True if all egress of the kind is denied.
    boolean isKilled(EgressKind kind);
}
