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

/// Counts describing one layout.
///
/// @param nodes       How many instances were laid out, after collapsing.
/// @param servedNodes How many nodes the server sent.
/// @param edges       How many edges are drawn.
/// @param lanes       How many lanes there are.
/// @param cols        How many flow columns there are.
/// @param duplicated  Instances laid out less nodes served. Positive where referenced documents are drawn
///                    in several lanes, negative where collapsing folded more away.
/// @param collapsed   How many instances are folded into a summary.
public record LayoutStats(int nodes,
                          int servedNodes,
                          int edges,
                          int lanes,
                          int cols,
                          int duplicated,
                          int collapsed) {

}
