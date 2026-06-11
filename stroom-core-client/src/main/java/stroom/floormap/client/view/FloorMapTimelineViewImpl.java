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
import stroom.item.client.SelectionBox;
import stroom.item.client.SimpleSelectionListModel;
import stroom.svg.client.Preset;
import stroom.widget.button.client.SvgButton;
import stroom.widget.datepicker.client.DateTimeBox;
import stroom.widget.datepicker.client.DateTimePopup;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseMoveEvent;
import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.List;
import java.util.function.Consumer;

/**
 * View implementation for the floor map timeline control.
 * Provides a progress bar and a draggable handle.
 */
public class FloorMapTimelineViewImpl extends ViewImpl implements FloorMapTimelineView {

    private final Widget widget;
    private Consumer<Double> clickHandler;
    private final SimpleSelectionListModel<Double> speedModel = new SimpleSelectionListModel<>();
    private boolean dragging;

    @UiField
    FlowPanel outerBar;
    @UiField
    FlowPanel innerBar;
    @UiField
    FlowPanel handle;
    @UiField
    DateTimeBox startDateTimeBox;
    @UiField
    DateTimeBox endDateTimeBox;
    @UiField
    SvgButton playPauseButton;
    @UiField
    SelectionBox<Double> speedSelectionBox;

    @Inject
    public FloorMapTimelineViewImpl(final Binder binder,
                                    final Provider<DateTimePopup> dateTimePopupProvider) {
        widget = binder.createAndBindUi(this);

        // Mouse handler events for dragging the timeline handle.
        outerBar.addDomHandler(this::onMouseDown, MouseDownEvent.getType());
        outerBar.addDomHandler(this::onMouseMove, MouseMoveEvent.getType());
        outerBar.addDomHandler(this::onMouseUp, MouseUpEvent.getType());

        startDateTimeBox.setPopupProvider(dateTimePopupProvider);
        endDateTimeBox.setPopupProvider(dateTimePopupProvider);

        // Initialise the box with the model
        speedSelectionBox.setModel(speedModel);
        speedSelectionBox.setDisplayValueFunction(value -> value + "x");
        speedSelectionBox.setRenderFunction(value -> SafeHtmlUtils.fromString(value + "x"));
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setProgressPct(final double pct) {
        // Move both the filled bar and the circle handle
        innerBar.getElement().getStyle().setWidth(pct, Unit.PCT);
        handle.getElement().getStyle().setLeft(pct, Unit.PCT);
    }

    @Override
    public void setClickHandler(final Consumer<Double> clickHandler) {
        this.clickHandler = clickHandler;
    }

    @Override
    public void setStartTime(final long startTime) {
        startDateTimeBox.setValue(startTime);
    }

    @Override
    public void setEndTime(final long endTime) {
        endDateTimeBox.setValue(endTime);
    }

    @Override
    public void setPlayPausePreset(final Preset preset) {
        playPauseButton.setSvg(preset.getSvgImage());
        playPauseButton.setTitle(preset.getTitle());
    }

    @Override
    public void setPlayPauseHandler(final Runnable handler) {
        playPauseButton.addClickHandler(e -> handler.run());
    }

    @Override
    public void setSpeedOptions(final List<Double> speeds) {
        speedModel.clear();
        speedModel.addItems(speeds);
    }

    @Override
    public void setSelectedSpeed(final Double speed) {
        speedSelectionBox.setValue(speed);
    }

    @Override
    public void setSpeedChangeHandler(final Consumer<Double> handler) {
        speedSelectionBox.addValueChangeHandler(e -> handler.accept(e.getValue()));
    }

    @Override
    public HandlerRegistration addStartTimeChangeHandler(final ValueChangeHandler<String> handler) {
        return startDateTimeBox.addValueChangeHandler(handler);
    }

    @Override
    public HandlerRegistration addEndTimeChangeHandler(final ValueChangeHandler<String> handler) {
        return endDateTimeBox.addValueChangeHandler(handler);
    }

    @Override
    public long getStartTime() {
        return startDateTimeBox.getValue() != null ? startDateTimeBox.getValue() : 0;
    }

    @Override
    public long getEndTime() {
        return endDateTimeBox.getValue() != null ? endDateTimeBox.getValue() : 0;
    }

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
            // Calculate the relative X position within the bar.
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
