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

import stroom.cell.colour.client.ColourInputCell;
import stroom.data.grid.client.MyDataGrid;
import stroom.floormap.client.presenter.FloorMapLayersPresenter.FloorMapLayersView;
import stroom.floormap.shared.TypeStyle;
import stroom.floormap.shared.TypeStyle.Shape;
import stroom.item.client.SelectionBox;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.cell.client.EditTextCell;
import com.google.gwt.cell.client.SafeHtmlCell;
import com.google.gwt.cell.client.SelectionCell;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.TextColumn;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The <strong>Layers</strong> panel — one row per configured fact type
 * ({@link TypeStyle}), i.e. per layer. A single component used in two modes,
 * configured once at construction by the host:
 *
 * <ul>
 *   <li><strong>Viewer</strong> ({@link #configureViewer}) — Editor &amp; Map tabs.
 *       Columns: swatch, name, count, state; toolbar: show/hide, solo, show-all.
 *       Reports live (transient, non-persisted) visibility intents to a
 *       {@link LayersHandler}; the host applies them to the canvas.</li>
 *   <li><strong>Editor</strong> ({@link #configureEditor}) — the Settings tab, in
 *       place of the retired bespoke Type Styles grid. Columns: swatch, editable
 *       type / shape / colour; toolbar: Discover, move up/down, remove. Edits the
 *       document's ordered {@code typeStyles}; the host ({@code FloorMapSettingsPresenter})
 *       persists them via its own {@code onWrite} (persistence path unchanged).</li>
 * </ul>
 *
 * <p>{@link #configureViewer} or {@link #configureEditor} must be called exactly
 * once, before the panel is revealed.</p>
 */
public class FloorMapLayersPresenter extends MyPresenterWidget<FloorMapLayersView> {

    private static final String DEFAULT_SWATCH_COLOUR = "#8b95a4";
    private static final String SHAPE_DEFAULT = "(default)";
    private static final String NO_PRESET = "(no view)";

    private final MyDataGrid<TypeStyle> dataGrid;
    private final ListDataProvider<TypeStyle> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<TypeStyle> selectionModel = new SingleSelectionModel<>();
    private final ButtonPanel buttonPanel = new ButtonPanel();

    private boolean editor;

    // --- viewer mode state ---
    private LayersHandler viewerHandler;
    private ButtonView toggleVisibilityButton;
    private ButtonView soloButton;
    private ButtonView lockButton;
    private ButtonView dimButton;
    private ButtonView showAllButton;
    private ButtonView savePresetButton;
    private SelectionBox<String> presetBox;
    private boolean canSavePresets;
    private Map<String, Integer> counts = new HashMap<>();
    private Set<String> hiddenTypes = new HashSet<>();
    private Set<String> lockedTypes = new HashSet<>();
    private Map<String, Double> opacityByType = new HashMap<>();
    private String soloType;

    // --- editor mode state ---
    private EditHandler editHandler;
    private ButtonView discoverButton;
    private ButtonView moveUpButton;
    private ButtonView moveDownButton;
    private ButtonView removeButton;

    @Inject
    public FloorMapLayersPresenter(final EventBus eventBus,
                                   final FloorMapLayersView view) {
        super(eventBus, view);
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setSelectionModel(selectionModel);
        view.setGridView(dataGrid);
        dataProvider.addDataDisplay(dataGrid);
        view.setToolbar(buttonPanel);
    }

    // -----------------------------------------------------------------------
    // Configuration (call exactly one, before the panel is revealed)
    // -----------------------------------------------------------------------

    /**
     * Configures the panel as a read-only <em>viewer</em> (Editor / Map tabs).
     *
     * @param handler        applies the live visibility / lock / dim / preset intents
     * @param canSavePresets {@code true} on the Editor tab (may save the current view
     *                       as a preset); {@code false} on the read-only Map tab
     */
    public void configureViewer(final LayersHandler handler, final boolean canSavePresets) {
        this.editor = false;
        this.viewerHandler = handler;
        this.canSavePresets = canSavePresets;
        initViewerColumns();

        // Preset ("view") bar above the toolbar.
        presetBox = new SelectionBox<>();
        presetBox.setNonSelectString(NO_PRESET);
        final FlowPanel presetBar = new FlowPanel();
        presetBar.add(new Label("View:"));
        presetBar.add(presetBox);
        getView().setPresetBar(presetBar);

        toggleVisibilityButton = buttonPanel.addButton(
                new Preset(SvgImage.EYE, "Show / hide layer", false));
        soloButton = buttonPanel.addButton(
                new Preset(SvgImage.FILTER, "Isolate (solo) this layer", false));
        lockButton = buttonPanel.addButton(
                new Preset(SvgImage.LOCKED, "Lock / unlock layer (protect from editing)", false));
        dimButton = buttonPanel.addButton(
                new Preset(SvgImage.BORDERED_CIRCLE, "Dim / undim layer", false));
        showAllButton = buttonPanel.addButton(
                new Preset(SvgImage.SHOW, "Reset (show all, unlock, undim)", true));
        if (canSavePresets) {
            savePresetButton = buttonPanel.addButton(SvgPresets.SAVE_AS);
            savePresetButton.setTitle("Save current view as a preset");
        }

        // Wire handlers here (not in onBind) so they attach to the just-created
        // buttons regardless of when the panel is bound/revealed.
        registerHandler(selectionModel.addSelectionChangeHandler(e -> updateButtonStates()));
        registerHandler(toggleVisibilityButton.addClickHandler(e -> fireForSelected(
                FloorMapLayersPresenter.this::fireToggleVisibility)));
        registerHandler(soloButton.addClickHandler(e -> fireForSelected(
                FloorMapLayersPresenter.this::fireToggleSolo)));
        registerHandler(lockButton.addClickHandler(e -> fireForSelected(
                FloorMapLayersPresenter.this::fireToggleLock)));
        registerHandler(dimButton.addClickHandler(e -> fireForSelected(
                FloorMapLayersPresenter.this::fireToggleDim)));
        registerHandler(showAllButton.addClickHandler(e -> {
            if (viewerHandler != null) {
                viewerHandler.onShowAll();
            }
        }));
        registerHandler(presetBox.addValueChangeHandler(e -> {
            if (viewerHandler != null) {
                viewerHandler.onApplyPreset(e.getValue());
            }
        }));
        if (savePresetButton != null) {
            registerHandler(savePresetButton.addClickHandler(e -> {
                if (viewerHandler != null) {
                    viewerHandler.onSavePreset();
                }
            }));
        }
        updateButtonStates();
    }

    /** Configures the panel as an <em>editor</em> of the type styles (Settings tab). */
    public void configureEditor(final EditHandler handler) {
        this.editor = true;
        this.editHandler = handler;
        initEditorColumns();
        discoverButton = buttonPanel.addButton(SvgPresets.REFRESH_BLUE);
        discoverButton.setTitle("Discover types from the facts store");
        moveUpButton = buttonPanel.addButton(SvgPresets.ARROW_UP);
        moveDownButton = buttonPanel.addButton(SvgPresets.ARROW_DOWN);
        removeButton = buttonPanel.addButton(SvgPresets.DELETE);

        // Wire handlers here (not in onBind) — see configureViewer.
        registerHandler(selectionModel.addSelectionChangeHandler(e -> updateButtonStates()));
        registerHandler(discoverButton.addClickHandler(e -> {
            if (editHandler != null) {
                editHandler.onDiscoverRequested();
            }
        }));
        registerHandler(moveUpButton.addClickHandler(e -> moveSelected(-1)));
        registerHandler(moveDownButton.addClickHandler(e -> moveSelected(1)));
        registerHandler(removeButton.addClickHandler(e -> removeSelected()));
        updateButtonStates();
    }

    private void fireForSelected(final Consumer<String> action) {
        final TypeStyle s = selectionModel.getSelectedObject();
        if (s != null && viewerHandler != null) {
            action.accept(s.getType());
        }
    }

    private void fireToggleVisibility(final String type) {
        viewerHandler.onToggleVisibility(type);
    }

    private void fireToggleSolo(final String type) {
        viewerHandler.onToggleSolo(type);
    }

    private void fireToggleLock(final String type) {
        viewerHandler.onToggleLock(type);
    }

    private void fireToggleDim(final String type) {
        viewerHandler.onToggleDim(type);
    }

    /**
     * Populates the preset ("view") picker (viewer mode). Setting the value does
     * not re-fire the apply handler.
     *
     * @param names    the available preset names
     * @param selected the currently-selected preset name, or {@code null} for none
     */
    public void setPresets(final List<String> names, final String selected) {
        if (presetBox == null) {
            return;
        }
        presetBox.clear();
        if (names != null) {
            presetBox.addItems(names);
        }
        presetBox.setValue(selected);
    }

    // -----------------------------------------------------------------------
    // Viewer mode
    // -----------------------------------------------------------------------

    private void initViewerColumns() {
        dataGrid.addColumn(swatchColumn(), "", 34);

        final Column<TypeStyle, String> nameColumn = new TextColumn<>() {
            @Override
            public String getValue(final TypeStyle style) {
                final String type = style.getType();
                return type == null || type.isEmpty()
                        ? "(unnamed)"
                        : type;
            }
        };
        dataGrid.addResizableColumn(nameColumn, "Layer", 150);

        final Column<TypeStyle, String> countColumn = new TextColumn<>() {
            @Override
            public String getValue(final TypeStyle style) {
                return String.valueOf(countFor(style.getType()));
            }
        };
        dataGrid.addColumn(countColumn, "Count", 60);

        final Column<TypeStyle, String> stateColumn = new TextColumn<>() {
            @Override
            public String getValue(final TypeStyle style) {
                return stateFor(style.getType());
            }
        };
        dataGrid.addColumn(stateColumn, "State", 90);
    }

    /**
     * Replaces the viewer contents and the display-only state.
     *
     * @param layers   the configured layers (the doc's ordered type styles)
     * @param counts   fact count per type at the current time
     * @param hidden   the set of types currently hidden (live, transient)
     * @param soloType the soloed type, or {@code null}
     */
    public void setData(final List<TypeStyle> layers,
                        final Map<String, Integer> counts,
                        final Set<String> hidden,
                        final Set<String> locked,
                        final Map<String, Double> opacityByType,
                        final String soloType) {
        this.counts = counts != null ? counts : new HashMap<>();
        this.hiddenTypes = hidden != null ? hidden : new HashSet<>();
        this.lockedTypes = locked != null ? locked : new HashSet<>();
        this.opacityByType = opacityByType != null ? opacityByType : new HashMap<>();
        this.soloType = soloType;
        final List<TypeStyle> list = layers != null ? layers : List.of();
        dataProvider.setList(list);
        dataGrid.setRowData(0, list);
        updateButtonStates();
    }

    private int countFor(final String type) {
        final Integer c = counts.get(type);
        return c != null ? c : 0;
    }

    private String stateFor(final String type) {
        if (soloType != null && !soloType.isEmpty()) {
            return soloType.equals(type)
                    ? "Isolated"
                    : "Hidden";
        }
        if (hiddenTypes.contains(type)) {
            return "Hidden";
        }
        final StringBuilder sb = new StringBuilder();
        if (lockedTypes.contains(type)) {
            sb.append("Locked");
        }
        final Double opacity = opacityByType.get(type);
        if (opacity != null && opacity < 1.0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("Dim");
        }
        return sb.length() > 0
                ? sb.toString()
                : "Shown";
    }

    // -----------------------------------------------------------------------
    // Editor mode
    // -----------------------------------------------------------------------

    private void initEditorColumns() {
        dataGrid.addColumn(swatchColumn(), "", 34);

        // Type name — editable text.
        final Column<TypeStyle, String> typeColumn = new Column<>(new EditTextCell()) {
            @Override
            public String getValue(final TypeStyle style) {
                return style.getType() != null ? style.getType() : "";
            }
        };
        typeColumn.setFieldUpdater((index, style, val) ->
                replaceTypeStyle(index, new TypeStyle(val, style.getShape(), style.getColour())));
        dataGrid.addResizableColumn(typeColumn, "Type", 140);

        // Shape — dropdown of the enum plus a "(default)" (null) option.
        final List<String> shapeOptions = new ArrayList<>();
        shapeOptions.add(SHAPE_DEFAULT);
        for (final Shape shape : Shape.values()) {
            shapeOptions.add(shape.name());
        }
        final Column<TypeStyle, String> shapeColumn = new Column<>(new SelectionCell(shapeOptions)) {
            @Override
            public String getValue(final TypeStyle style) {
                return style.getShape() != null ? style.getShape().name() : SHAPE_DEFAULT;
            }
        };
        shapeColumn.setFieldUpdater((index, style, val) -> {
            final Shape newShape = SHAPE_DEFAULT.equals(val) ? null : Shape.valueOf(val);
            replaceTypeStyle(index, new TypeStyle(style.getType(), newShape, style.getColour()));
        });
        dataGrid.addColumn(shapeColumn, "Shape", 110);

        // Colour — colour picker.
        final Column<TypeStyle, String> colourColumn = new Column<>(new ColourInputCell()) {
            @Override
            public String getValue(final TypeStyle style) {
                return style.getColour();
            }
        };
        colourColumn.setFieldUpdater((index, style, val) -> {
            final String colour = val != null && !val.isEmpty() ? val : null;
            replaceTypeStyle(index, new TypeStyle(style.getType(), style.getShape(), colour));
        });
        dataGrid.addColumn(colourColumn, "Colour", 90);
    }

    /** Loads the editable type-style list (Settings host, on read). */
    public void setEditList(final List<TypeStyle> typeStyles) {
        final List<TypeStyle> list = typeStyles != null
                ? new ArrayList<>(typeStyles)
                : new ArrayList<>();
        dataProvider.setList(list);
        refreshEditorGrid();
        updateButtonStates();
    }

    /** Returns a copy of the current editable type-style list (Settings host, on write). */
    public List<TypeStyle> getEditList() {
        return new ArrayList<>(dataProvider.getList());
    }

    private void replaceTypeStyle(final int index, final TypeStyle updated) {
        final List<TypeStyle> list = dataProvider.getList();
        if (index >= 0 && index < list.size()) {
            list.set(index, updated);
            refreshEditorGrid();
            fireEdited();
        }
    }

    private void moveSelected(final int delta) {
        final TypeStyle selected = selectionModel.getSelectedObject();
        if (selected == null) {
            return;
        }
        final List<TypeStyle> list = dataProvider.getList();
        final int index = list.indexOf(selected);
        final int target = index + delta;
        if (index >= 0 && target >= 0 && target < list.size()) {
            list.remove(index);
            list.add(target, selected);
            refreshEditorGrid();
            selectionModel.setSelected(selected, true);
            updateButtonStates();
            fireEdited();
        }
    }

    private void removeSelected() {
        final TypeStyle selected = selectionModel.getSelectedObject();
        if (selected != null) {
            dataProvider.getList().remove(selected);
            selectionModel.clear();
            refreshEditorGrid();
            updateButtonStates();
            fireEdited();
        }
    }

    private void refreshEditorGrid() {
        final List<TypeStyle> list = dataProvider.getList();
        dataGrid.setRowData(0, list);
        dataGrid.setRowCount(list.size(), true);
    }

    private void fireEdited() {
        if (editHandler != null) {
            editHandler.onTypeStylesChanged();
        }
    }

    // -----------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------

    private void updateButtonStates() {
        final boolean hasSelection = selectionModel.getSelectedObject() != null;
        if (editor) {
            final List<TypeStyle> list = dataProvider.getList();
            final int index = selectionModel.getSelectedObject() != null
                    ? list.indexOf(selectionModel.getSelectedObject())
                    : -1;
            removeButton.setEnabled(index >= 0);
            moveUpButton.setEnabled(index > 0);
            moveDownButton.setEnabled(index >= 0 && index < list.size() - 1);
        } else {
            toggleVisibilityButton.setEnabled(hasSelection);
            soloButton.setEnabled(hasSelection);
            lockButton.setEnabled(hasSelection);
            dimButton.setEnabled(hasSelection);
        }
    }

    private Column<TypeStyle, SafeHtml> swatchColumn() {
        return new Column<>(new SafeHtmlCell()) {
            @Override
            public SafeHtml getValue(final TypeStyle style) {
                return swatch(style);
            }
        };
    }

    private SafeHtml swatch(final TypeStyle style) {
        final String colour = sanitiseColour(style.getColour());
        return SafeHtmlUtils.fromTrustedString(
                "<span style=\"display:inline-block;width:12px;height:12px;border-radius:2px;"
                        + "border:1px solid rgba(0,0,0,.25);vertical-align:middle;background:"
                        + colour + ";\"></span>");
    }

    private static String sanitiseColour(final String colour) {
        if (colour == null || colour.isEmpty()) {
            return DEFAULT_SWATCH_COLOUR;
        }
        for (int i = 0; i < colour.length(); i++) {
            final char c = colour.charAt(i);
            final boolean ok = c == '#'
                    || (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z');
            if (!ok) {
                return DEFAULT_SWATCH_COLOUR;
            }
        }
        return colour;
    }

    /** Viewer callback — the user's live layer visibility / lock / dim intents. */
    public interface LayersHandler {

        void onToggleVisibility(String type);

        void onToggleSolo(String type);

        void onToggleLock(String type);

        void onToggleDim(String type);

        void onShowAll();

        /** Apply the named preset ("view"), or reset when {@code null}. */
        void onApplyPreset(String name);

        /** Capture the current live view as a new preset (Editor only). */
        void onSavePreset();
    }

    /** Editor callback — the type styles changed, or Discover was requested. */
    public interface EditHandler {

        /** Fired after any edit (cell change, reorder, remove) so the host can mark dirty. */
        void onTypeStylesChanged();

        /** The user pressed Discover; the host runs the scan and calls {@link #setEditList}. */
        void onDiscoverRequested();
    }

    /** View contract: a toolbar strip above a data grid (see the Tracking panel). */
    public interface FloorMapLayersView extends View {

        void setGridView(Widget gridWidget);

        void setToolbar(Widget toolbarWidget);

        void setPresetBar(Widget presetWidget);
    }
}
