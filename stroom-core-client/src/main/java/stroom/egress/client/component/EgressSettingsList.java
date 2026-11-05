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

package stroom.egress.client.component;

import stroom.cell.tickbox.client.TickBoxState;
import stroom.egress.shared.EgressKind;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.BiConsumer;

/// The mode and kill switch of each kind of destination, a row a kind. A kind is in report-only mode until
/// *Enforce* is ticked, and *Kill switch* denies everything of the kind in either mode.
public class EgressSettingsList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    DataGrid<EgressKindSetting> dataGrid;

    private BiConsumer<EgressKind, Boolean> onEnforceChange = (kind, enforce) -> {
    };
    private BiConsumer<EgressKind, Boolean> onKillSwitchChange = (kind, killed) -> {
    };

    /// Makes an empty list.
    public EgressSettingsList() {
        initWidget(BINDER.createAndBindUi(this));
        dataGrid.setKeyProvider(setting -> setting.getKind().name());
        addColumns();
    }

    /// Shows the settings.
    ///
    /// @param settings One row for each kind, in the order to show them.
    public void setData(final List<EgressKindSetting> settings) {
        dataGrid.setRowData(0, settings);
        dataGrid.setRowCount(settings.size());
    }

    /// @param onEnforceChange Given a kind and whether it is now enforced, when its tick box is pressed.
    public void setOnEnforceChange(final BiConsumer<EgressKind, Boolean> onEnforceChange) {
        this.onEnforceChange = onEnforceChange;
    }

    /// @param onKillSwitchChange Given a kind and whether its kill switch is now on, when its tick box is
    ///                           pressed.
    public void setOnKillSwitchChange(final BiConsumer<EgressKind, Boolean> onKillSwitchChange) {
        this.onKillSwitchChange = onKillSwitchChange;
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressKindSetting setting) -> setting.getKind().getDisplayValue())
                        .build(),
                DataGridUtil.headingBuilder("Kind")
                        .withToolTip("The kind of destination.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addColumn(
                DataGridUtil.updatableTickBoxColumnBuilder(TickBoxState.createTickBoxFunc(
                                (EgressKindSetting setting) -> NullSafe.get(setting, EgressKindSetting::isEnforced)))
                        .withFieldUpdater((index, row, value) -> {
                            if (row != null) {
                                onEnforceChange.accept(row.getKind(), value.toBoolean());
                            }
                        })
                        .centerAligned()
                        .build(),
                DataGridUtil.headingBuilder("Enforce")
                        .withToolTip("Ticked: a destination that no rule permits is refused. Unticked: it is only "
                                     + "reported, and the request goes ahead.")
                        .centerAligned()
                        .build(),
                ColumnSizeConstants.ENABLED_COL);
        dataGrid.addColumn(
                DataGridUtil.updatableTickBoxColumnBuilder(TickBoxState.createTickBoxFunc(
                                (EgressKindSetting setting) -> NullSafe.get(setting, EgressKindSetting::isKilled)))
                        .withFieldUpdater((index, row, value) -> {
                            if (row != null) {
                                onKillSwitchChange.accept(row.getKind(), value.toBoolean());
                            }
                        })
                        .centerAligned()
                        .build(),
                DataGridUtil.headingBuilder("Kill switch")
                        .withToolTip("Ticked: every destination of this kind is refused, whatever the rules say, "
                                     + "in either mode.")
                        .centerAligned()
                        .build(),
                ColumnSizeConstants.ENABLED_COL);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, EgressSettingsList> {

    }
}
