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

package stroom.egress.impl;

import stroom.egress.api.EgressRule;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;

import java.util.List;
import java.util.Optional;

/// Finds the rule that permits a normalised destination. An exact rule needs equal text. A prefix rule needs its
/// components to be the leading components of the destination, so `/stroom/output` never permits
/// `/stroom/output-old`.
public final class EgressMatcher {

    private EgressMatcher() {
    }

    /// Finds the first enabled rule that permits the destination.
    ///
    /// @param kind        The kind of the request.
    /// @param destination The normalised destination.
    /// @param rules       The compiled rules of that kind.
    /// @return The matching rule, if any.
    public static Optional<EgressRule> match(final EgressKind kind,
                                             final NormalisedDestination destination,
                                             final List<CompiledRule> rules) {
        for (final CompiledRule compiled : rules) {
            final EgressRule rule = compiled.rule();
            if (!rule.enabled() || rule.kind() != kind) {
                continue;
            }
            if (rule.match() == EgressMatch.EXACT) {
                if (compiled.destination().text().equals(destination.text())) {
                    return Optional.of(rule);
                }
            } else if (isPrefix(compiled.destination().components(), destination.components())) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private static boolean isPrefix(final List<String> prefix, final List<String> whole) {
        return prefix.size() <= whole.size() && whole.subList(0, prefix.size()).equals(prefix);
    }

    /// A rule with its destination already normalised, so matching is a comparison and not a parse.
    ///
    /// @param rule        The rule.
    /// @param destination Its normalised destination.
    public record CompiledRule(EgressRule rule, NormalisedDestination destination) {

    }
}
