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
import stroom.floormap.client.presenter.FloorMapDockPresenter;
import stroom.floormap.client.presenter.FloorMapDockPresenter.FloorMapDockView;
import stroom.floormap.client.presenter.FloorMapEditorPresenter;
import stroom.floormap.client.presenter.FloorMapEditorPresenter.FloorMapEditorView;
import stroom.floormap.client.presenter.FloorMapFactListPresenter;
import stroom.floormap.client.presenter.FloorMapFactListPresenter.FloorMapFactListView;
import stroom.floormap.client.presenter.FloorMapGroupEditPresenter;
import stroom.floormap.client.presenter.FloorMapGroupEditPresenter.FloorMapGroupEditView;
import stroom.floormap.client.presenter.FloorMapGroupsPresenter;
import stroom.floormap.client.presenter.FloorMapGroupsPresenter.FloorMapGroupsView;
import stroom.floormap.client.presenter.FloorMapInitPresenter;
import stroom.floormap.client.presenter.FloorMapInitPresenter.FloorMapInitView;
import stroom.floormap.client.presenter.FloorMapLayerStylePresenter;
import stroom.floormap.client.presenter.FloorMapLayerStylePresenter.FloorMapLayerStyleView;
import stroom.floormap.client.presenter.FloorMapLayersPresenter;
import stroom.floormap.client.presenter.FloorMapLayersPresenter.FloorMapLayersView;
import stroom.floormap.client.presenter.FloorMapMapPresenter;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.client.presenter.FloorMapObjectEditPresenter;
import stroom.floormap.client.presenter.FloorMapObjectEditPresenter.FloorMapObjectEditView;
import stroom.floormap.client.presenter.FloorMapPresenter;
import stroom.floormap.client.presenter.FloorMapQueryPresenter;
import stroom.floormap.client.presenter.FloorMapQueryPresenter.FloorMapQueryView;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.client.presenter.FloorMapTimeListPresenter;
import stroom.floormap.client.presenter.FloorMapTimeListPresenter.FloorMapTimeListView;
import stroom.floormap.client.presenter.FloorMapTimelinePresenter;
import stroom.floormap.client.presenter.FloorMapTimelinePresenter.FloorMapTimelineView;
import stroom.floormap.client.presenter.FloorMapTimelineSettingsPresenter;
import stroom.floormap.client.presenter.FloorMapTimelineSettingsPresenter.FloorMapTimelineSettingsView;
import stroom.floormap.client.presenter.FloorMapTrackingPresenter;
import stroom.floormap.client.presenter.FloorMapTrackingPresenter.FloorMapTrackingView;
import stroom.floormap.client.view.FloorMapCanvasViewImpl;
import stroom.floormap.client.view.FloorMapDockViewImpl;
import stroom.floormap.client.view.FloorMapEditorViewImpl;
import stroom.floormap.client.view.FloorMapFactListViewImpl;
import stroom.floormap.client.view.FloorMapGroupEditViewImpl;
import stroom.floormap.client.view.FloorMapGroupsViewImpl;
import stroom.floormap.client.view.FloorMapInitViewImpl;
import stroom.floormap.client.view.FloorMapLayerStyleViewImpl;
import stroom.floormap.client.view.FloorMapLayersViewImpl;
import stroom.floormap.client.view.FloorMapMapViewImpl;
import stroom.floormap.client.view.FloorMapObjectEditViewImpl;
import stroom.floormap.client.view.FloorMapQueryViewImpl;
import stroom.floormap.client.view.FloorMapSettingsViewImpl;
import stroom.floormap.client.view.FloorMapTimeListViewImpl;
import stroom.floormap.client.view.FloorMapTimelineSettingsViewImpl;
import stroom.floormap.client.view.FloorMapTimelineViewImpl;
import stroom.floormap.client.view.FloorMapTrackingViewImpl;

/**
 * GIN module for the Floor Map feature.
 * Binds the presenters and views for the floor map components.
 */
public class FloorMapModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(FloorMapPlugin.class);

        bind(FloorMapPresenter.class);

        bindPresenterWidget(FloorMapEditorPresenter.class,
                FloorMapEditorView.class,
                FloorMapEditorViewImpl.class);
        bindPresenterWidget(FloorMapMapPresenter.class,
                FloorMapMapView.class,
                FloorMapMapViewImpl.class);
        bindPresenterWidget(FloorMapSettingsPresenter.class,
                FloorMapSettingsView.class,
                FloorMapSettingsViewImpl.class);
        bindPresenterWidget(FloorMapInitPresenter.class,
                FloorMapInitView.class,
                FloorMapInitViewImpl.class);
        bindPresenterWidget(FloorMapCanvasPresenter.class,
                FloorMapCanvasView.class,
                FloorMapCanvasViewImpl.class);
        bindPresenterWidget(FloorMapTimelinePresenter.class,
                FloorMapTimelineView.class,
                FloorMapTimelineViewImpl.class);

        bindPresenterWidget(FloorMapTimelineSettingsPresenter.class,
                FloorMapTimelineSettingsView.class,
                FloorMapTimelineSettingsViewImpl.class);

        bindPresenterWidget(FloorMapQueryPresenter.class,
                FloorMapQueryView.class,
                FloorMapQueryViewImpl.class);

        bindPresenterWidget(FloorMapObjectEditPresenter.class,
                FloorMapObjectEditView.class,
                FloorMapObjectEditViewImpl.class);

        bindPresenterWidget(FloorMapFactListPresenter.class,
                FloorMapFactListView.class,
                FloorMapFactListViewImpl.class);

        bindPresenterWidget(FloorMapTimeListPresenter.class,
                FloorMapTimeListView.class,
                FloorMapTimeListViewImpl.class);

        bindPresenterWidget(FloorMapTrackingPresenter.class,
                FloorMapTrackingView.class,
                FloorMapTrackingViewImpl.class);

        bindPresenterWidget(FloorMapDockPresenter.class,
                FloorMapDockView.class,
                FloorMapDockViewImpl.class);

        bindPresenterWidget(FloorMapLayersPresenter.class,
                FloorMapLayersView.class,
                FloorMapLayersViewImpl.class);

        bindPresenterWidget(FloorMapLayerStylePresenter.class,
                FloorMapLayerStyleView.class,
                FloorMapLayerStyleViewImpl.class);

        bindPresenterWidget(FloorMapGroupsPresenter.class,
                FloorMapGroupsView.class,
                FloorMapGroupsViewImpl.class);

        bindPresenterWidget(FloorMapGroupEditPresenter.class,
                FloorMapGroupEditView.class,
                FloorMapGroupEditViewImpl.class);
    }
}
