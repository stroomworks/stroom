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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The pure classification core of the {@code DIFF} operator: a full-outer merge of the baseline ({@code t1}) and
 * comparison ({@code t2}) match sets, keyed on path identity, producing one {@link ClassifiedMatch} per distinct
 * path (see {@code docs/temporal-cypher-diff-operator.md} &sect;5). This class has no engine, storage, or query
 * dependency - it is exercised directly by unit tests.
 *
 * <p>Classification (&sect;3, &sect;5.3):</p>
 * <ul>
 *   <li>identity in {@code t2} only &rarr; {@link ChangeKind#ADDED}</li>
 *   <li>identity in {@code t1} only &rarr; {@link ChangeKind#REMOVED}</li>
 *   <li>identity in both, property sets differ &rarr; {@link ChangeKind#MODIFIED}</li>
 *   <li>identity in both, property sets equal &rarr; {@link ChangeKind#UNCHANGED}</li>
 * </ul>
 *
 * <p>A topology move (an element re-bound to a different neighbour) changes the identity tuple, so it surfaces
 * naturally as a {@code REMOVED} of the old path plus an {@code ADDED} of the new one (&sect;5.2) - not as a
 * single {@code MODIFIED}. Property-set equality is {@link Map#equals(Object)} over the flat rows, which - because
 * {@code Val} implementations compare by concrete type and value - is exactly the canonical equality of &sect;5.3.
 */
public final class DiffOperator {

    private DiffOperator() {
    }

    /**
     * Classify every path present at either instant.
     *
     * @param baseline   the {@code t1} matches; never null (may be empty). Identities are assumed distinct within
     *                   the list; a duplicate identity keeps the last occurrence.
     * @param comparison the {@code t2} matches; never null (may be empty), same distinctness assumption.
     * @return one {@link ClassifiedMatch} per distinct identity across both inputs, never null. {@code UNCHANGED}
     *         rows are included - suppression (if any) is the projection layer's concern, not this operator's.
     *         Ordering is deterministic: comparison-side order first (so {@code ADDED}/{@code MODIFIED}/
     *         {@code UNCHANGED} keep {@code t2} order), then baseline-only ({@code REMOVED}) paths in {@code t1}
     *         order.
     */
    public static List<ClassifiedMatch> classify(final List<DiffMatch> baseline,
                                                  final List<DiffMatch> comparison) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(comparison, "comparison");

        final Map<List<ElementId>, DiffMatch> baselineByIdentity = indexByIdentity(baseline);
        final Map<List<ElementId>, DiffMatch> comparisonByIdentity = indexByIdentity(comparison);

        final List<ClassifiedMatch> result = new ArrayList<>(
                baselineByIdentity.size() + comparisonByIdentity.size());

        // Comparison side first: ADDED / MODIFIED / UNCHANGED, preserving t2 order.
        for (final Map.Entry<List<ElementId>, DiffMatch> entry : comparisonByIdentity.entrySet()) {
            final List<ElementId> identity = entry.getKey();
            final DiffMatch comparisonMatch = entry.getValue();
            final DiffMatch baselineMatch = baselineByIdentity.get(identity);
            result.add(classifyPair(identity, baselineMatch, comparisonMatch));
        }

        // Baseline-only paths are REMOVED, preserving t1 order.
        for (final Map.Entry<List<ElementId>, DiffMatch> entry : baselineByIdentity.entrySet()) {
            if (!comparisonByIdentity.containsKey(entry.getKey())) {
                result.add(new ClassifiedMatch(
                        ChangeKind.REMOVED, entry.getKey(), entry.getValue().flatRow(), null));
            }
        }

        return result;
    }

    /**
     * Classify one identity given its (possibly missing) row on each side. Preconditions: at least one of the two
     * matches is non-null. Never returns null.
     */
    private static ClassifiedMatch classifyPair(final List<ElementId> identity,
                                                 final DiffMatch baselineMatch,
                                                 final DiffMatch comparisonMatch) {
        if (baselineMatch == null) {
            return new ClassifiedMatch(ChangeKind.ADDED, identity, null, comparisonMatch.flatRow());
        }
        final ChangeKind kind = baselineMatch.flatRow().equals(comparisonMatch.flatRow())
                ? ChangeKind.UNCHANGED
                : ChangeKind.MODIFIED;
        return new ClassifiedMatch(kind, identity, baselineMatch.flatRow(), comparisonMatch.flatRow());
    }

    /**
     * Index matches by their identity tuple, preserving encounter order (a {@link LinkedHashMap}) so the caller's
     * output ordering is deterministic. Never returns null.
     */
    private static Map<List<ElementId>, DiffMatch> indexByIdentity(final List<DiffMatch> matches) {
        final Map<List<ElementId>, DiffMatch> byIdentity = new LinkedHashMap<>(matches.size());
        for (final DiffMatch match : matches) {
            byIdentity.put(match.identity(), match);
        }
        return byIdentity;
    }
}
