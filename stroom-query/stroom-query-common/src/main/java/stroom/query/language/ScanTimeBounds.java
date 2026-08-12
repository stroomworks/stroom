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

package stroom.query.language;

import stroom.query.api.ExpressionTerm;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The result of {@link ScanTimeRangeExtractor#extract} - any time bound recognised in a {@code Scan}'s predicate,
 * plus the remaining terms that weren't part of that time bound (selectivity-relevant terms, for a cost estimate
 * to weigh).
 *
 * <p><b>Inclusivity is fixed, not carried per-bound</b> (Task 8.3): the pair is the half-open range
 * {@code [fromTimeMs, toTimeMs)} - {@code fromTimeMs} inclusive, {@code toTimeMs} <i>exclusive</i>. That matches
 * how {@code Query.timeRange} is applied at search time ({@code >=} lower / strict {@code <} upper, per
 * {@code ResultStoreManager}), so a consumer can copy the values straight into a {@code TimeRange}. The extractor
 * converts an inclusive user upper bound ({@code <=}, {@code between}) to {@code bound + 1ms} before it lands
 * here; time values are whole milliseconds, so that conversion is exact, not an approximation. A bound of null
 * means unbounded at that end.</p>
 *
 * <p>The range is a pruning hint and must contain every row the scan's evaluated predicate can match - it may be
 * wider than the user's bounds (the retained {@code WHERE} filters the excess), never narrower (a narrower hint
 * silently drops matching rows).</p>
 *
 * @param fromTimeMs       inclusive lower bound in epoch ms, or null if unbounded below.
 * @param toTimeMs         exclusive upper bound in epoch ms, or null if unbounded above.
 * @param selectivityTerms the enabled top-level terms that did not contribute a time bound.
 */
record ScanTimeBounds(@Nullable Long fromTimeMs, @Nullable Long toTimeMs, List<ExpressionTerm> selectivityTerms) {
}
