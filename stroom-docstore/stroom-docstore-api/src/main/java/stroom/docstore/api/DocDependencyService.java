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
import stroom.importexport.shared.Dependency;
import stroom.importexport.shared.DependencyCriteria;
import stroom.util.shared.ResultPage;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for managing document dependency relationships.
 * <p>
 * Dependencies are persisted in the doc_dependency table and kept current by direct calls from the
 * write paths of the entities that own them: as an entity is saved its owner (e.g. the docstore
 * {@link Store}, or the processor filter service) extracts the entity's dependencies from the object
 * it has just persisted and stores them via {@link #setDependencies}. Deletes clear edges via
 * {@link #removeDependencies}, and renames propagate the new name via {@link #propagateName}.
 * <p>
 */
public interface DocDependencyService {

    /**
     * Replace all stored edges for a document (or other entity, e.g. a processor filter) with the
     * supplied, already-computed dependencies. Callers extract the dependencies directly from the
     * object they have just persisted, so no re-read of the entity is required.
     * <p>
     * Every dependency is stored as definite. Use {@link #setDependencies(DocRef, DocDependencies)} for a
     * document that can name something ambiguously.
     */
    default void setDependencies(final DocRef docRef, final Set<DocRef> dependencies) {
        setDependencies(docRef, DocDependencies.of(dependencies));
    }

    /// Replace all stored edges for a document with the supplied, already-computed dependencies,
    /// storing those listed in [DocDependencies#ambiguous()] as candidates of an ambiguous name.
    ///
    /// @param docRef       The document (or other entity) whose edges are replaced.
    /// @param dependencies Its dependencies. Must not be null.
    void setDependencies(DocRef docRef, DocDependencies dependencies);

    /**
     * Remove all dependency edges from a document (called on doc delete).
     */
    void removeDependencies(DocRef docRef);

    /**
     * Propagate a name change to all edges that reference this doc.
     * Updates both from_name (where from_uuid matches) and to_name (where to_uuid matches).
     */
    void propagateName(DocRef docRef);

    /**
     * Query dependencies from the DB table (paginated, filtered, sorted).
     * The filter predicate is applied in Java after rows are fetched from
     * the database, allowing callers to apply checks that cannot be expressed
     * in SQL (e.g. document permission checks). Pagination is applied
     * <b>after</b> filtering so page sizes and totals are correct.
     *
     * @param criteria       the search/sort/page criteria
     * @param pseudoRefUuids UUIDs of known pseudo-refs (e.g. Searchable / Annotation refs) that
     *                       live outside the doc table and so must be reported as OK rather than
     *                       missing. Resolved by the caller, as for
     *                       {@link #fetchBrokenDependencies(Set)}.
     */
    ResultPage<Dependency> fetchDependencies(DependencyCriteria criteria, Set<String> pseudoRefUuids);

    /**
     * Get all broken dependencies (edges whose target no longer exists in the doc table), keyed by
     * the source document. Any source that is not itself an explorer tree node (e.g. a ProcessorFilter)
     * is rolled up to its owning explorer document (e.g. the filter's pipeline) so the broken
     * dependency can be surfaced on a real tree node.
     * <p>
     * Served from a short-lived cache that is refreshed from the DB on a time-based expiry, so results
     * may lag a recent change by a few seconds. Call {@link #invalidateBrokenDependencyCache()} to
     * force the next call to rebuild from the DB.
     *
     * @param pseudoRefUuids UUIDs of known pseudo-refs (e.g. Searchable / Annotation refs) that live
     *                       outside the doc table and so must not be treated as broken targets.
     */
    Map<DocRef, Set<DocRef>> fetchBrokenDependencies(final Set<String> pseudoRefUuids);

    /// Get every dependency recorded as one of several documents that a name matches, keyed by the
    /// source document. Unlike [#fetchBrokenDependencies(Set)], the targets exist: the problem is that
    /// more than one has the name the source uses, so the runtime cannot tell which is meant.
    ///
    /// Not cached. It is read when the explorer tree is rebuilt, which is when the broken-dependency
    /// cache is invalidated and re-read too.
    ///
    /// @return Source to the ambiguous targets it names. Empty if there are none.
    Map<DocRef, Set<DocRef>> fetchAmbiguousDependencies();

    /// Find the sources of one type that have a recorded edge to a document of the given type and name.
    ///
    /// Used to find the documents whose name-based references may have changed meaning because a
    /// document with one of those names was created, renamed or deleted: an XSLT's import or dictionary
    /// name can start or stop matching, or start or stop being ambiguous. The stored target name is
    /// matched, which is the name the source used for an edge recorded from a name, and the target's
    /// current name otherwise.
    ///
    /// @param fromType The source type, e.g. `XSLT`.
    /// @param toType   The target type, e.g. `Dictionary`.
    /// @param toNames  The target names to match, exactly.
    /// @return The matching sources. Empty if there are none, or if `toNames` is empty.
    Set<DocRef> findSourcesByTargetName(String fromType, String toType, Set<String> toNames);

    /**
     * Invalidate the broken-dependency cache so the next call to
     * {@link #fetchBrokenDependencies(Set)} rebuilds it from the DB rather than serving a stale
     * (time-expired) snapshot. Useful after a change that may have broken or fixed a dependency when
     * the result needs to be reflected immediately (e.g. an explorer tree rebuild).
     */
    void invalidateBrokenDependencyCache();

    /// Get the stored edges from any of the given sources, as recorded.
    ///
    /// **No permission check is applied**: the caller must check every end it shows, and should fail
    /// closed. Unlike [#getDependantsOf(DocRef)], a source that is not a document (e.g. a processor
    /// filter) is returned as itself, not rolled up to its owner.
    ///
    /// @param fromUuids The sources' UUIDs.
    /// @return The edges. Empty if there are none, or if `fromUuids` is empty.
    List<DependencyEdge> getEdgesFrom(Set<String> fromUuids);

    /// Get the stored edges to any of the given targets, as recorded.
    ///
    /// **No permission check is applied**, as for [#getEdgesFrom(Set)].
    ///
    /// @param toUuids The targets' UUIDs.
    /// @return The edges. Empty if there are none, or if `toUuids` is empty.
    List<DependencyEdge> getEdgesTo(Set<String> toUuids);

    /**
     * Get direct dependants of a specific document (for safe-delete).
     */
    Set<DocRef> getDependantsOf(DocRef docRef);

    /// Get the UUIDs of every source of the given type that has stored dependency edges. Used to find
    /// edges left behind by entities that no longer exist, e.g. processor filters that have been
    /// physically deleted.
    ///
    /// @param fromType The source type, e.g. `ProcessorFilter`.
    /// @return The distinct source UUIDs. Empty if there are none.
    Set<String> getSourceUuids(String fromType);
}
