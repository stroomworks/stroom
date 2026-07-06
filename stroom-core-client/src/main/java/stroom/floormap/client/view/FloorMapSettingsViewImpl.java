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

import stroom.document.client.event.DirtyUiHandlers;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

/**
 * GWT UiBinder view implementation for {@link stroom.floormap.client.presenter.FloorMapSettingsPresenter}.
 * <p>
 * Contains {@link SimplePanel} containers for store reference pickers (events and facts),
 * a value format dropdown widget, and a schema grid with its associated toolbar.
 * </p>
 * <p>
 * The layout is defined in the companion UiBinder template
 * {@code FloorMapSettingsViewImpl.ui.xml}.
 * </p>
 */
public class FloorMapSettingsViewImpl
        extends ViewWithUiHandlers<DirtyUiHandlers>
        implements FloorMapSettingsView, ReadOnlyChangeHandler {

    private final Widget widget;

    @UiField
    SimplePanel eventsStoreRefContainer;

    @UiField
    SimplePanel factsStoreRefContainer;

    @UiField
    SimplePanel valueFormatContainer;

    @UiField
    SimplePanel schemaToolbarContainer;

    @UiField
    SimplePanel schemaGridContainer;

    @Inject
    public FloorMapSettingsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    /**
     * Sets the view for the events store reference picker.
     *
     * @param view the store reference picker view to place inside the events container
     */
    @Override
    public void setEventsStoreRefView(final View view) {
        this.eventsStoreRefContainer.setWidget(view.asWidget());
    }

    /**
     * Sets the view for the facts store reference picker.
     *
     * @param view the store reference picker view to place inside the facts container
     */
    @Override
    public void setFactsStoreRefView(final View view) {
        this.factsStoreRefContainer.setWidget(view.asWidget());
    }

    /**
     * Sets the widget used for selecting the value format (e.g. a dropdown).
     *
     * @param widget the value format selection widget
     */
    @Override
    public void setValueFormatWidget(final Widget widget) {
        this.valueFormatContainer.setWidget(widget);
    }

    /**
     * Sets the toolbar widget displayed above the schema grid.
     *
     * @param toolbar the toolbar widget for schema-related actions
     */
    @Override
    public void setSchemaToolbar(final Widget toolbar) {
        this.schemaToolbarContainer.setWidget(toolbar);
    }

    /**
     * Sets the grid widget that displays the schema configuration.
     *
     * @param grid the schema grid widget
     */
    @Override
    public void setSchemaGrid(final Widget grid) {
        this.schemaGridContainer.setWidget(grid);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Currently a no-op — this view does not yet adjust any UI elements
     * in response to read-only state changes.
     * </p>
     *
     * @param readOnly {@code true} if the view should be read-only, {@code false} otherwise
     */
    @Override
    public void onReadOnly(final boolean readOnly) {
        // No code
    }

    // --------------------------------------------------------------------------------

    /**
     * GWT UiBinder interface that binds {@code FloorMapSettingsViewImpl.ui.xml}
     * to this view implementation.
     */
    public interface Binder extends UiBinder<Widget, FloorMapSettingsViewImpl> {

    }
}
