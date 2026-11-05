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

package stroom.query.impl;

import stroom.query.api.OffsetRange;
import stroom.query.api.ResultRequest;
import stroom.query.api.ResultRequest.ResultStyle;
import stroom.query.language.SearchRequestFactory;
import stroom.query.shared.QueryComponentRequest;
import stroom.query.shared.QuerySearchRequest;
import stroom.query.shared.QueryTablePreferencesUtil;
import stroom.util.shared.NullSafe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Turns the result requests a StroomQL query makes (its table, and its visualisation if it has a `show`
/// clause) into the ones a [QuerySearchRequest] asks for: the query's own, with the request's table
/// preferences, page and open groups, or, if the request has component requests, a copy of the query's
/// table for each of them.
final class QueryResultRequestMapper {

    private QueryResultRequestMapper() {
        // Utility class.
    }

    /// Gives the result requests to search with.
    ///
    /// @param resultRequests The result requests made from the query's text.
    /// @param searchRequest  The search request, saying which results are wanted and how.
    /// @return The result requests to search with.
    /// @throws IllegalArgumentException If a component request has no component ID, or shares one with
    ///                                  another.
    static List<ResultRequest> map(final List<ResultRequest> resultRequests,
                                   final QuerySearchRequest searchRequest) {
        final List<QueryComponentRequest> componentRequests = searchRequest.getComponentRequests();
        if (NullSafe.isEmptyCollection(componentRequests)) {
            return mapQueryResults(NullSafe.list(resultRequests), searchRequest);
        }
        return mapComponentResults(NullSafe.list(resultRequests), componentRequests);
    }

    // The query's own table and visualisation, with the search request's preferences, page and open groups
    private static List<ResultRequest> mapQueryResults(final List<ResultRequest> resultRequests,
                                                       final QuerySearchRequest searchRequest) {
        final List<ResultRequest> mapped = new ArrayList<>(resultRequests.size());
        for (final ResultRequest resultRequest : resultRequests) {
            // Apply the UI's table preferences.
            final ResultRequest withPreferences = QueryTablePreferencesUtil.applyTablePreferences(
                    resultRequest, searchRequest.getQueryTablePreferences());

            // The vis needs all the data, rather than just a page worth
            final OffsetRange range = resultRequest.getResultStyle() == ResultStyle.QL_VIS
                    ? withPreferences.getRequestedRange()
                    : searchRequest.getRequestedRange();

            mapped.add(withPreferences
                    .copy()
                    .groupSelection(searchRequest.getGroupSelection())
                    .requestedRange(range)
                    .build());
        }
        return mapped;
    }

    // A copy of the query's table for each component request; the query's visualisation isn't returned
    private static List<ResultRequest> mapComponentResults(final List<ResultRequest> resultRequests,
                                                           final List<QueryComponentRequest> componentRequests) {
        checkComponentIds(componentRequests);

        final Optional<ResultRequest> optionalTable = resultRequests
                .stream()
                .filter(resultRequest ->
                        SearchRequestFactory.TABLE_COMPONENT_ID.equals(resultRequest.getComponentId()))
                .findFirst();
        if (optionalTable.isEmpty()) {
            return List.of();
        }

        final ResultRequest table = optionalTable.get();
        final List<ResultRequest> mapped = new ArrayList<>(componentRequests.size());
        for (final QueryComponentRequest componentRequest : componentRequests) {
            mapped.add(QueryTablePreferencesUtil
                    .applyTablePreferences(table, componentRequest.getQueryTablePreferences())
                    .copy()
                    .componentId(componentRequest.getComponentId())
                    .groupSelection(componentRequest.getGroupSelection())
                    .requestedRange(componentRequest.getRequestedRange())
                    .build());
        }
        return mapped;
    }

    // Each copy of the table is stored and returned under its component ID, so they must be there and differ
    private static void checkComponentIds(final List<QueryComponentRequest> componentRequests) {
        final Set<String> componentIds = new HashSet<>();
        for (final QueryComponentRequest componentRequest : componentRequests) {
            final String componentId = NullSafe.get(componentRequest, QueryComponentRequest::getComponentId);
            if (NullSafe.isBlankString(componentId)) {
                throw new IllegalArgumentException("A component request has no component ID");
            }
            if (!componentIds.add(componentId)) {
                throw new IllegalArgumentException("More than one component request has the component ID '" +
                                                   componentId + "'");
            }
        }
    }
}
