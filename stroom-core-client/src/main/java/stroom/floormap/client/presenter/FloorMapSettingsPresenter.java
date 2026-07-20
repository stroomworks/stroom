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

package stroom.floormap.client.presenter;

import stroom.data.grid.client.MyDataGrid;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.DirtyUiHandlers;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.entity.shared.ExpressionCriteria;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.floormap.client.ValueAccessorFactory;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.shared.Fact;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapEntryParser;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.TypeStyle;
import stroom.floormap.shared.ValueFormat;
import stroom.planb.shared.PlanBDoc;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.security.shared.DocumentPermission;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.svg.client.SvgPresets;
import stroom.util.shared.TemporalEntry;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.cell.client.EditTextCell;
import com.google.gwt.cell.client.SelectionCell;
import com.google.gwt.core.client.GWT;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.ListBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Presenter for the <em>Settings</em> tab of a {@link FloorMapDoc}.
 *
 * <p>This presenter lets users configure:</p>
 * <ul>
 *   <li>The <strong>Events Store</strong> reference – a {@link PlanBDoc} used to persist
 *       floor-map event data.</li>
 *   <li>The <strong>Facts Store</strong> reference – a {@link SqlTemporalStoreDoc} used to
 *       persist floor-map fact data.</li>
 *   <li>The <strong>Value Format</strong> – the serialisation format for map values
 *       (selected from the {@link ValueFormat} enum).</li>
 *   <li>The <strong>Value Schema</strong> – an editable grid of
 *       {@link FloorMapFieldMapping} entries that define the fields, their roles, paths,
 *       display names, and default values.</li>
 *   <li>The <strong>Type Styles</strong> – an ordered grid of {@link TypeStyle} entries,
 *       one per fact type. The list order is the paint (z) order — types higher in the
 *       list paint behind those lower down — and each row's shape and colour define the
 *       default graphic drawn for an imageless fact of that type. A <em>Discover</em>
 *       action scans the Facts Store for the distinct types present in the data.</li>
 * </ul>
 *
 * <p>It extends {@link DocPresenter} and is embedded as a tab within
 * {@link FloorMapPresenter}'s tabbed view. The companion view interface is
 * {@link FloorMapSettingsView}.</p>
 *
 * @see FloorMapPresenter
 * @see FloorMapDoc
 * @see FloorMapFieldMapping
 * @see TypeStyle
 */
