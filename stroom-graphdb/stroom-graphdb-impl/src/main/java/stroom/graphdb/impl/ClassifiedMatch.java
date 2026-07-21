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

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One classified {@code DIFF} path: its {@link ChangeKind} plus the baseline ({@code t1}) and comparison
 * ({@code t2}) rows that produced it. The delta-table projection reads values from these - {@code before(x)} from
 * {@link #baselineRow}, {@code after(x)} from {@link #comparisonRow}, and a bare reference from whichever side the
 * path is present in (see {@code docs/temporal-cypher-diff-operator.md} &sect;4.3).
 *
 * @param changeKind    never null.
 * @param identity      never null; the shared classification identity (see {@link DiffMatch#identity()}).
 * @param baselineRow   the {@code t1} bound-value row, or {@code null} for {@link ChangeKind#ADDED} (absent at
 *                      {@code t1}).
 * @param comparisonRow the {@code t2} bound-value row, or {@code null} for {@link ChangeKind#REMOVED} (absent at
 *                      {@code t2}).
 */
public record ClassifiedMatch(
        ChangeKind changeKind,
        List<ElementId> identity,
        @Nullable Map<String, Val> baselineRow,
        @Nullable Map<String, Val> comparisonRow) {

    public ClassifiedMatch {
        Objects.requireNonNull(changeKind, "changeKind");
        Objects.requireNonNull(identity, "identity");
    }

    /**
     * The row a <b>bare</b> property reference resolves against: the comparison ({@code t2}) side when the path is
     * present there ({@code ADDED}/{@code MODIFIED}/{@code UNCHANGED}), otherwise the baseline ({@code t1}) side
     * ({@code REMOVED}) - the "value in whichever snapshot the element is present in" rule (&sect;4.3). Never null.
     */
    public Map<String, Val> presentRow() {
        return comparisonRow != null ? comparisonRow : Objects.requireNonNull(baselineRow, "baselineRow");
    }
}
