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

import stroom.alert.client.event.AlertEvent;
import stroom.dashboard.client.main.RefreshInterval;
import stroom.dashboard.shared.Automate;
import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.StroomQLQueryComponentSettings;
import stroom.util.shared.NullSafe;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Objects;

/// A StroomQL Query pane's Basic settings tab ([BasicStroomQLQuerySettings]): its name, and when it
/// runs.
public class BasicStroomQLQuerySettingsController
        extends BasicSettingsTabController<BasicStroomQLQuerySettings> {

    /// Made by GIN, with its content.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The content.
    @Inject
    public BasicStroomQLQuerySettingsController(final EventBus eventBus,
                                                final BasicStroomQLQuerySettings view) {
        super(eventBus, view);
    }

    @Override
    public void read(final ComponentConfig componentConfig) {
        super.read(componentConfig);

        final StroomQLQueryComponentSettings settings = getSettings(componentConfig);
        final Automate automate = settings.getAutomate() != null
                ? settings.getAutomate()
                : Automate.builder().build();

        getView().setQueryOnOpen(automate.isOpen());
        getView().setAutoRefresh(automate.isRefresh());
        // Settings written without an interval have none
        getView().setRefreshInterval(NullSafe.nonBlankStringElse(
                automate.getRefreshInterval(),
                Automate.DEFAULT_REFRESH_INTERVAL));
    }

    @Override
    public ComponentConfig write(final ComponentConfig componentConfig) {
        final ComponentConfig result = super.write(componentConfig);
        return result.copy().settings(writeSettings(getSettings(result))).build();
    }

    private StroomQLQueryComponentSettings writeSettings(final StroomQLQueryComponentSettings settings) {
        return settings
                .copy()
                .automate(Automate.builder()
                        .open(getView().isQueryOnOpen())
                        .refresh(getView().isAutoRefresh())
                        .refreshInterval(getView().getRefreshInterval())
                        .build())
                .build();
    }

    @Override
    public boolean validate() {
        final String problem = RefreshInterval.validate(getView().getRefreshInterval());
        if (problem != null) {
            AlertEvent.fireError(this, problem, null);
            return false;
        }
        return true;
    }

    @Override
    public boolean isDirty(final ComponentConfig componentConfig) {
        if (super.isDirty(componentConfig)) {
            return true;
        }

        final StroomQLQueryComponentSettings oldSettings = getSettings(componentConfig);
        final StroomQLQueryComponentSettings newSettings = writeSettings(oldSettings);
        return !Objects.equals(oldSettings.getAutomate(), newSettings.getAutomate());
    }

    // The pane's settings, or empty ones if it has none yet
    private static StroomQLQueryComponentSettings getSettings(final ComponentConfig componentConfig) {
        return componentConfig.getSettings() instanceof final StroomQLQueryComponentSettings settings
                ? settings
                : StroomQLQueryComponentSettings.builder().build();
    }
}
