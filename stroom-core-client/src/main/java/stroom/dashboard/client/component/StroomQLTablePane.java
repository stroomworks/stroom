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

package stroom.dashboard.client.component;

import stroom.query.client.result.component.ResultTable;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

/// A dashboard's StroomQL Table pane: the results of a StroomQL Query pane's query, as a table
/// (filled by its controller), or, while it has no query, a message saying to choose one.
public class StroomQLTablePane extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label noQuery;
    @UiField
    SimplePanel results;
    @UiField
    ResultTable table;

    /// Makes the pane, saying to choose a query.
    public StroomQLTablePane() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// @return The table, which its controller attaches the results' controller to.
    public ResultTable getTable() {
        return table;
    }

    /// Shows the table, or in its place the message saying to choose a query (an empty table with
    /// no columns isn't a grid a screen reader can make sense of).
    ///
    /// @param hasQuery Whether the table has a query whose results it shows.
    public void setHasQuery(final boolean hasQuery) {
        noQuery.setVisible(!hasQuery);
        results.setVisible(hasQuery);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, StroomQLTablePane> {

    }
}
