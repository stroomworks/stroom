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

package stroom.query.planner.cost;

import stroom.query.api.ExpressionTerm.Condition;

/**
 * The design doc's selectivity heuristic - <b>equality &lt; range &lt; unindexed-scan</b> (see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 3.2): a multiplier applied to a base row count,
 * lower meaning "narrows the result down more". These are calibration constants with no live data behind them
 * yet (nothing executes this cost model against real query outcomes to tune them) - reasonable, documented
 * defaults, not measured values.
 */
final class Selectivity {

    /** A single-value match - the most selective category (e.g. {@code field = 'x'}). */
    static final double EQUALITY = 0.01;

    /** A bounded-range or small-set match - less selective than equality but still narrows results
     *  (e.g. {@code field between 1 and 100}, {@code field in (a, b, c)}). */
    static final double RANGE = 0.1;

    /** No usefully-narrowing condition - a full unindexed scan of whatever the base row count already is
     *  (e.g. {@code field != 'x'}, which excludes only one value out of a potentially huge domain). */
    static final double UNINDEXED = 1.0;

    private Selectivity() {
        // Static utility - not instantiable.
    }

    static double forCondition(final Condition condition) {
        return switch (condition) {
            case EQUALS, EQUALS_CASE_SENSITIVE, IS_NULL, IS_NOT_NULL, IS_DOC_REF, IS_USER_REF ->
                    EQUALITY;
            case BETWEEN, GREATER_THAN, GREATER_THAN_OR_EQUAL_TO, LESS_THAN, LESS_THAN_OR_EQUAL_TO,
                    IN, IN_DICTIONARY, IN_FOLDER, STARTS_WITH, STARTS_WITH_CASE_SENSITIVE,
                    ENDS_WITH, ENDS_WITH_CASE_SENSITIVE ->
                    RANGE;
            default -> UNINDEXED;
        };
    }
}
