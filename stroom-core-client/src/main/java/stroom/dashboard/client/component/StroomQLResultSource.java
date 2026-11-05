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

package stroom.dashboard.client.component;

import stroom.query.client.QueryResultConsumer;

import com.google.web.bindery.event.shared.HandlerRegistration;

/// A dashboard pane whose StroomQL query's results StroomQL Table panes show, e.g. a StroomQL Query
/// pane. Each table is given its own copy of the query's table, with its own sorts, filters, page
/// and open groups.
public interface StroomQLResultSource {

    /// Adds a table to be given a copy of the query's results. The copy is made when a search
    /// starts, so a table added once the query has run makes it run again.
    ///
    /// @param tableId The table's pane ID, which its copy of the results is kept under.
    /// @param table   Given the table's results, asked for its preferences, page and open groups,
    ///                and given the model of the query's search
    ///                ([QueryResultConsumer#setQueryModel(stroom.query.client.QueryModel)]).
    /// @return Removes the table, which is given no more results.
    HandlerRegistration addResultTable(String tableId, QueryResultConsumer table);

    /// @return The query as it last ran, with the dashboard's parameters put into it, or null if it
    /// hasn't run.
    String getQuery();
}
