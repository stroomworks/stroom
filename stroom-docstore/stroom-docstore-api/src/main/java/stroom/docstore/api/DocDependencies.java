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

package stroom.docstore.api;

import stroom.docref.DocRef;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// The dependencies of one document, as extracted for recording in `doc_dependency`.
///
/// Most documents hold their dependencies as [DocRef] fields, so every dependency is definite and
/// [#ambiguous()] is empty. A document that refers to others **by name**, such as an XSLT, can name
/// something that matches several documents. Each match is recorded as a dependency so that none is
/// lost, and is also listed in [#ambiguous()] so that it is stored as one of several candidates rather
/// than as a dependency the document will actually follow.
///
/// @param dependencies Every dependency, definite and ambiguous. Never null.
/// @param ambiguous    The dependencies that are only candidates of an ambiguous name. Always a subset of
///                     `dependencies`. Never null.
public record DocDependencies(Set<DocRef> dependencies, Set<DocRef> ambiguous) {

    private static final DocDependencies EMPTY = new DocDependencies(Set.of(), Set.of());

    /// @throws NullPointerException     if either set is null.
    /// @throws IllegalArgumentException if `ambiguous` holds a dependency that `dependencies` does not.
    public DocDependencies {
        Objects.requireNonNull(dependencies, "Null dependencies supplied");
        Objects.requireNonNull(ambiguous, "Null ambiguous supplied");
        if (!dependencies.containsAll(ambiguous)) {
            throw new IllegalArgumentException("Every ambiguous dependency must also be a dependency");
        }
        dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
        ambiguous = Collections.unmodifiableSet(new LinkedHashSet<>(ambiguous));
    }

    /// @return No dependencies at all.
    public static DocDependencies empty() {
        return EMPTY;
    }

    /// @param dependencies The dependencies, all definite. May be null, which is treated as empty.
    /// @return The dependencies with none ambiguous, as for any document that holds [DocRef] fields.
    public static DocDependencies of(final Set<DocRef> dependencies) {
        return dependencies == null || dependencies.isEmpty()
                ? EMPTY
                : new DocDependencies(dependencies, Set.of());
    }

    /// @param docRef The dependency to ask about.
    /// @return true if `docRef` is recorded only as a candidate of an ambiguous name.
    public boolean isAmbiguous(final DocRef docRef) {
        return ambiguous.contains(docRef);
    }
}
