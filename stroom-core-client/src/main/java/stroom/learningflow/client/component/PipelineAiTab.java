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

import stroom.widget.component.client.Component;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/// A pipeline's Learning Status tab, shown when the pipeline, or a parent it inherits from, has a Smart
/// Pipeline element.
///
/// If the pipeline has more than one such element, the tab offers a choice of element ([PipelineAiElement]).
/// Below that it shows the status of the chosen element's learning and the actions available ([FeedDetail]):
/// its learning runs, their progress and conversation, guidance for the AI, and the streams waiting for it.
/// For an element that has not learned anything yet, it shows a message instead.
/// [PipelineAiTabController] fills it.
public class PipelineAiTab extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FlowPanel chooser;
    @UiField
    SelectionBox<PipelineAiElement> element;
    @UiField
    Label nothing;
    @UiField
    SimplePanel detailPanel;
    @UiField
    FeedDetail feedDetail;

    private Consumer<PipelineAiElement> onElement = chosen -> {
    };

    /// Makes the tab, empty.
    public PipelineAiTab() {
        initWidget(BINDER.createAndBindUi(this));
        element.setOnChange(chosen -> onElement.accept(chosen));
    }

    /// @return The panel showing the chosen element's learning, for its controller to attach to.
    public FeedDetail getFeedDetail() {
        return feedDetail;
    }

    /// Sets the pipeline's Smart Pipeline elements. The choice of element is shown only if there is more than
    /// one.
    ///
    /// @param elements The pipeline's Smart Pipeline elements.
    /// @param chosen   The element to show.
    public void setElements(final List<PipelineAiElement> elements, final PipelineAiElement chosen) {
        element.clear();
        element.addItems(elements);
        element.setValue(chosen);
        chooser.setVisible(elements.size() > 1);
    }

    /// Shows the chosen element's learning, hiding any message.
    public void showDetail() {
        nothing.setVisible(false);
        detailPanel.setVisible(true);
    }

    /// Shows a message, in place of the element's learning, explaining why there is nothing to show for the
    /// chosen element, e.g. that it has not learned anything yet.
    ///
    /// @param why The message.
    public void showNothing(final String why) {
        nothing.setText(why);
        nothing.setVisible(true);
        detailPanel.setVisible(false);
    }

    /// @param onElement Called with the element the user chooses.
    public void setOnElement(final Consumer<PipelineAiElement> onElement) {
        this.onElement = onElement;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, PipelineAiTab> {

    }
}
