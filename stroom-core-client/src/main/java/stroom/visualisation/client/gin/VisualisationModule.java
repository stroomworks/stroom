/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.visualisation.client.gin;

import stroom.core.client.gin.PluginModule;
import stroom.visualisation.client.VisualisationPlugin;
import stroom.visualisation.client.presenter.VisualisationPresenter;
import stroom.visualisation.client.presenter.VisualisationSettingsPresenter;
import stroom.visualisation.client.presenter.VisualisationSettingsPresenter.VisualisationSettingsView;
import stroom.visualisation.client.view.VisualisationSettingsViewImpl;

// STROOMWORKS-LOCAL: KEEP LOCAL ON MERGE FROM master.
// Part of "Make the Visualisation Asset system generic" - upstream's VisualisationAsset*
// classes were generalised into the shared stroom.document.asset subsystem so FloorMap can
// carry assets too. Upstream still has the visualisation-specific version, so a merge will
// try to reinstate it; keep this side and re-point any new upstream code at document.asset.
public class VisualisationModule extends PluginModule {
    @Override
    protected void configure() {
        bindPlugin(VisualisationPlugin.class);
        bind(VisualisationPresenter.class);
        bindPresenterWidget(VisualisationSettingsPresenter.class, VisualisationSettingsView.class,
                VisualisationSettingsViewImpl.class);
    }
}
