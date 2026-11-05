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

import stroom.dashboard.client.main.DashboardPane;
import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.StroomQLTableComponentSettings;
import stroom.query.shared.QueryTablePreferences;
import stroom.util.shared.NullSafe;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Objects;

/// A StroomQL Table pane's Basic settings tab ([BasicStroomQLTableSettings]): its name, the
/// StroomQL Query whose results it shows, and how many rows it shows on a page.
public class BasicStroomQLTableSettingsController extends BasicSettingsTabController<BasicStroomQLTableSettings> {

    private static final int DEFAULT_PAGE_SIZE = 100;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The content.
    @Inject
    public BasicStroomQLTableSettingsController(final EventBus eventBus,
                                                final BasicStroomQLTableSettings view) {
        super(eventBus, view);
    }

    @Override
    public void read(final ComponentConfig componentConfig) {
        super.read(componentConfig);

        getView().setQueryList(getDashboardContext()
                .getComponents()
                .getSortedComponentsByType(StroomQLQueryPaneController.TYPE.getId()));

        final StroomQLTableComponentSettings settings = (StroomQLTableComponentSettings) componentConfig.getSettings();
        getView().setQuery(getDashboardContext().getComponents().get(settings.getQueryId()));
        getView().setPageSize(NullSafe.getOrElse(
                settings.getQueryTablePreferences(),
                QueryTablePreferences::getPageSize,
                DEFAULT_PAGE_SIZE));
    }

    @Override
    public ComponentConfig write(final ComponentConfig componentConfig) {
        final ComponentConfig result = super.write(componentConfig);
        final StroomQLTableComponentSettings settings = (StroomQLTableComponentSettings) result.getSettings();
        return result.copy().settings(writeSettings(settings)).build();
    }

    private StroomQLTableComponentSettings writeSettings(final StroomQLTableComponentSettings settings) {
        final DashboardPane query = getView().getQuery();
        return settings
                .copy()
                .queryId(query == null
                        ? null
                        : query.getId())
                .queryTablePreferences(QueryTablePreferences
                        .copy(settings.getQueryTablePreferences())
                        .pageSize(getView().getPageSize())
                        .build())
                .build();
    }

    @Override
    public boolean isDirty(final ComponentConfig componentConfig) {
        if (super.isDirty(componentConfig)) {
            return true;
        }
        final StroomQLTableComponentSettings oldSettings =
                (StroomQLTableComponentSettings) componentConfig.getSettings();
        final StroomQLTableComponentSettings newSettings = writeSettings(oldSettings);
        return !Objects.equals(oldSettings.getQueryId(), newSettings.getQueryId())
               || !Objects.equals(
                pageSizeOf(oldSettings.getQueryTablePreferences()),
                pageSizeOf(newSettings.getQueryTablePreferences()));
    }

    // A page size, the default if none is set
    private static int pageSizeOf(final QueryTablePreferences preferences) {
        return NullSafe.getOrElse(preferences, QueryTablePreferences::getPageSize, DEFAULT_PAGE_SIZE);
    }
}
