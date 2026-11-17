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

import stroom.content.client.component.ContentTabController;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.widget.util.client.HasRefresh;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

/// The Smart Pipelines screen (A28), a content tab opened from the Monitoring menu: every Smart Pipeline of
/// every Learning Flow the user may read, with where each stands, as a Learning Flow's Pipelines tab lists its
/// own ([FeedListController]). It is an Admin's one view of them all: each row names its Learning Flow, says
/// why a pipeline stopped or what waits, and opens the pipeline at its Learning Status tab, where its learning is
/// followed and acted on, by the pipeline's permissions. Nothing is done to a pipeline's learning here.
///
/// The screen is the list alone, so it shows the list's widget as its own.
public class SupervisorController extends ContentTabController implements HasRefresh {

    /// The tab's type, kept from when the screen was the Supervisor, so that a tab left open is opened again.
    public static final String TAB_TYPE = "ShapeshifterAiSupervisor";

    private final FeedListController feedList;

    /// Made by GIN, with its list.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The list.
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

    /// Reads the pipelines again, as Stroom refreshes the tab it is shown in, unless auto refresh is off.
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
