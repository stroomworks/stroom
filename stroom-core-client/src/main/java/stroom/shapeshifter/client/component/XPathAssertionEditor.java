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


package stroom.shapeshifter.client.component;

import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// The content of the dialog that adds or edits one of a business rules scorer's assertions: its name
/// and its XPath. [XPathAssertionEditorController] shows it and checks what is entered.
public class XPathAssertionEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    TextBox name;
    @UiField
    TextBox xpath;

    /// Makes an empty editor.
    public XPathAssertionEditor() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// @return The name entered, as typed.
    public String getName() {
        return name.getValue();
    }

    /// @param value The assertion's name.
    public void setName(final String value) {
        name.setValue(value);
    }

    /// @return The XPath entered, as typed.
    public String getXpath() {
        return xpath.getValue();
    }

    /// @param value The assertion's XPath.
    public void setXpath(final String value) {
        xpath.setValue(value);
    }

    /// Puts the focus on its first field, the name.
    public void focus() {
        name.setFocus(true);
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        name.setReadOnly(readOnly);
        xpath.setReadOnly(readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, XPathAssertionEditor> {

    }
}
