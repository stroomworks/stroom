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

package stroom.egress.client.component;

import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMode;

/// The mode and kill switch of one kind of destination, a row of the settings list.
public class EgressKindSetting {

    private final EgressKind kind;
    private final EgressMode mode;
    private final boolean killed;

    /// Makes a setting.
    ///
    /// @param kind   The kind of destination.
    /// @param mode   What happens to a request that no rule permits.
    /// @param killed Whether everything of this kind is denied.
    public EgressKindSetting(final EgressKind kind, final EgressMode mode, final boolean killed) {
        this.kind = kind;
        this.mode = mode;
        this.killed = killed;
    }

    /// @return The kind of destination.
    public EgressKind getKind() {
        return kind;
    }

    /// @return What happens to a request that no rule permits.
    public EgressMode getMode() {
        return mode;
    }

    /// @return Whether everything of this kind is denied.
    public boolean isKilled() {
        return killed;
    }

    /// @return Whether requests that no rule permits are denied.
    public boolean isEnforced() {
        return mode == EgressMode.ENFORCE;
    }
}
