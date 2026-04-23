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

import stroom.dashboard.client.main.AddDomainTypePresenter.AddDomainTypeView;
import stroom.item.client.SelectionBox;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.List;

public class AddDomainTypeViewImpl extends ViewImpl implements AddDomainTypeView {

    /** Empty entry for domain type */
    private static final String EMPTY = "";

    /** Wildcard entry for domain type attribute part */
    private static final String WILDCARD = "*";

    private final Widget widget;

    @UiField
    SelectionBox<String> classPart;
    @UiField
    SelectionBox<String> attributePart;

    @Inject
    public AddDomainTypeViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        classPart.setNonSelectString(EMPTY);
        attributePart.setNonSelectString(EMPTY);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public SelectionBox<String> getClassPart() {
        return classPart;
    }

    @Override
    public SelectionBox<String> getAttributePart() {
        return attributePart;
    }

    @Override
    public void setDomainClasses(final List<String> domainClasses) {
        this.classPart.clear();
        if (domainClasses != null) {
            this.classPart.addItem(EMPTY);
            this.classPart.addItem(WILDCARD);
            this.classPart.addItems(domainClasses);
        }
    }

    @Override
    public void setDomainAttributes(final List<String> domainAttributes) {
        this.attributePart.clear();
        if (domainAttributes != null) {
            this.attributePart.addItem(EMPTY);
            this.attributePart.addItems(domainAttributes);
        }
    }

    @Override
    public void focus() {
        classPart.focus();
    }

    public interface Binder extends UiBinder<Widget, AddDomainTypeViewImpl> {

    }
}
