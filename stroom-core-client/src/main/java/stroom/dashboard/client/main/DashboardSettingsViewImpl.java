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

package stroom.dashboard.client.main;

import stroom.dashboard.client.main.DashboardSettingsPresenter.DashboardSettingsView;

import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class DashboardSettingsViewImpl
        extends ViewWithUiHandlers<DashboardSettingsUiHandlers>
        implements DashboardSettingsView {

    private final Widget widget;

    @UiField
    TextBox domainType;

    @Inject
    public DashboardSettingsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public String getDomainType() {
        return domainType.getText();
    }

    @Override
    public void setDomainType(final String domainType) {
        this.domainType.setText(domainType);
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        domainType.setEnabled(!readOnly);
    }

    @SuppressWarnings("unused")
    @UiHandler("domainType")
    public void onDomainTypeKeyUp(final KeyUpEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().triggerDirty();
        }
    }

    public interface Binder extends UiBinder<Widget, DashboardSettingsViewImpl> {

    }
}
