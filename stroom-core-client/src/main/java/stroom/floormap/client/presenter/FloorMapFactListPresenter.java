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
import stroom.floormap.client.ValuePathAccessor;
import stroom.floormap.client.presenter.FloorMapFactListPresenter.FloorMapFactListView;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.util.client.JSONUtil;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.InlineSvgToggleButton;

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

import java.util.List;
import java.util.function.Consumer;

/**
 * Presenter for the Fact List panel — a grid that lists all objects (facts)
 * known to the current floor map, allowing the user to select one for editing.
 *
 * <p>This presenter is used in two contexts:</p>
 * <ul>
 *   <li><strong>Editor tab</strong> ({@link FloorMapEditorPresenter}) — selection in the grid
 *       drives the Time List and Properties panels so the user can edit temporal
 *       entries for the selected object.</li>
 *   <li><strong>Map tab</strong> ({@link FloorMapMapPresenter}) — selection in the grid
 *       highlights the corresponding object on the SVG canvas.</li>
 * </ul>
 *
 * <p>The grid displays three columns: <em>Key</em>, <em>Type</em>, and <em>Name</em>,
 * populated from {@link FactObject} instances that are derived from
 * {@link stroom.util.shared.TemporalEntry} records.</p>
 *
 * <p>The toolbar provides:</p>
 * <ul>
 *   <li><strong>Add</strong> — adds a new object (delegated via {@link #setAddConsumer(Runnable)}).</li>
 *   <li><strong>Delete</strong> — deletes the currently selected object (delegated via
 *       {@link #setDeleteConsumer(Consumer)}). Disabled when nothing is selected.</li>
 *   <li><strong>Show All</strong> (toggle) — when ON, instructs the parent presenter to ignore
 *       the current time filter and display all objects; when OFF, reverts to time-filtered
 *       mode.</li>
 * </ul>
 *
 * <p>Renamed from {@code FloorMapObjectListPresenter}.</p>
 */
public class FloorMapFactListPresenter extends MyPresenterWidget<FloorMapFactListView> {

    private final MyDataGrid<FactObject> dataGrid;
    private final ListDataProvider<FactObject> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<FactObject> selectionModel = new SingleSelectionModel<>();
    private Consumer<FactObject> selectionConsumer;
    private Runnable showAllConsumer;
    private Runnable showTimeFilteredConsumer;
    private boolean showingAll = false;

    private final ButtonView addButton;
    private final ButtonView deleteButton;
    private Runnable addConsumer;
    private Consumer<String> deleteConsumer;

    @Inject
    public FloorMapFactListPresenter(final EventBus eventBus,
                                     final FloorMapFactListView view) {
        super(eventBus, view);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setSelectionModel(selectionModel);
        view.setGridView(dataGrid);
        initGridColumns();
        dataProvider.addDataDisplay(dataGrid);

        // Toolbar
        final ButtonPanel buttonPanel = new ButtonPanel();
        addButton = buttonPanel.addButton(SvgPresets.ADD);
        addButton.setTitle("Add New Object");
        deleteButton = buttonPanel.addButton(SvgPresets.DELETE);
        deleteButton.setTitle("Delete Object");
        deleteButton.setEnabled(false);

        // Show All toggle button
        final InlineSvgToggleButton showAllButton = new InlineSvgToggleButton();
        showAllButton.setSvg(SvgImage.HISTORY); // Clock image so correct for time stuff
        showAllButton.setTitle("Show All (ignore time filter)");
        showAllButton.setState(false);
        buttonPanel.addButton(showAllButton);
        view.setToolbar(buttonPanel);

        //noinspection unused e
        showAllButton.addClickHandler(e -> {
            showingAll = showAllButton.getState();
            if (showingAll) {
                if (showAllConsumer != null) {
                    showAllConsumer.run();
                }
            } else {
                if (showTimeFilteredConsumer != null) {
                    showTimeFilteredConsumer.run();
                }
            }
        });
    }

