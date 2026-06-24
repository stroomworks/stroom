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
import stroom.svg.client.Preset;
import stroom.svg.shared.SvgImage;
import stroom.widget.datepicker.client.UTCDate;

import com.google.gwt.animation.client.AnimationScheduler;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Presenter for the floor map timeline control. Handles time range selection and fires events when the time changes.
 * Provides a timeline bar with a play/pause button, progress scrubber, date labels and a settings icon that opens a
 * popup for date range and speed.
 */
public class FloorMapTimelinePresenter extends MyPresenterWidget<FloorMapTimelineView> {

    private static final Preset PLAY_PRESET = new Preset(SvgImage.PLAY, "Play", true);
    private static final Preset PAUSE_PRESET = new Preset(SvgImage.PAUSE, "Pause", true);
    private static final Preset SETTINGS_PRESET = new Preset(SvgImage.SETTINGS, "Playback Settings", true);
    private static final double SPEED_MULTIPLIER = 1000.0;

    private final FloorMapTimelineSettingsPresenter settingsPresenter;

    private long startTime;
    private long endTime;
    private long currentTime;

    private boolean playing;
    private double playbackSpeed;
    private double lastFrameTime;

    /** Optional callback fired when the user changes the visible time range via the settings popup. */
    private Runnable timeRangeChangeHandler;

    @Inject
    public FloorMapTimelinePresenter(final EventBus eventBus,
                                     final FloorMapTimelineView view,
                                     final FloorMapTimelineSettingsPresenter settingsPresenter) {
        super(eventBus, view);
        this.settingsPresenter = settingsPresenter;

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

        // Forward date changes from the settings popup back to the timeline.
        //noinspection unused e
        registerHandler(settingsPresenter.addStartTimeChangeHandler(e -> {
            this.startTime = settingsPresenter.getStartTime();
            updateProgress();
            updateDateLabels();
            if (timeRangeChangeHandler != null) {
                timeRangeChangeHandler.run();
            }
        }));

        //noinspection unused e
        registerHandler(settingsPresenter.addEndTimeChangeHandler(e -> {
            this.endTime = settingsPresenter.getEndTime();
            updateProgress();
            updateDateLabels();
            if (timeRangeChangeHandler != null) {
                timeRangeChangeHandler.run();
            }
        }));

        getView().setPlayPauseHandler(() -> {
            playing = !playing;
            if (playing) {
                getView().setPlayPausePreset(PAUSE_PRESET);
                lastFrameTime = 0;
                AnimationScheduler.get().requestAnimationFrame(playbackCallback);
            } else {
                getView().setPlayPausePreset(PLAY_PRESET);
            }
        });

        // Settings button opens the popup anchored above the settings icon.
        getView().setSettingsHandler(() -> settingsPresenter.show(getView().getSettingsButtonWidget()));

        settingsPresenter.setSpeedChangeHandler(speed -> this.playbackSpeed = speed);

        getView().setPlayPausePreset(PLAY_PRESET);
        getView().setSettingsPreset(SETTINGS_PRESET);

        settingsPresenter.setSpeedOptions(Arrays.asList(0.5, 1.0, 2.0, 5.0, 10.0));
        settingsPresenter.setSelectedSpeed(1.0);
        this.playbackSpeed = 1.0;
    }

    /**
     * Sets the total time range visible on the timeline.
     *
     * @param start Start time in milliseconds.
     * @param end   End time in milliseconds.
     */
    public void setTimeRange(final long start, final long end) {
        this.startTime = start;
        this.endTime = end;
        settingsPresenter.setStartTime(start);
        settingsPresenter.setEndTime(end);
        updateProgress();
        updateDateLabels();
    }

    /**
     * Sets the current selected time on the timeline.
     *
     * @param time The selected time in milliseconds.
     */
    public void setCurrentTime(final long time) {
        this.currentTime = time;
        updateProgress();
    }

