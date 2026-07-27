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

import stroom.floormap.client.presenter.FloorMapTimelineSettingsPresenter.FloorMapTimelineSettingsView;
import stroom.widget.datepicker.client.DateTimeBox;
import stroom.widget.datepicker.client.DateTimePopup;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View implementation for the timeline settings popup dialog.
 *
 * <p>Contains start/end date-time pickers to constrain the visible time range,
 * a loop-playback checkbox, and a "Show All" button that resets the range to
 * cover all available data. Playback speed is not set here — it has its own
 * menu opened from the speed badge on the timeline.</p>
 */
public class FloorMapTimelineSettingsViewImpl extends ViewImpl implements FloorMapTimelineSettingsView {

    private final Widget widget;

    /** Called when the Show All button is clicked. */
    private Runnable showAllHandler;

    @UiField
    DateTimeBox startDateTimeBox;
    @UiField
    DateTimeBox endDateTimeBox;
    @UiField
    CustomCheckBox loopCheckBox;
    @UiField
    Button showAllButton;

    @Inject
    public FloorMapTimelineSettingsViewImpl(final Binder binder,
                                            final Provider<DateTimePopup> dateTimePopupProvider) {
        widget = binder.createAndBindUi(this);

        startDateTimeBox.setPopupProvider(dateTimePopupProvider);
        endDateTimeBox.setPopupProvider(dateTimePopupProvider);

        // Registered once and dispatched through a field, so a second
        // setShowAllHandler() call replaces the handler instead of stacking
        // another click registration (which would run it twice per click).
        //noinspection unused e
        showAllButton.addClickHandler(e -> {
            if (showAllHandler != null) {
                showAllHandler.run();
            }
        });
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public boolean isLoopPlayback() {
        return Boolean.TRUE.equals(loopCheckBox.getValue());
    }

    @Override
    public void setLoopPlayback(final boolean loop) {
        loopCheckBox.setValue(loop);
    }

    @Override
    public void setShowAllHandler(final Runnable handler) {
        this.showAllHandler = handler;
    }

    @Override
    public void setShowAllEnabled(final boolean enabled) {
        showAllButton.setEnabled(enabled);
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
    public long getStartTime() {
        return getTimeOrZero(startDateTimeBox);
    }

    @Override
    public long getEndTime() {
        return getTimeOrZero(endDateTimeBox);
    }

    private static long getTimeOrZero(final DateTimeBox box) {
        final Long value = box.getValue();
        return value != null ? value : 0L;
    }

    @Override
    public HandlerRegistration addStartTimeChangeHandler(final ValueChangeHandler<String> handler) {
        return startDateTimeBox.addValueChangeHandler(handler);
    }

    @Override
    public HandlerRegistration addEndTimeChangeHandler(final ValueChangeHandler<String> handler) {
        return endDateTimeBox.addValueChangeHandler(handler);
    }

    public interface Binder extends UiBinder<Widget, FloorMapTimelineSettingsViewImpl> {

    }
}
