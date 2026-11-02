/*
 * Copyright 2026 Crown Copyright
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


package stroom.pipelinefactory.client.component;

import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// The content of the dialog for one value a Pipeline Factory writes onto an element of each
/// pipeline it makes (A113): the element's id, the property's name and the value.
/// [FactoryPropertyEditorController] shows it and checks what is entered.
public class FactoryPropertyEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    TextBox elementId;
    @UiField
    TextBox name;
    @UiField
    TextBox value;

    /// Makes an empty editor.
    public FactoryPropertyEditor() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// @return The element id, as typed.
    public String getElementId() {
        return elementId.getText();
    }

    /// @param elementId The element id, or null for none.
    public void setElementId(final String elementId) {
        this.elementId.setText(elementId);
    }

    /// @return The property name, as typed.
    public String getName() {
        return name.getText();
    }

    /// @param name The property name, or null for none.
    public void setName(final String name) {
        this.name.setText(name);
    }

    /// @return The value pattern, as typed.
    public String getValue() {
        return value.getText();
    }

    /// @param value The value pattern, or null for none.
    public void setValue(final String value) {
        this.value.setText(value);
    }

    /// Puts the focus on its first field, the element id.
    public void focus() {
        elementId.setFocus(true);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, FactoryPropertyEditor> {

    }
}
