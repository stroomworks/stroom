/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.util.shared;

import stroom.docref.DocRef;

import java.util.List;
import java.util.Map;

public interface HasDependencies {

    /**
     * Remap dependencies for a document.
     *
     * @param docRef     The document to apply dependency remappings to.
     * @param remappings The remappings to apply where relevant.
     * @return anything the user who triggered the operation should be told, in the order found, phrased
     * for them rather than for an operator. Never null; empty in the ordinary case where every
     * remapping that was called for was applied.
     * <p>
     * The channel exists because not every reference can be remapped. A document that holds its
     * references as names inside a text body cannot have them rewritten without editing the author's
     * text, so the reference survives the copy pointing at whatever the name resolves to in the new
     * location - which may be the original document, or nothing at all. That is not a failure the
     * document can fix, and it is invisible unless it is said out loud.
     * <p>
     * Warnings reach the current user, so an implementation must include nothing here that they are not
     * entitled to see.
     */
    List<String> remapDependencies(DocRef docRef, Map<DocRef, DocRef> remappings);
}
