/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.learningflow.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/// Extra budget a person gives a parked session beyond the document's budgets: tokens, milliseconds, or both.
/// The grant applies to that session alone, not to the document. Tokens add to what the session may spend over
/// its whole life; milliseconds add to how long each run of it may take.
@JsonInclude(Include.NON_NULL)
public class GrantRequest {

    @JsonProperty
    private final long tokens;
    @JsonProperty
    private final long ms;

    @JsonCreator
    public GrantRequest(@JsonProperty("tokens") final Long tokens, @JsonProperty("ms") final Long ms) {
        this.tokens = Objects.requireNonNullElse(tokens, 0L);
        this.ms = Objects.requireNonNullElse(ms, 0L);
    }

    public long getTokens() {
        return tokens;
    }

    public long getMs() {
        return ms;
    }

    /// Creates a grant from the values the grant dialog asks for, which gives time in minutes.
    ///
    /// @param tokens  More tokens over the session's life.
    /// @param minutes More minutes for each run.
    /// @return The grant.
    public static GrantRequest ofMinutes(final long tokens, final long minutes) {
        return new GrantRequest(tokens, minutes * 60_000L);
    }

    /// @return Why this grant would be refused, or null where it is valid. A valid grant adds something and
    /// takes nothing away; the server enforces the same rule.
    public String problem() {
        if (tokens < 0 || ms < 0) {
            return "A grant adds to what the session may spend; it cannot take anything away";
        }
        if (tokens == 0 && ms == 0) {
            return "A grant is more tokens, more minutes for each run, or both";
        }
        return null;
    }
}
