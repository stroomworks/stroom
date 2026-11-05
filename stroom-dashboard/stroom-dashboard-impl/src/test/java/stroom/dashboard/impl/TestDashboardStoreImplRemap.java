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

package stroom.dashboard.impl;

import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.ComponentSettings;
import stroom.dashboard.shared.DashboardConfig;
import stroom.dashboard.shared.DashboardDoc;
import stroom.dashboard.shared.StroomQLQueryComponentSettings;
import stroom.dashboard.shared.StroomQLTableComponentSettings;
import stroom.docref.DocRef;
import stroom.docstore.api.DependencyRemapper;
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.impl.StoreFactoryImpl;
import stroom.docstore.impl.memory.MemoryPersistence;
import stroom.query.common.v2.DataSourceProviderRegistry;
import stroom.query.common.v2.QueryDataSourceRemapper;
import stroom.query.language.SearchRequestFactory;
import stroom.security.mock.MockSecurityContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/// Remapping a dashboard's dependencies, e.g. on import, through the real store: the data source a StroomQL
/// Query pane's query reads.
class TestDashboardStoreImplRemap {

    private static final DocRef EVENTS = DocRef.builder()
            .type("Index")
            .uuid("events-uuid")
            .name("Events")
            .build();
    private static final DocRef NEW_EVENTS = DocRef.builder()
            .type("Index")
            .uuid("new-events-uuid")
            .name("New Events")
            .build();

    private DashboardStoreImpl store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Every query reads Events
        final SearchRequestFactory searchRequestFactory = Mockito.mock(SearchRequestFactory.class);
        Mockito.doAnswer(invocation -> {
            ((Consumer<DocRef>) invocation.getArgument(1)).accept(EVENTS);
            return null;
        }).when(searchRequestFactory).extractDataSourceOnly(Mockito.anyString(), Mockito.any());
        final DataSourceProviderRegistry dataSourceProviderRegistry = Mockito.mock(DataSourceProviderRegistry.class);
        Mockito.when(dataSourceProviderRegistry.getDataSourceDocRefs()).thenReturn(List.of(EVENTS));

        final MockSecurityContext securityContext = new MockSecurityContext();
        store = new DashboardStoreImpl(
                new StoreFactoryImpl(new MemoryPersistence(), null, securityContext, null, () -> null),
                securityContext,
                new DashboardSerialiser(new Serialiser2FactoryImpl()),
                new QueryDataSourceRemapper(searchRequestFactory, () -> dataSourceProviderRegistry));
    }

    @Test
    void stroomQLQuery_recordsDataSource() {
        final DependencyRemapper dependencyRemapper = new DependencyRemapper();

        store.getDependencyRemapFunction().remap(dashboard(), dependencyRemapper);

        assertThat(dependencyRemapper.getDependencies())
                .containsExactly(EVENTS);
    }

    @Test
    void stroomQLQuery_remapsDataSource() {
        final DependencyRemapper dependencyRemapper = new DependencyRemapper(Map.of(EVENTS, NEW_EVENTS));

        final DashboardDoc remapped = store.getDependencyRemapFunction().remap(dashboard(), dependencyRemapper);

        assertThat(settings(remapped, "query-1"))
                .isEqualTo(StroomQLQueryComponentSettings.builder()
                        .query("from New Events select Count")
                        .build());
        // The table holds no query, so is as it was
        assertThat(settings(remapped, "table-1"))
                .isEqualTo(settings(dashboard(), "table-1"));
    }

    @Test
    void stroomQLQuery_noQuery() {
        final DashboardDoc doc = dashboard(StroomQLQueryComponentSettings.builder().build());
        final DependencyRemapper dependencyRemapper = new DependencyRemapper(Map.of(EVENTS, NEW_EVENTS));

        final DashboardDoc remapped = store.getDependencyRemapFunction().remap(doc, dependencyRemapper);

        assertThat(settings(remapped, "query-1"))
                .isEqualTo(StroomQLQueryComponentSettings.builder().build());
        assertThat(dependencyRemapper.getDependencies())
                .isEmpty();
    }

    // A dashboard with a StroomQL Query pane reading Events, and a StroomQL Table pane showing it
    private static DashboardDoc dashboard() {
        return dashboard(StroomQLQueryComponentSettings.builder()
                .query("from Events select Count")
                .build());
    }

    private static DashboardDoc dashboard(final StroomQLQueryComponentSettings querySettings) {
        return DashboardDoc.builder()
                .uuid("dashboard-uuid")
                .name("Dashboard")
                .dashboardConfig(DashboardConfig.builder()
                        .components(List.of(
                                ComponentConfig.builder()
                                        .type("stroomql-query")
                                        .id("query-1")
                                        .name("Query")
                                        .settings(querySettings)
                                        .build(),
                                ComponentConfig.builder()
                                        .type("stroomql-table")
                                        .id("table-1")
                                        .name("Table")
                                        .settings(StroomQLTableComponentSettings.builder()
                                                .queryId("query-1")
                                                .build())
                                        .build()))
                        .build())
                .build();
    }

    private static ComponentSettings settings(final DashboardDoc doc, final String componentId) {
        return doc.getDashboardConfig()
                .getComponents()
                .stream()
                .filter(componentConfig -> componentId.equals(componentConfig.getId()))
                .findFirst()
                .orElseThrow()
                .getSettings();
    }
}
