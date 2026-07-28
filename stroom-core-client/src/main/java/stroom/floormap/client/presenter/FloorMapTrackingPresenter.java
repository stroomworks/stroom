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
import stroom.floormap.client.presenter.FloorMapTrackingPresenter.FloorMapTrackingView;
import stroom.floormap.shared.FloorMapAreaMembership;
import stroom.floormap.shared.FloorMapEntityList;
import stroom.floormap.shared.FloorMapEntityList.EntityEntry;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.cell.client.SafeHtmlCell;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.TextColumn;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.CellPreviewEvent;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Presenter for the Tracking panel on the Map tab — a grid listing every
 * entity seen on the floor map: moving entities from the events stream
 * (people, assets, vehicles, or any other typed event object) plus the
 * static facts from the facts query (objects, backgrounds and areas).
 *
 * <p>The <strong>Area</strong> column answers "what area is this in?" and is
 * deliberately polymorphic, because areas are themselves rows in this roster: an
 * entity row shows the innermost area containing it, while an area row shows how
 * many entities are currently inside it. Detail that will not fit — the full
 * nesting chain, the occupant list, or where an entity was last seen — goes in
 * the cell's tooltip.</p>
 *
 * <p>Selecting a row tracks that entity: the parent presenter highlights it on
 * the canvas, centres the camera on it, and follows it as it moves. Because a
 * deliberate manual pan pauses following, clicking the
 * <em>already-selected</em> row re-invokes the selection consumer (a plain
 * {@code SelectionChangeEvent} would not fire) so the user can resume
 * following without deselecting first. The toolbar's single
 * <strong>Stop Tracking</strong> button clears the selection.</p>
 */
public class FloorMapTrackingPresenter extends MyPresenterWidget<FloorMapTrackingView> {

    /** Column text for an entity that currently has no known area. */
    private static final String NO_AREA = "—";
    /** Cap on how many occupant names an area row's tooltip lists. */
    private static final int MAX_TOOLTIP_OCCUPANTS = 20;

    private final MyDataGrid<EntityEntry> dataGrid;
    private final ListDataProvider<EntityEntry> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<EntityEntry> selectionModel = new SingleSelectionModel<>();
    private Consumer<EntityEntry> selectionConsumer;

    private final ButtonView stopTrackingButton;

    /**
     * The current area-containment snapshot backing the Area column. Empty until
     * the parent pushes one, so the column reads as "no area" rather than
     * blowing up on a map with no areas.
     */
    private FloorMapAreaMembership areaMembership = FloorMapAreaMembership.EMPTY;

    /**
     * Resolves an area's fact key to the name shown to the user. Supplied by the
     * parent, which owns the fact list; falls back to the raw key.
     */
    private Function<String, String> areaNameResolver;

    /**
     * Last area an entity was seen in, by entity id. The roster is accumulating
     * — an entity with no events near the current instant keeps its row but has
     * no position — so the column shows "no area" while the tooltip can still
     * say where it was last seen.
     */
    private final Map<String, String> lastKnownAreaKey = new HashMap<>();

    @Inject
    public FloorMapTrackingPresenter(final EventBus eventBus,
                                     final FloorMapTrackingView view) {
        super(eventBus, view);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setSelectionModel(selectionModel);
        view.setGridView(dataGrid);
        initGridColumns();
        dataProvider.addDataDisplay(dataGrid);

        // Toolbar
        final ButtonPanel buttonPanel = new ButtonPanel();
        stopTrackingButton = buttonPanel.addButton(SvgPresets.CLEAR);
        stopTrackingButton.setTitle("Stop Tracking");
        view.setToolbar(buttonPanel);
    }

    @Override
    protected void onBind() {
        super.onBind();
        //noinspection unused e
        registerHandler(selectionModel.addSelectionChangeHandler(e -> {
            final EntityEntry selected = selectionModel.getSelectedObject();
            stopTrackingButton.setEnabled(selected != null);
            if (selectionConsumer != null) {
                selectionConsumer.accept(selected);
            }
        }));

        // Clicking the row that is already selected does not fire a
        // SelectionChangeEvent, but it must still re-invoke the consumer —
        // that is the "re-centre / resume following after a manual pan" gesture.
        registerHandler(dataGrid.addCellPreviewHandler((final CellPreviewEvent<EntityEntry> e) -> {
            if ("click".equals(e.getNativeEvent().getType())) {
                final EntityEntry clicked = e.getValue();
                if (clicked != null
                        && clicked.equals(selectionModel.getSelectedObject())
                        && selectionConsumer != null) {
                    selectionConsumer.accept(clicked);
                }
            }
        }));

        //noinspection unused e
        registerHandler(stopTrackingButton.addClickHandler(e ->
                selectionModel.clear()));
    }

