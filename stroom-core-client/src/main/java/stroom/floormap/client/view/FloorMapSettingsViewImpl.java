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

import stroom.document.client.event.DirtyUiHandlers;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.shared.FloorMapBackground;

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
import com.google.gwt.user.client.ui.ListBox;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.Date;
import java.util.List;

public class FloorMapSettingsViewImpl
        extends ViewWithUiHandlers<DirtyUiHandlers>
        implements FloorMapSettingsView, ReadOnlyChangeHandler {

    private final Widget widget;

    @UiField
    SimplePanel sourceFeed;

    @UiField
    TextBox backgroundImage;

    @UiField
    Button browseButton;

    @UiField
    Button addBackgroundButton;

    @UiField
    FileUpload fileUpload;

    @UiField
    ListBox backgroundList;

    @Inject
    public FloorMapSettingsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setSourceFeed(final View view) {
        this.sourceFeed.setWidget(view.asWidget());
    }

    @Override
    public void setBackgroundImage(final String backgroundImage) {
        this.backgroundImage.setText(backgroundImage);
    }

    @Override
    public String getBackgroundImage() {
        return this.backgroundImage.getText();
    }

    @Override
    public void setBackgroundImages(final List<FloorMapBackground> backgroundImages) {
        backgroundList.clear();
        if (backgroundImages != null) {
            for (final FloorMapBackground bg : backgroundImages) {
                backgroundList.addItem(new Date(bg.getValidFromTime()).toString());
            }
        }
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
    public void onReadOnly(final boolean readOnly) {
        backgroundImage.setEnabled(!readOnly);
        browseButton.setEnabled(!readOnly);
        addBackgroundButton.setEnabled(!readOnly);
        backgroundList.setEnabled(!readOnly);
    }

    @UiHandler("browseButton")
    void onBrowseClick(final ClickEvent event) {
        // Trigger the hidden file upload widget
        fileUpload.click();
    }

    @UiHandler("fileUpload")
    void onFileUploadChange(final ChangeEvent event) {
        // When a file is selected, read its content
        final Element element = fileUpload.getElement();
        readFile(element);
    }

    @UiHandler("backgroundImage")
    void onBackgroundImageChange(final ValueChangeEvent<String> event) {
        // Notify the presenter when the text box is manually edited
        if (getUiHandlers() != null) {
            getUiHandlers().onDirty();
        }
    }

    /**
     * Uses the browser's FileReader API to read a file and convert it to a Base64 Data URL.
     */
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

    /**
     * Callback method for the JSNI readFile logic.
     */
    void onFileRead(final String base64) {
        // Update the text box and trigger a change event so the presenter sees it
        backgroundImage.setValue(base64, true);
        if (getUiHandlers() != null) {
            getUiHandlers().onDirty();
        }
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, FloorMapSettingsViewImpl> {

    }
}
