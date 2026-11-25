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

/// Makes a row on first mention, for the tables whose rows are then read `FOR UPDATE` in the same
/// transaction.
final class RowMaker {

    private RowMaker() {
        // Static helpers only.
    }

    /// Makes the row `key` names where it is missing. Two nodes meeting a new key at once both insert;
    /// the unique key decides, and the loser reads what the winner wrote. The row is looked for first, so
    /// that the steady path — the row exists — takes no lock here.
    ///
    /// Where it is missing, the insert is `ON DUPLICATE KEY UPDATE` and not `IGNORE`: on a duplicate an
    /// `IGNORE` takes a shared lock, and the transactions that met the row missing together each then ask
    /// for the exclusive lock the others' shared locks refuse, so the database kills one as deadlocked.
    /// An update takes the exclusive lock at once, so they queue instead and a following `FOR UPDATE` has
    /// nothing to upgrade.
    ///
    /// @param context The transaction's context, which the caller goes on to lock the row in.
    /// @param table   The table the row belongs in.
    /// @param key     The condition naming the one row.
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
