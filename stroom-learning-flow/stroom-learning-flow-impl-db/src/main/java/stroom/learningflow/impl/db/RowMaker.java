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

package stroom.learningflow.impl.db;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

import java.util.Map;

/// Creates a row the first time it is needed, for tables whose rows are then read `FOR UPDATE` in the
/// same transaction.
final class RowMaker {

    private RowMaker() {
        // Static helpers only.
    }

    /// Creates the row that `key` identifies, if it does not exist. If two nodes find the row missing at
    /// the same time, both insert; the unique key lets only one row be created, and the other insert
    /// leaves it as it is. The row is looked for first, so that the usual case, where it exists, takes no
    /// lock here.
    ///
    /// The insert uses `ON DUPLICATE KEY UPDATE` rather than `INSERT IGNORE`. On a duplicate, `IGNORE`
    /// takes a shared lock; transactions that found the row missing together would each then ask for the
    /// exclusive lock that the others' shared locks block, and the database would abort one as
    /// deadlocked. An update takes the exclusive lock at once, so the transactions queue instead, and the
    /// caller's following `FOR UPDATE` has no lock to upgrade.
    ///
    /// @param context The transaction's context, in which the caller goes on to lock the row.
    /// @param table   The table the row belongs in.
    /// @param key     The condition that identifies the one row.
    /// @param version The table's version column, set to 1 on a new row and left as it is on a duplicate.
    /// @param values  The other columns of a new row.
    /// @param <R>     The table's record type.
    static <R extends Record> void makeIfMissing(final DSLContext context,
                                                 final Table<R> table,
                                                 final Condition key,
                                                 final TableField<R, Integer> version,
                                                 final Map<? extends Field<?>, ?> values) {
        final boolean exists = context.fetchExists(context.selectFrom(table).where(key));
        if (exists) {
            return;
        }
        context.insertInto(table)
                .set(version, 1)
                .set(values)
                .onDuplicateKeyUpdate()
                .set(version, version)
                .execute();
    }
}
