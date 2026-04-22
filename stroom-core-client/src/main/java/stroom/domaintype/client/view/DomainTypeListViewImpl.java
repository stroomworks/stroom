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

package stroom.domaintype.client.view;

import stroom.domaintype.client.presenter.DomainTypeListPresenter.DomainTypeListView;
import stroom.svg.client.Preset;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

public class DomainTypeListViewImpl extends ViewImpl implements DomainTypeListView {

    private final Widget widget;

    @UiField
    SimplePanel masterContainer;
    @UiField
    SimplePanel detailContainer;
    @UiField
    ButtonPanel masterButtonPanel;
    @UiField
    ButtonPanel detailButtonPanel;

    @Inject
    public DomainTypeListViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setMasterView(final Widget widget) {
        masterContainer.setWidget(widget);
    }

    @Override
    public void setDetailView(final Widget widget) {
        detailContainer.setWidget(widget);
    }

    @Override
    public ButtonView addButtonMaster(final Preset preset) {
        return masterButtonPanel.addButton(preset);
    }

    @Override
    public ButtonView addButtonDetail(final Preset preset) {
        return detailButtonPanel.addButton(preset);
    }

    public interface Binder extends UiBinder<Widget, DomainTypeListViewImpl> {
    }
}
