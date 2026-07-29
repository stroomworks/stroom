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

package stroom.graphdb.client.view;

import stroom.document.client.event.ChangeUiHandlers;
import stroom.graphdb.shared.GraphNodeTypeMapping;

import com.google.gwt.dom.client.Style.Display;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Task B1: a minimal add/edit/remove list editor
 * for {@code GraphDbDoc.nodeTypeMappings}.
 *
 * <p><b>Design choice.</b> This codebase's one existing full list-editor pattern for a {@code List<Pojo>} field
 * ({@code FieldListPresenter} + {@code FieldEditPresenter} in {@code stroom.receive.rules.client.presenter}, and
 * its siblings {@code SolrIndexFieldListPresenter}/{@code ElasticIndexFieldListPresenter}/
 * {@code IndexFieldListPresenter}) is a {@code MyDataGrid} plus a modal add/edit popup presenter - but every one
 * of those is wired in as an <em>entire document tab of its own</em> (see e.g. {@code RuleSetPresenter},
 * {@code SolrIndexPresenter}, {@code IndexPresenter}), never nested inside a single settings pane alongside
 * unrelated fields. Here {@code nodeTypeMappings} must sit in the same Settings tab as {@code temporalPrecision}
 * and {@code retention}, so it is built the way the fields <em>next to it</em> are built - as a plain,
 * pure-Java widget composed directly into {@code GraphDbSettingsViewImpl}, matching
 * {@code stroom.planb.client.view.RetentionSettingsWidget}'s composition role rather than inventing a nested
 * presenter/popup-dialog subtree that has no precedent inside a single settings view. (It cannot literally
 * extend that package's {@code AbstractSettingsWidget} either way - its {@code asWidget} is package-private
 * and this type lives in a different package.) Given the typically small number of mappings a graph needs, a
 * lightweight repeating text-box row (label / domain type / remove) with an "Add Mapping" button is a
 * proportionate read/edit surface for this Tier-1 field.</p>
 */
public class GraphNodeTypeMappingsWidget implements GraphNodeTypeMappingsView {

    private static final String LABEL_COLUMN_WIDTH = "150px";
    private static final String DOMAIN_TYPE_COLUMN_WIDTH = "200px";

    private final FlowPanel widget;
    private final FlowPanel rowsPanel;
    private final Button addButton;
    private final List<Row> rows = new ArrayList<>();

    private ChangeUiHandlers uiHandlers;
    private boolean readOnly;

    @Inject
    public GraphNodeTypeMappingsWidget() {
        final FlowPanel headerPanel = new FlowPanel();
        headerPanel.add(columnLabel("Label", LABEL_COLUMN_WIDTH));
        headerPanel.add(columnLabel("Domain Type", DOMAIN_TYPE_COLUMN_WIDTH));

        rowsPanel = new FlowPanel();

        addButton = new Button("Add Mapping");
        addButton.addClickHandler(event -> {
            if (!readOnly) {
                addRow("", "");
                fireChange();
            }
        });

        widget = new FlowPanel();
        widget.addStyleName("form-group");
        widget.add(headerPanel);
        widget.add(rowsPanel);
        widget.add(addButton);
    }

    private static Label columnLabel(final String text, final String width) {
        final Label label = new Label(text);
        label.getElement().getStyle().setDisplay(Display.INLINE_BLOCK);
        label.setWidth(width);
        return label;
    }

    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setUiHandlers(final ChangeUiHandlers uiHandlers) {
        this.uiHandlers = uiHandlers;
    }

    @Override
    public List<GraphNodeTypeMapping> getNodeTypeMappings() {
        final List<GraphNodeTypeMapping> mappings = new ArrayList<>();
        for (final Row row : rows) {
            final String label = row.labelBox.getValue().trim();
            final String domainType = row.domainTypeBox.getValue().trim();
            if (!label.isEmpty() && !domainType.isEmpty()) {
                mappings.add(new GraphNodeTypeMapping(label, domainType));
            }
        }
        return mappings;
    }

    @Override
    public void setNodeTypeMappings(final List<GraphNodeTypeMapping> nodeTypeMappings) {
        rowsPanel.clear();
        rows.clear();
        if (nodeTypeMappings != null) {
            for (final GraphNodeTypeMapping mapping : nodeTypeMappings) {
                addRow(mapping.getLabel(), mapping.getDomainType());
            }
        }
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;
        addButton.setEnabled(!readOnly);
        for (final Row row : rows) {
            row.setReadOnly(readOnly);
        }
    }

    private void addRow(final String label, final String domainType) {
        final Row row = new Row(label, domainType);
        rows.add(row);
        rowsPanel.add(row.panel);
    }

    private void fireChange() {
        if (uiHandlers != null) {
            uiHandlers.onChange();
        }
    }

    private final class Row {

        private final TextBox labelBox = new TextBox();
        private final TextBox domainTypeBox = new TextBox();
        private final Button removeButton = new Button("Remove");
        private final FlowPanel panel = new FlowPanel();

        private Row(final String label, final String domainType) {
            labelBox.setValue(label);
            labelBox.setWidth(LABEL_COLUMN_WIDTH);
            domainTypeBox.setValue(domainType);
            domainTypeBox.setWidth(DOMAIN_TYPE_COLUMN_WIDTH);

            panel.add(labelBox);
            panel.add(domainTypeBox);
            panel.add(removeButton);

            labelBox.addValueChangeHandler(event -> fireChange());
            domainTypeBox.addValueChangeHandler(event -> fireChange());
            removeButton.addClickHandler(event -> {
                if (!readOnly) {
                    rows.remove(this);
                    rowsPanel.remove(panel);
                    fireChange();
                }
            });

            setReadOnly(readOnly);
        }

        private void setReadOnly(final boolean readOnly) {
            labelBox.setEnabled(!readOnly);
            domainTypeBox.setEnabled(!readOnly);
            removeButton.setEnabled(!readOnly);
        }
    }
}
