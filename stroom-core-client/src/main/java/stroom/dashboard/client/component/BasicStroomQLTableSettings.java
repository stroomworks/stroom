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

import stroom.dashboard.client.main.DashboardPane;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// A StroomQL Table pane's Basic settings: its id and name, the StroomQL Query whose results it
/// shows, and how many rows it shows on a page.
public class BasicStroomQLTableSettings extends Component implements BasicSettingsFields {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label id;
    @UiField
    TextBox name;
    @UiField
    SelectionBox<DashboardPane> query;
    @UiField
    ValueSpinner pageSize;

    /// Makes the tab, showing a page size of 100.
    public BasicStroomQLTableSettings() {
        initWidget(BINDER.createAndBindUi(this));
        pageSize.setValue(100);
    }

    @Override
    public void focus() {
        name.setFocus(true);
    }

    @Override
    public void setId(final String id) {
        this.id.setText(id);
    }

    @Override
    public String getName() {
        return name.getText();
    }

    @Override
    public void setName(final String name) {
        this.name.setText(name);
    }

    /// Lists the queries to choose from, keeping the one chosen.
    ///
    /// @param queryList The dashboard's StroomQL Query panes.
    public void setQueryList(final List<DashboardPane> queryList) {
        final DashboardPane chosen = getQuery();
        query.clear();
        query.addItems(queryList);
        setQuery(chosen);
    }

    /// @return The query chosen, if any.
    public DashboardPane getQuery() {
        return query.getValue();
    }

    /// @param query The query to show as chosen, or null for none.
    public void setQuery(final DashboardPane query) {
        this.query.setValue(query);
    }

    /// @return The rows shown on each page.
    public int getPageSize() {
        return pageSize.getIntValue();
    }

    /// @param pageSize The rows shown on each page.
    public void setPageSize(final int pageSize) {
        this.pageSize.setValue(pageSize);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, BasicStroomQLTableSettings> {

    }
}
