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

package stroom.floormap.client.view;

import stroom.floormap.client.presenter.FloorMapTimelinePresenter.FloorMapTimelineView;
import stroom.svg.client.Preset;
import stroom.widget.button.client.SvgButton;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseMoveEvent;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOverEvent;
import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.function.Consumer;

/**
 * View implementation for the floor map timeline control.
 * Includes step-back, play/pause and step-forward buttons, a progress scrubber with date labels,
 * a settings button with a speed badge, and a histogram above the scrubber.
 * Features:
 * <ul>
 *   <li>Scrub tooltip — datetime pill above the handle while dragging.</li>
 *   <li>Histogram click-to-seek — clicking the histogram jumps the timeline head.</li>
 *   <li>Histogram hover tooltip — shows event count for the hovered bin.</li>
 *   <li>Tick marks — subtle vertical lines at regular intervals along the scrubber bar.</li>
 *   <li>ARIA slider attributes for accessibility.</li>
 * </ul>
 */
public class FloorMapTimelineViewImpl extends ViewImpl implements FloorMapTimelineView {

    private static final int HISTOGRAM_HEIGHT = 48;
    private static final String HISTOGRAM_BAR_COLOUR = "rgba(30,136,229,0.7)";
    private static final String HISTOGRAM_PEAK_COLOUR = "rgba(30,136,229,1.0)";
    private static final String TICK_COLOUR = "rgba(128,128,128,0.25)";
    /** Ideal number of tick marks — the actual count is rounded to a "nice" interval. */
    private static final int TARGET_TICK_COUNT = 10;

    private final Widget widget;
    /** Called on every mouse-move during a drag — updates visuals only, no data queries. */
    private Consumer<Double> scrubHandler;
    /** Called on mouse-up (release) — commits the time and triggers data queries. */
    private Consumer<Double> commitHandler;
    /** Called when the step-back button is clicked. */
    private Runnable stepBackHandler;
    /** Called when the step-forward button is clicked. */
    private Runnable stepForwardHandler;
    private boolean dragging;

    // Histogram state
    private int[] histogramBins;
    private Canvas histogramCanvas;
    /** Label shown above the histogram while the user hovers over it. */
    private Label histogramTooltip;

    @UiField
    SimplePanel histogramContainer;
    @UiField
    FlowPanel outerBar;
    @UiField
    FlowPanel innerBar;
    @UiField
    FlowPanel handle;
    @UiField
    Label scrubTooltip;
    @UiField
    SvgButton stepBackButton;
    @UiField
    SvgButton playPauseButton;
    @UiField
    SvgButton stepForwardButton;
    @UiField
    SvgButton settingsButton;
    @UiField
    Label speedBadge;
    @UiField
    Label startDateLabel;
    @UiField
    Label endDateLabel;

