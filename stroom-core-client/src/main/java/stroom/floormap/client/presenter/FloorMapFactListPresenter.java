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
import stroom.floormap.client.presenter.FloorMapFactListPresenter.FloorMapFactListView;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.InlineSvgToggleButton;
import stroom.svg.client.SvgPresets;

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

    public void setData(final List<FactObject> data) {
        dataProvider.setList(data);
        dataGrid.setRowData(0, data);
    }

    public void selectLast() {
        final List<FactObject> list = dataProvider.getList();
        if (list != null && !list.isEmpty()) {
            //noinspection SequencedCollectionMethodCanBeUsed
            selectionModel.setSelected(list.get(list.size() - 1), true);
        } else {
            selectionModel.clear();
        }
    }

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

    public FactObject getSelectedObject() {
        return selectionModel.getSelectedObject();
    }

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
         * @param entry the temporal entry; must not be {@code null}
         * @return a new fact object; never {@code null}
         */
        public static FactObject fromEntry(final stroom.util.shared.TemporalEntry entry) {
            String name = entry.getKey();
            String type = "";
            try {
                if (entry.getValue() != null && entry.getValue().trim().startsWith("{")) {
                    final com.google.gwt.json.client.JSONObject json =
                            stroom.util.client.JSONUtil.getObject(
                                    stroom.util.client.JSONUtil.parse(entry.getValue()));
                    if (json != null) {
                        final String parsedName = stroom.util.client.JSONUtil.getString(
                                json.get(stroom.floormap.client.FloorMapJsonKeys.NAME));
                        final String parsedType = stroom.util.client.JSONUtil.getString(
                                json.get(stroom.floormap.client.FloorMapJsonKeys.TYPE));
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

    public interface FloorMapFactListView extends View {

        void setGridView(Widget gridWidget);

        void setToolbar(Widget toolbarWidget);
    }
}
