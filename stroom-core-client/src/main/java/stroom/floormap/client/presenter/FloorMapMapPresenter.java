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
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.FloorMapBackground;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.meta.shared.MetaExpressionUtil;
import stroom.widget.tab.client.presenter.LinkTabsPresenter;
import stroom.widget.tab.client.presenter.TabData;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.Arrays;
import java.util.Objects;

/**
 * Main presenter for the Floor Map visualization.
 * Coordinates the map canvas, the timeline, and the log data view.
 */
public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc> {

    public static final Object MAP = new Object();
    public static final Object TIMELINE = new Object();
    public static final Object LOG_DATA = new Object();

    private final MetaPresenter metaPresenter;
    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private long selectedTime;
    private static final long ONE_DAY_MS = 24 * 60 * 60 * 1000;

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
        this.floorMapCanvasPresenter = floorMapCanvasPresenterProvider.get();
        this.floorMapTimelinePresenter = floorMapTimelinePresenterProvider.get();

        // Default initial time
        this.selectedTime = System.currentTimeMillis();

        final TabData dataTab = linkTabsPresenter.addTab("Data", metaPresenter);
        linkTabsPresenter.addTab("Temp", floorMapTempPresenter);
        linkTabsPresenter.changeSelectedTab(dataTab);

        setInSlot(LOG_DATA, linkTabsPresenter);
        setInSlot(MAP, floorMapCanvasPresenter);
        setInSlot(TIMELINE, floorMapTimelinePresenter);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(getEventBus().addHandler(TimeChangeEvent.getType(), e -> onTimeChange(e.getTime())));
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {
        updateTimelineRange();
        
        // Pick the background for the current time
        final FloorMapBackground activeBg = document.getActiveBackground(selectedTime);
        if (activeBg != null) {
            floorMapCanvasPresenter.setBackgroundImage(activeBg.getImage());
            floorMapCanvasPresenter.setMatrix(activeBg.getMatrix());
        } else {
            floorMapCanvasPresenter.setBackgroundImage(null);
            floorMapCanvasPresenter.setMatrix(FloorMapTransformationMatrix.identity());
        }

        // TEST DATA
        // TODO: REMOVE
        floorMapCanvasPresenter.setObjects(Arrays.asList(
                new FloorMapObject("Main Entrance", 100, 150),
                new FloorMapObject("Access Point 1", 500, 375)
        ));

        if (!Objects.equals(currentFeed, document.getFeed())) {
            currentFeed = document.getFeed();
            if (currentFeed != null) {
                metaPresenter.getCriteria().setExpression(MetaExpressionUtil.createFeedExpression(currentFeed));
                metaPresenter.refresh();
            }
        }
    }

    private void updateTimelineRange() {
        // By default, the timeline shows a range 24 hours each side of the current system time.
        final long start = selectedTime - ONE_DAY_MS;
        final long end = selectedTime + ONE_DAY_MS;

        floorMapTimelinePresenter.setTimeRange(start, end);
        floorMapTimelinePresenter.setCurrentTime(selectedTime);
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc document) {
        return document;
    }

    private void onTimeChange(final long time) {
        this.selectedTime = time;

        final FloorMapBackground activeBg = getEntity().getActiveBackground(time);
        if (activeBg != null) {
            floorMapCanvasPresenter.setBackgroundImage(activeBg.getImage());
            floorMapCanvasPresenter.setMatrix(activeBg.getMatrix());
        } else {
            floorMapCanvasPresenter.setBackgroundImage(null);
            floorMapCanvasPresenter.setMatrix(FloorMapTransformationMatrix.identity());
        }
    }

    public interface FloorMapMapView extends View {

    }
}
