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
import stroom.widget.datepicker.client.DateTimeBox;
import stroom.widget.datepicker.client.DateTimePopup;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.function.Consumer;

/**
 * View implementation for the floor map timeline control.
 * Provides a progress bar and a draggable handle.
 */
public class FloorMapTimelineViewImpl extends ViewImpl implements FloorMapTimelineView {

    private final Widget widget;

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

    private Consumer<Double> clickHandler;

    @Inject
    public FloorMapTimelineViewImpl(final Binder binder,
                                    final Provider<DateTimePopup> dateTimePopupProvider) {
        widget = binder.createAndBindUi(this);

        // Standard click handling for stability
        outerBar.addDomHandler(event -> {
            if (clickHandler != null) {
                final double x = event.getX();
                final double width = outerBar.getElement().getOffsetWidth();
                final double pct = Math.max(0, Math.min(100, (x / width) * 100));
                clickHandler.accept(pct);
            }
        }, ClickEvent.getType());

        startDateTimeBox.setPopupProvider(dateTimePopupProvider);
        endDateTimeBox.setPopupProvider(dateTimePopupProvider);
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

    public interface Binder extends UiBinder<Widget, FloorMapTimelineViewImpl> {

    }
}
