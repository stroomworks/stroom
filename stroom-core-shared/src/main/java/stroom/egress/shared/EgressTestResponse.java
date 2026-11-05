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

/// The answer to a "would this be allowed?" question.
@JsonPropertyOrder({
        "normalised",
        "outcome",
        "ruleId",
        "reason"
})
@JsonInclude(Include.NON_NULL)
public class EgressTestResponse {

    @JsonProperty
    private final String normalised;

    @JsonProperty
    private final String outcome;

    @JsonProperty
    private final Long ruleId;

    @JsonProperty
    private final String reason;

    @JsonCreator
    public EgressTestResponse(
            @JsonProperty("normalised") final String normalised,
            @JsonProperty("outcome") final String outcome,
            @JsonProperty("ruleId") final Long ruleId,
            @JsonProperty("reason") final String reason) {
        this.normalised = normalised;
        this.outcome = outcome;
        this.ruleId = ruleId;
        this.reason = reason;
    }

    /// @return The normalised destination, or null if it could not be normalised. Masked.
    public String getNormalised() {
        return normalised;
    }

    /// @return ALLOWED, WOULD_DENY or DENIED.
    public String getOutcome() {
        return outcome;
    }

    /// @return The rule that permits it, or null.
    public Long getRuleId() {
        return ruleId;
    }

    /// @return Why.
    public String getReason() {
        return reason;
    }

}
