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

import stroom.content.client.component.ContentTabController;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.widget.util.client.HasRefresh;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

/// The Smart Pipelines screen, a content tab opened from the Monitoring menu, that lists every Smart Pipeline
/// of every Learning Flow the user may read, with its current status.
///
/// It uses the same list as a Learning Flow's Pipelines tab ([FeedListController]), but across all Learning
/// Flows, giving an administrator one view of them all. Each row names its Learning Flow and says why a
/// pipeline has stopped or what is waiting. Opening a row opens the pipeline at its Learning Status tab, where
/// its learning can be followed and acted on, subject to the pipeline's permissions. This screen itself takes
/// no actions on a pipeline's learning.
///
/// The screen consists of the list alone, so it shows the list's widget as its own.
public class SupervisorController extends ContentTabController implements HasRefresh {

    /// The tab's type. It keeps the name from when this screen was called the Supervisor, so that a tab left
    /// open in a saved tab session from an earlier version is still opened again.
    public static final String TAB_TYPE = "LearningFlowSupervisor";

    private final FeedListController feedList;

    /// Made by GIN.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The list of pipelines.
    /// @param feedList The list's controller.
    @Inject
    public SupervisorController(final EventBus eventBus,
                                final FeedList view,
                                final FeedListController feedList) {
        super(eventBus);
        this.feedList = own(feedList).attach(view);
        initWidget(this.feedList.asWidget());
        feedList.showEveryLearningFlow();
    }

    /// Reads the pipelines again when Stroom refreshes the tab, unless the user has turned auto refresh off.
    @Override
    public void refresh() {
        if (feedList.isAutoRefresh()) {
            feedList.refresh();
        }
    }

    @Override
    public SvgImage getIcon() {
        return SvgImage.AI;
    }

    @Override
    public IconColour getIconColour() {
        return IconColour.GREY;
    }

    @Override
    public String getLabel() {
        return "Smart Pipelines";
    }

    @Override
    public String getType() {
        return TAB_TYPE;
    }
}
