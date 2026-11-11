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

package stroom.dependency.client.component;

/// What a pointer event in the Structure view landed on.
///
/// @param id     The id of the node it landed on, or inside, or null for none.
/// @param action The [StructureGraphSvg#DATA_ACTION] of the control it landed on, or null for none.
/// @param lane   The lane number of a lane control, or null.
public record StructureGraphHit(String id, String action, String lane) {

    /// Nothing: the background.
    public static final StructureGraphHit NONE = new StructureGraphHit(null, null, null);

}