    private void initGridColumns() {
        // Name Column
        final Column<EntityEntry, String> nameColumn = new TextColumn<>() {
            @Override
            public String getValue(final EntityEntry entry) {
                return entry.getDisplayName();
            }
        };
        dataGrid.addColumn(nameColumn, "Name");

        // Type Column (e.g. person, vehicle, object)
        final Column<EntityEntry, String> typeColumn = new TextColumn<>() {
            @Override
            public String getValue(final EntityEntry entry) {
                return entry.getType();
            }
        };
        dataGrid.addColumn(typeColumn, "Type");

        // Area Column — polymorphic by row: an entity shows the area it is
        // currently in, an area shows how many entities are inside it. That is
        // what makes the column meaningful for every row, since areas are part
        // of this roster too.
        final Column<EntityEntry, SafeHtml> areaColumn =
                new Column<>(new SafeHtmlCell()) {
                    @Override
                    public SafeHtml getValue(final EntityEntry entry) {
                        return areaCell(entry);
                    }
                };
        dataGrid.addResizableColumn(areaColumn, "Area", 150);

        // Id Column (the full entity id, e.g. an email address)
        final Column<EntityEntry, String> idColumn = new TextColumn<>() {
            @Override
            public String getValue(final EntityEntry entry) {
                return entry.getId();
            }
        };
        dataGrid.addColumn(idColumn, "Id");
    }

    /**
     * Renders one Area cell, with a {@code title} tooltip carrying the detail
     * that will not fit in the cell: the full innermost-to-outermost chain for a
     * nested entity, the occupant list for an area, or where an entity without a
     * current position was last seen.
     */
    private SafeHtml areaCell(final EntityEntry entry) {
        final String id = entry.getId();

        if (areaMembership.isArea(id)) {
            // An area row reports what is inside it, plus its own containing
            // area (areas nest) in the tooltip.
            final List<String> occupants = areaMembership.getOccupants(id);
            final String text = occupants.isEmpty()
                    ? "empty"
                    : occupants.size() + " inside";
            final StringBuilder tooltip = new StringBuilder();
            final String containing = areaNameFor(areaMembership.getInnermostAreaKey(id));
            if (containing != null) {
                tooltip.append("Inside ").append(containing).append('\n');
            }
            if (occupants.isEmpty()) {
                tooltip.append("Nothing is currently inside this area");
            } else {
                tooltip.append("Currently inside:");
                for (int i = 0; i < occupants.size() && i < MAX_TOOLTIP_OCCUPANTS; i++) {
                    tooltip.append("\n• ")
                            .append(FloorMapEntityList.displayName(occupants.get(i)));
                }
                if (occupants.size() > MAX_TOOLTIP_OCCUPANTS) {
                    tooltip.append("\n… and ")
                            .append(occupants.size() - MAX_TOOLTIP_OCCUPANTS)
                            .append(" more");
                }
            }
            return cell(text, tooltip.toString());
        }

        final List<String> containingKeys = areaMembership.getAreaKeys(id);
        if (containingKeys.isEmpty()) {
            // No position at this instant — say so plainly, and offer the last
            // known area in the tooltip rather than implying it is current.
            final String lastKnown = areaNameFor(lastKnownAreaKey.get(id));
            return cell(NO_AREA, lastKnown != null
                    ? "Not in a known area at this time (last seen in " + lastKnown + ")"
                    : "Not in a known area at this time");
        }

        // Innermost first, so the head is the most specific answer; the tooltip
        // carries the whole chain when areas nest.
        final String innermost = areaNameFor(containingKeys.get(0));
        if (containingKeys.size() == 1) {
            return cell(innermost, innermost);
        }
        final StringBuilder tooltip = new StringBuilder("Inside (innermost first):");
        for (final String key : containingKeys) {
            tooltip.append("\n• ").append(areaNameFor(key));
        }
        return cell(innermost + " +" + (containingKeys.size() - 1), tooltip.toString());
    }

    /**
     * Wraps cell text in a span carrying a {@code title} tooltip. Both are
     * escaped — entity ids and area names are document/query data.
     */
    private static SafeHtml cell(final String text, final String tooltip) {
        final SafeHtmlBuilder builder = new SafeHtmlBuilder();
        builder.appendHtmlConstant("<span title=\"");
        builder.appendEscaped(tooltip != null ? tooltip : "");
        builder.appendHtmlConstant("\">");
        builder.appendEscaped(text);
        builder.appendHtmlConstant("</span>");
        return builder.toSafeHtml();
    }

