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
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// The step of the learning flow chosen on the Setup tab's route map ([RouteMap]; design 07; design 05): its
/// card ([StepCard]); or, for the fallback, which has no card, what it is.
/// Beneath, the question text the step is asked with ([QuestionTexts]; A85, A86). The text every step shares,
/// the starting points, and what is wrong with the flow as a whole are under Learning steps on [SetupTab].
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
    QuestionTexts texts;

    /// Makes the section, showing no step.
    public ApproachSection() {
        initWidget(BINDER.createAndBindUi(this));
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

    /// The question texts the step is asked with, shown beneath it, each in its own place.
    ///
    /// @param heading   What they are the text of, e.g. "Question text of step 1: Choose the elements".
    /// @param templates The texts.
    public void setTexts(final String heading, final List<Template> templates) {
        textsHeading.setText(heading);
        texts.setTexts(templates);
    }

    /// @return The question texts shown, in order.
    public List<Template> getTexts() {
        return texts.getTexts();
    }

    /// Shows the words of one of the question texts shown, and where they come from.
    ///
    /// @param template The text.
    /// @param words    Its words.
    /// @param source   Where they come from.
    public void showText(final Template template, final String words, final String source) {
        texts.showText(template, words, source);
    }

    /// @param onTextEdit Told a question text and its words when the user edits them.
    public void setOnTextEdit(final BiConsumer<Template, String> onTextEdit) {
        texts.setOnTextEdit(onTextEdit);
    }

    /// @param onLoadBuiltIn Told the question text a built-in is to be loaded into.
    public void setOnLoadBuiltIn(final Consumer<Template> onLoadBuiltIn) {
        texts.setOnLoadBuiltIn(onLoadBuiltIn);
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        for (int i = 0; i < cards.getWidgetCount(); i++) {
            if (cards.getWidget(i) instanceof StepCard) {
                ((StepCard) cards.getWidget(i)).setReadOnly(readOnly);
            }
        }
        texts.setReadOnly(readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, ApproachSection> {

    }
}
