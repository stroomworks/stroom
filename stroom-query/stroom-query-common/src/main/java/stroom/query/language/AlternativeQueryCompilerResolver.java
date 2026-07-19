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

package stroom.query.language;

import stroom.docref.DocRef;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Task P6.1: a small, pure function picking which (if any) {@link AlternativeQueryCompiler} should handle a
 * search, extracted out of {@code QueryServiceImpl} so it is directly unit-testable without constructing that
 * class's full, sixteen-dependency Guice graph.
 */
public final class AlternativeQueryCompilerResolver {

    private AlternativeQueryCompilerResolver() {
        // Static utility - not instantiable.
    }

    /**
     * @param dataSourceRef the search's owning doc-ref (e.g. {@code SearchRequestSource.ownerDocRef}), or
     *                      {@code null} if the search has no known owning doc.
     * @param compilers     never null; the full set of registered alternative compilers (typically empty unless
     *                      a module contributing one, e.g. {@code stroom-graphdb-impl}, is installed).
     * @return empty if {@code dataSourceRef} is {@code null} or no compiler in {@code compilers} supports it;
     *         otherwise the first supporting compiler found.
     */
    public static Optional<AlternativeQueryCompiler> resolve(
            final @Nullable DocRef dataSourceRef, final Collection<AlternativeQueryCompiler> compilers) {
        Objects.requireNonNull(compilers, "compilers");
        if (dataSourceRef == null) {
            return Optional.empty();
        }
        return compilers.stream()
                .filter(compiler -> compiler.supports(dataSourceRef))
                .findFirst();
    }
}