    /**
     * Registers a callback to be invoked when the user changes the visible time range
     * via the settings popup. Used by {@code FloorMapMapPresenter} to re-run the histogram
     * query over the new range.
     *
     * @param handler Called whenever start or end time changes.
     */
    public void setTimeRangeChangeHandler(final Runnable handler) {
        this.timeRangeChangeHandler = handler;
    }

    /** @return The current timeline start time in milliseconds. */
    public long getStartTime() {
        return startTime;
    }

    /** @return The current timeline end time in milliseconds. */
    public long getEndTime() {
        return endTime;
    }

    private void updateProgress() {
        if (endTime > startTime) {
            final double percentage = ((double) (currentTime - startTime) / (endTime - startTime)) * 100;
            getView().setProgressPct(percentage);
        }
    }

    private void updateDateLabels() {
        getView().setStartDateLabel(formatTime(startTime));
        getView().setEndDateLabel(formatTime(endTime));
    }

    /**
     * Formats a millisecond timestamp as a short display string for the timeline labels.
     */
    private String formatTime(final long millis) {
        if (millis <= 0) {
            return "";
        }
        // Use GWT's UTCDate to build an ISO-style string without needing DateTimeFormat.
        final UTCDate date = UTCDate.create(millis);
        if (date == null) {
            return "";
        }
        // Build "yyyy-MM-dd HH:mm" style
        final int year = date.getFullYear();
        final int month = date.getMonth() + 1; // 0-indexed
        final int day = date.getDate();
        final int hour = date.getHours();
        final int min = date.getMinutes();
        return pad4(year) + "-" + pad2(month) + "-" + pad2(day) + " " + pad2(hour) + ":" + pad2(min);
    }

    private static String pad2(final int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static String pad4(final int value) {
        if (value < 10) {
            return "000" + value;
        } else if (value < 100) {
            return "00" + value;
        } else if (value < 1000) {
            return "0" + value;
        }
        return String.valueOf(value);
    }

    private final AnimationScheduler.AnimationCallback playbackCallback = new AnimationScheduler.AnimationCallback() {
        @Override
        public void execute(final double timestamp) {
            if (playing) {
                if (lastFrameTime > 0) {
                    final double delta = timestamp - lastFrameTime;
                    long newTime = currentTime + (long) (delta * playbackSpeed * SPEED_MULTIPLIER);

                    if (newTime > endTime) {
                        newTime = startTime;
                    }

                    setCurrentTime(newTime);
                    TimeChangeEvent.fire(FloorMapTimelinePresenter.this, newTime);
                }

                lastFrameTime = timestamp;
                AnimationScheduler.get().requestAnimationFrame(this);
            } else {
                lastFrameTime = 0;
            }
        }
    };

    /**
     * Provides histogram bin counts to be displayed above the scrubber on hover.
     *
     * @param binCounts  Array of event counts per time bucket.
     */
    public void setHistogramData(final int[] binCounts) {
        getView().setHistogramData(binCounts);
    }

    public interface FloorMapTimelineView extends View {

        void setProgressPct(double pct);

        void setClickHandler(Consumer<Double> clickHandler);

        /**
         * Set the text label shown at the left end of the timeline bar (start date).
         */
        void setStartDateLabel(String text);

        /**
         * Set the text label shown at the right end of the timeline bar (end date).
         */
        void setEndDateLabel(String text);

        void setPlayPausePreset(Preset preset);

        void setPlayPauseHandler(Runnable handler);

        /**
         * Set the icon/title for the settings gear button.
         */
        void setSettingsPreset(Preset preset);

        /**
         * Set the click handler for the settings gear button.
         */
        void setSettingsHandler(Runnable handler);

        /**
         * Returns the settings button widget so the popup can be anchored to it.
         */
        Widget getSettingsButtonWidget();

        /**
         * Provides histogram data (event counts per bin) for the hover pop-up.
         * An empty or null array clears the histogram.
         */
        void setHistogramData(int[] binCounts);
    }
}
