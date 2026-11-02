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

import stroom.shapeshifter.shared.Template;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.selection.client.SelectionBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/// The content of the dialog that loads a built-in text into one of a step's texts (design 05 §3):
/// the built-ins to choose from, and the words of the one chosen, shown before they are loaded.
/// [BuiltInTextController] shows it.
public class BuiltInText extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SelectionBox<Template> choice;
    @UiField
    TextArea preview;

    private Consumer<Template> onChoice = template -> {
    };

    /// Makes an empty dialog content.
    public BuiltInText() {
        initWidget(BINDER.createAndBindUi(this));
        choice.setOnChange(template -> onChoice.accept(template));
    }

    /// @param templates The built-ins that may be chosen, in the order they are offered.
    public void setChoices(final List<Template> templates) {
        choice.clear();
        choice.addItems(templates);
    }

    /// @return The built-in chosen, or null for none.
    public Template getChoice() {
        return choice.getValue();
    }

    /// @param template The built-in to show as chosen.
    public void setChoice(final Template template) {
        choice.setValue(template);
    }

    /// @param text The words of the built-in chosen, shown and not editable.
    public void setPreview(final String text) {
        preview.setValue(text);
    }

    /// @param onChoice Told the built-in the user chooses.
    public void setOnChoice(final Consumer<Template> onChoice) {
        this.onChoice = onChoice;
    }

    /// Puts the focus on the choice.
    public void focus() {
        choice.focus();
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, BuiltInText> {

    }
}