public class FloorMapSettingsPresenter
        extends DocPresenter<FloorMapSettingsView, FloorMapDoc>
        implements DirtyUiHandlers {

    private final DocSelectionBoxPresenter eventsStoreRefPresenter;
    private final DocSelectionBoxPresenter factsStoreRefPresenter;

    // Value Format
    private final ListBox valueFormatListBox;

    // Value Schema grid
    private final MyDataGrid<FloorMapFieldMapping> schemaGrid;
    private final ListDataProvider<FloorMapFieldMapping> schemaDataProvider;
    private final SingleSelectionModel<FloorMapFieldMapping> schemaSelectionModel;
    private final ButtonView addButton;
    private final ButtonView removeButton;
    private boolean readOnly;

    // Type Styles — the shared Layers panel in editor mode (replaces the old grid).
    /** Slot hosting the shared Layers panel (editor mode) on the Settings tab. */
    public static final Object TYPE_STYLES_SLOT = new Object();
    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);
    private final RestFactory restFactory;
    private final FloorMapLayersPresenter typeStylesPanel;

    @Inject
    public FloorMapSettingsPresenter(final EventBus eventBus,
                                     final FloorMapSettingsView view,
                                     final Provider<DocSelectionBoxPresenter> docSelectionBoxPresenterProvider,
                                     final RestFactory restFactory,
                                     final Provider<FloorMapLayersPresenter> layersProvider) {
        super(eventBus, view);

        this.restFactory = restFactory;
        this.typeStylesPanel = layersProvider.get();

        view.setUiHandlers(this);

        this.eventsStoreRefPresenter = docSelectionBoxPresenterProvider.get();
        this.eventsStoreRefPresenter.setIncludedTypes(PlanBDoc.TYPE);
        this.eventsStoreRefPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setEventsStoreRefView(this.eventsStoreRefPresenter.getView());

        this.factsStoreRefPresenter = docSelectionBoxPresenterProvider.get();
        this.factsStoreRefPresenter.setIncludedTypes(SqlTemporalStoreDoc.TYPE);
        this.factsStoreRefPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setFactsStoreRefView(this.factsStoreRefPresenter.getView());

        // Value Format dropdown
        valueFormatListBox = new ListBox();
        for (final ValueFormat vf : ValueFormat.values()) {
            valueFormatListBox.addItem(vf.name());
        }
        //noinspection unused e
        valueFormatListBox.addChangeHandler(e -> onChange());
        view.setValueFormatWidget(valueFormatListBox);

        // Value Schema grid
        schemaGrid = new MyDataGrid<>(this);
        schemaSelectionModel = new SingleSelectionModel<>();
        schemaGrid.setSelectionModel(schemaSelectionModel);
        schemaDataProvider = new ListDataProvider<>();
        schemaDataProvider.addDataDisplay(schemaGrid);
        initSchemaColumns();

        // Schema toolbar
        final ButtonPanel buttonPanel = new ButtonPanel();
        addButton = buttonPanel.addButton(SvgPresets.ADD);
        addButton.setTitle("Add Field Mapping");
        removeButton = buttonPanel.addButton(SvgPresets.DELETE);
        removeButton.setTitle("Remove Field Mapping");
        removeButton.setEnabled(false);

        view.setSchemaToolbar(buttonPanel);
        view.setSchemaGrid(schemaGrid);

        // Type Styles — the shared Layers panel in editor mode (Discover / reorder /
        // shape+colour). Persistence stays here: onWrite reads the panel's list.
        typeStylesPanel.configureEditor(new FloorMapLayersPresenter.EditHandler() {
            @Override
            public void onTypeStylesChanged() {
                onChange();
            }

            @Override
            public void onDiscoverRequested() {
                onDiscoverTypes();
            }
        });
        setInSlot(TYPE_STYLES_SLOT, typeStylesPanel);
    }

    /**
     * Initialises the columns displayed in the Value Schema data grid.
     *
     * <p>Four editable columns are added in order:</p>
     * <ol>
     *   <li><strong>Role</strong> – a dropdown ({@link SelectionCell}) of {@link Role} values.</li>
     *   <li><strong>Path</strong> – an {@link EditTextCell} for the JSON path.</li>
     *   <li><strong>Display Name</strong> – an {@link EditTextCell} for the label.</li>
     *   <li><strong>Default</strong> – an {@link EditTextCell} for the default value.</li>
     * </ol>
     *
     * <p>Because {@link FloorMapFieldMapping} is immutable, each column's
     * {@code FieldUpdater} creates a replacement instance and swaps it in
     * the data provider list at the same index.</p>
     */
    private void initSchemaColumns() {
        // Role column – dropdown
        final List<String> roleOptions = Arrays.stream(Role.values())
                .map(Role::name)
                .collect(Collectors.toList());
        final Column<FloorMapFieldMapping, String> roleColumn =
                new Column<>(new SelectionCell(roleOptions)) {
                    @Override
                    public String getValue(final FloorMapFieldMapping mapping) {
                        return mapping.getRole() != null ? mapping.getRole().name() : Role.CUSTOM.name();
                    }
                };
        roleColumn.setFieldUpdater((index, mapping, val) -> {
            if (!readOnly) {
                final Role newRole = Role.valueOf(val);
                replaceMapping(index, new FloorMapFieldMapping(
                        mapping.getPath(), newRole, mapping.getDisplayName(), mapping.getDefaultValue()));
            }
        });
        schemaGrid.addColumn(roleColumn, "Role");

        // Path column – editable text
        final Column<FloorMapFieldMapping, String> pathColumn =
                new Column<>(new EditTextCell()) {
                    @Override
                    public String getValue(final FloorMapFieldMapping mapping) {
                        return mapping.getPath() != null ? mapping.getPath() : "";
                    }
                };
        pathColumn.setFieldUpdater((index, mapping, val) -> {
            if (!readOnly) {
                replaceMapping(index, new FloorMapFieldMapping(
                        val, mapping.getRole(), mapping.getDisplayName(), mapping.getDefaultValue()));
            }
        });
        schemaGrid.addColumn(pathColumn, "Path");

        // Display Name column – editable text
        final Column<FloorMapFieldMapping, String> nameColumn =
                new Column<>(new EditTextCell()) {
                    @Override
                    public String getValue(final FloorMapFieldMapping mapping) {
                        return mapping.getDisplayName() != null ? mapping.getDisplayName() : "";
                    }
                };
        nameColumn.setFieldUpdater((index, mapping, val) -> {
            if (!readOnly) {
                replaceMapping(index, new FloorMapFieldMapping(
                        mapping.getPath(), mapping.getRole(), val, mapping.getDefaultValue()));
            }
        });
        schemaGrid.addColumn(nameColumn, "Display Name");

        // Default Value column – editable text
        final Column<FloorMapFieldMapping, String> defaultColumn =
                new Column<>(new EditTextCell()) {
                    @Override
                    public String getValue(final FloorMapFieldMapping mapping) {
                        return mapping.getDefaultValue() != null ? mapping.getDefaultValue() : "";
                    }
                };
        defaultColumn.setFieldUpdater((index, mapping, val) -> {
            if (!readOnly) {
                replaceMapping(index, new FloorMapFieldMapping(
                        mapping.getPath(), mapping.getRole(), mapping.getDisplayName(), val));
            }
        });
        schemaGrid.addColumn(defaultColumn, "Default");
    }

    /**
     * Replaces the {@link FloorMapFieldMapping} at the given index in the
     * data provider list with a new instance, refreshes the grid, and marks
     * the document as dirty.
     *
     * @param index   the zero-based position in the list
     * @param updated the replacement mapping
     */
    private void replaceMapping(final int index, final FloorMapFieldMapping updated) {
        final List<FloorMapFieldMapping> list = schemaDataProvider.getList();
        if (index >= 0 && index < list.size()) {
            list.set(index, updated);
            refreshGrid();
            onChange();
        }
    }

    @Override
    protected void onBind() {
        super.onBind();
        //noinspection unused e
        registerHandler(eventsStoreRefPresenter.addDataSelectionHandler(e -> onChange()));
        //noinspection unused e
        registerHandler(factsStoreRefPresenter.addDataSelectionHandler(e -> onChange()));
        //noinspection unused e
        registerHandler(schemaSelectionModel.addSelectionChangeHandler(
                e -> removeButton.setEnabled(schemaSelectionModel.getSelectedObject() != null)));
        //noinspection unused e
        registerHandler(addButton.addClickHandler(e -> onAddMapping()));
        //noinspection unused e
        registerHandler(removeButton.addClickHandler(e -> onRemoveMapping()));
        // Type-styles editing (Discover / reorder / shape+colour) is handled inside
        // the embedded Layers panel; it reports edits via the EditHandler wired above.
    }

    /**
     * Handles the <em>Add</em> button click by creating a new
     * {@link FloorMapFieldMapping} with role {@link Role#CUSTOM}, a placeholder
     * path of {@code "."}, and a display name of {@code "New Field"}.
     *
     * <p>The new mapping is appended to the schema data provider, the grid is
     * refreshed, and the document is marked as dirty via {@link #onChange()}.</p>
     */
    private void onAddMapping() {
        // Add a new CUSTOM field with empty path
        final FloorMapFieldMapping newMapping =
                new FloorMapFieldMapping(".", Role.CUSTOM, "New Field", null);
        final List<FloorMapFieldMapping> list = schemaDataProvider.getList();
        list.add(newMapping);
        refreshGrid();
        onChange();
    }

    /**
     * Handles the <em>Remove</em> button click by deleting the currently selected
     * {@link FloorMapFieldMapping} from the schema grid.
     *
     * <p>If no row is selected the method is a no-op. After removal the selection
     * is cleared, the remove button is disabled, the grid is refreshed, and the
     * document is marked as dirty via {@link #onChange()}.</p>
     */
    private void onRemoveMapping() {
        final FloorMapFieldMapping selected = schemaSelectionModel.getSelectedObject();
        if (selected != null) {
            final List<FloorMapFieldMapping> list = schemaDataProvider.getList();
            list.remove(selected);
            schemaSelectionModel.clear();
            removeButton.setEnabled(false);
            refreshGrid();
            onChange();
        }
    }

    /**
     * Synchronises the schema {@link ListDataProvider}'s list with the
     * {@link MyDataGrid} widget, updating both the displayed rows and the total
     * row count so the grid reflects the current state of the data provider.
     */
    private void refreshGrid() {
        final List<FloorMapFieldMapping> list = schemaDataProvider.getList();
        schemaGrid.setRowData(0, list);
        schemaGrid.setRowCount(list.size(), true);
    }

    // -----------------------------------------------------------------------
    // Type Styles
    // -----------------------------------------------------------------------

    /**
     * Discovers the distinct types present in the configured facts store and
     * merges any new ones into the panel's type list (alphabetically; existing
     * entries keep their position and settings). Invoked by the embedded Layers
     * panel's Discover button via its {@link FloorMapLayersPresenter.EditHandler}.
     */
    private void onDiscoverTypes() {
        if (readOnly) {
            return;
        }
        final DocRef storeRef = factsStoreRefPresenter.getSelectedEntityReference();
        if (storeRef == null || storeRef.getName() == null || storeRef.getName().isEmpty()) {
            return;
        }
        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTerm(ExpressionTerm.builder()
                        .field("Map").condition(Condition.EQUALS).value(storeRef.getName())
                        .build())
                .build();
        final ExpressionCriteria criteria = new ExpressionCriteria(expression);
        final List<FloorMapFieldMapping> schema = new ArrayList<>(schemaDataProvider.getList());
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.find(criteria))
                .onSuccess(result -> {
                    final List<TemporalEntry> entries = result != null ? result.getValues() : null;
                    final List<Fact> parsed = FloorMapEntryParser.parse(
                            entries, schema,
                            ValueAccessorFactory.forFormat(currentValueFormat()), null);
                    final Set<String> discovered = new LinkedHashSet<>();
                    for (final Fact fact : parsed) {
                        if (fact.getType() != null && !fact.getType().isEmpty()) {
                            discovered.add(fact.getType());
                        }
                    }
                    typeStylesPanel.setEditList(
                            TypeStyle.merge(typeStylesPanel.getEditList(), discovered));
                    onChange();
                })
                .exec();
    }

    private ValueFormat currentValueFormat() {
        try {
            return ValueFormat.valueOf(valueFormatListBox.getSelectedValue());
        } catch (final IllegalArgumentException e) {
            return ValueFormat.JSON;
        }
    }

    /**
     * Populates the view from a persisted {@link FloorMapDoc}.
     *
     * <p>The method performs the following steps:</p>
     * <ol>
     *   <li>Sets the Events Store and Facts Store selection boxes to the
     *       references stored in the document and enables/disables them
     *       according to the {@code readOnly} flag.</li>
     *   <li>Selects the matching {@link ValueFormat} entry in the dropdown.</li>
     *   <li>Copies the document's value schema list into the data provider and
     *       refreshes the grid.</li>
     *   <li>Enables or disables the <em>Add</em> and <em>Remove</em> toolbar
     *       buttons based on the {@code readOnly} flag.</li>
     * </ol>
     *
     * @param docRef      the {@link DocRef} identifying the document being read
     * @param floorMapDoc the persisted document whose settings are displayed
     * @param readOnly    {@code true} if the UI should be non-editable
     */
    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc floorMapDoc, final boolean readOnly) {
        this.readOnly = readOnly;
        eventsStoreRefPresenter.setSelectedEntityReference(floorMapDoc.getEventsStoreRef(), true);
        eventsStoreRefPresenter.setEnabled(!readOnly);
        factsStoreRefPresenter.setSelectedEntityReference(floorMapDoc.getFactsStoreRef(), true);
        factsStoreRefPresenter.setEnabled(!readOnly);

        // Value Format
        final ValueFormat vf = floorMapDoc.getValueFormat();
        for (int i = 0; i < valueFormatListBox.getItemCount(); i++) {
            if (valueFormatListBox.getItemText(i).equals(vf.name())) {
                valueFormatListBox.setSelectedIndex(i);
                break;
            }
        }
        valueFormatListBox.setEnabled(!readOnly);

        // Value Schema
        final List<FloorMapFieldMapping> schema =
                new ArrayList<>(floorMapDoc.getValueSchema());
        schemaDataProvider.setList(schema);
        refreshGrid();

        addButton.setEnabled(!readOnly);
        removeButton.setEnabled(!readOnly && schemaSelectionModel.getSelectedObject() != null);

        // Type Styles — load into the embedded Layers panel (editor mode).
        typeStylesPanel.setEditList(floorMapDoc.getTypeStyles());
    }

    /**
     * Reads the current UI state and produces an updated {@link FloorMapDoc}.
     *
     * <p>The selected value format is parsed from the dropdown; if parsing fails
     * it falls back to {@link ValueFormat#JSON}. A new {@link FloorMapDoc} is
     * built via the document's copy-builder, incorporating the currently selected
     * events store reference, facts store reference, value format, and value
     * schema list.</p>
     *
     * @param doc the existing document to base the updated copy on
     * @return a new {@link FloorMapDoc} instance reflecting the current UI state
     */
    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc doc) {
        // Read value format from dropdown
        final String selectedFormat = valueFormatListBox.getSelectedValue();
        ValueFormat vf = ValueFormat.JSON;
        try {
            vf = ValueFormat.valueOf(selectedFormat);
        } catch (final IllegalArgumentException ignored) {
            // Fall back to JSON
        }

        return doc.copy()
                .eventsStoreRef(eventsStoreRefPresenter.getSelectedEntityReference())
                .factsStoreRef(factsStoreRefPresenter.getSelectedEntityReference())
                .valueFormat(vf)
                .valueSchema(new ArrayList<>(schemaDataProvider.getList()))
                .typeStyles(typeStylesPanel.getEditList())
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #onChange()} to propagate the dirty state up to
     * the parent {@link FloorMapPresenter}, which manages the document
     * save lifecycle.</p>
     */
    @Override
    public void onDirty() {
        onChange();
    }

    /**
     * View contract for {@link FloorMapSettingsPresenter}.
     *
     * <p>Provides slots for the events/facts store selection widgets, the value
     * format dropdown, and the value schema grid with its toolbar.</p>
     */
    public interface FloorMapSettingsView extends View, HasUiHandlers<DirtyUiHandlers>, ReadOnlyChangeHandler {

        /**
         * Sets the view widget for the Events Store document-selection box.
         *
         * @param view the {@link DocSelectionBoxPresenter} view
         */
        void setEventsStoreRefView(View view);

        /**
         * Sets the view widget for the Facts Store document-selection box.
         *
         * @param view the {@link DocSelectionBoxPresenter} view
         */
        void setFactsStoreRefView(View view);

        /**
         * Sets the widget used for the Value Format dropdown.
         *
         * @param widget the {@link ListBox} (or equivalent) widget
         */
        void setValueFormatWidget(Widget widget);

        /**
         * Sets the toolbar widget (add/remove buttons) above the schema grid.
         *
         * @param toolbar the {@link ButtonPanel} widget
         */
        void setSchemaToolbar(Widget toolbar);

        /**
         * Sets the data grid widget that displays the Value Schema mappings.
         *
         * @param grid the {@link MyDataGrid} widget
         */
        void setSchemaGrid(Widget grid);

        /**
         * Sets the toolbar widget for the Type Styles grid (Discover / reorder /
         * remove buttons).
         *
         * @param toolbar the toolbar widget
         */
        void setTypeStylesToolbar(Widget toolbar);

        /**
         * Sets the data grid widget that displays the per-type styles.
         *
         * @param grid the {@link MyDataGrid} widget
         */
        void setTypeStylesGrid(Widget grid);
    }
}
