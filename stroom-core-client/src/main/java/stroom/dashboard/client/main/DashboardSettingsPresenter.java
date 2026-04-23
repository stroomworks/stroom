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
import stroom.domaintype.client.DomainTypeClient;
import stroom.domaintype.shared.DomainType;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.List;

public class DashboardSettingsPresenter
        extends DocPresenter<DashboardSettingsView, DashboardDoc>
        implements DashboardSettingsUiHandlers {

    private final DomainTypeClient domainTypeClient;

    @Inject
    public DashboardSettingsPresenter(final EventBus eventBus,
                                      final DashboardSettingsView view,
                                      final DomainTypeClient domainTypeClient) {
        super(eventBus, view);
        this.domainTypeClient = domainTypeClient;
        view.setUiHandlers(this);

        domainTypeClient.fetchClassParts(view::setDomainClasses);
    }

    @Override
    protected void onRead(final DocRef docRef, final DashboardDoc dashboard, final boolean readOnly) {
        final String domainTypeStr = dashboard.getDomainType();
        if (domainTypeStr != null && !domainTypeStr.isBlank()) {
            final DomainType domainType = new DomainType(domainTypeStr);
            getView().setDomainTypeClassPart(domainType.getClassPart());
            onClassChange(domainType.getClassPart(), false);
            getView().setDomainTypeAttributePart(domainType.getAttributePart());
        }

        getView().onReadOnly(readOnly);
    }

    @Override
    protected DashboardDoc onWrite(final DashboardDoc dashboard) {
        final String domainClass = getView().getDomainTypeClassPart();
        final String domainAttribute = getView().getDomainTypeAttributePart();

        String domainType = null;
        if (domainClass != null && !domainClass.isBlank()) {
            domainType = domainClass + "." + (domainAttribute != null ? domainAttribute : "");
        } else if (domainAttribute != null && !domainAttribute.isBlank()) {
            domainType = domainAttribute;
        }

        return dashboard.copy().domainType(domainType).build();
    }

    @Override
    public void onClassChange(final String classPart) {
        onClassChange(classPart, true);
    }

    private void onClassChange(final String classPart, final boolean dirty) {
        getView().setDomainTypeAttributePart(null);
        if (classPart != null && !classPart.isBlank()) {
            domainTypeClient.fetchAttributeParts(classPart, getView()::setDomainAttributes);
        } else {
            getView().setDomainAttributes(null);
        }
        if (dirty) {
            triggerDirty();
        }
    }

    @Override
    public void triggerDirty() {
        onChange();
    }

    public interface DashboardSettingsView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<DashboardSettingsUiHandlers> {

        String getDomainTypeClassPart();

        void setDomainTypeClassPart(String domainTypeClassPart);

        void setDomainClasses(List<String> domainClasses);

        String getDomainTypeAttributePart();

        void setDomainTypeAttributePart(String domainTypeAttributePart);

        void setDomainAttributes(List<String> domainAttributes);
    }
}
