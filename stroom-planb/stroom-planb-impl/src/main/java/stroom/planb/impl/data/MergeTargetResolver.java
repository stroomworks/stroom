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

package stroom.planb.impl.data;

import stroom.docstore.api.DocumentNotFoundException;

/**
 * Maps the document UUID a received fragment is named after onto the store it should be merged into.
 *
 * <p>A fragment carries no type information - it is a directory named after a document UUID - so the resolver is
 * also the thing that decides whether a given fragment belongs to this feature at all. Because a
 * {@link PartMergeProcessor} <b>discards</b> any fragment whose document cannot be resolved, two features must
 * never share staging directories: each would treat the other's fragments as belonging to deleted documents and
 * silently delete them.</p>
 */
public interface MergeTargetResolver {

    /**
     * Resolves the store a fragment should be merged into.
     *
     * <p><b>Preconditions:</b> {@code docUuid} is not null.
     * <b>Postconditions:</b> returns the target for {@code docUuid}, or throws if the document no longer exists.
     * <b>Null status:</b> {@code docUuid} is not nullable; the return value is never null.
     *
     * @param docUuid the UUID the fragment directory is named after.
     * @return the target to merge into, never null.
     * @throws DocumentNotFoundException if no such document exists, in which case the fragment is discarded.
     */
    MergeTarget resolve(String docUuid);
}
