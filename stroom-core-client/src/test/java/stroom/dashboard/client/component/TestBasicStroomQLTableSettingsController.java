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

import stroom.dashboard.client.main.DashboardContext;
import stroom.dashboard.client.main.DashboardPane;
import stroom.dashboard.client.main.Panes;
import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.StroomQLTableComponentSettings;
import stroom.query.api.Column;
import stroom.query.shared.QueryTablePreferences;

import com.google.gwt.junit.GWTMockUtilities;
import com.google.web.bindery.event.shared.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestBasicStroomQLTableSettingsController {

    private static final String QUERY_ID = "stroomql-query-1";
    private static final List<Column> COLUMNS = List.of(Column.builder().id("col-1").name("UserId").build());

    private BasicStroomQLTableSettings view;
    private DashboardPane query;
    private BasicStroomQLTableSettingsController controller;

    @BeforeEach
    void setUp() {
        GWTMockUtilities.disarm();
        view = Mockito.mock(BasicStroomQLTableSettings.class);
        query = Mockito.mock(DashboardPane.class);
        Mockito.when(query.getId()).thenReturn(QUERY_ID);
        final Panes panes = Mockito.mock(Panes.class);
        Mockito.when(panes.get(QUERY_ID)).thenReturn(query);
        Mockito.when(panes.getSortedComponentsByType(StroomQLQueryPaneController.TYPE.getId()))
                .thenReturn(List.of(query));
        final DashboardContext dashboardContext = Mockito.mock(DashboardContext.class);
        Mockito.when(dashboardContext.getComponents()).thenReturn(panes);

        controller = new BasicStroomQLTableSettingsController(new SimpleEventBus(), view);
        controller.setDashboardContext(dashboardContext);
    }

    @AfterEach
    void tearDown() {
        GWTMockUtilities.restore();
    }

    @Test
    void read() {
        controller.read(config(QUERY_ID, QueryTablePreferences.builder().pageSize(25).build()));

        Mockito.verify(view).setQueryList(List.of(query));
        Mockito.verify(view).setQuery(query);
        Mockito.verify(view).setPageSize(25);
    }

    @Test
    void read_noPreferences() {
        controller.read(config(null, null));

        Mockito.verify(view).setQuery(null);
        Mockito.verify(view).setPageSize(100);
    }

    @Test
    void write_keepsTheOtherPreferences() {
        // The table's columns, sorts and formats are kept; only its query and page size change
        final ComponentConfig config = config(null, QueryTablePreferences.builder().columns(COLUMNS).build());
        controller.read(config);
        shown(query, 50);

        final StroomQLTableComponentSettings settings = settings(controller.write(config));

        assertThat(settings.getQueryId())
                .isEqualTo(QUERY_ID);
        assertThat(settings.getQueryTablePreferences().getPageSize())
                .isEqualTo(50);
        assertThat(settings.getQueryTablePreferences().getColumns())
                .isEqualTo(COLUMNS);
    }

    @Test
    void write_noQuery() {
        final ComponentConfig config = config(QUERY_ID, null);
        controller.read(config);
        shown(null, 100);

        assertThat(settings(controller.write(config)).getQueryId())
                .isNull();
    }

    @Test
    void isDirty_unchanged() {
        // A table with no page size of its own shows the default, which isn't a change
        final ComponentConfig config = config(QUERY_ID, null);
        controller.read(config);
        shown(query, 100);

        assertThat(controller.isDirty(config))
                .isFalse();
    }

    @Test
    void isDirty_pageSize() {
        final ComponentConfig config = config(QUERY_ID, null);
        controller.read(config);
        shown(query, 20);

        assertThat(controller.isDirty(config))
                .isTrue();
    }

    @Test
    void isDirty_query() {
        final ComponentConfig config = config(null, null);
        controller.read(config);
        shown(query, 100);

        assertThat(controller.isDirty(config))
                .isTrue();
    }

    // What the tab shows, as the user left it
    private void shown(final DashboardPane chosenQuery, final int pageSize) {
        Mockito.when(view.getName()).thenReturn("Events");
        Mockito.when(view.getQuery()).thenReturn(chosenQuery);
        Mockito.when(view.getPageSize()).thenReturn(pageSize);
    }

    private static StroomQLTableComponentSettings settings(final ComponentConfig config) {
        return (StroomQLTableComponentSettings) config.getSettings();
    }

    private static ComponentConfig config(final String queryId, final QueryTablePreferences preferences) {
        return ComponentConfig.builder()
                .id("stroomql-table-1")
                .name("Events")
                .type(StroomQLTablePaneController.TYPE.getId())
                .settings(StroomQLTableComponentSettings.builder()
                        .queryId(queryId)
                        .queryTablePreferences(preferences)
                        .build())
                .build();
    }
}
