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

package stroom.graphdb.impl;

import stroom.query.language.functions.Val;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One matched path of a {@code DIFF} pattern at a single instant, as produced by
 * {@code GraphTraversalEngine.executeDiffBindings}: the path's classification identity plus its full bound-value
 * row. Two of these (one per instant) are compared by {@link DiffOperator} to classify the path.
 *
 * @param identity never null, never empty; the ordered tuple of bound element identities (anchor node, then each
 *  hop's edge + target node &sect;5.2). This is
 *                 the classification key: two paths at different instants are "the same path" iff their identities
 *                 are equal.
 * @param flatRow  never null; every bound variable's every property, keyed {@code "variable.property"} (exactly
 *                 the engine's internal row shape). Carries the full property set of every bound element, so a
 *                 by-value comparison of two instants' rows for the same identity is precisely the
 *                 {@code MODIFIED}-vs-{@code UNCHANGED} test, and any {@code variable.property} value is
 *                 available for {@code before(...)}/{@code after(...)}/bare-reference projection.
 */
public record DiffMatch(List<ElementId> identity, Map<String, Val> flatRow) {

    public DiffMatch {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(flatRow, "flatRow");
        if (identity.isEmpty()) {
            throw new IllegalArgumentException("identity must not be empty");
        }
        identity = List.copyOf(identity);
    }
}
