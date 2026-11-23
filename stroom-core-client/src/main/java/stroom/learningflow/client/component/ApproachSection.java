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

/// The step of the learning flow chosen on the Setup tab's route map ([RouteMap]; design 07; design 05): its
/// card ([StepCard]); or, for the fallback, which has no card, what it is.
/// Beneath, the question text the step is asked with, or the text every step shares (A85, A86). The starting
/// points, and what is wrong with the flow as a whole, are under Learning steps on [SetupTab].
/// [ApproachSectionController] fills it.
public class ApproachSection extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    // The question text under the fallback's heading, whose own line sets it off. A class, not a
    // ":empty ~" rule: Chromium keeps every element such a rule has matched, closed screens and all.
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

    /// Makes the section, showing no step.
    public ApproachSection() {
        initWidget(BINDER.createAndBindUi(this));
        textsToggle.setOnClick(() -> onTextsToggle.run());
    }

    /// Removes the step shown, for another to be shown.
    public void clearStep() {
        cards.clear();
        fallback.setVisible(false);
        textsPart.removeStyleName(TEXTS_UNDER_HEADING);
    }

    /// Shows a step's card.
    ///
    /// @return The card, for its controller to fill.
    public StepCard showCard() {
        final StepCard card = new StepCard();
        card.setReadOnly(isReadOnly());
        cards.add(card);
        return card;
    }

    /// Shows the fallback, which has no card.
    public void showFallback() {
        fallback.setVisible(true);
        textsPart.addStyleName(TEXTS_UNDER_HEADING);
    }

    /// The question texts shown beneath the step, each in its own place.
    ///
    /// @param heading   What they are the text of.
    /// @param toggle    What the link beside the heading shows instead.
    /// @param templates The texts.
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

    /// Shows the words of one of the question texts shown, and where they come from.
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

    /// @param onTextsToggle Run when the user asks for the other question texts.
    public void setOnTextsToggle(final Runnable onTextsToggle) {
        this.onTextsToggle = onTextsToggle;
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
