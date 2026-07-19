/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.graphdb.client.gin;

import stroom.core.client.gin.PluginModule;
import stroom.graphdb.client.GraphDbPlugin;
import stroom.graphdb.client.presenter.GraphDbDataPresenter;
import stroom.graphdb.client.presenter.GraphDbPresenter;
import stroom.graphdb.client.presenter.GraphDbSettingsPresenter;
import stroom.graphdb.client.presenter.GraphDbSettingsPresenter.GraphDbSettingsView;
import stroom.graphdb.client.view.GraphDbDataViewImpl;
import stroom.graphdb.client.view.GraphDbSettingsViewImpl;

/**
 * Task P6.2: client-side Gin module for {@code GraphDbDoc} - named {@code GraphDbModule} only because
 * {@code SqlTemporalStoreModule}/{@code PlanBModule} establish that as the client-side Gin module's conventional
 * name; unrelated to the server-side {@code stroom.graphdb.impl.GraphDbModule}.
 */
public class GraphDbModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(GraphDbPlugin.class);
        bind(GraphDbPresenter.class);
        bindPresenterWidget(
                GraphDbSettingsPresenter.class,
                GraphDbSettingsView.class,
                GraphDbSettingsViewImpl.class);
        bindPresenterWidget(
                GraphDbDataPresenter.class,
                GraphDbDataPresenter.GraphDbDataView.class,
                GraphDbDataViewImpl.class);
    }
}
