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
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// Shows the step of the learning flow selected on the Setup tab's route map ([RouteMap]): its card
/// ([StepCard]), or, for the fallback step, which has no card, a description of it. Beneath it are the question
/// texts the step is asked with, or, at the user's choice, the texts every step shares. The default setups,
/// and what is wrong with the flow as a whole, are shown elsewhere under Learning steps on [SetupTab].
/// [ApproachSectionController] fills it.
public class ApproachSection extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    // Styles the question texts when they sit under the fallback's heading, whose own rule line separates them.
    // It is a class rather than a ":empty ~" CSS rule because Chromium keeps a reference to every element such
    // a rule has matched, including those of closed screens, which leaks memory.
    private static final String TEXTS_UNDER_HEADING = "approach-section__texts--under-heading";

    @UiField
    FlowPanel cards;
    @UiField
    HTMLPanel fallback;
    @UiField
    FlowPanel textsPart;
    @UiField
    Label textsHeading;
    @UiField
    LinkButton textsToggle;
    @UiField
    FlowPanel texts;

    private final Map<Template, LearnQuestionText> slots = new LinkedHashMap<>();
    private Runnable onTextsToggle = () -> {
    };
    private BiConsumer<Template, String> onTextEdit = (template, text) -> {
    };
    private Consumer<Template> onLoadBuiltIn = template -> {
    };

    /// Creates the section, showing no step.
    public ApproachSection() {
        initWidget(BINDER.createAndBindUi(this));
        textsToggle.setOnClick(() -> onTextsToggle.run());
    }

    /// Removes the step shown, so that another can be shown.
    public void clearStep() {
        cards.clear();
        fallback.setVisible(false);
        textsPart.removeStyleName(TEXTS_UNDER_HEADING);
    }

    /// Adds an empty card for a step.
    ///
    /// @return The card, for the controller to fill.
    public StepCard showCard() {
        final StepCard card = new StepCard();
        card.setReadOnly(isReadOnly());
        cards.add(card);
        return card;
    }

    /// Shows the description of the fallback step, which has no card.
    public void showFallback() {
        fallback.setVisible(true);
        textsPart.addStyleName(TEXTS_UNDER_HEADING);
    }

    /// Sets the question texts shown beneath the step, each in its own text box.
    ///
    /// @param heading   The heading above the texts, saying whose texts they are.
    /// @param toggle    The text of the link beside the heading, which switches between the step's texts and
    ///                  the texts every step shares.
    /// @param templates The texts to show.
    public void setTexts(final String heading, final String toggle, final List<Template> templates) {
        textsHeading.setText(heading);
        textsToggle.setText(toggle);
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

    /// Shows the words of one of the question texts shown, and where they come from. Does nothing if that
    /// text isn't shown.
    ///
    /// @param template The question text.
    /// @param words    Its words.
    /// @param source   A status line saying where the words come from.
    public void showText(final Template template, final String words, final String source) {
        final LearnQuestionText slot = slots.get(template);
        if (slot != null) {
            slot.setWords(words, source);
        }
    }

    /// @param onTextsToggle Run when the user presses the link to switch to the other question texts.
    public void setOnTextsToggle(final Runnable onTextsToggle) {
        this.onTextsToggle = onTextsToggle;
    }

    /// @param onTextEdit Called with a question text and its words when the user edits them.
    public void setOnTextEdit(final BiConsumer<Template, String> onTextEdit) {
        this.onTextEdit = onTextEdit;
    }

    /// @param onLoadBuiltIn Called with the question text that a built-in text is to be loaded into.
    public void setOnLoadBuiltIn(final Consumer<Template> onLoadBuiltIn) {
        this.onLoadBuiltIn = onLoadBuiltIn;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        for (int i = 0; i < cards.getWidgetCount(); i++) {
            if (cards.getWidget(i) instanceof StepCard) {
                ((StepCard) cards.getWidget(i)).setReadOnly(readOnly);
            }
        }
        slots.values().forEach(slot -> slot.setReadOnly(readOnly));
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, ApproachSection> {

    }
}
