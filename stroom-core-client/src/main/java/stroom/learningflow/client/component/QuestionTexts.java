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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.Template;
import stroom.widget.component.client.Component;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// A list of a Learning Flow's question texts (A85, A86), each in its own place ([LearnQuestionText]): its name,
/// where its words come from, the words to edit, and Load built-in. On the Setup tab it shows the texts of the
/// step chosen ([ApproachSection]), and, under Learning steps' Advanced options ([SetupTab]), the texts every
/// step shares. [ApproachSectionController] fills both, as it holds the document's texts.
public class QuestionTexts extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FlowPanel texts;

    private final Map<Template, LearnQuestionText> slots = new LinkedHashMap<>();
    private BiConsumer<Template, String> onTextEdit = (template, text) -> {
    };
    private Consumer<Template> onLoadBuiltIn = template -> {
    };

    /// Makes the list, with no texts.
    public QuestionTexts() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// The question texts shown, each in its own place, in place of any shown before.
    ///
    /// @param templates The texts, in order.
    public void setTexts(final List<Template> templates) {
        slots.clear();
        texts.clear();
        for (final Template template : templates) {
            final LearnQuestionText slot = new LearnQuestionText();
            slot.setName(template.getDisplayValue());
            slot.setVariables(LearnFlow.variablesTitle(template));
            slot.setReadOnly(isReadOnly());
            slot.setOnEdit(words -> onTextEdit.accept(template, words));
            slot.setOnLoad(() -> onLoadBuiltIn.accept(template));
            slots.put(template, slot);
            texts.add(slot);
        }
    }

    /// @return The question texts shown, in order.
    public List<Template> getTexts() {
        return new ArrayList<>(slots.keySet());
    }

    /// Shows the words of one of the question texts shown, and where they come from; nothing where it isn't
    /// shown.
    ///
    /// @param template The text.
    /// @param words    Its words.
    /// @param source   Where they come from.
    public void showText(final Template template, final String words, final String source) {
        final LearnQuestionText slot = slots.get(template);
        if (slot != null) {
            slot.setWords(words, source);
        }
    }

    /// @param onTextEdit Told a question text and its words when the user edits them.
    public void setOnTextEdit(final BiConsumer<Template, String> onTextEdit) {
        this.onTextEdit = onTextEdit;
    }

    /// @param onLoadBuiltIn Told the question text a built-in is to be loaded into.
    public void setOnLoadBuiltIn(final Consumer<Template> onLoadBuiltIn) {
        this.onLoadBuiltIn = onLoadBuiltIn;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        slots.values().forEach(slot -> slot.setReadOnly(readOnly));
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, QuestionTexts> {

    }
}
