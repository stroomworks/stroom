/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.dashboard.client.main;

import stroom.alert.client.event.ConfirmEvent;
import stroom.dashboard.client.main.DashboardSettingsPresenter.DashboardSettingsView;
import stroom.dashboard.shared.DashboardDoc;
import stroom.data.grid.client.MyDataGrid;
import stroom.docref.DocRef;
import stroom.domaintype.shared.DomainType;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.HasToolbar;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.SvgButton;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DashboardSettingsPresenter
        extends DocPresenter<DashboardSettingsView, DashboardDoc>
        implements DashboardSettingsUiHandlers, HasToolbar {

    private static final Comparator<DomainType> DOMAIN_TYPE_COMPARATOR = (o1, o2) -> {
        final String s1 = o1.toString();
        final String s2 = o2.toString();
        int res = s1.compareToIgnoreCase(s2);
        if (res == 0) {
            res = s1.compareTo(s2);
        }
        return res;
    };

    private static final String EMPTY = "";

    private final MyDataGrid<DomainType> grid;
    private final MultiSelectionModelImpl<DomainType> selectionModel;

    private final AddDomainTypePresenter addDomainTypePresenter;

    private final ButtonPanel toolbar;
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView deleteButton;

    private final List<DomainType> domainTypes = new ArrayList<>();
    private boolean readOnly = true;

    @Inject
    public DashboardSettingsPresenter(final EventBus eventBus,
                                      final DashboardSettingsView view,
                                      final AddDomainTypePresenter addDomainTypePresenter) {
        super(eventBus, view);
        this.addDomainTypePresenter = addDomainTypePresenter;
        view.setUiHandlers(this);

        grid = new MyDataGrid<>(this);
        selectionModel = grid.addDefaultSelectionModel(false);
        grid.addColumn(new Column<>(new TextCell()) {
            @Override
            public String getValue(final DomainType object) {
                return object.toString();
            }
        }, EMPTY);
        view.setView(grid);

        toolbar = new ButtonPanel();
        addButton = SvgButton.create(SvgPresets.ADD);
        toolbar.addButton(addButton);
        editButton = SvgButton.create(SvgPresets.EDIT);
        toolbar.addButton(editButton);
        deleteButton = SvgButton.create(SvgPresets.DELETE);
        toolbar.addButton(deleteButton);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(addButton.addClickHandler(event -> onAddDomainType()));
        registerHandler(editButton.addClickHandler(event -> onEditDomainType()));
        registerHandler(deleteButton.addClickHandler(event -> onDelete()));

        registerHandler(selectionModel.addSelectionHandler(event -> updateButtons()));
    }

    private void onDelete() {
        final DomainType selected = selectionModel.getSelected();
        if (selected != null) {
            ConfirmEvent.fire(this,
                    "Are you sure you want to remove domain type '" + selected + "'?",
                    result -> {
                        if (result) {
                            domainTypes.remove(selected);
                            refresh();
                            triggerDirty();
                        }
                    });
        }
    }

    private void onAddDomainType() {
        addDomainTypePresenter.show(null, domainType -> {
            if (!domainTypes.contains(domainType)) {
                domainTypes.add(domainType);
                domainTypes.sort(DOMAIN_TYPE_COMPARATOR);
                refresh();
                selectionModel.setSelected(domainType);
                triggerDirty();
            }
        });
    }

    private void onEditDomainType() {
        final DomainType selected = selectionModel.getSelected();
        if (selected != null) {
            addDomainTypePresenter.show(selected, domainType -> {
                if (!domainTypes.contains(domainType) || domainType.equals(selected)) {
                    final int index = domainTypes.indexOf(selected);
                    if (index != -1) {
                        domainTypes.set(index, domainType);
                        domainTypes.sort(DOMAIN_TYPE_COMPARATOR);
                        refresh();
                        selectionModel.setSelected(domainType);
                        triggerDirty();
                    }
                }
            });
        }
    }

    private void updateButtons() {
        addButton.setEnabled(!readOnly);
        editButton.setEnabled(!readOnly && selectionModel.getSelected() != null);
        deleteButton.setEnabled(!readOnly && selectionModel.getSelected() != null);
    }

    private void refresh() {
        grid.setRowData(0, domainTypes);
        grid.setRowCount(domainTypes.size());
    }

    @Override
    protected void onRead(final DocRef docRef, final DashboardDoc dashboard, final boolean readOnly) {
        this.readOnly = readOnly;
        domainTypes.clear();
        if (dashboard.getDomainTypes() != null) {
            domainTypes.addAll(dashboard.getDomainTypes());
            domainTypes.sort(DOMAIN_TYPE_COMPARATOR);
        }
        refresh();
        selectionModel.clear();
        updateButtons();

        getView().onReadOnly(readOnly);
    }

    @Override
    protected DashboardDoc onWrite(final DashboardDoc dashboard) {
        return dashboard.copy().domainTypes(new ArrayList<>(domainTypes)).build();
    }

    @Override
    public List<Widget> getToolbars() {
        return Collections.singletonList(toolbar);
    }

    @Override
    public void triggerDirty() {
        onChange();
        updateButtons();
    }

    public interface DashboardSettingsView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<DashboardSettingsUiHandlers> {

        void setView(Widget widget);
    }
}