    @Override
    protected void onBind() {
        super.onBind();
        //noinspection unused e
        registerHandler(selectionModel.addSelectionChangeHandler(e -> {
            final FactObject selected = selectionModel.getSelectedObject();
            deleteButton.setEnabled(selected != null);
            if (selectionConsumer != null) {
                selectionConsumer.accept(selected);
            }

            if (selected != null) {
                final List<FactObject> list = dataProvider.getList();
                if (list != null) {
                    final int index = list.indexOf(selected);
                    if (index >= 0) {
                        com.google.gwt.core.client.Scheduler.get().scheduleDeferred(() -> {
                            if (index < dataGrid.getVisibleItemCount()) {
                                try {
                                    final com.google.gwt.dom.client.TableRowElement rowEl =
                                            dataGrid.getRowElement(index);
                                    if (rowEl != null) {
                                        stroom.widget.util.client.ElementUtil.scrollIntoViewNearest(rowEl);
                                    }
                                } catch (final Exception ex) {
                                    // Ignore - if the scroll doesn't work it doesn't matter
                                }
                            }
                        });
                    }
                }
            }
        }));

        //noinspection unused e
        registerHandler(addButton.addClickHandler(e -> {
            if (addConsumer != null) {
                addConsumer.run();
            }
        }));

        //noinspection unused e
        registerHandler(deleteButton.addClickHandler(e -> {
            if (deleteConsumer != null) {
                final FactObject selected = selectionModel.getSelectedObject();
                if (selected != null) {
                    deleteConsumer.accept(selected.getKey());
                }
            }
        }));
    }

    private void initGridColumns() {
        // Key Column
        final Column<FactObject, String> keyColumn = new TextColumn<>() {
            @Override
            public String getValue(final FactObject object) {
                return object.getKey();
            }
        };
        dataGrid.addColumn(keyColumn, "Key");

        // Type Column
        final Column<FactObject, String> typeColumn = new TextColumn<>() {
            @Override
            public String getValue(final FactObject object) {
                return object.getType();
            }
        };
        dataGrid.addColumn(typeColumn, "Type");

        // Name Column
        final Column<FactObject, String> nameColumn = new TextColumn<>() {
            @Override
            public String getValue(final FactObject object) {
                return object.getName();
            }
        };
        dataGrid.addColumn(nameColumn, "Name");
    }

    /**
     * Replaces the entire grid data with the supplied list.
     *
     * <p>Both the backing {@link ListDataProvider} and the visible
     * {@link MyDataGrid} row data are updated. The current selection is
     * <em>not</em> automatically adjusted — callers should follow up with
     * {@link #setSelected(String)} or {@link #selectLast()} as appropriate.</p>
     *
     * @param data the list of fact objects to display; must not be {@code null}
     */
    public void setData(final List<FactObject> data) {
        dataProvider.setList(data);
        dataGrid.setRowData(0, data);
    }

    /**
     * Selects the last item in the grid, or clears the selection if the grid
     * is empty.
     *
     * <p>Typically called after a new object is added so that the freshly
     * created entry is immediately selected and visible to the user.</p>
     */
    public void selectLast() {
        final List<FactObject> list = dataProvider.getList();
        if (list != null && !list.isEmpty()) {
            //noinspection SequencedCollectionMethodCanBeUsed
            selectionModel.setSelected(list.get(list.size() - 1), true);
        } else {
            selectionModel.clear();
        }
    }

    /**
     * Selects the grid row whose key matches the given value.
     *
     * <p>If {@code key} is {@code null} or no matching row is found, the
     * current selection is cleared. This is the primary mechanism used by the
     * Editor and Map tabs to restore a previous selection after the grid data
     * has been refreshed.</p>
     *
     * @param key the temporal-store key to look for; may be {@code null}
     */
    public void setSelected(final String key) {
        final List<FactObject> list = dataProvider.getList();
        if (list != null && key != null) {
            for (final FactObject obj : list) {
                if (key.equals(obj.getKey())) {
                    selectionModel.setSelected(obj, true);
                    return;
                }
            }
        }
        selectionModel.clear();
    }

    /**
     * Returns the currently selected {@link FactObject}, or {@code null} if
     * nothing is selected.
     *
     * @return the selected fact object, or {@code null}
     */
    public FactObject getSelectedObject() {
        return selectionModel.getSelectedObject();
    }

