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

/// A permitted destination, as shown and edited on the Egress Policy screen.
@JsonPropertyOrder({
        "id",
        "kind",
        "destination",
        "match",
        "comment",
        "enabled"
})
@JsonInclude(Include.NON_NULL)
public class EgressRuleDto {

    @JsonProperty
    private final Long id;

    @JsonProperty
    private final EgressKind kind;

    @JsonProperty
    private final String destination;

    @JsonProperty
    private final EgressMatch match;

    @JsonProperty
    private final String comment;

    @JsonProperty
    private final boolean enabled;

    @JsonCreator
    public EgressRuleDto(
            @JsonProperty("id") final Long id,
            @JsonProperty("kind") final EgressKind kind,
            @JsonProperty("destination") final String destination,
            @JsonProperty("match") final EgressMatch match,
            @JsonProperty("comment") final String comment,
            @JsonProperty("enabled") final boolean enabled) {
        this.id = id;
        this.kind = kind;
        this.destination = destination;
        this.match = match;
        this.comment = comment;
        this.enabled = enabled;
    }

    /// @return The id, or null for a rule that has not been saved.
    public Long getId() {
        return id;
    }

    /// @return The kind of destination.
    public EgressKind getKind() {
        return kind;
    }

    /// @return The destination. It is stored in its normalised form.
    public String getDestination() {
        return destination;
    }

    /// @return Whether the rule matches exactly or by whole-component prefix.
    public EgressMatch getMatch() {
        return match;
    }

    /// @return Why the rule exists. Required.
    public String getComment() {
        return comment;
    }

    /// @return Whether the rule is in force.
    public boolean isEnabled() {
        return enabled;
    }

}
