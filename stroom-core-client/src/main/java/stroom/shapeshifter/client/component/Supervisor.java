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
import stroom.widget.component.client.LayerContainer;
import stroom.widget.tab.client.LinkTabBar;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// The Shapeshifter AI Supervisor (A28, design 06 A115): three levels, master to detail. The learning
/// pipelines above; beneath them the selected pipeline's sessions, and beside them on a second tab the
/// streams waiting on it; and beneath those the selected session's conversation, turn by turn, with the
/// whole of it on a second tab, beside its path drawn over the flow (A88).
public class Supervisor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    LearnerList learnerList;
    @UiField
    LinkTabBar tabBar;
    @UiField
    LayerContainer layerContainer;
    @UiField
    LinkTabBar conversationTabBar;
    @UiField
    LayerContainer conversationLayerContainer;
    @UiField
    FlowChart flowChart;

    // Shown as layers beneath the conversation's tabs, so made here rather than placed by the template
    private final TurnList turnList = new TurnList();
    private final TurnTranscript transcript = new TurnTranscript();

    /// Makes the screen, with its lists empty until their controllers fill them and nothing drawn.
    public Supervisor() {
        initWidget(BINDER.createAndBindUi(this));
        tabBar.setTabPanel(layerContainer.getElement());
        conversationTabBar.setTabPanel(conversationLayerContainer.getElement());
        // The chart beside the transcript is narrower than the drawing, so the drawing is scaled down to it.
        flowChart.fitToPane();
    }

    /// @return The learning pipelines, which its controller attaches the list's controller to.
    public LearnerList getLearnerList() {
        return learnerList;
    }

    /// @return The tab bar beneath the pipelines: Sessions and Waiting streams.
    public LinkTabBar getTabBar() {
        return tabBar;
    }

    /// @return Where the selected tab's list is shown.
    public LayerContainer getLayerContainer() {
        return layerContainer;
    }

    /// @return The tab bar beside the chart: the turns, and the whole conversation.
    public LinkTabBar getConversationTabBar() {
        return conversationTabBar;
    }

    /// @return Where the selected conversation tab is shown.
    public LayerContainer getConversationLayerContainer() {
        return conversationLayerContainer;
    }

    /// @return The selected session's turns, which its controller attaches the list's controller to.
    public TurnList getTurnList() {
        return turnList;
    }

    /// @return The selected session's whole conversation, turn after turn (A116).
    public TurnTranscript getTranscript() {
        return transcript;
    }

    /// @return The document's flow drawn beside the turns, with the session's path over it (A88).
    public FlowChart getFlowChart() {
        return flowChart;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, Supervisor> {

    }
}