    /**
     * Registers a callback that is invoked whenever the grid selection changes.
     *
     * <p>The consumer receives the newly selected {@link FactObject}, or
     * {@code null} if the selection was cleared. Both the Editor tab and the
     * Map tab register a consumer here to react to selection changes.</p>
     *
     * @param selectionConsumer called on every selection change
     */
    public void setSelectionConsumer(final Consumer<FactObject> selectionConsumer) {
        this.selectionConsumer = selectionConsumer;
    }

    /**
     * Sets the action to run when the user clicks the Add button.
     *
     * @param addConsumer called when the add button is clicked
     */
    public void setAddConsumer(final Runnable addConsumer) {
        this.addConsumer = addConsumer;
    }

    /**
     * Sets the action to run when the user clicks the Delete button.
     * The consumer receives the key of the selected fact.
     *
     * @param deleteConsumer called with the selected fact's key
     */
    public void setDeleteConsumer(final Consumer<String> deleteConsumer) {
        this.deleteConsumer = deleteConsumer;
    }

    /**
     * Sets the action to run when the user toggles "Show all" ON.
     *
     * @param showAllConsumer called when show-all is activated
     */
    public void setShowAllConsumer(final Runnable showAllConsumer) {
        this.showAllConsumer = showAllConsumer;
    }

    /**
     * Sets the action to run when the user toggles "Show all" OFF.
     *
     * @param showTimeFilteredConsumer called when time-filtered mode is restored
     */
    public void setShowTimeFilteredConsumer(final Runnable showTimeFilteredConsumer) {
        this.showTimeFilteredConsumer = showTimeFilteredConsumer;
    }

    // -----------------------------------------------------------------------

    /**
     * Represents a single object (fact) entry shown in the list.
     * Identified by its temporal-store key; carries display name and type.
     */
    @SuppressWarnings("ClassCanBeRecord")
    public static class FactObject {

        private final String key;
        private final String name;
        private final String type;

        public FactObject(final String key, final String name, final String type) {
            this.key = key;
            this.name = name;
            this.type = type;
        }

        /**
         * Creates a {@link FactObject} from a {@link stroom.util.shared.TemporalEntry}
         * by parsing the JSON value for {@code name} and {@code type}.
         *
         * @param entry  the temporal entry; must not be {@code null}
         * @param schema the value schema used to resolve field paths
         * @return a new fact object; never {@code null}
         */
        public static FactObject fromEntry(final stroom.util.shared.TemporalEntry entry,
                                           final List<FloorMapFieldMapping> schema) {
            String name = entry.getKey();
            String type = "";
            try {
                if (entry.getValue() != null && entry.getValue().trim().startsWith("{")) {
                    final JSONObject json = ValuePathAccessor.parse(entry.getValue());
                    if (json != null) {
                        final String parsedName = JSONUtil.getString(
                                ValuePathAccessor.get(json,
                                        FloorMapEntryParser.findPath(schema, Role.LABEL)));
                        final String parsedType = JSONUtil.getString(
                                ValuePathAccessor.get(json,
                                        FloorMapEntryParser.findPath(schema, Role.TYPE)));
                        if (parsedName != null && !parsedName.isEmpty()) {
                            name = parsedName;
                        }
                        if (parsedType != null) {
                            type = parsedType;
                        }
                    }
                }
            } catch (final Exception ex) {
                // Use key as display name
            }
            return new FactObject(entry.getKey(), name, type);
        }

        public String getKey() {
            return key;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final FactObject that = (FactObject) o;
            return java.util.Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(key);
        }
    }

    /**
     * View contract for the Fact List panel.
     *
     * <p>Implementations provide the layout that hosts the data grid and
     * the toolbar strip above it.</p>
     */
    public interface FloorMapFactListView extends View {

        /**
         * Sets the data-grid widget into the main content area of the panel.
         *
         * @param gridWidget the data grid widget; must not be {@code null}
         */
        void setGridView(Widget gridWidget);

        /**
         * Sets the toolbar widget (containing Add, Delete, Show All buttons)
         * into the toolbar area above the grid.
         *
         * @param toolbarWidget the toolbar widget; must not be {@code null}
         */
        void setToolbar(Widget toolbarWidget);
    }
}
