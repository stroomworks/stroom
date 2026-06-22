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

package stroom.sqlstore.impl;

import stroom.entity.shared.ExpressionCriteria;
import stroom.sqlstore.shared.ChangeOperation;
import stroom.sqlstore.shared.TemporalStoreTimeRange;
import stroom.util.shared.ResultPage;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface UpdatableTemporalStoreDao {

    TemporalEntry create(TemporalEntry entry);

    TemporalEntry update(TemporalEntry entry);

    Optional<TemporalEntry> fetch(TemporalEntryId id);

    boolean delete(TemporalEntryId id);

    ResultPage<TemporalEntry> find(ExpressionCriteria criteria);

    /**
     * Returns the absolute latest version of every key in the specified map,
     * with no upper-time constraint.
     *
     * <p>Equivalent to calling {@link #find} with only a {@code Map = mapName}
     * term (no time term), then deduplicating in-process to one entry per key.
     * Used to back the "Show all" toggle in the Floor Map Editor Fact List.</p>
     *
     * @param mapName name of the map to query; must not be {@code null} or blank
     * @return deduplicated list — one entry per key — sorted by key ascending;
     *         never {@code null}, may be empty
     */
    List<TemporalEntry> fetchAll(String mapName);

    /**
     * Returns the minimum and maximum {@code effective_time} values present in
     * the store for the given map name.
     *
     * <p>Executes a single {@code SELECT MIN(effective_time), MAX(effective_time)}
     * aggregation — no deduplication is performed.</p>
     *
     * @param mapName name of the map to query; must not be {@code null} or blank
     * @return the time range; both fields are {@code null} if the store is empty
     */
    TemporalStoreTimeRange getTimeRange(String mapName);

    /**
     * Applies a list of upsert and delete operations atomically within a
     * single database transaction.
     *
     * <p>Operations are applied strictly in list order. If any operation
     * fails the entire transaction is rolled back and no changes are
     * persisted.</p>
     *
     * @param operations ordered list of operations; must not be {@code null}
     * @throws RuntimeException (propagated from JOOQ) if any operation fails;
     *                          the transaction is rolled back automatically
     */
    void applyChanges(List<ChangeOperation> operations);

    void clear(String mapName);

    long count(String mapName);

    void search(ExpressionCriteria criteria, Consumer<TemporalEntry> consumer);
}

