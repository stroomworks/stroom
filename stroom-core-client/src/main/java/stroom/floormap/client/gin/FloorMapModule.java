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
import stroom.floormap.client.FloorMapPlugin;
import stroom.floormap.client.presenter.FloorMapCanvasPresenter;
import stroom.floormap.client.presenter.FloorMapCanvasPresenter.FloorMapCanvasView;
import stroom.floormap.client.presenter.FloorMapMapPresenter;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.client.presenter.FloorMapObjectEditPresenter;
import stroom.floormap.client.presenter.FloorMapObjectEditPresenter.FloorMapObjectEditView;
import stroom.floormap.client.presenter.FloorMapObjectListPresenter;
import stroom.floormap.client.presenter.FloorMapObjectListPresenter.FloorMapObjectListView;
import stroom.floormap.client.presenter.FloorMapPresenter;
import stroom.floormap.client.presenter.FloorMapQueryPresenter;
import stroom.floormap.client.presenter.FloorMapQueryPresenter.FloorMapQueryView;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.client.presenter.FloorMapTempPresenter;
import stroom.floormap.client.presenter.FloorMapTempPresenter.FloorMapTempView;
import stroom.floormap.client.presenter.FloorMapTimelinePresenter;
import stroom.floormap.client.presenter.FloorMapTimelinePresenter.FloorMapTimelineView;
import stroom.floormap.client.view.FloorMapCanvasViewImpl;
import stroom.floormap.client.view.FloorMapMapViewImpl;
import stroom.floormap.client.view.FloorMapObjectEditViewImpl;
import stroom.floormap.client.view.FloorMapObjectListViewImpl;
import stroom.floormap.client.view.FloorMapQueryViewImpl;
import stroom.floormap.client.view.FloorMapSettingsViewImpl;
import stroom.floormap.client.view.FloorMapTempViewImpl;
import stroom.floormap.client.view.FloorMapTimelineViewImpl;

/**
 * GIN module for the Floor Map feature.
 * Binds the presenters and views for the floor map components.
 */
public class FloorMapModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(FloorMapPlugin.class);

        bind(FloorMapPresenter.class);

        bindPresenterWidget(FloorMapMapPresenter.class,
                FloorMapMapView.class,
                FloorMapMapViewImpl.class);
        bindPresenterWidget(FloorMapSettingsPresenter.class,
                FloorMapSettingsView.class,
                FloorMapSettingsViewImpl.class);
        bindPresenterWidget(FloorMapTempPresenter.class,
                FloorMapTempView.class,
                FloorMapTempViewImpl.class);
        bindPresenterWidget(FloorMapCanvasPresenter.class,
                FloorMapCanvasView.class,
                FloorMapCanvasViewImpl.class);
        bindPresenterWidget(FloorMapTimelinePresenter.class,
                FloorMapTimelineView.class,
                FloorMapTimelineViewImpl.class);

        bindPresenterWidget(FloorMapQueryPresenter.class,
                FloorMapQueryView.class,
                FloorMapQueryViewImpl.class);

        bindPresenterWidget(FloorMapObjectEditPresenter.class,
                FloorMapObjectEditView.class,
                FloorMapObjectEditViewImpl.class);

        bindPresenterWidget(FloorMapObjectListPresenter.class,
                FloorMapObjectListView.class,
                FloorMapObjectListViewImpl.class);
    }
}
