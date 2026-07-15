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

import stroom.data.grid.client.MyDataGrid;
import stroom.floormap.client.presenter.FloorMapUserListPresenter.FloorMapUserListView;
import stroom.floormap.shared.FloorMapUserList.UserEntry;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.TextColumn;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.CellPreviewEvent;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.function.Consumer;

/**
 * Presenter for the User Tracking panel on the Map tab — a grid listing every
 * user (person object) seen on the floor map during playback.
 *
 * <p>Selecting a row tracks that user: the parent presenter highlights them on
 * the canvas and the viewport follows them as they move. Because a manual
 * pan/zoom pauses following, clicking the <em>already-selected</em> row
 * re-invokes the selection consumer (a plain {@code SelectionChangeEvent}
 * would not fire) so the user can resume following without deselecting first.
 * The toolbar's single <strong>Stop Tracking</strong> button clears the
 * selection.</p>
 */
public class FloorMapUserListPresenter extends MyPresenterWidget<FloorMapUserListView> {

    private final MyDataGrid<UserEntry> dataGrid;
    private final ListDataProvider<UserEntry> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<UserEntry> selectionModel = new SingleSelectionModel<>();
    private Consumer<UserEntry> selectionConsumer;

    private final ButtonView stopTrackingButton;

    @Inject
    public FloorMapUserListPresenter(final EventBus eventBus,
                                     final FloorMapUserListView view) {
        super(eventBus, view);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setSelectionModel(selectionModel);
        view.setGridView(dataGrid);
        initGridColumns();
        dataProvider.addDataDisplay(dataGrid);

        // Toolbar
        final ButtonPanel buttonPanel = new ButtonPanel();
        stopTrackingButton = buttonPanel.addButton(SvgPresets.CLEAR);
        stopTrackingButton.setTitle("Stop Tracking");
        view.setToolbar(buttonPanel);
    }

    @Override
    protected void onBind() {
        super.onBind();
        //noinspection unused e
        registerHandler(selectionModel.addSelectionChangeHandler(e -> {
            final UserEntry selected = selectionModel.getSelectedObject();
            stopTrackingButton.setEnabled(selected != null);
            if (selectionConsumer != null) {
                selectionConsumer.accept(selected);
            }
        }));

        // Clicking the row that is already selected does not fire a
        // SelectionChangeEvent, but it must still re-invoke the consumer —
        // that is the "resume following after a manual pan/zoom" gesture.
        registerHandler(dataGrid.addCellPreviewHandler((final CellPreviewEvent<UserEntry> e) -> {
            if ("click".equals(e.getNativeEvent().getType())) {
                final UserEntry clicked = e.getValue();
                if (clicked != null
                        && clicked.equals(selectionModel.getSelectedObject())
                        && selectionConsumer != null) {
                    selectionConsumer.accept(clicked);
                }
            }
        }));

        //noinspection unused e
        registerHandler(stopTrackingButton.addClickHandler(e ->
                selectionModel.clear()));
    }

    private void initGridColumns() {
        // Name Column
        final Column<UserEntry, String> nameColumn = new TextColumn<>() {
            @Override
            public String getValue(final UserEntry entry) {
                return entry.getDisplayName();
            }
        };
        dataGrid.addColumn(nameColumn, "Name");

        // Id Column (the full entity id, e.g. an email address)
        final Column<UserEntry, String> idColumn = new TextColumn<>() {
            @Override
            public String getValue(final UserEntry entry) {
                return entry.getId();
            }
        };
        dataGrid.addColumn(idColumn, "Id");
    }

    /**
     * Replaces the entire grid data with the supplied list.
     *
     * <p>The current selection is <em>not</em> automatically adjusted —
     * callers should follow up with {@link #setSelected(String)}. Because
     * {@link UserEntry} equality is id-based, restoring the same id does not
     * re-fire the selection consumer.</p>
     *
     * @param data the users to display; must not be {@code null}
     */
    public void setData(final List<UserEntry> data) {
        dataProvider.setList(data);
        dataGrid.setRowData(0, data);
    }

    /**
     * Selects the grid row whose user id matches the given value.
     *
     * <p>If {@code id} is {@code null} or no matching row is found, the
     * current selection is cleared.</p>
     *
     * @param id the user id to look for; may be {@code null}
     */
    public void setSelected(final String id) {
        final List<UserEntry> list = dataProvider.getList();
        if (list != null && id != null) {
            for (final UserEntry entry : list) {
                if (id.equals(entry.getId())) {
                    selectionModel.setSelected(entry, true);
                    return;
                }
            }
        }
        selectionModel.clear();
    }

    /**
     * Returns the id of the currently selected user, or {@code null} if
     * nothing is selected.
     */
    public String getSelectedId() {
        final UserEntry selected = selectionModel.getSelectedObject();
        return selected != null
                ? selected.getId()
                : null;
    }

    /**
     * Registers a callback invoked whenever the tracked user changes.
     *
     * <p>The consumer receives the newly selected {@link UserEntry}, or
     * {@code null} when tracking stops. It is also re-invoked with the current
     * entry when the already-selected row is clicked again (resume-follow
     * gesture).</p>
     *
     * @param selectionConsumer called on every selection change or re-click
     */
    public void setSelectionConsumer(final Consumer<UserEntry> selectionConsumer) {
        this.selectionConsumer = selectionConsumer;
    }

    /**
     * View contract for the User Tracking panel.
     *
     * <p>Implementations provide the layout that hosts the data grid and the
     * toolbar strip above it.</p>
     */
    public interface FloorMapUserListView extends View {

        /**
         * Sets the data-grid widget into the main content area of the panel.
         *
         * @param gridWidget the data grid widget; must not be {@code null}
         */
        void setGridView(Widget gridWidget);

        /**
         * Sets the toolbar widget (containing the Stop Tracking button) into
         * the toolbar area above the grid.
         *
         * @param toolbarWidget the toolbar widget; must not be {@code null}
         */
        void setToolbar(Widget toolbarWidget);
    }
}
