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
 */
record ScanTimeBounds(@Nullable Long fromTimeMs, @Nullable Long toTimeMs, List<ExpressionTerm> selectivityTerms) {
}
