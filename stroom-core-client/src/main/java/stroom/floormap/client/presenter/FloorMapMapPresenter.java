/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.floormap.client.presenter;

import stroom.data.client.presenter.MetaPresenter;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.meta.shared.MetaExpressionUtil;
import stroom.widget.tab.client.presenter.LinkTabsPresenter;
import stroom.widget.tab.client.presenter.TabData;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.Objects;

/**
 * Main presenter for the Floor Map visualization.
 * Coordinates the map canvas, the timeline, and the log data view.
 */
public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc> {

    /**
     * Slot for the SVG map canvas.
     */
    public static final Object MAP = new Object();
    /**
     * Slot for the timeline control.
     */
    public static final Object TIMELINE = new Object();
    /**
     * Slot for the log data/tabs view.
     */
    public static final Object LOG_DATA = new Object();

    private final MetaPresenter metaPresenter;
    private final FloorMapCanvasPresenter floorMapCanvasPresenter;

    private DocRef currentFeed;

    @Inject
    public FloorMapMapPresenter(final EventBus eventBus,
                                final FloorMapMapView view,
                                final LinkTabsPresenter linkTabsPresenter,
                                final MetaPresenter metaPresenter,
                                final FloorMapTempPresenter floorMapTempPresenter,
                                final Provider<FloorMapCanvasPresenter> floorMapCanvasPresenterProvider,
                                final Provider<FloorMapTimelinePresenter> floorMapTimelinePresenterProvider) {
        super(eventBus, view);
        this.metaPresenter = metaPresenter;
        
        // Initialize the canvas presenter which handles the SVG map rendering
        this.floorMapCanvasPresenter = floorMapCanvasPresenterProvider.get();

        // Initialize the timeline presenter
        final FloorMapTimelinePresenter floorMapTimelinePresenter = floorMapTimelinePresenterProvider.get();

        // Set a default range for now (last 24 hours)
        // TODO: Let the user choose the displayed time range.
        final long now = System.currentTimeMillis();
        floorMapTimelinePresenter.setTimeRange(now - (24 * 60 * 60 * 1000), now);
        floorMapTimelinePresenter.setCurrentTime(now);

        final TabData dataTab = linkTabsPresenter.addTab("Data", metaPresenter);
        linkTabsPresenter.addTab("Temp", floorMapTempPresenter);
        linkTabsPresenter.changeSelectedTab(dataTab);

        setInSlot(LOG_DATA, linkTabsPresenter);
        setInSlot(MAP, floorMapCanvasPresenter);
        setInSlot(TIMELINE, floorMapTimelinePresenter);
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {
        // Pass the background image from the document to the canvas presenter
        floorMapCanvasPresenter.setBackgroundImage(document.getBackgroundImage());

        // Update the log data view based on the selected feed
        if (!Objects.equals(currentFeed, document.getFeed())) {
            currentFeed = document.getFeed();
            if (currentFeed != null) {
                metaPresenter.getCriteria().setExpression(MetaExpressionUtil.createFeedExpression(currentFeed));
                metaPresenter.refresh();
            }
        }
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc document) {
        return document;
    }

    public interface FloorMapMapView extends View {

    }
}
