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

import stroom.shapeshifter.client.component.FeedSituation.Tone;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.widget.button.client.ToolbarToggleButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.task.client.TaskMonitorFactory;
import stroom.widget.tickbox.client.TickBox;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.AbstractDataProvider;
import com.google.gwt.view.client.Range;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/// The feeds a Shapeshifter AI document learns and translates, on its Feeds tab: a row each, named by its
/// pipeline (one pipeline per feed, as the Pipeline Factory makes them), with a chip saying where it stands
/// and a line of what it scores or what waits ([FeedSituation#listChip]). Above, which feeds to show, with
/// how many there are of each, and whether to show those whose pipeline was deleted; the pager's toolbar
/// has the tab's auto refresh.
public class FeedList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final Templates TEMPLATES = GWT.create(Templates.class);

    @UiField
    SelectionBox<FeedFilter> filter;
    @UiField
    TickBox showDeleted;
    @UiField
    PagerPanel pager;
    @UiField
    ToolbarToggleButton autoRefreshButton;
    @UiField
    DataGrid<SupervisorLearner> dataGrid;

    private final MultiSelectionModelImpl<SupervisorLearner> selectionModel;
    private final Map<FeedFilter, Integer> counts = new EnumMap<>(FeedFilter.class);
    private Consumer<FeedFilter> onFilter = chosen -> {
    };
    private Consumer<Boolean> onShowDeleted = show -> {
    };

    /// Makes an empty list, showing every feed.
    public FeedList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder(FeedList::row).build(),
                DataGridUtil.headingBuilder("Feed").build(),
                280);
        filter.setDisplayValueFunction(value -> value == null
                ? ""
                : value.getDisplayValue() + (counts.containsKey(value)
                        ? " (" + counts.get(value) + ")"
                        : ""));
        filter.addItems(FeedFilter.values());
        filter.setValue(FeedFilter.ALL);
        filter.setOnChange(chosen -> onFilter.accept(chosen));
        showDeleted.setOnChange(show -> onShowDeleted.accept(Boolean.TRUE.equals(show)));
    }

    /// @param dataProvider Gives the list its rows, a page at a time.
    public void setDataProvider(final AbstractDataProvider<SupervisorLearner> dataProvider) {
        dataProvider.addDataDisplay(dataGrid);
    }

    /// @return The task monitor that spins the pager's refresh button.
    public TaskMonitorFactory getTaskMonitor() {
        return pager;
    }

    /// @return The selection, one feed at most.
    public MultiSelectionModel<SupervisorLearner> getSelectionModel() {
        return selectionModel;
    }

    /// Goes back to the first page, e.g. after the filter has changed.
    public void showFirstPage() {
        dataGrid.setVisibleRange(new Range(0, PageRequest.DEFAULT_PAGE_LENGTH));
    }

    /// Shows how many feeds each filter lists.
    ///
    /// @param byFilter How many, by filter.
    public void setCounts(final Map<FeedFilter, Integer> byFilter) {
        if (!counts.equals(byFilter)) {
            counts.clear();
            counts.putAll(byFilter);
            final FeedFilter chosen = filter.getValue();
            filter.clear();
            filter.addItems(FeedFilter.values());
            filter.setValue(chosen);
        }
    }

    /// @param value Which feeds to show.
    public void setFilter(final FeedFilter value) {
        filter.setValue(value);
    }

    /// @param onFilter Told which feeds the user wants shown.
    public void setOnFilter(final Consumer<FeedFilter> onFilter) {
        this.onFilter = onFilter;
    }

    /// @param onShowDeleted Told whether the user wants feeds whose pipeline was deleted shown.
    public void setOnShowDeleted(final Consumer<Boolean> onShowDeleted) {
        this.onShowDeleted = onShowDeleted;
    }

    /// @param onAutoRefreshToggle Told whether auto refresh is now on, when its button is pressed.
    public void setOnAutoRefreshToggle(final Consumer<Boolean> onAutoRefreshToggle) {
        autoRefreshButton.setOnToggle(onAutoRefreshToggle);
    }

    /// A feed's name: its pipeline's, and the element's id where it isn't the usual one.
    ///
    /// @param learner A feed's learner row.
    /// @return Its name.
    static String name(final SupervisorLearner learner) {
        final String pipeline = learner.getPipeline() == null || learner.getPipeline().getName() == null
                ? NullSafe.get(learner.getLearner(), ref -> ref.getPipelineUuid())
                : learner.getPipeline().getName();
        final String element = NullSafe.get(learner.getLearner(), ref -> ref.getElementId());
        return element == null || "shapeshifterAi".equals(element)
                ? NullSafe.string(pipeline)
                : pipeline + " / " + element;
    }

    private static SafeHtml row(final SupervisorLearner learner) {
        return TEMPLATES.row(name(learner),
                chipClasses(FeedSituation.listTone(learner)),
                FeedSituation.listChip(learner),
                FeedSituation.listSummary(learner));
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

        // A feed's row: its name and chip on one line, what it scores or what waits beneath (escaped)
        @Template("<div class=\"feed-list__row\"><span class=\"feed-list__name\">{0}</span>"
                  + "<span class=\"{1}\">{2}</span><span class=\"feed-list__summary\">{3}</span></div>")
        SafeHtml row(String name, String chipClasses, String chip, String summary);
    }
}
