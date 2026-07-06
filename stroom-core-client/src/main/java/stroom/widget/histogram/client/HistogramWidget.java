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

package stroom.widget.histogram.client;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.MouseMoveEvent;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;

import java.util.function.Consumer;

/**
 * A histogram widget that renders on {@code int[]} of bin counts as vertical bars on an HTML5 Canvas.
 * <p>
 *     Features:
 *     <ul>
 *         <li>Normalised bar heights (tallest bin fills the widget height)</li>
 *         <li>Hover tooltip showing per-bin count</li>
 *         <li>Click callback with the clicked percentage [0..1]</li>
 *         <li>Tick marks at regular intervals.</li>
 *         <li>Placeholder text when all bins are zero.</li>
 *     </ul>
 * </p>
 */
public class HistogramWidget extends Composite {

    private static final int DEFAULT_HEIGHT = 48;
    private static final String DEFAULT_BAR_COLOUR = "rgba(30,136,229,0.7)";
    private static final String DEFAULT_PEAK_COLOUR = "rgba(30,136,229,1.0)";
    private static final String TICK_COLOUR = "rgba(128,128,128,0.25)";
    private static final int TARGET_TICK_COUNT = 10;

    private final FlowPanel container;
    private final Canvas canvas;
    private final Label tooltip;
    private int[] bins;
    private int height = DEFAULT_HEIGHT;
    private String barColour = DEFAULT_BAR_COLOUR;
    private String peakColour = DEFAULT_PEAK_COLOUR;
    private String emptyMessage = "No events in this time range";

    // Called when the user clicks, with the fractional position [0..1]
    private Consumer<Double> clickHandler;

    public HistogramWidget() {
        container = new FlowPanel();
        container.addStyleName("stroom-histogram-container");

        canvas = Canvas.createIfSupported();
        if (canvas != null) {
            canvas.addStyleName("stroom-histogram-canvas");

            tooltip = new Label();
            tooltip.addStyleName("stroom-histogram-tooltip");

            final FlowPanel wrapper = new FlowPanel();
            wrapper.addStyleName("stroom-histogram-wrapper");
            wrapper.add(canvas);
            wrapper.add(tooltip);
            container.add(wrapper);

            canvas.addDomHandler(this::onMouseMove, MouseMoveEvent.getType());
            canvas.addDomHandler(e -> hideTooltip(), MouseOutEvent.getType());
            canvas.addDomHandler(this::onClick, ClickEvent.getType());
        } else {
            tooltip = null;
        }

        initWidget(container);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Sets the bin data and triggers a redraw. */
    public void setData(final int[] binCounts) {
        this.bins = binCounts;
        Scheduler.get().scheduleDeferred(this::draw);
    }

    /** Sets the click handler - receives the fractional position [0..1]. */
    public void setClickHandler(final Consumer<Double> handler) {
        this.clickHandler = handler;
    }

    /** Customise the bar colour (CSS colour string). */
    public void setBarColour(final String colour) {
        this.barColour = colour;
    }

    /** Customise the peak bar colour. */
    public void setPeakColour(final String colour) {
        this.peakColour = colour;
    }

    /** Customise the height in pixels. */
    public void setHeight(final int px) {
        this.height = px;
    }

    /** Customise the text shown when all bins are zero. */
    public void setEmptyMessage(final String msg) {
        this.emptyMessage = msg;
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    /** Draws the histogram bars and tick marks onto the canvas. Called whenever new data arrives. */
    private void draw() {
        if (canvas  == null || bins == null || bins.length == 0) {
            return;
        }

        // Sync the canvas pixel dimensions to its CSS layout dimensions.
        final int width = container.getOffsetWidth();
        if (width <= 0) {
            return;
        }

        canvas.setCoordinateSpaceWidth(width);
        canvas.setCoordinateSpaceHeight(height);

        final Context2d ctx = canvas.getContext2d();
        ctx.clearRect(0, 0, width, height);

        // Find peak count for normalisation.
        int max = 0;
        for (final int count : bins) {
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
            ctx.fillText(emptyMessage, width / 2.0, height / 2.0);
            ctx.restore();
            return;
        }

        // ---- Draw histogram bars ----
        final int n = bins.length;
        final double barW = (double) width / n;

        for (int i = 0; i < n; i++) {
            final double barH = ((double) bins[i] / max) * (height - 2);
            if (barH < 1) {
                continue;
            }
            final double x = i * barW;
            final double y = height - barH;

            // Use a brighter colour for the peak bin.
            ctx.setFillStyle(bins[i] == max ? peakColour : barColour);
            ctx.fillRect(x, y, Math.max(1, barW - 1), barH);
        }

        // ---- Draw tick marks at regular intervals ----
        // Calculate a tick interval so ticks align with even bin boundaries.
        final int tickInterval = tickInterval(n);
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
    private static int tickInterval(final int binCount) {
        final int raw = Math.max(1, binCount / TARGET_TICK_COUNT);
        // Round up to the nearest step: 1, 2, 5, 10, 20, 25, 50, 100…
        final int[] interval = {1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500};
        for (final int n : interval) {
            if (n >= raw) {
                return n;
            }
        }
        return raw;
    }

    // -----------------------------------------------------------------------
    // Interaction
    // -----------------------------------------------------------------------

    private void onMouseMove(final MouseMoveEvent event) {
        if (bins == null || bins.length == 0 || tooltip == null) {
            return;
        }
        final int containerWidth = container.getOffsetWidth();
        if (containerWidth <= 0) {
            return;
        }
        final int relX = event.getX();
        final int binIndex = (int) Math.min(
                bins.length - 1,
                Math.max(0, (relX / (double) containerWidth) * bins.length));
        final int count = bins[binIndex];

        tooltip.setText(count + " event" + (count == 1 ? "" : "s"));

        // Position the tooltip horizontally centred on the cursor, within the canvas bounds.
        final int tooltipW = tooltip.getOffsetWidth();
        final int clampedX = Math.max(0, Math.min(containerWidth - tooltipW, relX - tooltipW / 2));
        tooltip.getElement().getStyle().setLeft(clampedX, Unit.PX);
        tooltip.addStyleName("stroom-histogram-tooltip--visible");
    }

    private void hideTooltip() {
        if (tooltip != null) {
            tooltip.removeStyleName("stroom-histogram-tooltip--visible");
        }
    }

    private void onClick(final ClickEvent event) {
        if (clickHandler == null) {
            return;
        }

        final int w = container.getOffsetWidth();
        if (w <= 0) {
            return;
        }

        clickHandler.accept(Math.max(0, Math.min(1.0, event.getX() / (double) w)));
    }
}
