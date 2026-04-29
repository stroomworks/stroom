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

package stroom.floormap.client.gin;

import stroom.core.client.gin.PluginModule;
import stroom.floormap.client.presenter.FloorMapProcessingPresenter.FloorMapProcessingView;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.client.FloorMapPlugin;
import stroom.floormap.client.presenter.FloorMapPresenter;
import stroom.floormap.client.presenter.FloorMapProcessingPresenter;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter;
import stroom.floormap.client.view.FloorMapProcessingViewImpl;
import stroom.floormap.client.view.FloorMapSettingsViewImpl;

public class FloorMapModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(FloorMapPlugin.class);

        bind(FloorMapPresenter.class);

        bindPresenterWidget(FloorMapSettingsPresenter.class,
                FloorMapSettingsView.class,
                FloorMapSettingsViewImpl.class);
        bindPresenterWidget(FloorMapProcessingPresenter.class,
                FloorMapProcessingView.class,
                FloorMapProcessingViewImpl.class);
    }
}
