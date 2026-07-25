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

package stroom.query.planner.cypher;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The curated allowlist of scalar functions a Cypher {@code RETURN} may apply to matched row values, and the
 * mapping from a Cypher function name to its Stroom expression-engine name.
 *
 * <p>Rather than reimplement Cypher's standard library, a Cypher {@code RETURN} function is lowered to Stroom's
 * existing 232-function expression engine ({@code stroom.query.language.functions}) - see
 * {@code CypherToLogicalPlan.renderFunctionCall} and {@code GraphTraversalEngine}'s projection. Only pure,
 * deterministic functions are exposed; the library's dashboard/annotation/link/current-user/state/I-O functions
 * are deliberately withheld. Functions are exposed under their <b>Stroom</b> names (so their semantics are exactly
 * Stroom's, with no surprise), plus a small set of Cypher-name aliases that are semantically identical to their
 * target. A fuller Cypher-name-and-semantics compatibility layer (e.g. Cypher's {@code substring(s, start,
 * length)} vs Stroom's {@code substring(s, startIndex, endIndex)}, or a {@code coalesce} built on {@code if}/
 * {@code isNull}) is a deliberate follow-on.</p>
 */
public final class CypherFunctions {

    // Pure, deterministic Stroom functions exposed in a Cypher RETURN, by their Stroom name. (`substring` is NOT
    // here: the bare name is adapted to Cypher's (string, start, length) semantics - see
    // CypherToLogicalPlan.renderCypherAdaptedFunction; Stroom's own (string, start, endIndex) substring stays
    // reachable as `stroom_substring`, an alias below.)
    private static final Set<String> ALLOWED = Set.of(
            // strings
            "upperCase", "lowerCase", "substringBefore", "substringAfter", "replace", "stringLength",
            "concat", "indexOf", "lastIndexOf", "contains", "toString", "decode", "encodeUrl", "decodeUrl", "hash",
            // maths
            "add", "round", "floor", "ceiling", "negate",
            // type coercion / tests
            "toBoolean", "toDouble", "toInteger", "toLong", "typeOf",
            "isNull", "isValue", "isNumber", "isString", "isBoolean",
            // conditional
            "if", "case", "match");

    // Cypher-name aliases that are semantically identical to their Stroom target (a plain name swap, no argument
    // adaptation). Cypher functions whose signature differs from Stroom's (substring/left/right/coalesce/size) are
    // adapted in CypherToLogicalPlan.renderCypherAdaptedFunction instead, not aliased here.
    private static final Map<String, String> ALIASES = Map.of(
            "toUpper", "upperCase",
            "toLower", "lowerCase",
            "ceil", "ceiling",
            // Escape hatch: Stroom's own substring (string, start, endIndex), since the bare `substring` is now
            // Cypher's (string, start, length).
            "stroom_substring", "substring");

    private CypherFunctions() {
    }

    /**
     * The Stroom expression-engine name for a Cypher function name, or {@code null} if the function is not exposed
     * in a Cypher {@code RETURN}.
     *
     * <p><b>Null status:</b> {@code cypherName} non-null; returns null when unsupported.</p>
     */
    public static @Nullable String toStroomName(final String cypherName) {
        final String aliased = ALIASES.get(cypherName);
        if (aliased != null) {
            return aliased;
        }
        return ALLOWED.contains(cypherName) ? cypherName : null;
    }

    /** A sorted, comma-separated list of the supported function names, for error messages. */
    public static String supportedNames() {
        return Stream.concat(ALIASES.keySet().stream(), ALLOWED.stream())
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
