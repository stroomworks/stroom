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

/// A pipeline's AI Translation tab (design 07), shown where the pipeline, or a parent it inherits from, has a
/// Learning Flow element: which element, where it has more than one ([PipelineAiElement]); then where that
/// element's feed stands and what can be done about it ([FeedDetail]): its learning runs, their progress and
/// conversation, a note to the AI, and the streams waiting for it. An element that hasn't learned anything
/// yet says so instead. [PipelineAiTabController] fills it.
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

    /// @return Where the element chosen stands, which its controller attaches to.
    public FeedDetail getFeedDetail() {
        return feedDetail;
    }

    /// The pipeline's AI elements: the choice of them is offered only where there is more than one.
    ///
    /// @param elements Its AI elements.
    /// @param chosen   The one shown.
    public void setElements(final List<PipelineAiElement> elements, final PipelineAiElement chosen) {
        element.clear();
        element.addItems(elements);
        element.setValue(chosen);
        chooser.setVisible(elements.size() > 1);
    }

    /// Shows where the element chosen stands.
    public void showDetail() {
        nothing.setVisible(false);
        detailPanel.setVisible(true);
    }

    /// Says why there is nothing to show for the element chosen, e.g. that it hasn't learned anything yet.
    ///
    /// @param why What to say.
    public void showNothing(final String why) {
        nothing.setText(why);
        nothing.setVisible(true);
        detailPanel.setVisible(false);
    }

    /// @param onElement Told the element the user chooses.
    public void setOnElement(final Consumer<PipelineAiElement> onElement) {
        this.onElement = onElement;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, PipelineAiTab> {

    }
}
