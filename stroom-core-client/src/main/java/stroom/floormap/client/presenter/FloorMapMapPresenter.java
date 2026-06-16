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
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.floormap.client.event.FloorMapDataEvent;
import stroom.floormap.client.event.MapObjectMovedEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.FloorMapBackground;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.util.shared.TemporalEntry;
import stroom.widget.tab.client.presenter.LinkTabsPresenter;
import stroom.widget.tab.client.presenter.TabData;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Main presenter for the Floor Map visualization.
 * Coordinates the map canvas, the timeline, and the log data view.
 */
public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc> {

    public static final Object MAP = new Object();
    public static final Object TIMELINE = new Object();
    public static final Object LOG_DATA = new Object();

    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);

    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private final FloorMapObjectEditPresenter floorMapObjectEditPresenter;

    private final RestFactory restFactory;
    private final LinkTabsPresenter linkTabsPresenter;

    // Local state for batch saving
    private final List<TemporalEntry> pendingUpdates = new ArrayList<>();

    private long selectedTime;
    private static final long ONE_DAY_MS = 24 * 60 * 60 * 1000;

    @Inject
    public FloorMapMapPresenter(final EventBus eventBus,
                                final FloorMapMapView view,
                                final RestFactory restFactory,
                                final LinkTabsPresenter linkTabsPresenter,
                                final MetaPresenter metaPresenter,
                                final FloorMapTempPresenter floorMapTempPresenter,
                                final Provider<FloorMapObjectEditPresenter> floorMapObjectEditPresenterProvider,
                                final Provider<FloorMapCanvasPresenter> floorMapCanvasPresenterProvider,
                                final Provider<FloorMapTimelinePresenter> floorMapTimelinePresenterProvider) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.linkTabsPresenter = linkTabsPresenter;

        this.floorMapCanvasPresenter = floorMapCanvasPresenterProvider.get();
        this.floorMapTimelinePresenter = floorMapTimelinePresenterProvider.get();
        this.floorMapObjectEditPresenter = floorMapObjectEditPresenterProvider.get();

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
        registerHandler(getEventBus().addHandler(FloorMapDataEvent.getType(), e ->
                floorMapCanvasPresenter.setObjects(e.getObjects())));

        registerHandler(getEventBus().addHandler(MapObjectSelectedEvent.getType(), e -> {
            // Swap to edit menu
            floorMapObjectEditPresenter.setObject(e.getObjectId());
            setInSlot(LOG_DATA, floorMapObjectEditPresenter);
        }));

        registerHandler(getEventBus().addHandler(MapObjectMovedEvent.getType(), e -> {
            // Mark document dirty when an object finishes dragging
            setDirty(true);

            // Format coordinate value as "map3, x, y"
            final String valueStr = "map3, " + e.getX() + ", " + e.getY();
            final TemporalEntry entry = new TemporalEntry(
                    "location_plan_b",
                    e.getObjectId(),
                    selectedTime,
                    valueStr
            );

            pendingUpdates.add(entry);
        }));

        floorMapObjectEditPresenter.setEditStateConsumer(floorMapCanvasPresenter::setIsDraggingEnabled);
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
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc document) {
        // Batch flush all pending updates to the REST API before returning the document.
        if (!pendingUpdates.isEmpty()) {
            for (final TemporalEntry entry : pendingUpdates) {
                // Example REST call
                restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                        .method(res -> res.update(entry))
                        .exec();
            }

            pendingUpdates.clear();
        }
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

    @Override
    protected boolean hasAssociatedDirty() {
        return !pendingUpdates.isEmpty();
    }

    private void updateTimelineRange() {
        // By default, the timeline shows a range 24 hours each side of the current system time.
        final long start = selectedTime - ONE_DAY_MS;
        final long end = selectedTime + ONE_DAY_MS;

        floorMapTimelinePresenter.setTimeRange(start, end);
        floorMapTimelinePresenter.setCurrentTime(selectedTime);
    }

    public void toggleEditMode(final boolean editMode) {
        floorMapCanvasPresenter.setEditMode(editMode);
        if (!editMode) {
            // Revert bottom panel to normal timeline + tabs
            setInSlot(LOG_DATA, linkTabsPresenter);
        }
    }

    public interface FloorMapMapView extends View {

    }
}
