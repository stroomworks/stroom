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

import stroom.domaintype.client.DomainTypeClient;
import stroom.domaintype.shared.DomainType;
import stroom.item.client.SelectionBox;
import stroom.widget.popup.client.event.DisablePopupEvent;
import stroom.widget.popup.client.event.EnablePopupEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.popup.client.view.DialogAction;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.function.Consumer;

public class AddDomainTypePresenter extends MyPresenterWidget<AddDomainTypePresenter.AddDomainTypeView> {

    private static final int WIDTH = 400;

    private static final int HEIGHT = 320;

    private final DomainTypeClient domainTypeClient;

    @Inject
    public AddDomainTypePresenter(final EventBus eventBus,
                                  final AddDomainTypeView view,
                                  final DomainTypeClient domainTypeClient) {
        super(eventBus, view);
        this.domainTypeClient = domainTypeClient;
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(getView().getClassPart().addValueChangeHandler(event -> {
            onClassChange(event.getValue(), null);
            validate();
        }));
        registerHandler(getView().getAttributePart().addValueChangeHandler(event -> {
            validate();
        }));
    }

    private void onClassChange(final String classPart, final String attributePart) {
        getView().getAttributePart().setValue(attributePart);
        if (classPart != null && !classPart.isBlank()) {
            domainTypeClient.fetchAttributeParts(classPart, getView()::setDomainAttributes);
        } else {
            getView().setDomainAttributes(null);
        }
    }

    private void validate() {
        final String classPart = getView().getClassPart().getValue();
        final String attributePart = getView().getAttributePart().getValue();
        if (classPart != null && !classPart.isBlank() && attributePart != null && !attributePart.isBlank()) {
            EnablePopupEvent.builder(this).action(DialogAction.OK).fire();
        } else {
            DisablePopupEvent.builder(this).action(DialogAction.OK).fire();
        }
    }

    public void show(final DomainType domainType, final Consumer<DomainType> consumer) {
        final String title;
        if (domainType != null) {
            title = "Edit Domain Type";
            getView().getClassPart().setValue(domainType.getClassPart());
            onClassChange(domainType.getClassPart(), domainType.getAttributePart());
        } else {
            title = "Add Domain Type";
            getView().getClassPart().setValue(null);
            getView().getAttributePart().setValue(null);
        }

        domainTypeClient.fetchClassParts(getView()::setDomainClasses);

        final PopupSize popupSize = PopupSize.resizable(WIDTH, HEIGHT);
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption(title)
                .onShow(e -> {
                    getView().focus();
                    validate();
                })
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final String classPart = getView().getClassPart().getValue();
                        final String attributePart = getView().getAttributePart().getValue();
                        if (classPart != null && !classPart.isBlank() && attributePart != null && !attributePart.isBlank()) {
                            consumer.accept(new DomainType(classPart, attributePart));
                            e.hide();
                        }
                    } else {
                        e.hide();
                    }
                })
                .fire();
    }

    public interface AddDomainTypeView extends View {

        SelectionBox<String> getClassPart();

        SelectionBox<String> getAttributePart();

        void setDomainClasses(List<String> domainClasses);

        void setDomainAttributes(List<String> domainAttributes);

        void focus();
    }
}
