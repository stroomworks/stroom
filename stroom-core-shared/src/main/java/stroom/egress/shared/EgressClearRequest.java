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

/// Asks for aggregated attempts to be removed.
@JsonPropertyOrder({
        "beforeMs"
})
@JsonInclude(Include.NON_NULL)
public class EgressClearRequest {

    @JsonProperty
    private final Long beforeMs;

    @JsonCreator
    public EgressClearRequest(
            @JsonProperty("beforeMs") final Long beforeMs) {
        this.beforeMs = beforeMs;
    }

    /// @return Remove attempts last seen before this time, or null to remove all of them.
    public Long getBeforeMs() {
        return beforeMs;
    }

}
