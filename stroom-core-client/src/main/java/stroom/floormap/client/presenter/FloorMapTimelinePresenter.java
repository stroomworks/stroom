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

import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapTimelinePresenter.FloorMapTimelineView;

import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.function.Consumer;

/**
 * Presenter for the floor map timeline control.
 * Handles time range selection and fires events when the time changes.
 */
public class FloorMapTimelinePresenter extends MyPresenterWidget<FloorMapTimelineView> {
    private long startTime;
    private long endTime;
    private long currentTime;

    @Inject
    public FloorMapTimelinePresenter(final EventBus eventBus,
                                     final FloorMapTimelineView view) {
        super(eventBus, view);

        view.setClickHandler(percentage -> {
            final long duration = endTime - startTime;
            final long newTime = startTime + (long) (duration * (percentage / 100.0));
            setCurrentTime(newTime);

            // Fire a TimeChangeEvent so listeners (like the map) can update
            TimeChangeEvent.fire(this, newTime);
        });
    }

    @Override
    protected void onBind() {
        super.onBind();

        // Update the timeline when the user picks a new START datetime.
        registerHandler(getView().addStartTimeChangeHandler(e -> {
            this.startTime = getView().getStartTime();
            updateProgress();
        }));

        // Update the timeline when the user picks a new END datetime.
        registerHandler(getView().addEndTimeChangeHandler(e -> {
            this.endTime = getView().getEndTime();
            updateProgress();
        }));
    }

    /**
     * Sets the total time range visible on the timeline.
     * @param start Start time in milliseconds.
     * @param end End time in milliseconds.
     */
    public void setTimeRange(final long start, final long end) {
        this.startTime = start;
        this.endTime = end;
        getView().setStartTime(start);
        getView().setEndTime(end);
        updateProgress();
    }

    /**
     * Sets the current selected time on the timeline.
     * @param time The selected time in milliseconds.
     */
    public void setCurrentTime(final long time) {
        this.currentTime = time;
        updateProgress();
    }

    private void updateProgress() {
        if (endTime > startTime) {
            final double percentage = ((double) (currentTime - startTime) / (endTime - startTime)) * 100;
            getView().setProgressPct(percentage);
        }
    }

    public interface FloorMapTimelineView extends View {
        void setProgressPct(double pct);
        void setClickHandler(Consumer<Double> clickHandler);
        void setStartTime(long startTime);
        void setEndTime(long endTime);

        HandlerRegistration addStartTimeChangeHandler(ValueChangeHandler<String> handler);
        HandlerRegistration addEndTimeChangeHandler(ValueChangeHandler<String> handler);
        long getStartTime();
        long getEndTime();
    }
}
