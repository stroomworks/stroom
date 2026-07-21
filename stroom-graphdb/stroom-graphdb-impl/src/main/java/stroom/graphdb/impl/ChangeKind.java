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

/**
 * How a {@code DIFF} path changed between the baseline ({@code t1}) and comparison ({@code t2}) instants - the
 * value of the {@code changeKind} pseudo-column (see {@code docs/temporal-cypher-diff-operator.md} &sect;3).
 */
public enum ChangeKind {
    /** Absent at {@code t1}, present at {@code t2}. */
    ADDED,
    /** Present at {@code t1}, absent at {@code t2} (a deletion, or - with a filter - no longer a match; &sect;5.4). */
    REMOVED,
    /** Present at both, but some bound element's property set differs. */
    MODIFIED,
    /** Present at both with identical property sets (suppressed by default in the delta table). */
    UNCHANGED
}
