/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.receive.rules.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.Set;

public class FieldEditPresenter extends MyPresenterWidget<FieldEditPresenter.FieldEditView> {

    private Set<String> otherFieldNames;

    @Inject
    public FieldEditPresenter(final EventBus eventBus, final FieldEditView view) {
        super(eventBus, view);
    }

    public void read(final QueryField field, final Set<String> otherFieldNames) {
        this.otherFieldNames = otherFieldNames;
        getView().setFieldType(field.getFldType());
        getView().setName(field.getFldName());
    }

    public QueryField write() {
        String name = getView().getName();
        name = name.trim();

        if (name.length() == 0) {
            AlertEvent.fireWarn(this, "A field must have a name", null);
            return null;
        }
        if (otherFieldNames.contains(name)) {
            AlertEvent.fireWarn(this, "A field with this name already exists", null);
            return null;
        }

        return create(getView().getFieldType(), name);
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        final PopupSize popupSize = PopupSize.resizableX();
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface FieldEditView extends View, Focus {

        FieldType getFieldType();

        void setFieldType(FieldType type);

        String getName();

        void setName(final String name);
    }

    private QueryField create(final FieldType type, final String name) {
        return QueryField
                .builder()
                .fldName(name)
                .fldType(type)
                .queryable(true)
                .build();
    }
}
