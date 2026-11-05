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

import stroom.query.api.Column;
import stroom.query.api.GroupSelection;
import stroom.query.api.OffsetRange;
import stroom.query.api.ResultRequest;
import stroom.query.api.ResultRequest.ResultStyle;
import stroom.query.api.Sort;
import stroom.query.api.Sort.SortDirection;
import stroom.query.api.TableSettings;
import stroom.query.language.SearchRequestFactory;
import stroom.query.shared.QueryComponentRequest;
import stroom.query.shared.QuerySearchRequest;
import stroom.query.shared.QueryTablePreferences;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestQueryResultRequestMapper {

    private static final String COLUMN_ID = "column1";
    private static final OffsetRange VIS_RANGE = OffsetRange.UNBOUNDED;
    private static final Sort ASCENDING = new Sort(0, SortDirection.ASCENDING);
    private static final Sort DESCENDING = new Sort(0, SortDirection.DESCENDING);

    @Test
    void map_noComponentRequests_givesQueryTableAndVis() {
        final OffsetRange range = new OffsetRange(20, 10);
        final GroupSelection groupSelection = openGroup("group1");
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder()
                .requestedRange(range)
                .groupSelection(groupSelection)
                .queryTablePreferences(preferencesSortedBy(DESCENDING))
                .build();

        final List<ResultRequest> mapped = QueryResultRequestMapper.map(queryResults(), searchRequest);

        assertThat(mapped)
                .extracting(ResultRequest::getComponentId)
                .containsExactly(SearchRequestFactory.TABLE_COMPONENT_ID, SearchRequestFactory.VIS_COMPONENT_ID);

        final ResultRequest table = mapped.get(0);
        assertThat(table.getRequestedRange()).isEqualTo(range);
        assertThat(table.getGroupSelection()).isEqualTo(groupSelection);
        assertThat(sortOf(table)).isEqualTo(DESCENDING);

        // The vis keeps the range it was made with, as it needs all the data
        final ResultRequest vis = mapped.get(1);
        assertThat(vis.getRequestedRange()).isEqualTo(VIS_RANGE);
        assertThat(vis.getGroupSelection()).isEqualTo(groupSelection);
    }

    @Test
    void map_emptyComponentRequests_givesQueryTableAndVis() {
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder()
                .componentRequests(List.of())
                .build();

        final List<ResultRequest> mapped = QueryResultRequestMapper.map(queryResults(), searchRequest);

        assertThat(mapped)
                .extracting(ResultRequest::getComponentId)
                .containsExactly(SearchRequestFactory.TABLE_COMPONENT_ID, SearchRequestFactory.VIS_COMPONENT_ID);
    }

    @Test
    void map_noResultRequests_givesNone() {
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder().build();

        assertThat(QueryResultRequestMapper.map(null, searchRequest)).isEmpty();
    }

    @Test
    void map_componentRequests_givesCopyOfTableForEach() {
        final OffsetRange range1 = new OffsetRange(0, 100);
        final OffsetRange range2 = new OffsetRange(5, 1);
        final GroupSelection groupSelection2 = openGroup("group2");
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder()
                // Ignored, as each component has its own
                .requestedRange(new OffsetRange(50, 50))
                .queryTablePreferences(preferencesSortedBy(DESCENDING))
                .componentRequests(List.of(
                        new QueryComponentRequest("pane1", preferencesSortedBy(ASCENDING), range1, null),
                        new QueryComponentRequest("pane2", null, range2, groupSelection2)))
                .build();

        final List<ResultRequest> mapped = QueryResultRequestMapper.map(queryResults(), searchRequest);

        // The query's own table and its vis aren't returned
        assertThat(mapped)
                .extracting(ResultRequest::getComponentId)
                .containsExactly("pane1", "pane2");
        assertThat(mapped)
                .extracting(ResultRequest::getResultStyle)
                .containsOnly(ResultStyle.TABLE);

        final ResultRequest pane1 = mapped.get(0);
        assertThat(pane1.getRequestedRange()).isEqualTo(range1);
        // No groups open
        assertThat(pane1.getGroupSelection()).isEqualTo(new GroupSelection());
        assertThat(sortOf(pane1)).isEqualTo(ASCENDING);

        // No preferences, so the table is as the query made it
        final ResultRequest pane2 = mapped.get(1);
        assertThat(pane2.getRequestedRange()).isEqualTo(range2);
        assertThat(pane2.getGroupSelection()).isEqualTo(groupSelection2);
        assertThat(sortOf(pane2)).isNull();
    }

    @Test
    void map_componentRequests_samePreferencesGiveSameTableSettings() {
        // The store is shared by components whose table settings are equal, so equal preferences must give
        // equal settings
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder()
                .componentRequests(List.of(
                        new QueryComponentRequest("pane1", preferencesSortedBy(ASCENDING), null, null),
                        new QueryComponentRequest("pane2", preferencesSortedBy(ASCENDING), null, null)))
                .build();

        final List<ResultRequest> mapped = QueryResultRequestMapper.map(queryResults(), searchRequest);

        assertThat(mapped.get(0).getMappings()).isEqualTo(mapped.get(1).getMappings());
    }

    @Test
    void map_componentRequests_queryWithNoTable_givesNone() {
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder()
                .componentRequests(List.of(new QueryComponentRequest("pane1", null, null, null)))
                .build();

        final List<ResultRequest> mapped = QueryResultRequestMapper.map(List.of(vis()), searchRequest);

        assertThat(mapped).isEmpty();
    }

    @Test
    void map_componentRequestWithNoId_isRefused() {
        final List<QueryComponentRequest> componentRequests = new ArrayList<>();
        componentRequests.add(new QueryComponentRequest("pane1", null, null, null));
        componentRequests.add(new QueryComponentRequest(" ", null, null, null));
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder()
                .componentRequests(componentRequests)
                .build();

        assertThatThrownBy(() -> QueryResultRequestMapper.map(queryResults(), searchRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A component request has no component ID");
    }

    @Test
    void map_nullComponentRequest_isRefused() {
        final List<QueryComponentRequest> componentRequests = new ArrayList<>();
        componentRequests.add(null);
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder()
                .componentRequests(componentRequests)
                .build();

        assertThatThrownBy(() -> QueryResultRequestMapper.map(queryResults(), searchRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A component request has no component ID");
    }

    @Test
    void map_componentRequestsSharingAnId_areRefused() {
        final QuerySearchRequest searchRequest = QuerySearchRequest.builder()
                .componentRequests(List.of(
                        new QueryComponentRequest("pane1", null, null, null),
                        new QueryComponentRequest("pane1", null, null, null)))
                .build();

        assertThatThrownBy(() -> QueryResultRequestMapper.map(queryResults(), searchRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("More than one component request has the component ID 'pane1'");
    }

    // The result requests a query with a `show` clause makes: its table and its vis
    private static List<ResultRequest> queryResults() {
        return List.of(table(), vis());
    }

    private static ResultRequest table() {
        return ResultRequest.builder()
                .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                .resultStyle(ResultStyle.TABLE)
                .addMappings(tableSettings())
                .build();
    }

    private static ResultRequest vis() {
        return ResultRequest.builder()
                .componentId(SearchRequestFactory.VIS_COMPONENT_ID)
                .resultStyle(ResultStyle.QL_VIS)
                .addMappings(tableSettings(), tableSettings())
                .requestedRange(VIS_RANGE)
                .build();
    }

    private static TableSettings tableSettings() {
        return TableSettings.builder()
                .addColumns(Column.builder()
                        .id(COLUMN_ID)
                        .name("Column 1")
                        .build())
                .build();
    }

    private static QueryTablePreferences preferencesSortedBy(final Sort sort) {
        return QueryTablePreferences.builder()
                .columns(List.of(Column.builder()
                        .id(COLUMN_ID)
                        .name("Column 1")
                        .sort(sort)
                        .build()))
                .build();
    }

    private static GroupSelection openGroup(final String group) {
        return GroupSelection.builder()
                .openGroups(Set.of(group))
                .build();
    }

    private static Sort sortOf(final ResultRequest resultRequest) {
        return resultRequest.getMappings().get(0).getColumns().get(0).getSort();
    }
}
