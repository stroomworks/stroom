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
 * Provides a timeline bar with step-back/play-pause/step-forward buttons, a progress scrubber, date labels,
 * a speed badge, and a settings icon that opens a popup for date range, speed and loop options.
 */
public class FloorMapTimelinePresenter extends MyPresenterWidget<FloorMapTimelineView> {

    private static final Preset PLAY_PRESET = new Preset(SvgImage.PLAY, "Play", true);
    private static final Preset PAUSE_PRESET = new Preset(SvgImage.PAUSE, "Pause", true);
    private static final Preset SETTINGS_PRESET = new Preset(SvgImage.SETTINGS, "Playback Settings", true);
    private static final Preset STEP_BACK_PRESET = new Preset(SvgImage.STEP_BACKWARD, "Step Back", true);
    private static final Preset STEP_FORWARD_PRESET = new Preset(SvgImage.STEP_FORWARD, "Step Forward", true);
    private static final double SPEED_MULTIPLIER = 1000.0;
    /**
     * Minimum wall-clock interval (ms) between data query fires during playback.
     * The visual position updates every animation frame; queries are throttled to this rate
     * so the server is not overwhelmed at high playback speeds.
     */
    private static final double PLAYBACK_QUERY_INTERVAL_MS = 300.0;

    private final FloorMapTimelineSettingsPresenter settingsPresenter;

    private long startTime;
    private long endTime;
    private long currentTime;

    /** Earliest timestamp observed in histogram data — used by Show All. */
    private long dataRangeMin = Long.MAX_VALUE;
    /** Latest timestamp observed in histogram data — used by Show All. */
    private long dataRangeMax = Long.MIN_VALUE;

    private boolean playing;
    private double playbackSpeed;
    private double lastFrameTime;
    /** Wall-clock timestamp (ms) of the most recent playback data query, used for throttling. */
    private double lastQueryWallClockTime;

    /** Optional callback fired when the user changes the visible time range via the settings popup. */
    private Runnable timeRangeChangeHandler;

    @Inject
    public FloorMapTimelinePresenter(final EventBus eventBus,
                                     final FloorMapTimelineView view,
                                     final FloorMapTimelineSettingsPresenter settingsPresenter) {
        super(eventBus, view);
        this.settingsPresenter = settingsPresenter;

        view.setScrubHandler(percentage -> {
            // Visual-only update during drag — no data query fired.
            final long duration = endTime - startTime;
            final long newTime = startTime + (long) (duration * (percentage / 100.0));
            setCurrentTime(newTime);
            view.setScrubTooltip(formatTime(newTime));
        });

        view.setCommitHandler(percentage -> {
            // User released the scrubber: commit the position and fire a data query.
            final long duration = endTime - startTime;
            final long newTime = startTime + (long) (duration * (percentage / 100.0));
            setCurrentTime(newTime);
            TimeChangeEvent.fire(this, newTime);
        });
    }

