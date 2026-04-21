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

package stroom.domaintype.client.presenter;

import stroom.alert.client.event.ConfirmEvent;
import stroom.data.grid.client.MyDataGrid;
import stroom.docref.DocRef;
import stroom.document.client.event.DirtyEvent;
import stroom.domaintype.shared.DomainType;
import stroom.domaintype.shared.DomainTypeDoc;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.HasToolbar;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.SvgButton;
import stroom.widget.popup.client.presenter.TextBoxPopup;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class DomainTypeListPresenter extends
        DocPresenter<DomainTypeListPresenter.DomainTypeListView, DomainTypeDoc> implements HasToolbar {

    private final MyDataGrid<String> masterGrid;
    private final MultiSelectionModelImpl<String> masterSelectionModel;

    private final MyDataGrid<String> detailGrid;
    private final MultiSelectionModelImpl<String> detailSelectionModel;

    private final ButtonView addClassButton;
    private final ButtonView addAttributeButton;
    private final ButtonView removeButton;
    private final TextBoxPopup textBoxPopup;
    private final ButtonPanel toolbar;

    private final Map<String, List<String>> data = new TreeMap<>();
    private String selectedClass;
    private boolean readOnly = true;

    @Inject
    public DomainTypeListPresenter(final EventBus eventBus,
                                   final DomainTypeListView view,
                                   final TextBoxPopup textBoxPopup) {
        super(eventBus, view);
        this.textBoxPopup = textBoxPopup;

        masterGrid = new MyDataGrid<String>(this);
        masterSelectionModel = masterGrid.addDefaultSelectionModel(false);
        masterGrid.addColumn(new Column<String, String>(new TextCell()) {
            @Override
            public String getValue(final String object) {
                return object;
            }
        }, "Class");
        view.setMasterView(masterGrid);

        detailGrid = new MyDataGrid<String>(this);
        detailSelectionModel = detailGrid.addDefaultSelectionModel(false);
        detailGrid.addColumn(new Column<String, String>(new TextCell()) {
            @Override
            public String getValue(final String object) {
                return object;
            }
        }, "Attribute");
        view.setDetailView(detailGrid);

        addClassButton = SvgButton.create(SvgPresets.ADD);
        addClassButton.setTitle("Add Class");

        addAttributeButton = SvgButton.create(SvgPresets.ADD);
        addAttributeButton.setTitle("Add Attribute");

        removeButton = SvgButton.create(SvgPresets.DELETE);
        removeButton.setTitle("Remove");

        toolbar = new ButtonPanel();
        toolbar.addButton(addClassButton);
        toolbar.addButton(addAttributeButton);
        toolbar.addButton(removeButton);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(addClassButton.addClickHandler(event -> onAddClass()));
        registerHandler(addAttributeButton.addClickHandler(event -> onAddAttribute()));
        registerHandler(removeButton.addClickHandler(event -> onRemove()));

        registerHandler(masterSelectionModel.addSelectionHandler(event -> {
            selectedClass = masterSelectionModel.getSelected();
            refreshDetail();
            updateButtons();
        }));

        registerHandler(detailSelectionModel.addSelectionHandler(event -> updateButtons()));
    }

    private void onAddClass() {
        textBoxPopup.setText("");
        textBoxPopup.show("Add Class", value -> {
            if (value != null && !value.trim().isEmpty()) {
                final String trimmed = value.trim();
                data.putIfAbsent(trimmed, new ArrayList<>());
                refreshMaster();
                masterSelectionModel.setSelected(trimmed);
                fireDirty();
            }
        });
    }

    private void onAddAttribute() {
        if (selectedClass != null) {
            textBoxPopup.setText("");
            textBoxPopup.show("Add Attribute to " + selectedClass, value -> {
                if (value != null && !value.trim().isEmpty()) {
                    final String trimmed = value.trim();
                    final List<String> attrs = data.get(selectedClass);
                    if (!attrs.contains(trimmed)) {
                        attrs.add(trimmed);
                        Collections.sort(attrs);
                        refreshDetail();
                        detailSelectionModel.setSelected(trimmed);
                        fireDirty();
                    }
                }
            });
        }
    }

    private void onRemove() {
        final String selAttr = detailSelectionModel.getSelected();
        final String selClass = masterSelectionModel.getSelected();

        if (selAttr != null) {
            ConfirmEvent.fire(this, "Are you sure you want to remove attribute '" + selAttr + "'?", result -> {
                if (result) {
                    data.get(selectedClass).remove(selAttr);
                    refreshDetail();
                    fireDirty();
                }
            });
        } else if (selClass != null) {
            ConfirmEvent.fire(this, "Are you sure you want to remove class '" + selClass + "' and all its attributes?",
                    result -> {
                        if (result) {
                            data.remove(selClass);
                            refreshMaster();
                            fireDirty();
                        }
                    });
        }
    }

    private void fireDirty() {
        DirtyEvent.fire(this, true);
        updateButtons();
    }

    private void updateButtons() {
        addClassButton.setEnabled(!readOnly);
        addAttributeButton.setEnabled(!readOnly && selectedClass != null);
        final boolean hasSelection = masterSelectionModel.getSelected() != null ||
                                     detailSelectionModel.getSelected() != null;
        removeButton.setEnabled(!readOnly && hasSelection);
    }

    private void refreshMaster() {
        final List<String> classes = new ArrayList<>(data.keySet());
        masterGrid.setRowData(0, classes);
        masterGrid.setRowCount(classes.size());
    }

    private void refreshDetail() {
        if (selectedClass != null) {
            final List<String> attrs = data.get(selectedClass);
            detailGrid.setRowData(0, attrs);
            detailGrid.setRowCount(attrs.size());
        } else {
            detailGrid.setRowData(0, Collections.emptyList());
            detailGrid.setRowCount(0);
        }
    }

    @Override
    protected void onRead(final DocRef docRef, final DomainTypeDoc document, final boolean readOnly) {
        this.readOnly = readOnly;
        data.clear();
        if (document.getDomainTypes() != null) {
            for (final DomainType dt : document.getDomainTypes()) {
                data.computeIfAbsent(dt.getClassPart(), k -> new ArrayList<>()).add(dt.getAttributePart());
            }
            for (final List<String> attrs : data.values()) {
                Collections.sort(attrs);
            }
        }
        refreshMaster();
        selectedClass = null;
        masterSelectionModel.clear();
        refreshDetail();
        updateButtons();
    }

    @Override
    protected DomainTypeDoc onWrite(final DomainTypeDoc document) {
        final List<DomainType> domainTypes = new ArrayList<>();
        for (final Map.Entry<String, List<String>> entry : data.entrySet()) {
            if (entry.getValue().isEmpty()) {
                domainTypes.add(new DomainType(entry.getKey(), ""));
            } else {
                for (final String attr : entry.getValue()) {
                    domainTypes.add(new DomainType(entry.getKey(), attr));
                }
            }
        }
        return document.copy().domainTypes(domainTypes).build();
    }

    @Override
    public List<Widget> getToolbars() {
        return Collections.singletonList(toolbar);
    }

    public interface DomainTypeListView extends View {

        void setMasterView(Widget widget);

        void setDetailView(Widget widget);
    }

}
