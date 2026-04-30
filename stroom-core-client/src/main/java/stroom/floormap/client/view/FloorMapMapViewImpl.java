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

import stroom.floormap.client.presenter.FloorMapMapPresenter;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.ProvidesResize;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.gwt.user.client.ui.ResizeLayoutPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

public class FloorMapMapViewImpl extends ViewImpl implements FloorMapMapView, RequiresResize, ProvidesResize {

    private final Widget widget;

    @UiField
    ResizeLayoutPanel mapPanel;
    @UiField
    SimplePanel logPanel;

    @Inject
    public FloorMapMapViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setInSlot(final Object slot, final Widget content) {
        if (FloorMapMapPresenter.MAP.equals(slot)) {
            mapPanel.setWidget(content);
        } else if (FloorMapMapPresenter.LOG_DATA.equals(slot)) {
            logPanel.setWidget(content);
        }
    }

    @Override
    public void onResize() {
        // Ensures the signal reaches ResizeLayoutPanel which then passes it to the canvas.
        if (widget instanceof  RequiresResize) {
            ((RequiresResize) widget).onResize();
        }
    }

    public interface Binder extends UiBinder<Widget, FloorMapMapViewImpl> {

    }
}
