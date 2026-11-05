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
import stroom.egress.shared.EgressMatch;

import java.util.Objects;

/// A permitted destination.
///
/// @param id          A unique identifier for the rule, or null if it has not been stored.
/// @param kind        The kind of destination.
/// @param destination The destination in its normalised form.
/// @param match       Whether the destination is compared exactly or as a whole-component prefix.
/// @param comment     Why the rule exists. Required.
/// @param enabled     Whether the rule is in force.
public record EgressRule(Long id,
                         EgressKind kind,
                         String destination,
                         EgressMatch match,
                         String comment,
                         boolean enabled) {

    public EgressRule {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(match, "match");
        if (match == EgressMatch.PREFIX && !kind.isPrefixAllowed()) {
            throw new IllegalArgumentException("A " + kind + " rule can only match exactly");
        }
    }
}
