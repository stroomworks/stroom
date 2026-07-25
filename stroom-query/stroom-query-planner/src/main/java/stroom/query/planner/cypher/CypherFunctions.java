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

/**
 * The two function vocabularies a Cypher {@code RETURN} may use (Phase 13): <b>bare</b> names are Cypher-standard
 * functions (portable, Cypher semantics); the <b>{@code stroom.}</b> namespace exposes Stroom-native extensions.
 * The two can never clash, and any {@code stroom.} call is a visible non-portable dependency (the Cypher/Neo4j
 * {@code apoc.*} convention).
 *
 * <p>Neither reimplements anything: a call lowers to Stroom's existing expression engine
 * ({@code stroom.query.language.functions}) - the namespace is purely the front-end vocabulary and validation, and
 * the rendered {@code ${…}}/{@code name(…)} text always uses Stroom's raw engine function names (see
 * {@code CypherToLogicalPlan.renderFunctionCall}). Bare Cypher functions whose <em>signature</em> differs from
 * Stroom's ({@code substring}/{@code left}/{@code right}/{@code size}/{@code coalesce}/{@code id}/{@code type}) are
 * adapted in {@code CypherToLogicalPlan.renderCypherAdaptedFunction}, not mapped here.</p>
 */
public final class CypherFunctions {

    // Bare Cypher-standard function name -> Stroom render name, for the ones that are a plain 1:1 map (no argument
    // adaptation). The signature-differing Cypher functions are handled by renderCypherAdaptedFunction instead.
    private static final Map<String, String> CYPHER_STANDARD = Map.ofEntries(
            Map.entry("toUpper", "upperCase"),
            Map.entry("toLower", "lowerCase"),
            Map.entry("ceil", "ceiling"),
            Map.entry("toString", "toString"),
            Map.entry("toInteger", "toInteger"),
            Map.entry("toFloat", "toFloat"),
            Map.entry("toBoolean", "toBoolean"),
            Map.entry("round", "round"),
            Map.entry("floor", "floor"));

    // Pure, deterministic Stroom-native functions callable as `stroom.<name>` (render name == the name). Includes
    // `substring` (Stroom's (string, start, endIndex) semantics - the bare `substring` is Cypher's instead). The
    // library's dashboard/annotation/link/current-user/state/I-O functions are deliberately withheld. Stroom's bare
    // year/month/day/hour/... are current-time truncation (not component extractors), so those are not exposed; the
    // arg-taking date functions (format/parse, the floor/round/ceiling time-bucketing families, isWeekend, now) are.
    private static final Set<String> STROOM = Set.of(
            // strings
            "upperCase", "lowerCase", "substring", "substringBefore", "substringAfter", "replace", "stringLength",
            "concat", "indexOf", "lastIndexOf", "contains", "toString", "decode", "encodeUrl", "decodeUrl", "hash",
            // maths
            "add", "round", "floor", "ceiling", "negate",
            // type coercion / tests
            "toBoolean", "toDouble", "toFloat", "toInteger", "toLong", "typeOf",
            "isNull", "isValue", "isNumber", "isString", "isBoolean",
            // conditional
            "if", "case", "match",
            // date/time
            "formatDate", "parseDate", "formatDuration", "parseDuration", "now", "isWeekend",
            "floorSecond", "floorMinute", "floorHour", "floorDay", "floorWeek", "floorMonth", "floorYear",
            "roundSecond", "roundMinute", "roundHour", "roundDay", "roundWeek", "roundMonth", "roundYear",
            "ceilingSecond", "ceilingMinute", "ceilingHour", "ceilingDay", "ceilingWeek", "ceilingMonth",
            "ceilingYear");

    /** The recognised function namespace for Stroom-native extensions. */
    public static final String STROOM_NAMESPACE = "stroom";

    private CypherFunctions() {
    }

    /**
     * The Stroom render name for a bare, 1:1-mapped Cypher-standard function, or {@code null} if {@code name} is
     * not such a function (it may still be a signature-adapted Cypher function - handled elsewhere - or a Stroom
     * extension, or unknown).
     */
    public static @Nullable String cypherRenderName(final String name) {
        return CYPHER_STANDARD.get(name);
    }

    /** Whether {@code name} is a Stroom-native function callable as {@code stroom.<name>}. */
    public static boolean isStroomFunction(final String name) {
        return STROOM.contains(name);
    }

    /** A sorted, comma-separated list of the Stroom-namespace functions, for error messages. */
    public static String stroomNames() {
        return STROOM.stream().sorted().collect(Collectors.joining(", "));
    }
}
