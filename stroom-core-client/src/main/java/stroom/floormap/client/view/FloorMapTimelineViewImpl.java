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
import stroom.floormap.client.presenter.FloorMapTimelinePresenter.OutOfRange;
import stroom.svg.client.Preset;
import stroom.widget.button.client.SvgButton;
import stroom.widget.histogram.client.HistogramWidget;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseMoveEvent;
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
    private final HistogramWidget histogramWidget;

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
    FlowPanel rightControls;
    @UiField
    Label speedBadge;
    @UiField
    Label startDateLabel;
    @UiField
    Label endDateLabel;
    @UiField
    Label outOfRangeLeftLabel;
    @UiField
    Label outOfRangeRightLabel;

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

        // Build the histogram widget and place it inside the container.
        histogramWidget = new HistogramWidget();
        // Histogram click-to-seek — treat it like releasing the scrubber.
        histogramWidget.setClickHandler(fraction -> {
            if (commitHandler != null) {
                commitHandler.accept(fraction * 100.0);
            }
        });
        histogramContainer.setWidget(histogramWidget);

        // Set tooltip text for out-of-range indicators (labels are declared in UiBinder).
        //noinspection UnnecessaryUnicodeEscape
        outOfRangeLeftLabel.setText("\u00AB"); // «
        outOfRangeLeftLabel.setTitle("Object out of range. Extend timeline range to view object.");
        //noinspection UnnecessaryUnicodeEscape
        outOfRangeRightLabel.setText("\u00BB"); // »
        outOfRangeRightLabel.setTitle("Object out of range. Extend timeline range to view object.");
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

    @Override
    public void addRightControl(final Widget widget) {
        rightControls.add(widget);
    }

    // -----------------------------------------------------------------------
    // Speed badge
    // -----------------------------------------------------------------------

    @Override
    public void setSpeedBadge(final String text) {
        speedBadge.setText(text);
    }

    @Override
    public void setHistogramData(final int[] binCounts) {
        if (histogramWidget != null) {
            histogramWidget.setData(binCounts);
        }
    }

    @Override
    public void setOutOfRangeIndicator(final OutOfRange direction) {
        outOfRangeLeftLabel.setVisible(direction == OutOfRange.BEFORE);
        outOfRangeRightLabel.setVisible(direction == OutOfRange.AFTER);
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
