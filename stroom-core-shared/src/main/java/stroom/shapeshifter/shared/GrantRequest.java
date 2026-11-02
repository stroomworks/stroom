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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/// What a person grants a parked session beyond the document's budgets (A77): tokens, milliseconds, or
/// both, recorded against the attempt alone. Tokens add to what the session may spend over its life;
/// milliseconds to how long each run of it may take (design 06 A145).
@JsonInclude(Include.NON_NULL)
public class GrantRequest {

    @JsonProperty
    private final long tokens;
    @JsonProperty
    private final long ms;

    @JsonCreator
    public GrantRequest(@JsonProperty("tokens") final long tokens, @JsonProperty("ms") final long ms) {
        this.tokens = tokens;
        this.ms = ms;
    }

    public long getTokens() {
        return tokens;
    }

    public long getMs() {
        return ms;
    }

    /// A grant as the dialog asks for it, in minutes.
    ///
    /// @param tokens  More tokens over the session's life.
    /// @param minutes More minutes for each run.
    /// @return The grant.
    public static GrantRequest ofMinutes(final long tokens, final long minutes) {
        return new GrantRequest(tokens, minutes * 60_000L);
    }

    /// @return Why this grant would be refused, or null where it is one: a grant adds something and takes
    /// nothing away, as the stage holds it to.
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
