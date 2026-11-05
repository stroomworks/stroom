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

import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.StroomQLQueryComponentSettings;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.query.shared.QueryResource;
import stroom.util.shared.NullSafe;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Objects;
import java.util.function.Consumer;

/// A StroomQL Query pane's settings: Basic and Selection Query. The selection query's fields are
/// those of the data source the pane's query reads (its `from`), found from the query's text once
/// for each text.
public class StroomQLQuerySettingsController extends PaneSettingsController {

    private static final QueryResource QUERY_RESOURCE = GWT.create(QueryResource.class);

    private String query;
    // The query text that currentDataSource was found for
    private String currentDataSourceQuery;
    private DocRef currentDataSource;

    /// Made by GIN, with its content and its tabs.
    ///
    /// @param eventBus       The application's event bus.
    /// @param view           Its component.
    /// @param basicSettings  The basic settings.
    /// @param selectionQuery The selection query.
    /// @param restFactory    Finds the query's data source.
    @Inject
    public StroomQLQuerySettingsController(final EventBus eventBus,
                                           final PaneSettings view,
                                           final BasicStroomQLQuerySettingsController basicSettings,
                                           final SelectionHandlersController selectionQuery,
                                           final RestFactory restFactory) {
        super(eventBus, view);
        own(basicSettings);
        own(selectionQuery);

        selectionQuery.setDataSourceRefConsumer(consumer -> findDataSource(restFactory, consumer));

        addTab("Basic", basicSettings);
        addTab("Selection Query", selectionQuery);
    }

    @Override
    public void read(final ComponentConfig componentConfig) {
        query = componentConfig.getSettings() instanceof final StroomQLQueryComponentSettings settings
                ? settings.getQuery()
                : null;
        super.read(componentConfig);
    }

    // Gives the consumer the data source of the pane's query, or null if it has no query or the
    // query names none
    private void findDataSource(final RestFactory restFactory, final Consumer<DocRef> consumer) {
        final String queryString = query;
        if (NullSafe.isBlankString(queryString)) {
            consumer.accept(null);
        } else if (Objects.equals(queryString, currentDataSourceQuery)) {
            consumer.accept(currentDataSource);
        } else {
            restFactory
                    .create(QUERY_RESOURCE)
                    .method(res -> res.fetchDataSourceFromQueryString(queryString))
                    .onSuccess(result -> {
                        currentDataSource = result;
                        currentDataSourceQuery = queryString;
                        consumer.accept(result);
                    })
                    .onFailure(new DefaultErrorHandler(this, () -> {
                        currentDataSource = null;
                        currentDataSourceQuery = queryString;
                        consumer.accept(null);
                    }))
                    .owner(this)
                    .taskMonitorFactory(taskMonitor())
                    .exec();
        }
    }
}
