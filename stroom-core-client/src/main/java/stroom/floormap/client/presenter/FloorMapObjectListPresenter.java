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
import stroom.floormap.client.presenter.FloorMapObjectListPresenter.FloorMapObjectListView;

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

public class FloorMapObjectListPresenter extends MyPresenterWidget<FloorMapObjectListView> {

    private final MyDataGrid<FactObject> dataGrid;
    private final ListDataProvider<FactObject> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<FactObject> selectionModel = new SingleSelectionModel<>();
    private Consumer<FactObject> selectionConsumer;

    @Inject
    public FloorMapObjectListPresenter(final EventBus eventBus,
                                       final FloorMapObjectListView view) {
        super(eventBus, view);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setSelectionModel(selectionModel);
        view.setGridView(dataGrid);
        initGridColumns();
        dataProvider.addDataDisplay(dataGrid);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(selectionModel.addSelectionChangeHandler(e -> {
            if (selectionConsumer != null) {
                selectionConsumer.accept(selectionModel.getSelectedObject());
            }

            final FactObject selected = selectionModel.getSelectedObject();
            if (selected != null) {
                final List<FactObject> list = dataProvider.getList();
                if (list != null) {
                    final int index = list.indexOf(selected);
                    if (index >= 0) {
                        com.google.gwt.core.client.Scheduler.get().scheduleDeferred(() -> {
                            if (index < dataGrid.getVisibleItemCount()) {
                                try {
                                    final com.google.gwt.dom.client.TableRowElement rowEl = dataGrid.getRowElement(index);
                                    if (rowEl != null) {
                                        stroom.widget.util.client.ElementUtil.scrollIntoViewNearest(rowEl);
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
    }

    private void initGridColumns() {
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

    public static class FactObject {
        private final String key;
        private final String name;
        private final String type;

        public FactObject(final String key, final String name, final String type) {
            this.key = key;
            this.name = name;
            this.type = type;
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

    public interface FloorMapObjectListView extends View {
        void setGridView(Widget gridWidget);
    }
}
