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

package stroom.index.client.view;

import stroom.index.client.presenter.IndexFieldEditPresenter.IndexFieldEditView;
import stroom.index.client.presenter.IndexFieldEditUiHandlers;
import stroom.index.shared.LuceneFieldTypes;
import stroom.item.client.SelectionBox;
import stroom.query.api.datasource.AnalyzerType;
import stroom.query.api.datasource.FieldType;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

public class IndexFieldEditViewImpl
        extends ViewWithUiHandlers<IndexFieldEditUiHandlers>
        implements IndexFieldEditView {

    /** Empty entry for domain type */
    private static final String EMPTY = "";

    /** Wildcard entry for domain type attribute part */
    private static final String WILDCARD = "*";

    private final Widget widget;
    @UiField
    SelectionBox<FieldType> type;
    @UiField
    TextBox name;
    @UiField
    SelectionBox<String> domainTypeClassPart;
    @UiField
    SelectionBox<String> domainTypeAttributePart;
    @UiField
    CustomCheckBox stored;
    @UiField
    CustomCheckBox indexed;
    @UiField
    CustomCheckBox positions;
    @UiField
    SelectionBox<AnalyzerType> analyser;
    @UiField
    CustomCheckBox caseSensitive;
    @UiField
    SimplePanel denseVectorOptions;

    @Inject
    public IndexFieldEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);

        type.addItems(LuceneFieldTypes.FIELD_TYPES);
        analyser.addItems(AnalyzerType.values());

        domainTypeClassPart.setNonSelectString(EMPTY);
        domainTypeAttributePart.setNonSelectString(EMPTY);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void focus() {
        type.focus();
    }

    @Override
    public FieldType getType() {
        return type.getValue();
    }

    @Override
    public void setType(final FieldType type) {
        this.type.setValue(type);
    }

    @Override
    public String getFieldName() {
        return name.getText();
    }

    @Override
    public void setFieldName(final String fieldName) {
        name.setText(fieldName);
    }

    @Override
    public String getDomainTypeClassPart() {
        return domainTypeClassPart.getValue();
    }

    @Override
    public void setDomainTypeClassPart(final String domainTypeClassPart) {
        this.domainTypeClassPart.setValue(domainTypeClassPart);
    }

    @Override
    public void setDomainClasses(final List<String> domainClasses) {
        this.domainTypeClassPart.clear();
        if (domainClasses != null) {
            this.domainTypeClassPart.addItem(EMPTY);
            this.domainTypeClassPart.addItem(WILDCARD);
            this.domainTypeClassPart.addItems(domainClasses);
        }
    }

    @Override
    public String getDomainTypeAttributePart() {
        return domainTypeAttributePart.getValue();
    }

    @Override
    public void setDomainTypeAttributePart(final String domainTypeAttributePart) {
        this.domainTypeAttributePart.setValue(domainTypeAttributePart);
    }

    @Override
    public void setDomainAttributes(final List<String> domainAttributes) {
        this.domainTypeAttributePart.clear();
        if (domainAttributes != null) {
            this.domainTypeAttributePart.addItem(EMPTY);
            this.domainTypeAttributePart.addItems(domainAttributes);
        }
    }

    @Override
    public boolean isStored() {
        return stored.getValue();
    }

    @Override
    public void setStored(final boolean stored) {
        this.stored.setValue(stored);
    }

    @Override
    public boolean isIndexed() {
        return indexed.getValue();
    }

    @Override
    public void setIndexed(final boolean indexed) {
        this.indexed.setValue(indexed);
    }

    @Override
    public boolean isTermPositions() {
        return positions.getValue();
    }

    @Override
    public void setTermPositions(final boolean termPositions) {
        positions.setValue(termPositions);
    }

    @Override
    public AnalyzerType getAnalyzerType() {
        return analyser.getValue();
    }

    @Override
    public void setAnalyzerType(final AnalyzerType analyzerType) {
        this.analyser.setValue(analyzerType);
    }

    @Override
    public boolean isCaseSensitive() {
        return caseSensitive.getValue();
    }

    @Override
    public void setCaseSensitive(final boolean caseSensitive) {
        this.caseSensitive.setValue(caseSensitive);
    }

    @Override
    public void setDenseVectorOptions(final View view) {
        this.denseVectorOptions.setWidget(view.asWidget());
    }

    @Override
    public void setDenseVectorOptionsVisible(final boolean visible) {
        this.denseVectorOptions.setVisible(visible);
    }

    @UiHandler("type")
    @SuppressWarnings("unused")
    public void onTypeChange(final ValueChangeEvent<?> event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onDirty();
        }
    }

    @UiHandler("domainTypeClassPart")
    @SuppressWarnings("unused")
    public void onDomainClassChange(final ValueChangeEvent<String> event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onClassChange(event.getValue());
        }
    }

    @UiHandler("domainTypeAttributePart")
    @SuppressWarnings("unused")
    public void onDomainAttributeChange(final ValueChangeEvent<String> event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onDirty();
        }
    }

    public interface Binder extends UiBinder<Widget, IndexFieldEditViewImpl> {

    }
}
