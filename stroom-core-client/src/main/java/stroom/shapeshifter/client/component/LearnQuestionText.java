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

import stroom.widget.button.client.Button;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.function.Consumer;

/// One text the node selected on the Learn tab's chart is asked with (A85, A86): its name, where its
/// words come from, the words themselves to edit, and a button to load a built-in's in their place.
/// [LearnTab] shows one for each of the node's texts.
public class LearnQuestionText extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label heading;
    @UiField
    Label status;
    @UiField
    TextArea text;
    @UiField
    Button load;

    private Consumer<String> onEdit = value -> {
    };
    private Runnable onLoad = () -> {
    };

    /// Makes an empty text.
    public LearnQuestionText() {
        initWidget(BINDER.createAndBindUi(this));
        text.addValueChangeHandler(event -> onEdit.accept(event.getValue()));
        load.setOnClick(() -> {
            if (!isReadOnly()) {
                onLoad.run();
            }
        });
    }

    /// @param name The text's name, e.g. "System text".
    public void setName(final String name) {
        heading.setText(name);
        text.setAriaLabel(name);
    }

    /// @param words  The words the text has, the document's own or the built-in's.
    /// @param source Where they come from.
    public void setWords(final String words, final String source) {
        text.setValue(words);
        status.setText(source);
    }

    /// @param variables What the words may use, shown as the text's tooltip.
    public void setVariables(final String variables) {
        text.setTitle(variables);
    }

    /// @param onEdit Told the words when the user edits them.
    public void setOnEdit(final Consumer<String> onEdit) {
        this.onEdit = onEdit;
    }

    /// @param onLoad Run when Load built-in is pressed (not when read only).
    public void setOnLoad(final Runnable onLoad) {
        this.onLoad = onLoad;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        text.setReadOnly(readOnly);
        load.setEnabled(!readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, LearnQuestionText> {

    }
}
