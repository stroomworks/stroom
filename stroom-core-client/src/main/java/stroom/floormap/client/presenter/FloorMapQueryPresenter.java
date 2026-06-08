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

import stroom.entity.client.presenter.HasToolbar;
import stroom.floormap.client.presenter.FloorMapQueryPresenter.FloorMapQueryView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.query.api.Column;
import stroom.query.client.presenter.QueryEditPresenter;
import stroom.query.shared.QueryTablePreferences;
import stroom.task.client.TaskMonitorFactory;

import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import javax.inject.Inject;

public class FloorMapQueryPresenter extends MyPresenterWidget<FloorMapQueryView> implements HasToolbar {

    private final QueryEditPresenter queryEditPresenter;
    private String currentEntityColumn;
    private String currentLocationColumn;

    @Inject
    public FloorMapQueryPresenter(final EventBus eventBus,
                                  final FloorMapQueryView view,
                                  final QueryEditPresenter queryEditPresenter) {
        super(eventBus, view);
        this.queryEditPresenter = queryEditPresenter;
        view.setQueryEditView(queryEditPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();

        // Listen to column updates inside the table so we can update the dropdown lists dynamically.
        registerHandler(queryEditPresenter.addChangeHandler(this::updateColumnSelections));
    }

    private void updateColumnSelections() {
        final QueryTablePreferences tablePrefs = queryEditPresenter.write();
        if (tablePrefs != null && tablePrefs.getColumns() != null) {
            final List<String> colNames = tablePrefs
                    .getColumns()
                    .stream()
                    .map(Column::getName)
                    .toList();

            // Save the user's current selection before repopulating.
            final String selectedEntity = getView().getEntityIdColumn();
            final String selectedLocation = getView().getLocationIdColumn();
            getView().setAvailableColumns(colNames);

            // Re-apply selections if they still exist in the updated column list.
            if (colNames.contains(selectedEntity)) {
                getView().setEntityIdColumn(selectedEntity);
                this.currentEntityColumn = selectedEntity;
            } else if (colNames.contains(currentEntityColumn)) {
                getView().setEntityIdColumn(currentEntityColumn);
            }

            if (colNames.contains(selectedLocation)) {
                getView().setLocationIdColumn(selectedLocation);
                this.currentLocationColumn = selectedLocation;
            } else if (colNames.contains(currentLocationColumn)) {
                getView().setLocationIdColumn(currentLocationColumn);
            }
        }
    }

    public void read(final FloorMapDoc doc) {
        this.currentEntityColumn = doc.getEntityIdColumn();
        this.currentLocationColumn = doc.getLocationIdColumn();

        getView().setEntityIdColumn(currentEntityColumn);
        getView().setLocationIdColumn(currentLocationColumn);

        // Populate the inner query editor.
        queryEditPresenter.setQuery(doc.asDocRef(), doc.getQuery(), false);
        queryEditPresenter.setTimeRange(doc.getQueryTimeRange());
        queryEditPresenter.read(doc.getQueryTablePreferences());

        updateColumnSelections();
    }

    public FloorMapDoc write(final FloorMapDoc doc) {
        this.currentEntityColumn = getView().getEntityIdColumn();
        this.currentLocationColumn = getView().getLocationIdColumn();

        return doc.copy()
                .entityIdColumn(currentEntityColumn)
                .locationIdColumn(currentLocationColumn)
                .query(queryEditPresenter.getQuery())
                .queryTimeRange(queryEditPresenter.getTimeRange())
                .queryTablePreferences(queryEditPresenter.write())
                .build();
    }

    public void setTaskMonitorFactory(final TaskMonitorFactory taskMonitorFactory) {
        queryEditPresenter.setTaskMonitorFactory(taskMonitorFactory);
    }

    @Override
    public List<Widget> getToolbars() {
        return queryEditPresenter.getToolbars();
    }

    public interface FloorMapQueryView extends View {
        void setQueryEditView(View view);

        void setAvailableColumns(List<String> columnNames);

        void setEntityIdColumn(String entityId);
        void setLocationIdColumn(String locationId);

        String getEntityIdColumn();
        String getLocationIdColumn();
    }
}
