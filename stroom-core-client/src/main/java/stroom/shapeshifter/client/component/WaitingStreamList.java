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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.shared.BacklogStream;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.datepicker.client.ClientDateUtil;
import stroom.widget.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.AbstractDataProvider;
import com.google.gwt.view.client.Range;

/// The selected learning pipeline's waiting streams (design 04 §6, design 06 A115), a row per stream:
/// whether it has been raised, its feed and type, its id, since when it has waited, how many times it has
/// been tried, the transform it fell short of and why; oldest first. Nothing here is an action.
public class WaitingStreamList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    PagerPanel pager;
    @UiField
    DataGrid<BacklogStream> dataGrid;

    /// Makes an empty list.
    public WaitingStreamList() {
        initWidget(BINDER.createAndBindUi(this));
        addColumns();
    }

    /// @param dataProvider Gives the list its rows, a page at a time.
    public void setDataProvider(final AbstractDataProvider<BacklogStream> dataProvider) {
        dataProvider.addDataDisplay(dataGrid);
    }

    /// @return The task monitor that spins the pager's refresh button.
    public TaskMonitorFactory getTaskMonitor() {
        return pager;
    }

    /// Goes back to the first page, e.g. for another pipeline's streams.
    public void showFirstPage() {
        dataGrid.setVisibleRange(new Range(0, PageRequest.DEFAULT_PAGE_LENGTH));
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) -> text(stream.isRaised()
                        ? "Raised"
                        : "")).build(),
                DataGridUtil.headingBuilder("Raised")
                        .withToolTip("A stream that has waited past the document's maximum deferral age. It "
                                     + "goes on waiting; this is the flag that says somebody should look.")
                        .build(),
                70);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(stream.getFeed() + " / " + stream.getType())).build(),
                DataGridUtil.headingBuilder("Feed and type").build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(Long.toString(stream.getInputId()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Stream")
                        .withToolTip("The stream's id, as the data browser has it.")
                        .rightAligned()
                        .build(),
                80);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(ClientDateUtil.toISOString(stream.getFirstDeferredMs()))).build(),
                DataGridUtil.headingBuilder("Waiting since")
                        .withToolTip("When the stream first fell short and was held.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(Integer.toString(stream.getTries()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Tries")
                        .withToolTip("How many times a task has decided this stream and held it again.")
                        .rightAligned()
                        .build(),
                60);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(NullSafe.getOrElse(stream.getFailedAgainst(), v -> v, "nothing bound"))).build(),
                DataGridUtil.headingBuilder("Held against")
                        .withToolTip("The snapshot of the transform the stream was run through and fell "
                                     + "short of. A promotion moves it on.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) -> text(stream.getReason())).build(),
                DataGridUtil.headingBuilder("Reason").build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, WaitingStreamList> {

    }
}
