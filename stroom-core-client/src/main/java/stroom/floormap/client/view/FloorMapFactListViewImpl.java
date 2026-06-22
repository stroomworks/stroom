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

import stroom.floormap.client.presenter.FloorMapFactListPresenter.FloorMapFactListView;

import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View implementation for the Fact List panel.
 *
 * <p>Layout: optional toolbar pinned above the data grid.</p>
 *
 * <p>Renamed from {@code FloorMapObjectListViewImpl}.</p>
 */
public class FloorMapFactListViewImpl extends ViewImpl implements FloorMapFactListView {

    private final FlowPanel root;
    private final SimplePanel toolbarContainer;
    private final SimplePanel gridContainer;

    @Inject
    public FloorMapFactListViewImpl() {
        toolbarContainer = new SimplePanel();

        gridContainer = new SimplePanel();
        gridContainer.setSize("100%", "100%");

        root = new FlowPanel();
        root.setSize("100%", "100%");
        root.add(toolbarContainer);
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
}
