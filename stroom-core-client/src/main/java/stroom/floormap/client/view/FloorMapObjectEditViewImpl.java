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

import stroom.floormap.client.presenter.FloorMapObjectEditPresenter.FloorMapObjectEditView;
import stroom.widget.datepicker.client.DateTimeBox;
import stroom.widget.datepicker.client.DateTimePopup;

import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.gwtplatform.mvp.client.ViewImpl;

public class FloorMapObjectEditViewImpl extends ViewImpl implements FloorMapObjectEditView {

    private final Widget widget;

    @UiField
    SimplePanel toolbarContainer;
    @UiField
    SimplePanel gridContainer;
    @UiField
    Label objectLabel;
    @UiField
    DateTimeBox effectiveTimeBox;
    @UiField
    TextBox xBox;
    @UiField
    TextBox yBox;
    @UiField
    Button saveBtn;

    @Inject
    public FloorMapObjectEditViewImpl(final Binder binder,
                                      final Provider<DateTimePopup> dateTimePopupProvider) {
        widget = binder.createAndBindUi(this);
        effectiveTimeBox.setPopupProvider(dateTimePopupProvider);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setObjectLabel(final String label) {
        objectLabel.setText(label);
    }

    @Override
    public void setToolbar(final Widget toolbarWidget) {
        toolbarContainer.setWidget(toolbarWidget);
    }

    @Override
    public void setGridView(final Widget gridWidget) {
        gridContainer.setWidget(gridWidget);
    }

    @Override
    public long getEffectiveTime() {
        return effectiveTimeBox.getValue();
    }

    @Override
    public void setEffectiveTime(final long timeMs) {
        effectiveTimeBox.setValue(timeMs);
    }

    @Override
    public double getX() {
        try {
            return Double.parseDouble(xBox.getText());
        } catch (final NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public void setX(final double x) {
        xBox.setText(String.valueOf(x));
    }

    @Override
    public double getY() {
        try {
            return Double.parseDouble(yBox.getText());
        } catch (final NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public void setY(final double y) {
        yBox.setText(String.valueOf(y));
    }

    @Override
    public HandlerRegistration addSaveHandler(final ClickHandler handler) {
        return saveBtn.addClickHandler(handler);
    }

    @Override
    public void setEnabled(final boolean enabled) {
        effectiveTimeBox.setEnabled(enabled);
        xBox.setEnabled(enabled);
        yBox.setEnabled(enabled);
        saveBtn.setEnabled(enabled);
    }

    // --------------------------------------------------------------------------------

    public interface Binder extends UiBinder<Widget, FloorMapObjectEditViewImpl> {

    }
}
