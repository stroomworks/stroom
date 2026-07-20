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

package stroom.floormap.client.view;

import stroom.floormap.client.presenter.FloorMapLayersPresenter.FloorMapLayersView;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View implementation for the Layers panel.
 *
 * <p>Layout: a button toolbar pinned above the layer data grid using a
 * {@link DockLayoutPanel}, matching the Tracking / Fact List panels.</p>
 */
public class FloorMapLayersViewImpl extends ViewImpl implements FloorMapLayersView {

    /** Height of the button toolbar in pixels. */
    private static final int TOOLBAR_HEIGHT_PX = 26;
    /** Height of the preset ("view") bar in pixels. */
    private static final int PRESET_BAR_HEIGHT_PX = 30;

    private final DockLayoutPanel root;
    private final SimplePanel presetContainer;
    private final SimplePanel toolbarContainer;
    private final SimplePanel gridContainer;

    @Inject
    public FloorMapLayersViewImpl() {
        presetContainer = new SimplePanel();
        toolbarContainer = new SimplePanel();

        gridContainer = new SimplePanel();
        gridContainer.setSize("100%", "100%");

        root = new DockLayoutPanel(Unit.PX);
        root.setSize("100%", "100%");
        root.addNorth(presetContainer, PRESET_BAR_HEIGHT_PX);
        root.addNorth(toolbarContainer, TOOLBAR_HEIGHT_PX);
        root.add(gridContainer);
    }

    @Override
    public Widget asWidget() {
        return root;
    }

    @Override
    public void setGridView(final Widget gridWidget) {
        gridContainer.setWidget(gridWidget);
    }

    @Override
    public void setToolbar(final Widget toolbarWidget) {
        toolbarContainer.setWidget(toolbarWidget);
    }

    @Override
    public void setPresetBar(final Widget presetWidget) {
        presetContainer.setWidget(presetWidget);
    }
}
