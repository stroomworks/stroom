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
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseMoveEvent;
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
 * Includes a play button, progress bar with date labels, and a settings button.
 * A histogram canvas is located above the scrubber showing the distribution of events across the timeline range.
 */
public class FloorMapTimelineViewImpl extends ViewImpl implements FloorMapTimelineView {

    private static final int HISTOGRAM_HEIGHT = 48;
    private static final String HISTOGRAM_BAR_COLOUR = "rgba(30,136,229,0.7)";
    private static final String HISTOGRAM_PEAK_COLOUR = "rgba(30,136,229,1.0)";

    private final Widget widget;
    private Consumer<Double> clickHandler;
    private boolean dragging;

    // Histogram state
    private int[] histogramBins;
    private Canvas histogramCanvas;

    @UiField
    SimplePanel histogramContainer;
    @UiField
    FlowPanel outerBar;
    @UiField
    FlowPanel innerBar;
    @UiField
    FlowPanel handle;
    @UiField
    SvgButton playPauseButton;
    @UiField
    SvgButton settingsButton;
    @UiField
    Label startDateLabel;
    @UiField
    Label endDateLabel;

    @Inject
    public FloorMapTimelineViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);

        // Mouse handlers for dragging the timeline handle.
        outerBar.addDomHandler(this::onMouseDown, MouseDownEvent.getType());
        outerBar.addDomHandler(this::onMouseMove, MouseMoveEvent.getType());
        outerBar.addDomHandler(this::onMouseUp, MouseUpEvent.getType());

        // Build the histogram canvas and put it inside the container.
        if (Canvas.isSupported()) {
            histogramCanvas = Canvas.createIfSupported();
            histogramCanvas.addStyleName("stroom-floormap-timeline-histogram-canvas");
            histogramContainer.setWidget(histogramCanvas);
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
    }

    @Override
    public void setClickHandler(final Consumer<Double> clickHandler) {
        this.clickHandler = clickHandler;
    }

    // -----------------------------------------------------------------------
    // Date labels
    // -----------------------------------------------------------------------

    @Override
    public void setStartDateLabel(final String text) {
        startDateLabel.setText(text);
    }

    @Override
    public void setEndDateLabel(final String text) {
        endDateLabel.setText(text);
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
     * Draws the histogram bars onto the canvas element. Called whenever new data arrives.
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
            return;
        }

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
    }

    // -----------------------------------------------------------------------
    // Mouse / drag handling
    // -----------------------------------------------------------------------

    private void onMouseDown(final MouseDownEvent event) {
        if (event.getNativeButton() == NativeEvent.BUTTON_LEFT) {
            dragging = true;
            updatePosition(event.getClientX());
            DOM.setCapture(outerBar.getElement());
            event.preventDefault();
        }
    }

    private void onMouseMove(final MouseMoveEvent event) {
        if (dragging) {
            updatePosition(event.getClientX());
        }
    }

    private void onMouseUp(final MouseUpEvent event) {
        if (dragging) {
            dragging = false;
            DOM.releaseCapture(outerBar.getElement());
        }
    }

    private void updatePosition(final int clientX) {
        if (clickHandler != null) {
            final Element element = outerBar.getElement();
            final int absoluteLeft = element.getAbsoluteLeft();
            final int width = element.getOffsetWidth();

            final double relativeX = clientX - absoluteLeft;
            final double pct = Math.max(0, Math.min(100, (relativeX / width) * 100));
            clickHandler.accept(pct);
        }
    }

    public interface Binder extends UiBinder<Widget, FloorMapTimelineViewImpl> {

    }
}
