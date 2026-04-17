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
import stroom.dashboard.shared.DashboardDoc;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

public class DashboardSettingsPresenter
        extends DocPresenter<DashboardSettingsView, DashboardDoc>
        implements DashboardSettingsUiHandlers {

    @Inject
    public DashboardSettingsPresenter(final EventBus eventBus, final DashboardSettingsView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    @Override
    protected void onRead(final DocRef docRef, final DashboardDoc dashboard, final boolean readOnly) {
        getView().setDashboardType(dashboard.getDashboardType());
        getView().onReadOnly(readOnly);
    }

    @Override
    protected DashboardDoc onWrite(final DashboardDoc dashboard) {
        return dashboard.copy().dashboardType(getView().getDashboardType()).build();
    }

    @Override
    public void triggerDirty() {
        onChange();
    }

    public interface DashboardSettingsView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<DashboardSettingsUiHandlers> {

        String getDashboardType();

        void setDashboardType(String dashboardType);
    }
}