    @Inject
    public FloorMapTimelineViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);

        // Mouse handlers for dragging the timeline handle.
        outerBar.addDomHandler(this::onBarMouseDown, MouseDownEvent.getType());
        outerBar.addDomHandler(this::onBarMouseMove, MouseMoveEvent.getType());
        outerBar.addDomHandler(this::onBarMouseUp, MouseUpEvent.getType());

        // Step button handlers.
        //noinspection unused e
        stepBackButton.addDomHandler(e -> {
            if (stepBackHandler != null) {
                stepBackHandler.run();
            }
        }, ClickEvent.getType());
        //noinspection unused e
        stepForwardButton.addDomHandler(e -> {
            if (stepForwardHandler != null) {
                stepForwardHandler.run();
            }
        }, ClickEvent.getType());

        // ARIA: mark the outer bar as a slider.
        final Element barEl = outerBar.getElement();
        barEl.setAttribute("role", "slider");
        barEl.setAttribute("aria-valuemin", "0");
        barEl.setAttribute("aria-valuemax", "100");
        barEl.setAttribute("aria-valuenow", "0");
        barEl.setAttribute("tabindex", "0");

        // Build the histogram canvas and tooltip, then put them inside the container.
        if (Canvas.isSupported()) {
            histogramCanvas = Canvas.createIfSupported();
            histogramCanvas.addStyleName("stroom-floormap-timeline-histogram-canvas");

            // Build a tooltip label for bin hover info (hidden by default).
            histogramTooltip = new Label();
            histogramTooltip.addStyleName("stroom-floormap-timeline-histogram-tooltip");

            // Use a relative-positioned wrapper so the canvas and tooltip overlay each other.
            final FlowPanel canvasWrapper = new FlowPanel();
            canvasWrapper.addStyleName("stroom-floormap-timeline-histogram-canvas-wrapper");
            canvasWrapper.add(histogramCanvas);
            canvasWrapper.add(histogramTooltip);
            histogramContainer.setWidget(canvasWrapper);

            // Histogram hover — show per-bin event count.
            histogramCanvas.addDomHandler(this::onHistogramMouseMove, MouseMoveEvent.getType());
            //noinspection unused e
            histogramCanvas.addDomHandler(e -> hideHistogramTooltip(), MouseOutEvent.getType());

            // Histogram click-to-seek — clicking jumps the timeline head.
            histogramCanvas.addDomHandler(this::onHistogramClick, ClickEvent.getType());
        }

        // Redraw the histogram if the widget gains size after the initial data arrived (e.g. panel was collapsed
        // during load). This is a reliable fallback for the deferred-draw below.
        //noinspection unused e
        histogramContainer.addDomHandler(e -> {
            if (histogramBins != null) {
                drawHistogram();
            }
        }, MouseOverEvent.getType());
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    // -----------------------------------------------------------------------
    // Progress bar
    // -----------------------------------------------------------------------

    @Override
    public void setProgressPct(final double pct) {
        innerBar.getElement().getStyle().setWidth(pct, Unit.PCT);
        handle.getElement().getStyle().setLeft(pct, Unit.PCT);
        scrubTooltip.getElement().getStyle().setLeft(pct, Unit.PCT);

        // Keep ARIA in sync so screen-readers announce the current position.
        outerBar.getElement().setAttribute("aria-valuenow", String.valueOf((int) Math.round(pct)));
    }

    @Override
    public void setScrubTooltip(final String text) {
        scrubTooltip.setText(text);
        // Also push to ARIA so screen-readers announce the formatted datetime.
        outerBar.getElement().setAttribute("aria-valuetext", text);
    }

    @Override
    public void setScrubHandler(final Consumer<Double> scrubHandler) {
        this.scrubHandler = scrubHandler;
    }

    @Override
    public void setCommitHandler(final Consumer<Double> commitHandler) {
        this.commitHandler = commitHandler;
    }

    // -----------------------------------------------------------------------
    // Date labels
    // -----------------------------------------------------------------------

    @Override
    public void setStartDateLabel(final String text) {
        startDateLabel.setText(text);
        outerBar.getElement().setAttribute("aria-label",
                "Timeline from " + text + " to " + endDateLabel.getText());
    }

    @Override
    public void setEndDateLabel(final String text) {
        endDateLabel.setText(text);
        outerBar.getElement().setAttribute("aria-label",
                "Timeline from " + startDateLabel.getText() + " to " + text);
    }

    // -----------------------------------------------------------------------
    // Play/Pause button
    // -----------------------------------------------------------------------

    @Override
    public void setPlayPausePreset(final Preset preset) {
        playPauseButton.setSvg(preset.getSvgImage());
        playPauseButton.setTitle(preset.getTitle());
    }

    @Override
    public void setPlayPauseHandler(final Runnable handler) {
        //noinspection unused e
        playPauseButton.addClickHandler(e -> handler.run());
    }

    // -----------------------------------------------------------------------
    // Step buttons
    // -----------------------------------------------------------------------

    @Override
    public void setStepBackPreset(final Preset preset) {
        stepBackButton.setSvg(preset.getSvgImage());
        stepBackButton.setTitle(preset.getTitle());
    }

    @Override
    public void setStepForwardPreset(final Preset preset) {
        stepForwardButton.setSvg(preset.getSvgImage());
        stepForwardButton.setTitle(preset.getTitle());
    }

    @Override
    public void setStepBackHandler(final Runnable handler) {
        this.stepBackHandler = handler;
    }

    @Override
    public void setStepForwardHandler(final Runnable handler) {
        this.stepForwardHandler = handler;
    }

    // -----------------------------------------------------------------------
    // Settings button
    // -----------------------------------------------------------------------

    @Override
    public void setSettingsPreset(final Preset preset) {
        settingsButton.setSvg(preset.getSvgImage());
        settingsButton.setTitle(preset.getTitle());
    }

    @Override
    public void setSettingsHandler(final Runnable handler) {
        //noinspection unused e
        settingsButton.addClickHandler(e -> handler.run());
    }

    @Override
    public Widget getSettingsButtonWidget() {
        return settingsButton;
    }

    // -----------------------------------------------------------------------
    // Speed badge
    // -----------------------------------------------------------------------

    @Override
    public void setSpeedBadge(final String text) {
        speedBadge.setText(text);
    }

    // -----------------------------------------------------------------------
    // Histogram
    // -----------------------------------------------------------------------

    @Override
    public void setHistogramData(final int[] binCounts) {
        this.histogramBins = binCounts;
        // Defer so the browser has laid out the DOM and getOffsetWidth()
        // returns the real pixel width rather than 0.
        Scheduler.get().scheduleDeferred(this::drawHistogram);
    }

    /**
     * Draws the histogram bars and tick marks onto the canvas. Called whenever new data arrives.
     */
    private void drawHistogram() {
        if (histogramCanvas == null || histogramBins == null || histogramBins.length == 0) {
            return;
        }

        // Sync the canvas pixel dimensions to its CSS layout dimensions.
        final int width = histogramContainer.getOffsetWidth();
        final int height = HISTOGRAM_HEIGHT;
        if (width <= 0) {
            return;
        }

        histogramCanvas.setCoordinateSpaceWidth(width);
        histogramCanvas.setCoordinateSpaceHeight(height);

        final Context2d ctx = histogramCanvas.getContext2d();
        ctx.clearRect(0, 0, width, height);

        // Find peak count for normalisation.
        int max = 0;
        for (final int count : histogramBins) {
            if (count > max) {
                max = count;
            }
        }
        if (max == 0) {
            // A query has run but returned no events for this time range.
            // Draw a centred placeholder so the user knows what the space is for.
            ctx.save();
            ctx.setFont("italic 11px sans-serif");
            ctx.setTextAlign(Context2d.TextAlign.CENTER);
            ctx.setTextBaseline(Context2d.TextBaseline.MIDDLE);
            ctx.setFillStyle("rgba(128,128,128,0.45)");
            ctx.fillText("No events in this time range", width / 2.0, height / 2.0);
            ctx.restore();
            return;
        }

        // ---- Draw histogram bars ----
        final int n = histogramBins.length;
        final double barW = (double) width / n;

        for (int i = 0; i < n; i++) {
            final double barH = ((double) histogramBins[i] / max) * (height - 2);
            if (barH < 1) {
                continue;
            }
            final double x = i * barW;
            final double y = height - barH;

            // Use a brighter colour for the peak bin.
            ctx.setFillStyle(histogramBins[i] == max ? HISTOGRAM_PEAK_COLOUR : HISTOGRAM_BAR_COLOUR);
            ctx.fillRect(x, y, Math.max(1, barW - 1), barH);
        }

        // ---- Draw tick marks at regular intervals ----
        // Calculate a "nice" tick interval so ticks align with even bin boundaries.
        final int tickInterval = niceTickInterval(n);
        ctx.setFillStyle(TICK_COLOUR);
        for (int i = tickInterval; i < n; i += tickInterval) {
            final double x = i * barW;
            ctx.fillRect(x, 0, 1, height);
        }
    }

    /**
     * Returns a tick interval (in bins) that divides {@code binCount} into approximately
     * {@code targetCount} evenly-spaced ticks, rounded to a "nice" number (1, 2, 5, 10, 20…).
     */
    private static int niceTickInterval(final int binCount) {
        final int raw = Math.max(1, binCount / FloorMapTimelineViewImpl.TARGET_TICK_COUNT);
        // Round up to the nearest "nice" step: 1, 2, 5, 10, 20, 25, 50, 100…
        final int[] nice = {1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500};
        for (final int n : nice) {
            if (n >= raw) {
                return n;
            }
        }
        return raw;
    }

    // -----------------------------------------------------------------------
    // Histogram — hover tooltip
    // -----------------------------------------------------------------------

    private void onHistogramMouseMove(final MouseMoveEvent event) {
        if (histogramBins == null || histogramBins.length == 0 || histogramTooltip == null) {
            return;
        }
        final int containerWidth = histogramContainer.getOffsetWidth();
        if (containerWidth <= 0) {
            return;
        }
        final int relX = event.getX();
        final int binIndex = (int) Math.min(
                histogramBins.length - 1,
                Math.max(0, (relX / (double) containerWidth) * histogramBins.length));
        final int count = histogramBins[binIndex];

        histogramTooltip.setText(count + " event" + (count == 1 ? "" : "s"));
        // Position the tooltip horizontally centred on the cursor, within the canvas bounds.
        final int tooltipW = histogramTooltip.getOffsetWidth();
        final int clampedX = Math.max(0, Math.min(containerWidth - tooltipW, relX - tooltipW / 2));
        histogramTooltip.getElement().getStyle().setLeft(clampedX, Unit.PX);
        histogramTooltip.addStyleName("stroom-floormap-timeline-histogram-tooltip--visible");
    }

    private void hideHistogramTooltip() {
        if (histogramTooltip != null) {
            histogramTooltip.removeStyleName("stroom-floormap-timeline-histogram-tooltip--visible");
        }
    }

    // -----------------------------------------------------------------------
    // Histogram — click-to-seek
    // -----------------------------------------------------------------------

    private void onHistogramClick(final ClickEvent event) {
        final int containerWidth = histogramContainer.getOffsetWidth();
        if (containerWidth <= 0) {
            return;
        }
        final double pct = Math.max(0, Math.min(100,
                (event.getX() / (double) containerWidth) * 100.0));
        // Treat a histogram click the same as releasing the scrubber (commits a data query).
        if (commitHandler != null) {
            commitHandler.accept(pct);
        }
    }

    // -----------------------------------------------------------------------
    // Mouse / drag handling on the scrubber bar
    // -----------------------------------------------------------------------

    private void onBarMouseDown(final MouseDownEvent event) {
        if (event.getNativeButton() == NativeEvent.BUTTON_LEFT) {
            dragging = true;
            scrubTooltip.addStyleName("stroom-floormap-timeline-scrub-tooltip--visible");
            // Move the handle immediately on click but do not yet fire a data query.
            notifyScrub(event.getClientX());
            DOM.setCapture(outerBar.getElement());
            event.preventDefault();
        }
    }

    private void onBarMouseMove(final MouseMoveEvent event) {
        if (dragging) {
            // Keep updating the visual position while dragging — still no data query.
            notifyScrub(event.getClientX());
        }
    }

    private void onBarMouseUp(final MouseUpEvent event) {
        if (dragging) {
            dragging = false;
            scrubTooltip.removeStyleName("stroom-floormap-timeline-scrub-tooltip--visible");
            // Commit the final position: this is the single point at which we fire a data query.
            notifyCommit(event.getClientX());
            DOM.releaseCapture(outerBar.getElement());
        }
    }

    /**
     * Notifies the scrub handler with the percentage position for the given client X coordinate.
     * Updates visuals immediately but intentionally does NOT trigger a data query.
     */
    private void notifyScrub(final int clientX) {
        if (scrubHandler != null) {
            scrubHandler.accept(computeBarPct(clientX));
        }
    }

    /**
     * Notifies the commit handler with the percentage position for the given client X coordinate.
     * This is the signal that the user has finished scrubbing and a data query should be fired.
     */
    private void notifyCommit(final int clientX) {
        if (commitHandler != null) {
            commitHandler.accept(computeBarPct(clientX));
        }
    }

    /** Converts a client-X pixel position to a [0, 100] percentage along the scrubber bar. */
    private double computeBarPct(final int clientX) {
        final Element element = outerBar.getElement();
        final int absoluteLeft = element.getAbsoluteLeft();
        final int width = element.getOffsetWidth();
        final double relativeX = clientX - absoluteLeft;
        return Math.max(0, Math.min(100, (relativeX / width) * 100));
    }

    public interface Binder extends UiBinder<Widget, FloorMapTimelineViewImpl> {

    }
}
