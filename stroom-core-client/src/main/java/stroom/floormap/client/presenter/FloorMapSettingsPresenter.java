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
import stroom.docref.DocRef;
import stroom.document.client.event.DirtyUiHandlers;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.floormap.client.FloorMapAria;
import stroom.floormap.client.cell.AccessibleSelectionCell;
import stroom.floormap.client.cell.AccessibleTextInputCell;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.ValueFormat;
import stroom.security.shared.DocumentPermission;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.TableCellElement;
import com.google.gwt.dom.client.TableRowElement;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Presenter for the <em>Settings</em> tab of a {@link FloorMapDoc}.
 *
 * <p>This presenter lets users configure:</p>
 * <ul>
 *   <li>The <strong>Events Store</strong> reference – a {@link SqlTemporalStoreDoc} used to
 *       persist floor-map event data.</li>
 *   <li>The <strong>Facts Store</strong> reference – a {@link SqlTemporalStoreDoc} used to
 *       persist floor-map fact data.</li>
 *   <li>The <strong>Value Format</strong> – the serialisation format for map values
 *       (selected from the {@link ValueFormat} enum).</li>
 *   <li>The <strong>Value Schema</strong> – an editable grid of
 *       {@link FloorMapFieldMapping} entries that define the fields, their roles, paths,
 *       display names, and default values.</li>
 * </ul>
 *
 * <p>Per-type presentation (shape/colour/paint order) is configured on the
 * <em>Layers</em> panel (in the Map/Editor dock), not here.</p>
 *
 * <p>It extends {@link DocPresenter} and is embedded as a tab within
 * {@link FloorMapPresenter}'s tabbed view. The companion view interface is
 * {@link FloorMapSettingsView}.</p>
 *
 * @see FloorMapPresenter
 * @see FloorMapDoc
 * @see FloorMapFieldMapping
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

    // Held so that onRead can push the read-only state into them: the cells render
    // `disabled` when read-only, rather than looking editable while the field updaters
    // silently discard the edit.
    private AccessibleSelectionCell roleCell;
    private AccessibleTextInputCell pathCell;
    private AccessibleTextInputCell nameCell;
    private AccessibleTextInputCell defaultCell;
    private final ButtonView addButton;
    private final ButtonView removeButton;
    private boolean readOnly;

    /**
     * Set once the Editor tab enables area support on this document; onRead
     * then re-applies {@link #applyAreaPatch()} so the grid never reverts the
     * upgrade before it is persisted.
     */
    private boolean areaPatchActive;

    @Inject
    public FloorMapSettingsPresenter(final EventBus eventBus,
                                     final FloorMapSettingsView view,
                                     final Provider<DocSelectionBoxPresenter> docSelectionBoxPresenterProvider) {
        super(eventBus, view);

        view.setUiHandlers(this);

        this.eventsStoreRefPresenter = docSelectionBoxPresenterProvider.get();
        this.eventsStoreRefPresenter.setIncludedTypes(SqlTemporalStoreDoc.TYPE);
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
        // Note for anyone tempted to set KeyboardSelectionPolicy.DISABLED here: don't.
        // An earlier revision did, to remove a stray tab stop — GWT marks the
        // keyboard-selected cell's wrapper div `tabindex="0"`, and as the selected cell
        // moves that stop can land before the control being tabbed away from, sending
        // focus backwards. Disabling the policy does give a clean row-major tab order,
        // but it also switches off the table's arrow-key and space handling, and this
        // grid's Remove button is enabled solely by schemaSelectionModel — so row
        // selection became mouse-only and Remove stopped being operable by keyboard at
        // all. A worse bargain than the tab order it bought.
        //
        // Note also that MyDataGrid's empty keyboard handler does NOT apply to this
        // grid: it is installed by the two-arg setSelectionModel override, and the
        // one-arg call above goes straight to AbstractHasData, so the policy here is
        // GWT's own ENABLED default with GWT's DefaultKeyboardSelectionHandler live.
        // The stray tab stop is the pre-existing behaviour of every Stroom grid, not
        // something this feature introduced. See §13.3 of docs/floormap-accessibility.md.
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
    }

    /**
     * Builds the accessible name for a control in the schema grid.
     *
     * <p>Every row's control would otherwise announce identically ("Role", "Path", …), so the
     * row's JSON path is included to distinguish them.</p>
     *
     * <p>Rows whose path is null or empty fall back to their 1-based position. Note a newly
     * added row does <em>not</em> take that fallback: {@link #onAddMapping()} seeds the path
     * with the placeholder {@code "."}, which is non-empty, so it announces as "Role for ."
     * until the user types a real path. Only a persisted document with a blank path reaches
     * the positional form.</p>
     */
    private String schemaCellLabel(final String columnName, final int rowIndex) {
        final List<FloorMapFieldMapping> list = schemaDataProvider.getList();
        String row = null;
        if (rowIndex >= 0 && rowIndex < list.size()) {
            final FloorMapFieldMapping mapping = list.get(rowIndex);
            if (mapping != null && mapping.getPath() != null && !mapping.getPath().isEmpty()) {
                row = mapping.getPath();
            }
        }
        if (row == null) {
            row = "row " + (rowIndex + 1);
        }
        return columnName + " for " + row;
    }

    /**
     * Initialises the columns displayed in the Value Schema data grid.
     *
     * <p>Four editable columns are added in order:</p>
     * <ol>
     *   <li><strong>Role</strong> – a dropdown ({@link AccessibleSelectionCell}) of
     *       {@link Role} values.</li>
     *   <li><strong>Path</strong> – an {@link AccessibleTextInputCell} for the JSON path.</li>
     *   <li><strong>Display Name</strong> – an {@link AccessibleTextInputCell} for the
     *       label.</li>
     *   <li><strong>Default</strong> – an {@link AccessibleTextInputCell} for the default
     *       value.</li>
     * </ol>
     *
     * <p>These are the accessible variants rather than GWT's {@code SelectionCell} and
     * {@code EditTextCell}, both of which render {@code tabindex="-1"} and so cannot be
     * reached by keyboard in a Stroom grid — see {@link AccessibleSelectionCell} for the
     * reason.</p>
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
        roleCell = new AccessibleSelectionCell(roleOptions, index -> schemaCellLabel("Role", index));
        final Column<FloorMapFieldMapping, String> roleColumn =
                new Column<>(roleCell) {
                    @Override
                    public String getValue(final FloorMapFieldMapping mapping) {
                        return mapping.getRole() != null ? mapping.getRole().name() : Role.CUSTOM.name();
                    }
                };
        roleColumn.setFieldUpdater((index, mapping, val) -> {
            if (!readOnly) {
                final Role newRole = Role.valueOf(val);
                replaceMapping(index, new FloorMapFieldMapping(
                        mapping.getPath(), newRole, mapping.getDisplayName(), mapping.getDefaultValue()), roleColumn);
            }
        });
        schemaGrid.addColumn(roleColumn, "Role");

        // Path column – editable text
        pathCell = new AccessibleTextInputCell(index -> schemaCellLabel("Path", index));
        final Column<FloorMapFieldMapping, String> pathColumn =
                new Column<>(pathCell) {
                    @Override
                    public String getValue(final FloorMapFieldMapping mapping) {
                        return mapping.getPath() != null ? mapping.getPath() : "";
                    }
                };
        pathColumn.setFieldUpdater((index, mapping, val) -> {
            if (!readOnly) {
                replaceMapping(index, new FloorMapFieldMapping(
                        val, mapping.getRole(), mapping.getDisplayName(), mapping.getDefaultValue()), pathColumn);
            }
        });
        schemaGrid.addColumn(pathColumn, "Path");

        // Display Name column – editable text
        nameCell = new AccessibleTextInputCell(index -> schemaCellLabel("Display Name", index));
        final Column<FloorMapFieldMapping, String> nameColumn =
                new Column<>(nameCell) {
                    @Override
                    public String getValue(final FloorMapFieldMapping mapping) {
                        return mapping.getDisplayName() != null ? mapping.getDisplayName() : "";
                    }
                };
        nameColumn.setFieldUpdater((index, mapping, val) -> {
            if (!readOnly) {
                replaceMapping(index, new FloorMapFieldMapping(
                        mapping.getPath(), mapping.getRole(), val, mapping.getDefaultValue()), nameColumn);
            }
        });
        schemaGrid.addColumn(nameColumn, "Display Name");

        // Default Value column – editable text
        defaultCell = new AccessibleTextInputCell(index -> schemaCellLabel("Default", index));
        final Column<FloorMapFieldMapping, String> defaultColumn =
                new Column<>(defaultCell) {
                    @Override
                    public String getValue(final FloorMapFieldMapping mapping) {
                        return mapping.getDefaultValue() != null ? mapping.getDefaultValue() : "";
                    }
                };
        defaultColumn.setFieldUpdater((index, mapping, val) -> {
            if (!readOnly) {
                replaceMapping(index, new FloorMapFieldMapping(
                        mapping.getPath(), mapping.getRole(), mapping.getDisplayName(), val), defaultColumn);
            }
        });
        schemaGrid.addColumn(defaultColumn, "Default");
    }

    /**
     * Replaces the {@link FloorMapFieldMapping} at the given index in the
     * data provider list with a new instance and marks the document as dirty.
     *
     * <p>It does not refresh the grid: mutating the data provider's list already redraws
     * the affected row, and a full refresh here would be actively harmful — see the
     * comment in the body.</p>
     *
     * @param index       the zero-based position in the list
     * @param updated     the replacement mapping
     * @param column      the column whose control was being edited, so focus can be put
     *                    back on it if the row redraw drops it. Passed as the column rather
     *                    than an index so the position is resolved from the grid at the
     *                    moment it is needed — a hard-coded index would silently point at
     *                    the wrong cell if a column were ever inserted.
     */
    private void replaceMapping(final int index,
                                final FloorMapFieldMapping updated,
                                final Column<FloorMapFieldMapping, String> column) {
        final List<FloorMapFieldMapping> list = schemaDataProvider.getList();
        if (index >= 0 && index < list.size()) {
            // This redraws the row on its own: getList() hands back a ListDataProvider
            // wrapper that flags itself modified and flushes on mutation, so there is no
            // unnotified path.
            //
            // Do NOT add refreshGrid() back here. The flush above marks only this row and
            // replaces just its children; refreshGrid()'s setRowData(0, list) covers the
            // whole range, which sends GWT down its replaceAllChildren path and destroys
            // every row's DOM. The focus guard below depends on the difference: a control
            // in another row survives a single-row redraw and keeps focus, and only
            // because of that can restoreSchemaFocusAfterRedraw leave it alone.
            list.set(index, updated);
            onChange();
            // The redraw replaces the row element, destroying the control being edited and
            // dropping focus to <body>. Without this a keyboard user was ejected from the
            // grid after every single edit and had to tab all the way back in.
            restoreSchemaFocusAfterRedraw(index, schemaGrid.getColumnIndex(column));
        }
    }

    /**
     * Re-focuses the control at the given cell once the row redraw triggered by an edit has
     * replaced it.
     *
     * <p>Only acts if focus was actually lost — if the user committed the edit by tabbing to
     * a control in another row, that control survives the redraw and keeps focus, and
     * stealing it back would be worse than the problem. The cost of the conservative choice
     * is that a Tab <em>within</em> the edited row lands back on the cell just left, so the
     * user tabs once more; that beats guessing at their intent.</p>
     */
    private void restoreSchemaFocusAfterRedraw(final int rowIndex, final int columnIndex) {
        Scheduler.get().scheduleDeferred(() -> {
            final Element active = getActiveElement(Document.get());
            final boolean focusLost = active == null
                    || "body".equalsIgnoreCase(active.getTagName());
            if (focusLost) {
                focusSchemaControl(rowIndex, columnIndex);
            }
        });
    }

    private void focusSchemaControl(final int rowIndex, final int columnIndex) {
        if (rowIndex < 0 || rowIndex >= schemaGrid.getVisibleItemCount()) {
            return;
        }
        final TableRowElement row = schemaGrid.getRowElement(rowIndex);
        if (row == null) {
            return;
        }
        final TableCellElement cell = row.getCells().getItem(columnIndex);
        if (cell == null) {
            return;
        }
        // FloorMapAria rather than a local tag scan: it is the same feature's helper, it
        // skips disabled and tabindex="-1" candidates that must not be focused, and it
        // reports whether it found anything — a silent no-op here would look exactly like
        // a working restore.
        FloorMapAria.focusFirstFocusable(cell);
    }

    /**
     * Returns the document's currently focused element, or {@code null} if nothing is
     * focused.
     *
     * <p><strong>Why this is JSNI.</strong> {@code document.activeElement} is not exposed by
     * GWT: {@link Document} has {@code getElementById} and {@code getDocumentElement} but no
     * {@code getActiveElement}, in 2.13.0 or any earlier version. There is no Java API to
     * call, so reading it at all requires dropping to JavaScript. This project has no
     * Elemental2 dependency, so {@code DomGlobal.document.activeElement} is not an option
     * either.</p>
     *
     * <p><strong>Why it is duplicated rather than shared.</strong> The obvious home for this
     * already exists — {@code stroom.widget.popup.client.view.CurrentFocus} declares the
     * identical method as {@code public static native}. Its enclosing class is
     * package-private, though, so nothing outside that package can reach it, and
     * {@code AbstractTabBar} carries its own copy for the same reason. This is the third such
     * copy. Making {@code CurrentFocus} public, or lifting the method into a shared client
     * utility, would let the three collapse into one; that is a change to shared widget code
     * and deliberately not made here. ({@code AnnotationEditPresenter} also reads
     * {@code $doc.activeElement}, but inline inside an unrelated clipboard JSNI method, so it
     * would not be folded in by the same change.)</p>
     *
     * <p><strong>Why it takes a {@link Document} parameter</strong> rather than using
     * {@code $doc} directly, which would be shorter: purely to match the two widget copies
     * above, so all three read alike and a future de-duplication is a straight lift.</p>
     *
     * <p>Used by {@link #restoreSchemaFocusAfterRedraw(int, int)} to tell "the redraw threw
     * focus away" from "the user moved focus somewhere deliberately", which decides whether
     * putting focus back is a repair or a theft.</p>
     *
     * @param doc the document to query, normally {@link Document#get()}
     * @return the focused element, or {@code null} if there is none
     */
    private static native Element getActiveElement(Document doc) /*-{
        return doc.activeElement;
    }-*/;

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

    /**
     * Pushes the read-only state into the schema grid's cells. Call before
     * {@link #refreshGrid()}, since the cells only pick it up when they re-render.
     */
    private void setSchemaCellsReadOnly(final boolean readOnly) {
        roleCell.setReadOnly(readOnly);
        pathCell.setReadOnly(readOnly);
        nameCell.setReadOnly(readOnly);
        defaultCell.setReadOnly(readOnly);
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
        setSchemaCellsReadOnly(readOnly);
        refreshGrid();

        addButton.setEnabled(!readOnly);
        removeButton.setEnabled(!readOnly && schemaSelectionModel.getSelectedObject() != null);

        // Keep an Editor-tab area-support upgrade visible in the schema grid: the
        // document just read may predate the upgrade (it is only persisted on
        // save), and this tab's onWrite replaces the schema wholesale.
        if (areaPatchActive) {
            applyAreaPatch();
        }

        // Note: measurementUnits is deliberately NOT read or written by this tab.
        // A map's scale is set with the Editor's Set Scale tool and staged in its
        // doc session; because nothing here touches the field, copy() carries it
        // through untouched and no cross-tab patching is needed.
    }

    /**
     * Merges the default area mappings ({@code GEOMETRY}/{@code FILL}/
     * {@code OPACITY}) into the Value Schema grid, if absent. Called when the
     * Editor tab enables area support on this document — this tab writes
     * {@code valueSchema} wholesale from its grid state on save, so an unpatched
     * grid would silently revert the upgrade. Idempotent; stays active so
     * subsequent reads re-apply it until the upgrade is persisted (after which
     * the merge is a no-op). The matching {@code "area"} type style is handled
     * by the Layers panel.
     */
    public void applyAreaPatch() {
        areaPatchActive = true;
        final ValueFormat vf = getEntity() != null
                ? getEntity().getValueFormat()
                : ValueFormat.JSON;
        schemaDataProvider.setList(new ArrayList<>(
                FloorMapFieldMapping.withAreaMappings(schemaDataProvider.getList(), vf)));
        refreshGrid();
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

        // Note: typeStyles is intentionally NOT written here — it is owned by
        // the Layers panel (persisted via the Editor tab). copy() preserves the
        // document's existing typeStyles.
        return doc.copy()
                .eventsStoreRef(eventsStoreRefPresenter.getSelectedEntityReference())
                .factsStoreRef(factsStoreRefPresenter.getSelectedEntityReference())
                .valueFormat(vf)
                .valueSchema(new ArrayList<>(schemaDataProvider.getList()))
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
    }
}
