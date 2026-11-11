/*
 * Copyright 2026 Crown Copyright
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

package stroom.dependency.client.layout;

import stroom.dependency.shared.EdgeCertainty;
import stroom.dependency.shared.EdgeKind;

/// An edge between two laid-out [Instance]s, after any collapse has redirected its ends to a summary.
///
/// @param a         The instance the edge leaves.
/// @param b         The instance the edge arrives at.
/// @param kind      How the two relate.
/// @param back      For a flow edge, true if it was removed to break a cycle; null for other kinds.
/// @param certainty How sure the server is that the edge exists, or null if not known.
/// @param element   The pipeline element the edge comes from, or null.
/// @param ambiguous True if `a` names `b` by a name more than one document has, so `b` is only one of
///                  several candidates. Drawn as a warning.
public record LayoutEdge(Instance a,
                         Instance b,
                         EdgeKind kind,
                         Boolean back,
                         EdgeCertainty certainty,
                         String element,
                         boolean ambiguous) {

}
