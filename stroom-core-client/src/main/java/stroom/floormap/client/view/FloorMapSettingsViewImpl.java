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

package stroom.floormap.client.view;

import stroom.document.client.event.DirtyUiHandlers;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.widget.datepicker.client.DateTimeBox;
import stroom.widget.datepicker.client.DateTimePopup;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.FileUpload;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class FloorMapSettingsViewImpl
        extends ViewWithUiHandlers<DirtyUiHandlers>
        implements FloorMapSettingsView, ReadOnlyChangeHandler {

    private final Widget widget;

    @UiField
    DateTimeBox validFromBox;

    @UiField
    TextBox backgroundImage;

    @UiField
    Button browseButton;

    @UiField
    Button addBackgroundButton;

    @UiField
    FileUpload fileUpload;

    @UiField
    SimplePanel toolbarContainer;

    @UiField
    SimplePanel gridContainer;

    @UiField
    ValueSpinner rotation;

    @UiField
    Image imagePreview;

    @Inject
    public FloorMapSettingsViewImpl(final Binder binder,
                                    final Provider<DateTimePopup> dateTimePopupProvider) {
        widget = binder.createAndBindUi(this);
        validFromBox.setPopupProvider(dateTimePopupProvider);

        rotation.setMin(0L);
        rotation.setMax(359L);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setBackgroundImage(final String backgroundImage) {
        this.backgroundImage.setText(backgroundImage);
        this.imagePreview.setUrl(backgroundImage);
    }

    @Override
    public String getBackgroundImage() {
        return this.backgroundImage.getText();
    }

    @Override
    public void setToolbar(final Widget widget) {
        this.toolbarContainer.setWidget(widget);
    }

    @Override
    public void setGridView(final Widget widget) {
        this.gridContainer.setWidget(widget);
    }

    @Override
    public void setStartTime(final long startTime) {
        validFromBox.setValue(startTime);
    }

    @Override
    public long getStartTime() {
        return validFromBox.getValue();
    }

    @Override
    public void setRotation(final double degrees) {
        rotation.setValue((long) degrees);
    }

    @Override
    public double getRotation() {
        return rotation.getValue().doubleValue();
    }

    @Override
    public HandlerRegistration addBackgroundImageChangeHandler(final ValueChangeHandler<String> handler) {
        return backgroundImage.addValueChangeHandler(handler);
    }

    @Override
    public HandlerRegistration addAddBackgroundHandler(final ClickHandler handler) {
        return addBackgroundButton.addClickHandler(handler);
    }

    @Override
    public HandlerRegistration addRotationChangeHandler(final ValueChangeHandler<Long> handler) {
        return rotation.addValueChangeHandler(handler);
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        backgroundImage.setEnabled(!readOnly);
        browseButton.setEnabled(!readOnly);
        addBackgroundButton.setEnabled(!readOnly);
    }

    @UiHandler("browseButton")
    void onBrowseClick(final ClickEvent event) {
        fileUpload.click();
    }

    @UiHandler("fileUpload")
    void onFileUploadChange(final ChangeEvent event) {
        final Element element = fileUpload.getElement();
        readFile(element);
    }

    @UiHandler("backgroundImage")
    void onBackgroundImageChange(final ValueChangeEvent<String> event) {
        imagePreview.setUrl(event.getValue());
        if (getUiHandlers() != null) {
            getUiHandlers().onDirty();
        }
    }

    private native void readFile(Element element) /*-{
        var self = this;
        var file = element.files[0];
        if (file) {
            var reader = new FileReader();
            reader.onload = function(e) {
                var base64 = e.target.result;
                self.@stroom.floormap.client.view.FloorMapSettingsViewImpl::onFileRead(Ljava/lang/String;)(base64);
            };
            reader.readAsDataURL(file);
        }
    }-*/;

    void onFileRead(final String base64) {
        backgroundImage.setValue(base64, true);
        imagePreview.setUrl(base64);
        if (getUiHandlers() != null) {
            getUiHandlers().onDirty();
        }
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, FloorMapSettingsViewImpl> {

    }
}
