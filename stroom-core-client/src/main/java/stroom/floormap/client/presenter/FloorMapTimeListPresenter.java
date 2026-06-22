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
import stroom.floormap.client.presenter.FloorMapTimeListPresenter.FloorMapTimeListView;
import stroom.svg.client.SvgPresets;
import stroom.util.client.JSONUtil;
import stroom.util.shared.TemporalEntry;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.dom.client.TableRowElement;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.TextColumn;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Presenter for the Time List panel — the centre column of the Editor tab's
 * bottom strip.
 *
 * <p>Shows every {@link TemporalEntry} for the currently selected fact,
 * sorted ascending by effective time (oldest first, most recent last).
 * When a fact is selected, {@link #setData(List)} populates the grid and
 * {@link #selectLast()} auto-selects the last (most recent) entry.</p>
 *
 * <h3>Toolbar</h3>
 * <p>Add and Delete buttons. Delete is enabled only when a row is selected.
 * Clicks are reported via consumers that the Editor presenter sets.</p>
 */
public class FloorMapTimeListPresenter extends MyPresenterWidget<FloorMapTimeListView> {

    private final MyDataGrid<TemporalEntry> dataGrid;
    private final ListDataProvider<TemporalEntry> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<TemporalEntry> selectionModel = new SingleSelectionModel<>();

    private final ButtonView addButton;
    private final ButtonView deleteButton;

    private Consumer<TemporalEntry> selectionConsumer;
    private Runnable addConsumer;
    private Consumer<TemporalEntry> deleteConsumer;

    @Inject
    public FloorMapTimeListPresenter(final EventBus eventBus,
                                     final FloorMapTimeListView view) {
        super(eventBus, view);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setSelectionModel(selectionModel);
        view.setGridView(dataGrid);
        initGridColumns();
        dataProvider.addDataDisplay(dataGrid);

        final ButtonPanel toolbar = new ButtonPanel();
        addButton = toolbar.addButton(SvgPresets.ADD);
        deleteButton = toolbar.addButton(SvgPresets.DELETE);
        deleteButton.setEnabled(false);
        view.setToolbar(toolbar);
    }

    @Override
    protected void onBind() {
        super.onBind();

        //noinspection unused
        registerHandler(selectionModel.addSelectionChangeHandler(e -> {
            final TemporalEntry selected = selectionModel.getSelectedObject();
            deleteButton.setEnabled(selected != null);
            if (selectionConsumer != null) {
                selectionConsumer.accept(selected);
            }
            // Scroll selected row into view; headers + toolbar remain fixed
            if (selected != null) {
                final List<TemporalEntry> list = dataProvider.getList();
                if (list != null) {
                    final int index = list.indexOf(selected);
                    if (index >= 0) {
                        com.google.gwt.core.client.Scheduler.get().scheduleDeferred(() -> {
                            if (index < dataGrid.getVisibleItemCount()) {
                                try {
                                    final TableRowElement rowEl = dataGrid.getRowElement(index);
                                    if (rowEl != null) {
                                        stroom.widget.util.client.ElementUtil
                                                .scrollIntoViewNearest(rowEl);
                                    }
                                } catch (final Exception ex) {
                                    // Ignore
                                }
                            }
                        });
                    }
                }
            }
        }));

        //noinspection unused
        registerHandler(addButton.addClickHandler(e -> {
            if (addConsumer != null) {
                addConsumer.run();
            }
        }));

        //noinspection unused
        registerHandler(deleteButton.addClickHandler(e -> {
            if (deleteConsumer != null) {
                final TemporalEntry selected = selectionModel.getSelectedObject();
                if (selected != null) {
                    deleteConsumer.accept(selected);
                }
            }
        }));
    }

    // -----------------------------------------------------------------------
    // Data
    // -----------------------------------------------------------------------

    /**
     * Replaces the grid contents with the supplied entries.
     * The entries should already be sorted ascending by effective time.
     *
     * @param entries entries to display; may be {@code null} (treated as empty)
     */
    public void setData(final List<TemporalEntry> entries) {
        selectionModel.clear();
        deleteButton.setEnabled(false);
        if (entries != null) {
            dataProvider.setList(entries);
            dataGrid.setRowData(0, entries);
        } else {
            dataProvider.setList(Collections.emptyList());
            dataGrid.setRowData(0, Collections.emptyList());
        }
    }

    /**
     * Selects the last (most recent) entry in the grid.
     * No-op when the list is empty.
     */
    public void selectLast() {
        final List<TemporalEntry> list = dataProvider.getList();
        if (list != null && !list.isEmpty()) {
            //noinspection SequencedCollectionMethodCanBeUsed
            selectionModel.setSelected(list.get(list.size() - 1), true);
        }
    }

    /** Returns the currently selected entry, or {@code null} if none. */
    public TemporalEntry getSelectedEntry() {
        return selectionModel.getSelectedObject();
    }

    // -----------------------------------------------------------------------
    // Consumer wiring
    // -----------------------------------------------------------------------

    /**
     * Sets the consumer called whenever the row selection changes.
     *
     * @param selectionConsumer called with the selected entry, or {@code null}
     *                          when the selection is cleared
     */
    public void setSelectionConsumer(final Consumer<TemporalEntry> selectionConsumer) {
        this.selectionConsumer = selectionConsumer;
    }

    /**
     * Sets the action to perform when the Add button is clicked.
     *
     * @param addConsumer the runnable to invoke on Add
     */
    public void setAddConsumer(final Runnable addConsumer) {
        this.addConsumer = addConsumer;
    }

    /**
     * Sets the action to perform when the Delete button is clicked.
     * Called with the currently selected entry.
     *
     * @param deleteConsumer called with the entry to delete
     */
    public void setDeleteConsumer(final Consumer<TemporalEntry> deleteConsumer) {
        this.deleteConsumer = deleteConsumer;
    }

    // -----------------------------------------------------------------------
    // Grid columns
    // -----------------------------------------------------------------------

    private void initGridColumns() {
        final Column<TemporalEntry, String> timeColumn = new TextColumn<>() {
            @Override
            public String getValue(final TemporalEntry entry) {
                return new Date(entry.getEffectiveTimeMs()).toString();
            }
        };
        dataGrid.addColumn(timeColumn, "Effective Time");

        final Column<TemporalEntry, String> summaryColumn = new TextColumn<>() {
            @Override
            public String getValue(final TemporalEntry entry) {
                return extractSummary(entry);
            }
        };
        dataGrid.addColumn(summaryColumn, "Summary");
    }

    private static String extractSummary(final TemporalEntry entry) {
        final String value = entry.getValue();
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            if (value.trim().startsWith("{")) {
                final JSONObject json = JSONUtil.getObject(JSONUtil.parse(value));
                if (json != null) {
                    final String name = JSONUtil.getString(json.get("name"));
                    final String type = JSONUtil.getString(json.get("type"));
                    final StringBuilder sb = new StringBuilder();
                    if (type != null && !type.isEmpty()) {
                        sb.append(type);
                    }
                    if (name != null && !name.isEmpty()) {
                        //noinspection SizeReplaceableByIsEmpty Not in GWT
                        if (sb.length() > 0) {
                            sb.append(" - ");
                        }
                        sb.append(name);
                    }
                    //noinspection SizeReplaceableByIsEmpty Not in GWT
                    return sb.length() > 0 ? sb.toString() : value;
                }
            }
        } catch (final Exception ex) {
            // Fall through to raw value
        }
        return value;
    }

    // -----------------------------------------------------------------------
    // View interface
    // -----------------------------------------------------------------------

    public interface FloorMapTimeListView extends View {

        void setGridView(Widget gridWidget);

        void setToolbar(Widget toolbarWidget);

    }
}
