/*
 * Copyright 2026 Crown Copyright
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


package stroom.pipelinefactory.client.component;

import stroom.pipelinefactory.shared.PipelineFactoryCombination;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datepicker.client.ClientDateUtil;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/// What a Pipeline Factory has met and made (design 06 §3.5): one row per combination, with what
/// became of its pipeline, with Open, Forget and Refresh. One row can be selected; a double click
/// opens its pipeline. Open is enabled only while the pipeline made for the selected combination is
/// still there, and Forget only when the factory isn't read only.
public class MadePipelineList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ToolbarButton openButton;
    @UiField
    ToolbarButton forgetButton;
    @UiField
    ToolbarButton refreshButton;
    @UiField
    DataGrid<PipelineFactoryCombination> dataGrid;

    private final MultiSelectionModelImpl<PipelineFactoryCombination> selectionModel;
    private Consumer<PipelineFactoryCombination> onOpen = combination -> {
    };
    private Consumer<PipelineFactoryCombination> onForget = combination -> {
    };
    private Runnable onRefresh = () -> {
    };

    /// Makes an empty list.
    public MadePipelineList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        addColumns();

        openButton.setOnClick(this::open);
        forgetButton.setOnClick(() -> {
            final PipelineFactoryCombination selected = selectionModel.getSelected();
            if (selected != null && !isReadOnly()) {
                onForget.accept(selected);
            }
        });
        refreshButton.setOnClick(() -> onRefresh.run());
        selectionModel.addSelectionHandler(event -> {
            enableButtons();
            if (event.getSelectionType().isDoubleSelect()) {
                open();
            }
        });
        enableButtons();
    }

    /// Shows the combinations, none selected.
    ///
    /// @param combinations The combinations, in the order to show them.
    public void setData(final List<PipelineFactoryCombination> combinations) {
        selectionModel.clear();
        dataGrid.setRowData(0, combinations);
        dataGrid.setRowCount(combinations.size());
        enableButtons();
    }

    /// @param onOpen Given the selected combination, whose pipeline is still there, when Open is
    ///               pressed or it is double clicked.
    public void setOnOpen(final Consumer<PipelineFactoryCombination> onOpen) {
        this.onOpen = onOpen;
    }

    /// @param onForget Given the selected combination when Forget is pressed (not when read only).
    public void setOnForget(final Consumer<PipelineFactoryCombination> onForget) {
        this.onForget = onForget;
    }

    /// @param onRefresh Run when Refresh is pressed.
    public void setOnRefresh(final Runnable onRefresh) {
        this.onRefresh = onRefresh;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        enableButtons();
    }

    private void open() {
        final PipelineFactoryCombination selected = selectionModel.getSelected();
        if (selected != null && selected.isPipelineExists()) {
            onOpen.accept(selected);
        }
    }

    private void enableButtons() {
        final PipelineFactoryCombination selected = selectionModel.getSelected();
        openButton.setEnabled(selected != null && selected.isPipelineExists());
        forgetButton.setEnabled(selected != null && !isReadOnly());
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(CombinationText::describe).build(),
                DataGridUtil.headingBuilder("Combination")
                        .withToolTip("The key values the factory met.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(CombinationText::state).build(),
                DataGridUtil.headingBuilder("State")
                        .withToolTip("Whether its pipeline was made, and whether it is still there.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((PipelineFactoryCombination row) -> row.getPipeline() == null
                        ? ""
                        : nonNull(row.getPipeline().getName())).build(),
                DataGridUtil.headingBuilder("Pipeline")
                        .withToolTip("The pipeline made for it.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((PipelineFactoryCombination row) ->
                        Integer.toString(row.getTries())).rightAligned().build(),
                DataGridUtil.headingBuilder("Tries")
                        .withToolTip("How many times the factory has tried to make its pipeline.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((PipelineFactoryCombination row) ->
                        nonNull(ClientDateUtil.toISOString(row.getCreateTimeMs()))).build(),
                DataGridUtil.headingBuilder("First met").build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((PipelineFactoryCombination row) ->
                        nonNull(ClientDateUtil.toISOString(row.getUpdateTimeMs()))).build(),
                DataGridUtil.headingBuilder("Last changed").build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder((PipelineFactoryCombination row) ->
                        nonNull(row.getMessage())).build(),
                DataGridUtil.headingBuilder("Why it failed")
                        .withToolTip("Why the last try to make its pipeline failed. It is tried again later.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static String nonNull(final String text) {
        return text == null
                ? ""
                : text;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, MadePipelineList> {

    }
}
