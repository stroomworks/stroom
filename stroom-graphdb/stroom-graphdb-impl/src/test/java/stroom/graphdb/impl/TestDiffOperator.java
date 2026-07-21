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

import stroom.graphdb.impl.ElementId.Edge;
import stroom.graphdb.impl.ElementId.Node;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiffOperator}, the pure classification core of the {@code DIFF} operator. Exercises the
 * full 2&times;2 (ADDED / REMOVED / MODIFIED / UNCHANGED) plus the topology-move case, without any engine.
 */
class TestDiffOperator {

    private static DiffMatch match(final List<ElementId> identity, final Map<String, Val> flatRow) {
        return new DiffMatch(identity, flatRow);
    }

    private static DiffMatch node(final long uid, final Map<String, Val> flatRow) {
        return match(List.of(new Node(uid)), flatRow);
    }

    @Test
    void identityOnlyInComparison_isAdded() {
        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(),
                List.of(node(1, Map.of("a.id", ValString.create("x")))));

        assertThat(result).singleElement().satisfies(m -> {
            assertThat(m.changeKind()).isEqualTo(ChangeKind.ADDED);
            assertThat(m.baselineRow()).isNull();
            assertThat(m.comparisonRow()).isEqualTo(Map.of("a.id", ValString.create("x")));
        });
    }

    @Test
    void identityOnlyInBaseline_isRemoved() {
        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(node(1, Map.of("a.id", ValString.create("x")))),
                List.of());

        assertThat(result).singleElement().satisfies(m -> {
            assertThat(m.changeKind()).isEqualTo(ChangeKind.REMOVED);
            assertThat(m.baselineRow()).isEqualTo(Map.of("a.id", ValString.create("x")));
            assertThat(m.comparisonRow()).isNull();
        });
    }

    @Test
    void sameIdentity_differentProperties_isModified() {
        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(node(1, Map.of("a.status", ValString.create("active")))),
                List.of(node(1, Map.of("a.status", ValString.create("closed")))));

        assertThat(result).singleElement().satisfies(m -> {
            assertThat(m.changeKind()).isEqualTo(ChangeKind.MODIFIED);
            assertThat(m.baselineRow()).isEqualTo(Map.of("a.status", ValString.create("active")));
            assertThat(m.comparisonRow()).isEqualTo(Map.of("a.status", ValString.create("closed")));
        });
    }

    @Test
    void sameIdentity_identicalProperties_isUnchanged() {
        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(node(1, Map.of("a.status", ValString.create("active")))),
                List.of(node(1, Map.of("a.status", ValString.create("active")))));

        assertThat(result).singleElement().satisfies(m ->
                assertThat(m.changeKind()).isEqualTo(ChangeKind.UNCHANGED));
    }

    @Test
    void distinctValTypes_withEqualText_areNotEqual_soModified() {
        // "12" (string) vs 12 (long): Val equality is type-and-value, so this is a real change.
        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(node(1, Map.of("a.v", ValString.create("12")))),
                List.of(node(1, Map.of("a.v", ValLong.create(12L)))));

        assertThat(result).singleElement().satisfies(m ->
                assertThat(m.changeKind()).isEqualTo(ChangeKind.MODIFIED));
    }

    @Test
    void edgeRebound_toDifferentNeighbour_surfacesAsRemovedPlusAdded() {
        // Path a-[e]->b at t1; at t2 the same edge type now points a-[e]->c. The identity tuple differs
        // (dst uid 2 vs 3), so it is a REMOVED of the old path and an ADDED of the new one - not a MODIFIED.
        final List<ElementId> oldPath = List.of(new Node(1), new Edge(1, 100, 2), new Node(2));
        final List<ElementId> newPath = List.of(new Node(1), new Edge(1, 100, 3), new Node(3));

        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(match(oldPath, Map.of("a.id", ValString.create("a")))),
                List.of(match(newPath, Map.of("a.id", ValString.create("a")))));

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(m -> {
            assertThat(m.changeKind()).isEqualTo(ChangeKind.ADDED);
            assertThat(m.identity()).isEqualTo(newPath);
        });
        assertThat(result).anySatisfy(m -> {
            assertThat(m.changeKind()).isEqualTo(ChangeKind.REMOVED);
            assertThat(m.identity()).isEqualTo(oldPath);
        });
    }

    @Test
    void edgePropertyChange_sameTopology_isModified() {
        final List<ElementId> path = List.of(new Node(1), new Edge(1, 100, 2), new Node(2));
        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(match(path, Map.of("c.startTime", ValString.create("t-early")))),
                List.of(match(path, Map.of("c.startTime", ValString.create("t-late")))));

        assertThat(result).singleElement().satisfies(m ->
                assertThat(m.changeKind()).isEqualTo(ChangeKind.MODIFIED));
    }

    @Test
    void mixedSet_classifiesEachPathIndependently_comparisonOrderThenRemoved() {
        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(
                        node(1, Map.of("a.id", ValString.create("unchanged"))),
                        node(2, Map.of("a.id", ValString.create("old"))),
                        node(3, Map.of("a.status", ValString.create("active")))),
                List.of(
                        node(1, Map.of("a.id", ValString.create("unchanged"))),
                        node(3, Map.of("a.status", ValString.create("closed"))),
                        node(4, Map.of("a.id", ValString.create("new")))));

        assertThat(result).extracting(ClassifiedMatch::changeKind).containsExactly(
                ChangeKind.UNCHANGED,   // node 1, comparison order
                ChangeKind.MODIFIED,    // node 3, comparison order
                ChangeKind.ADDED,       // node 4, comparison order
                ChangeKind.REMOVED);    // node 2, baseline-only, last
    }

    @Test
    void bareReference_resolvesAgainstPresentSnapshot() {
        final List<ClassifiedMatch> result = DiffOperator.classify(
                List.of(node(2, Map.of("a.id", ValString.create("gone")))),
                List.of(node(1, Map.of("a.id", ValString.create("here")))));

        final ClassifiedMatch added = result.stream()
                .filter(m -> m.changeKind() == ChangeKind.ADDED).findFirst().orElseThrow();
        final ClassifiedMatch removed = result.stream()
                .filter(m -> m.changeKind() == ChangeKind.REMOVED).findFirst().orElseThrow();

        // ADDED reads from the comparison (t2) side; REMOVED from the baseline (t1) side.
        assertThat(added.presentRow().get("a.id")).isEqualTo(ValString.create("here"));
        assertThat(removed.presentRow().get("a.id")).isEqualTo(ValString.create("gone"));
    }
}
