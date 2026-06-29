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
import stroom.item.client.SelectionBox;
import stroom.item.client.SimpleSelectionListModel;
import stroom.widget.datepicker.client.DateTimeBox;
import stroom.widget.datepicker.client.DateTimePopup;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.List;
import java.util.function.Consumer;

/**
 * View for the timeline settings popup.
 * Contains date range pickers and playback speed selector.
 */
public class FloorMapTimelineSettingsViewImpl extends ViewImpl implements FloorMapTimelineSettingsView {

    private final Widget widget;
    private final SimpleSelectionListModel<Double> speedModel = new SimpleSelectionListModel<>();

    @UiField
    DateTimeBox startDateTimeBox;
    @UiField
    DateTimeBox endDateTimeBox;
    @UiField
    SelectionBox<Double> speedSelectionBox;
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

        speedSelectionBox.setModel(speedModel);
        speedModel.setDisplayValueFunction(FloorMapTimelineSettingsViewImpl::formatSpeedLabel);
        speedModel.setRenderFunction(v -> SafeHtmlUtils.fromString(formatSpeedLabel(v)));
    }

    @Override
    public Widget asWidget() {
        return widget;
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
    public boolean isLoopPlayback() {
        return Boolean.TRUE.equals(loopCheckBox.getValue());
    }

    @Override
    public void setLoopPlayback(final boolean loop) {
        loopCheckBox.setValue(loop);
    }

    @Override
    public void setShowAllHandler(final Runnable handler) {
        //noinspection unused e
        showAllButton.addClickHandler(e -> handler.run());
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
        return startDateTimeBox.getValue() != null ? startDateTimeBox.getValue() : 0;
    }

    @Override
    public long getEndTime() {
        return endDateTimeBox.getValue() != null ? endDateTimeBox.getValue() : 0;
    }

    @Override
    public HandlerRegistration addStartTimeChangeHandler(final ValueChangeHandler<String> handler) {
        return startDateTimeBox.addValueChangeHandler(handler);
    }

    @Override
    public HandlerRegistration addEndTimeChangeHandler(final ValueChangeHandler<String> handler) {
        return endDateTimeBox.addValueChangeHandler(handler);
    }

    /**
     * Formats a speed value for display in the dropdown, e.g. {@code "×1"}, {@code "×0.5"},
     * {@code "×1,000"}. Mirrors the badge format used by the timeline view.
     */
    private static String formatSpeedLabel(final Double speed) {
        if (speed == null) {
            return "";
        }
        if (speed >= 1000) {
            final long rounded = Math.round(speed);
            final String raw = String.valueOf(rounded);
            final int len = raw.length();
            if (len > 3) {
                return "x" + raw.substring(0, len - 3) + "," + raw.substring(len - 3);
            }
            return "x" + raw;
        }
        if (speed == Math.floor(speed)) {
            return "x" + (int) Math.round(speed);
        }
        return "x" + speed;
    }

    public interface Binder extends UiBinder<Widget, FloorMapTimelineSettingsViewImpl> {

    }
}
