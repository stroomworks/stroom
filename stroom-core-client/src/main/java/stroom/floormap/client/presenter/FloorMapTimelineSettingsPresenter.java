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

import stroom.floormap.client.presenter.FloorMapTimelineSettingsPresenter.FloorMapTimelineSettingsView;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.popup.client.presenter.PopupPosition.PopupLocation;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.Rect;

import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.function.Consumer;

/**
 * Presenter for the timeline settings popup.
 * Shows playback speed and date range controls when the settings button is clicked.
 */
public class FloorMapTimelineSettingsPresenter
        extends MyPresenterWidget<FloorMapTimelineSettingsView> {

    @Inject
    public FloorMapTimelineSettingsPresenter(final EventBus eventBus,
                                             final FloorMapTimelineSettingsView view) {
        super(eventBus, view);
    }

    /**
     * Show the settings popup positioned relative to the anchor widget (e.g. the settings button).
     *
     * @param anchor The widget to anchor the popup above/below.
     */
    public void show(final Widget anchor) {
        Rect relativeRect = new Rect(anchor.getElement());
        relativeRect = relativeRect.grow(3);
        final PopupPosition popupPosition = new PopupPosition(relativeRect, PopupLocation.ABOVE);
        ShowPopupEvent.builder(this)
                .popupType(PopupType.POPUP)
                .popupPosition(popupPosition)
                .addAutoHidePartner(anchor.getElement())
                .fire();
    }

    public void hide() {
        HidePopupRequestEvent.builder(this).fire();
    }

    public void setSpeedOptions(final List<Double> speeds) {
        getView().setSpeedOptions(speeds);
    }

    public void setSelectedSpeed(final Double speed) {
        getView().setSelectedSpeed(speed);
    }

    public void setSpeedChangeHandler(final Consumer<Double> handler) {
        getView().setSpeedChangeHandler(handler);
    }

    public void setStartTime(final long startTime) {
        getView().setStartTime(startTime);
    }

    public void setEndTime(final long endTime) {
        getView().setEndTime(endTime);
    }

    public long getStartTime() {
        return getView().getStartTime();
    }

    public long getEndTime() {
        return getView().getEndTime();
    }

    public HandlerRegistration addStartTimeChangeHandler(final ValueChangeHandler<String> handler) {
        return getView().addStartTimeChangeHandler(handler);
    }

    public HandlerRegistration addEndTimeChangeHandler(final ValueChangeHandler<String> handler) {
        return getView().addEndTimeChangeHandler(handler);
    }

    public interface FloorMapTimelineSettingsView extends View {

        void setSpeedOptions(List<Double> speeds);

        void setSelectedSpeed(Double speed);

        void setSpeedChangeHandler(Consumer<Double> handler);

        void setStartTime(long startTime);

        void setEndTime(long endTime);

        long getStartTime();

        long getEndTime();

        HandlerRegistration addStartTimeChangeHandler(ValueChangeHandler<String> handler);

        HandlerRegistration addEndTimeChangeHandler(ValueChangeHandler<String> handler);
    }
}
