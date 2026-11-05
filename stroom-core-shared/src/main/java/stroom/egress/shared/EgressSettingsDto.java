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

package stroom.egress.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/// The mode and kill switch of each kind of destination.
@JsonPropertyOrder({
        "modes",
        "killSwitches"
})
@JsonInclude(Include.NON_NULL)
public class EgressSettingsDto {

    @JsonProperty
    private final Map<EgressKind, EgressMode> modes;

    @JsonProperty
    private final Map<EgressKind, Boolean> killSwitches;

    @JsonCreator
    public EgressSettingsDto(
            @JsonProperty("modes") final Map<EgressKind, EgressMode> modes,
            @JsonProperty("killSwitches") final Map<EgressKind, Boolean> killSwitches) {
        this.modes = modes;
        this.killSwitches = killSwitches;
    }

    /// @return The mode of each kind.
    public Map<EgressKind, EgressMode> getModes() {
        return modes;
    }

    /// @return Whether the kill switch is on for each kind.
    public Map<EgressKind, Boolean> getKillSwitches() {
        return killSwitches;
    }

}
