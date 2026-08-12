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

package stroom.query.common.v2;

import stroom.query.api.Column;
import stroom.query.api.DateTimeSettings;
import stroom.query.api.OffsetRange;
import stroom.query.api.TimeFilter;
import stroom.query.language.functions.ValuesConsumer;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.util.List;
import java.util.function.Consumer;

public interface DataStore extends ValuesConsumer {

    /**
     * Get the columns that this data store knows about.
     */
    List<Column> getColumns();

    /**
     * Get child items from the data for the currently open groups and time filter, passing each to
     * {@code resultConsumer}.
     *
     * @param openGroups The open groups to get child items for.
     * @param timeFilter The time filter to use to limit the data returned.
     */
    void fetch(List<Column> columns,
               OffsetRange range,
               OpenGroups openGroups,
               TimeFilter timeFilter,
               ItemMapper mapper,
               Consumer<Item> resultConsumer,
               Consumer<Long> totalRowCountConsumer);

    /**
     * Clear the data store.
     */
    void clear();

    /**
     * Get the completion state associated with receiving all search results and having added them to the store
     * successfully.
     *
     * @return The search completion state for the data store.
     */
    CompletionState getCompletionState();

    /**
     * Read items from the supplied input and transfer them to the data store.
     *
     * @param input The input to read.
     */
    void readPayload(Input input);

    /**
     * Write data from the data store to an output removing them from the datastore as we go as they will be transferred
     * to another store.
     *
     * @param output The output to write to.
     */
    void writePayload(Output output);

    long getByteSize();

    /**
     * The total number of result rows this store has <b>received</b> (been given via {@link #accept}). Intended as
     * a cheap, relative size signal for choosing an execution strategy (e.g. which side of a join to build) rather
     * than as an authoritative count of currently-retrievable items.
     *
     * <p><b>Cost:</b> O(1) - a single in-memory counter read. Unlike {@link #getByteSize()} (which for an on-heap
     * store serialises the whole dataset), this neither scans, fetches, nor serialises anything, so it is always
     * safe to call.</p>
     *
     * <p><b>When it is valid:</b> the value reflects everything received <i>up to the moment of the call</i>. It
     * equals the store's final received total only once its search has completed (see {@link CompletionState} /
     * {@code ResultStore.awaitCompletion()}); called mid-population it returns a partial, monotonically
     * non-decreasing count.</p>
     *
     * <p><b>Relationship to what {@link #fetch} yields:</b> it counts rows <i>received</i>, so:</p>
     * <ul>
     *   <li>for a <b>flat</b> store (no grouping, no result trimming) - which is exactly what a compiled join side
     *   is - it equals the number of rows an unbounded {@link #fetch} yields;</li>
     *   <li>for a store <b>with grouping</b> it counts the input rows received, <i>not</i> the (smaller) number of
     *   grouped rows fetch would return;</li>
     *   <li>it is <b>not</b> reduced by result <b>trimming</b>/{@code maxResults} - a trimmed store may fetch fewer
     *   rows than it received.</li>
     * </ul>
     * <p>Treat it as an exact fetch-row count only for the flat, un-trimmed case.</p>
     *
     * <p><b>Comparability &amp; intended use:</b> only compare values from stores of the <b>same implementation
     * and configuration</b> (a single query's join sides always are). Because it is a relative sizing hint for
     * strategy selection only, a wrong value can at worst pick a slower plan - it must <b>never</b> feed a
     * correctness decision.</p>
     *
     * <p><b>Postconditions:</b> the returned value is always {@code >= 0}. No parameters; nothing nullable.</p>
     */
    long getSize();

    KeyFactory getKeyFactory();

    DateTimeSettings getDateTimeSettings();
}
