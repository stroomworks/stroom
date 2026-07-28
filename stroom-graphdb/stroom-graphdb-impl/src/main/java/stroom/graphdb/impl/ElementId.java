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
 * The stable identity of one graph element bound by a pattern variable, used to classify a {@code DIFF}'s matches
 * across two instants. Identity is the interned
 * graph UID(s) - never a projected property value - so a node whose property changed is still recognised as the
 * <i>same</i> node (that is exactly the {@code MODIFIED} case to detect, not hide).
 *
 * <p>Both variants are records, so value-equality and {@code hashCode} are automatic - an ordered
 * {@code List<ElementId>} is a correct classification key.</p>
 */
public sealed interface ElementId {

    /** A node's identity: its interned node UID. */
    record Node(long uid) implements ElementId {
    }

    /** An edge's identity: the interned {@code (src, edgeType, dst)} triple - the edge's whole storage key
     * (versions of one edge share this triple), so it is stable across instants. */
    record Edge(long srcUid, long edgeTypeUid, long dstUid) implements ElementId {
    }
}