    /**
     * The display name for an area key, or {@code null} when there is no key.
     * Falls back to the raw key if the parent supplied no resolver.
     */
    private String areaNameFor(final String areaKey) {
        if (areaKey == null) {
            return null;
        }
        if (areaNameResolver != null) {
            final String name = areaNameResolver.apply(areaKey);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return areaKey;
    }

    /**
     * Updates the Area column's backing snapshot.
     *
     * <p>Called on every facts/events query refresh (~300ms during playback), so
     * the grid is only redrawn when the containment actually changed — otherwise
     * playback would re-render the grid continuously.</p>
     *
     * @param areaMembership   the new containment snapshot; may be {@code null}
     * @param areaNameResolver resolves an area fact key to its display name; may
     *                         be {@code null} to show raw keys
     */
    public void setAreaMembership(final FloorMapAreaMembership areaMembership,
                                  final Function<String, String> areaNameResolver) {
        final FloorMapAreaMembership next = areaMembership != null
                ? areaMembership
                : FloorMapAreaMembership.EMPTY;
        this.areaNameResolver = areaNameResolver;

        // Remember where each entity was last seen before overwriting, so a row
        // that loses its position can still say where it was.
        for (final String entityId : next.getEntityIds()) {
            lastKnownAreaKey.put(entityId, next.getInnermostAreaKey(entityId));
        }

        final boolean changed = !Objects.equals(this.areaMembership, next);
        this.areaMembership = next;
        if (changed) {
            dataGrid.redraw();
        }
    }

    /**
     * Replaces the entire grid data with the supplied list.
     *
     * <p>The current selection is <em>not</em> automatically adjusted —
     * callers should follow up with {@link #setSelected(String)}. Because
     * {@link EntityEntry} equality is id-based, restoring the same id does not
     * re-fire the selection consumer.</p>
     *
     * @param data the entities to display; must not be {@code null}
     */
    public void setData(final List<EntityEntry> data) {
        dataProvider.setList(data);
        dataGrid.setRowData(0, data);
    }

    /**
     * Discards the containment snapshot and the last-seen-in history — called
     * when the roster itself is reset (a (re-)opened document), so a new map
     * cannot inherit the previous one's areas.
     */
    public void clearAreaState() {
        areaMembership = FloorMapAreaMembership.EMPTY;
        lastKnownAreaKey.clear();
    }

    /**
     * Selects the grid row whose entity id matches the given value.
     *
     * <p>If {@code id} is {@code null} or no matching row is found, the
     * current selection is cleared.</p>
     *
     * @param id the entity id to look for; may be {@code null}
     */
    public void setSelected(final String id) {
        final List<EntityEntry> list = dataProvider.getList();
        if (list != null && id != null) {
            for (final EntityEntry entry : list) {
                if (id.equals(entry.getId())) {
                    selectionModel.setSelected(entry, true);
                    return;
                }
            }
        }
        selectionModel.clear();
    }

    /**
     * Returns the id of the currently selected entity, or {@code null} if
     * nothing is selected.
     */
    public String getSelectedId() {
        final EntityEntry selected = selectionModel.getSelectedObject();
        return selected != null
                ? selected.getId()
                : null;
    }

    /**
     * Registers a callback invoked whenever the tracked entity changes.
     *
     * <p>The consumer receives the newly selected {@link EntityEntry}, or
     * {@code null} when tracking stops. It is also re-invoked with the current
     * entry when the already-selected row is clicked again (re-centre /
     * resume-follow gesture).</p>
     *
     * @param selectionConsumer called on every selection change or re-click
     */
    public void setSelectionConsumer(final Consumer<EntityEntry> selectionConsumer) {
        this.selectionConsumer = selectionConsumer;
    }

    /**
     * View contract for the Tracking panel.
     *
     * <p>Implementations provide the layout that hosts the data grid and the
     * toolbar strip above it.</p>
     */
    public interface FloorMapTrackingView extends View {

        /**
         * Sets the data-grid widget into the main content area of the panel.
         *
         * @param gridWidget the data grid widget; must not be {@code null}
         */
        void setGridView(Widget gridWidget);

        /**
         * Sets the toolbar widget (containing the Stop Tracking button) into
         * the toolbar area above the grid.
         *
         * @param toolbarWidget the toolbar widget; must not be {@code null}
         */
        void setToolbar(Widget toolbarWidget);
    }
}
