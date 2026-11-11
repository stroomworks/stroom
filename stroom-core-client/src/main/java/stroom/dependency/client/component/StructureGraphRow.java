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

import stroom.dependency.client.layout.InstanceKind;
import stroom.docref.DocRef;

/// One row of the Structure view's table: one document as the graph draws it, in one lane, with how it
/// connects to the rest. The table says in words what the graph shows, so it is what assistive technology
/// reads.
///
/// @param id           The id of the instance in the full-detail layout.
/// @param kind         What the instance draws, e.g. a node on the flow row or a referenced document.
/// @param docRef       The document, or null if there is none to act on.
/// @param name         The document's name.
/// @param type         The document's type, for people.
/// @param lane         The lane number.
/// @param laneName     The lane, for people, e.g. `Lane 2: FW-LOGS:RAW_EVENTS`.
/// @param upstream     What flows into it, as names; `None` if nothing.
/// @param downstream   What it flows into, as names; `None` if nothing.
/// @param references   What it refers to, as names; `None` if nothing.
/// @param referencedBy What refers to it, as names; `None` if nothing.
/// @param notes        Anything else there is to say about it (see [StructureGraphNotes]), as sentences;
///                     empty if there is nothing.
public record StructureGraphRow(String id,
                                InstanceKind kind,
                                DocRef docRef,
                                String name,
                                String type,
                                int lane,
                                String laneName,
                                String upstream,
                                String downstream,
                                String references,
                                String referencedBy,
                                String notes) {

}
