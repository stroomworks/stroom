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

package stroom.learningflow.client.component;

import stroom.docref.DocRef;
import stroom.learningflow.client.component.FeedSituation.Tone;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.svg.shared.SvgImage;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.button.client.ToolbarButtonCell;
import stroom.widget.button.client.ToolbarToggleButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.datagrid.client.model.ColumnSizing;
import stroom.widget.quickfilter.client.QuickFilterPage;
import stroom.widget.task.client.TaskMonitorFactory;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.cell.client.Cell.Context;
import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.AbstractDataProvider;
import com.google.gwt.view.client.Range;

import java.util.function.Consumer;

/// A table of Smart Pipelines and the state of each one's learning, used on a Learning Flow's Pipelines tab
/// and, for every Learning Flow, on the Smart Pipelines screen. [FeedListController] fills it.
///
/// There is a row for each pipeline (usually one per feed, as a Content Template creates them), with a button
/// that opens the pipeline at its Learning Status tab, where its learning is followed and acted on. A row
/// shows the pipeline's name; on the Smart Pipelines screen, the Learning Flow it learns under; a chip with
/// its state ([FeedSituation#listChip]); and a line saying what it scores, what is waiting or why it stopped
/// ([FeedSituation#listSummary]). Above the table is a quick filter on names. The pager's toolbar has auto
/// refresh and the filter button, which opens the dialog that chooses which pipelines to show
/// ([FeedFilterEditorController]), as Stroom's other lists are filtered.
public class FeedList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final Templates TEMPLATES = GWT.create(Templates.class);

    private static final String FILTER_TITLE = "Filter Pipelines";
    private static final String FILTERING_TITLE = "Pipelines are being filtered; click to change or clear";

    @UiField
    QuickFilterPage page;
    @UiField
    PagerPanel pager;
    @UiField
    ToolbarToggleButton autoRefreshButton;
    @UiField
    ToolbarButton filterButton;
    @UiField
    DataGrid<SupervisorLearner> dataGrid;

    private final MultiSelectionModelImpl<SupervisorLearner> selectionModel;
    private final Column<SupervisorLearner, String> learningFlowColumn =
            DataGridUtil.textColumnBuilder(FeedList::learningFlow).build();
    private boolean learningFlowShown;
    private Consumer<SupervisorLearner> onOpen = learner -> {
    };

    /// Creates an empty, unfiltered list.
    public FeedList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        // A pipeline that has been deleted gets no button, as there is nothing to open
        final ToolbarButtonCell<SupervisorLearner> open = new ToolbarButtonCell<SupervisorLearner>(SvgImage.OPEN,
                "feed-list__open",
                learner -> "Open pipeline " + name(learner),
                learner -> onOpen.accept(learner)) {
            @Override
            public void render(final Context context, final SupervisorLearner value, final SafeHtmlBuilder sb) {
                if (value != null && !value.isPipelineDeleted()) {
                    super.render(context, value, sb);
                }
            }
        };
        dataGrid.addColumn(DataGridUtil.column(learner -> learner, () -> open),
                DataGridUtil.headingBuilder("Open").build(),
                48);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(FeedList::name).build(),
                DataGridUtil.headingBuilder("Pipeline").build(),
                320);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder(FeedList::chip).build(),
                DataGridUtil.headingBuilder("Where it stands").build(),
                160);
        dataGrid.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder(FeedSituation::listSummary).build(),
                DataGridUtil.headingBuilder("Detail").build(),
                300);
    }

    /// Shows or hides the column, after the pipeline's name, that names the Learning Flow each pipeline learns
    /// under. It is shown when the list holds the pipelines of more than one Learning Flow.
    ///
    /// @param shown Whether the column is shown.
    public void setLearningFlowShown(final boolean shown) {
        if (shown == learningFlowShown) {
            return;
        }
        learningFlowShown = shown;
        if (shown) {
            dataGrid.insertColumn(2, learningFlowColumn, DataGridUtil.headingBuilder("Learning Flow")
                            .withToolTip("The Learning Flow the pipeline's Smart Pipeline element learns under.")
                            .build(),
                    ColumnSizing.resizable(220));
        } else {
            dataGrid.removeColumn(learningFlowColumn);
        }
    }

    /// @param dataProvider Supplies the list's rows, a page at a time.
    public void setDataProvider(final AbstractDataProvider<SupervisorLearner> dataProvider) {
        dataProvider.addDataDisplay(dataGrid);
    }

    /// @return The task monitor that animates the pager's refresh button while the rows are being fetched.
    public TaskMonitorFactory getTaskMonitor() {
        return pager;
    }

    /// @return The selection, which holds at most one pipeline.
    public MultiSelectionModel<SupervisorLearner> getSelectionModel() {
        return selectionModel;
    }

    /// Goes back to the first page, e.g. after the filter has changed.
    public void showFirstPage() {
        dataGrid.setVisibleRange(new Range(0, PageRequest.DEFAULT_PAGE_LENGTH));
    }

    /// Sets the filter button's tooltip to say whether the list is filtered (feeds left out, or deleted
    /// pipelines included).
    ///
    /// @param filtering Whether the list is filtered by the dialog.
    public void setFiltering(final boolean filtering) {
        filterButton.setTitle(filtering
                ? FILTERING_TITLE
                : FILTER_TITLE);
    }

    /// @param onFilterButton Run when the filter button is pressed, to open the dialog that chooses which
    ///                       pipelines to show.
    public void setOnFilterButton(final Runnable onFilterButton) {
        filterButton.setOnClick(onFilterButton);
    }

    /// @param onNameFilter Called with the quick filter's text when it changes.
    public void setOnNameFilter(final Consumer<String> onNameFilter) {
        page.setOnFilterChange(onNameFilter);
    }

    /// @param onOpen Called with the row whose pipeline the user opens.
    public void setOnOpen(final Consumer<SupervisorLearner> onOpen) {
        this.onOpen = onOpen;
    }

    /// @param onAutoRefreshToggle Called with whether auto refresh is now on, when its button is pressed.
    public void setOnAutoRefreshToggle(final Consumer<Boolean> onAutoRefreshToggle) {
        autoRefreshButton.setOnToggle(onAutoRefreshToggle);
    }

    /// The name a row shows: the pipeline's name (or its UUID if the name isn't known), followed by the
    /// element's id when that isn't the usual `learningFlow`, to tell apart two learners in one pipeline.
    ///
    /// @param learner A pipeline's learner row.
    /// @return The name to show.
    static String name(final SupervisorLearner learner) {
        final String pipeline = learner.getPipeline() == null || learner.getPipeline().getName() == null
                ? NullSafe.get(learner.getLearner(), ref -> ref.getPipelineUuid())
                : learner.getPipeline().getName();
        final String element = NullSafe.get(learner.getLearner(), ref -> ref.getElementId());
        return element == null || "learningFlow".equals(element)
                ? NullSafe.string(pipeline)
                : pipeline + " / " + element;
    }

    /// The name of the Learning Flow a pipeline learns under.
    ///
    /// @param learner A pipeline's learner row.
    /// @return The Learning Flow's name, or its UUID if the name isn't known; empty if there is none.
    static String learningFlow(final SupervisorLearner learner) {
        final DocRef doc = learner.getDoc();
        if (doc == null) {
            return "";
        }
        return doc.getName() == null
                ? NullSafe.string(doc.getUuid())
                : doc.getName();
    }

    private static SafeHtml chip(final SupervisorLearner learner) {
        return TEMPLATES.chip(chipClasses(FeedSituation.listTone(learner)), FeedSituation.listChip(learner));
    }


    private static String chipClasses(final Tone tone) {
        switch (tone) {
            case GOOD:
                return "feed-list__chip feed-list__chip--good";
            case WARNING:
                return "feed-list__chip feed-list__chip--warning";
            case DANGER:
                return "feed-list__chip feed-list__chip--danger";
            case INFO:
                return "feed-list__chip feed-list__chip--info";
            default:
                return "feed-list__chip feed-list__chip--idle";
        }
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, FeedList> {

    }


    // --------------------------------------------------------------------------------


    interface Templates extends SafeHtmlTemplates {

        // A pipeline's learning state, as a chip. The text is escaped
        @Template("<span class=\"{0}\">{1}</span>")
        SafeHtml chip(String chipClasses, String chip);
    }
}
