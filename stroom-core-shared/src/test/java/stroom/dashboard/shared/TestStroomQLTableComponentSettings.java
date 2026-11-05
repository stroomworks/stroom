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

package stroom.dashboard.shared;

import stroom.query.api.Column;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.Sort;
import stroom.query.api.Sort.SortDirection;
import stroom.query.shared.QueryTablePreferences;
import stroom.test.common.TestUtil;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestStroomQLTableComponentSettings {

    private static final StroomQLTableComponentSettings SETTINGS = StroomQLTableComponentSettings.builder()
            .queryId("query-1")
            .queryTablePreferences(QueryTablePreferences.builder()
                    .columns(List.of(Column.builder()
                            .id("column-1")
                            .name("Count")
                            .sort(new Sort(0, SortDirection.DESCENDING))
                            .build()))
                    .pageSize(50)
                    .build())
            .selectionFilter(List.of(new ComponentSelectionHandler(
                    "handler-1",
                    "table-2",
                    ExpressionOperator.builder().build(),
                    true)))
            .build();

    @Test
    void testSerialisation() {
        // Read as one of the dashboard's component settings, as a dashboard holds it
        final ComponentSettings result = TestUtil.testSerialisation(SETTINGS, ComponentSettings.class);

        assertThat(result)
                .isEqualTo(SETTINGS);
    }

    @Test
    void testSerialisation_type() {
        final String json = JsonUtil.writeValueAsString(SETTINGS, false);

        assertThat(json)
                .contains("\"type\":\"stroomql-table\"");
    }

    @Test
    void testSerialisation_empty() {
        final StroomQLTableComponentSettings settings = StroomQLTableComponentSettings.builder().build();

        final ComponentSettings result = TestUtil.testSerialisation(settings, ComponentSettings.class);

        assertThat(result)
                .isEqualTo(settings);
    }

    @Test
    void testCopy() {
        final StroomQLTableComponentSettings copy = SETTINGS.copy().build();

        assertThat(copy)
                .isEqualTo(SETTINGS);
        assertThat(SETTINGS.copy().queryId("query-2").build().getQueryId())
                .isEqualTo("query-2");
    }

    @Test
    void testCopy_doesNotShareSelectionFilter() {
        final List<ComponentSelectionHandler> selectionFilter = new ArrayList<>(SETTINGS.getSelectionFilter());
        final StroomQLTableComponentSettings settings = SETTINGS.copy().selectionFilter(selectionFilter).build();

        final StroomQLTableComponentSettings copy = settings.copy().build();
        selectionFilter.clear();

        // The copy has its own list, so a change to the original's doesn't reach it
        assertThat(copy.getSelectionFilter())
                .hasSize(1);
    }
}