    @Override
    protected void onBind() {
        super.onBind();

        // Forward date changes from the settings popup back to the timeline.
        registerHandler(settingsPresenter.addStartTimeChangeHandler(e -> {
            this.startTime = settingsPresenter.getStartTime();
            updateProgress();
            updateDateLabels();
            if (timeRangeChangeHandler != null) {
                timeRangeChangeHandler.run();
            }
        }));

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

        // Step-back: jump one histogram bin backward.
        getView().setStepBackHandler(() -> stepBy(-1));

        // Step-forward: jump one histogram bin forward.
        getView().setStepForwardHandler(() -> stepBy(1));

        // Settings button opens the popup anchored above the settings icon.
        getView().setSettingsHandler(() -> settingsPresenter.show(getView().getSettingsButtonWidget()));

        settingsPresenter.setSpeedChangeHandler(speed -> {
            this.playbackSpeed = speed;
            getView().setSpeedBadge(formatSpeed(speed));
        });

        // Loop/stop-at-end toggle: default to looping.
        settingsPresenter.setLoopPlayback(true);

        // Wire the Show All button: disabled until we have histogram data with a valid range.
        settingsPresenter.setShowAllEnabled(false);
        settingsPresenter.setShowAllHandler(() -> {
            if (dataRangeMin < dataRangeMax) {
                // Apply a small 5% padding on each side so the first/last events are not
                // flush against the edges of the histogram.
                final long padding = Math.max(1, (dataRangeMax - dataRangeMin) / 20);
                final long newStart = dataRangeMin - padding;
                final long newEnd = dataRangeMax + padding;
                setTimeRange(newStart, newEnd);
                if (timeRangeChangeHandler != null) {
                    timeRangeChangeHandler.run();
                }
            }
        });

        getView().setPlayPausePreset(PLAY_PRESET);
        getView().setStepBackPreset(STEP_BACK_PRESET);
        getView().setStepForwardPreset(STEP_FORWARD_PRESET);
        getView().setSettingsPreset(SETTINGS_PRESET);

        settingsPresenter.setSpeedOptions(Arrays.asList(0.5, 1.0, 10.0, 100.0, 1_000.0, 10_000.0));
        settingsPresenter.setSelectedSpeed(1.0);
        this.playbackSpeed = 1.0;
        getView().setSpeedBadge(formatSpeed(1.0));
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
     * Steps the timeline by the given number of histogram bins and fires a data query.
     * Positive values step forward; negative values step backward.
     *
     * @param bins Number of bins to step (positive = forward, negative = backward).
     */
    private void stepBy(final int bins) {
        if (endTime <= startTime) {
            return;
        }
        // Use the same bin count that the histogram uses (default 50 if not set).
        final long duration = endTime - startTime;
        final int binCount = 50;
        final long stepMs = duration / binCount * bins;
        final long newTime = Math.max(startTime, Math.min(endTime, currentTime + stepMs));
        setCurrentTime(newTime);
        TimeChangeEvent.fire(this, newTime);
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

    /**
     * Formats a playback speed value as a badge string, e.g. {@code "×1"} or {@code "×0.5"}.
     * Large values are comma-formatted (e.g. {@code "×1,000"}).
     */
    private static String formatSpeed(final double speed) {
        if (speed >= 1000) {
            // Format with thousands separator — GWT has no String.format %,d so we do it manually.
            final long rounded = Math.round(speed);
            final String raw = String.valueOf(rounded);
            final int len = raw.length();
            if (len > 3) {
                return "x" + raw.substring(0, len - 3) + "," + raw.substring(len - 3);
            }
            return "x" + raw;
        }
        // For values < 1000: show as integer where possible.
        if (speed == Math.floor(speed)) {
            return "x" + (int) speed;
        }
        return "x" + speed;
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
                        if (settingsPresenter.isLoopPlayback()) {
                            // Loop: wrap back to the start.
                            newTime = startTime;
                            // Reset the query clock on loop-around so the first frame after
                            // wrapping always triggers a fresh query.
                            lastQueryWallClockTime = 0;
                        } else {
                            // Stop at end: park at the end time and pause.
                            newTime = endTime;
                            playing = false;
                            getView().setPlayPausePreset(PLAY_PRESET);
                            setCurrentTime(newTime);
                            TimeChangeEvent.fire(FloorMapTimelinePresenter.this, newTime);
                            lastFrameTime = 0;
                            lastQueryWallClockTime = 0;
                            return;
                        }
                    }

                    // Always update the visual position (smooth 60 fps).
                    setCurrentTime(newTime);

                    // Only fire a data query if enough wall-clock time has elapsed since
                    // the last one, preventing the server from being overwhelmed.
                    if (lastQueryWallClockTime == 0
                            || timestamp - lastQueryWallClockTime >= PLAYBACK_QUERY_INTERVAL_MS) {
                        TimeChangeEvent.fire(FloorMapTimelinePresenter.this, newTime);
                        lastQueryWallClockTime = timestamp;
                    }
                }

                lastFrameTime = timestamp;
                AnimationScheduler.get().requestAnimationFrame(this);
            } else {
                lastFrameTime = 0;
                lastQueryWallClockTime = 0;
            }
        }
    };

    /**
     * Provides histogram bin counts to be displayed above the scrubber.
     *
     * @param binCounts  Array of event counts per bin.
     */
    public void setHistogramData(final int[] binCounts) {
        getView().setHistogramData(binCounts);
    }

    /**
     * Records the actual min/max timestamps seen in the current histogram data.
     * Called by {@code FloorMapMapPresenter} after each histogram query completes.
     * Enables the "Show All" button once a valid range is known.
     *
     * @param min Earliest event timestamp in the queried data (milliseconds).
     * @param max Latest event timestamp in the queried data (milliseconds).
     */
    public void setDataRange(final long min, final long max) {
        if (min <= max) {
            this.dataRangeMin = min;
            this.dataRangeMax = max;
            settingsPresenter.setShowAllEnabled(true);
        }
    }

    public interface FloorMapTimelineView extends View {

        void setProgressPct(double pct);

        /**
         * Sets the handler called on every mouse-move during a drag.
         * Should update the visual position only — must NOT trigger a data query.
         */
        void setScrubHandler(Consumer<Double> scrubHandler);

        /**
         * Sets the handler called when the user releases the scrubber (mouse-up) or
         * clicks directly on the histogram to seek.
         * This is the point at which a data query should be fired.
         */
        void setCommitHandler(Consumer<Double> commitHandler);

        /**
         * Updates the text of the scrub tooltip shown above the handle during dragging.
         */
        void setScrubTooltip(String text);

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

        /** Set the icon/title for the step-back button. */
        void setStepBackPreset(Preset preset);

        /** Set the icon/title for the step-forward button. */
        void setStepForwardPreset(Preset preset);

        /** Set the click handler for the step-back button. */
        void setStepBackHandler(Runnable handler);

        /** Set the click handler for the step-forward button. */
        void setStepForwardHandler(Runnable handler);

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
         * Updates the speed badge label shown beside the settings button (e.g. "1×").
         */
        void setSpeedBadge(String text);

        /**
         * Provides histogram data (event counts per bin) for display above the scrubber.
         * An empty or null array clears the histogram.
         */
        void setHistogramData(int[] binCounts);
    }
}
