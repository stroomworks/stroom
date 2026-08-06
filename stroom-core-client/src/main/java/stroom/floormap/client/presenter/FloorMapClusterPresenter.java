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
import stroom.data.grid.client.PagerView;
import stroom.floormap.client.FloorMapCellHtml;
import stroom.floormap.shared.FloorMapAreaCellText;
import stroom.floormap.shared.FloorMapAreaMembership;
import stroom.floormap.shared.FloorMapCluster;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MouseUtil;

import com.google.gwt.cell.client.SafeHtmlCell;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.TextColumn;
import com.google.gwt.view.client.CellPreviewEvent;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Lists the members of one cluster, so entities merged into a summary glyph stay
 * reachable.
 *
 * <p>This is the part that actually answers the original problem. Ten users at one
 * desk were not merely invisible, they were <em>unreachable</em>: nothing on the
 * canvas could be clicked to get at the nine underneath. A count on the glyph says
 * how many there are; the hover tooltip names them; this dialog is where one can be
 * picked out and followed.</p>
 *
 * <p>Choosing a row tracks that entity — the same path the Tracking panel uses — and
 * closes the dialog, because the thing the user then wants to look at is the map.</p>
 *
 * <p>The <strong>Area</strong> column has the single meaning it has everywhere else:
 * <em>which area is this member inside?</em> It carries no "last seen in" fallback,
 * unlike the Tracking panel's version — this dialog describes one drawn frame and
 * holds no history. Every member of a cluster has a position by construction, so
 * that fallback would have nothing to say.</p>
 */
public class FloorMapClusterPresenter extends MyPresenterWidget<PagerView> {

    /** Column text for a member that is not inside any area. */
    private static final String NO_AREA = "—";

    /** Room for a dozen or so rows before the grid pages, over the map. */
    private static final PopupSize POPUP_SIZE = PopupSize.resizable(500, 500);

    private final MyDataGrid<ClusterMember> dataGrid;
    private final ListDataProvider<ClusterMember> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<ClusterMember> selectionModel = new SingleSelectionModel<>();
    private final ButtonView trackButton;

    /** Called with a member id when the user picks one; set per {@link #show}. */
    private Consumer<String> onTrack;

    @Inject
    public FloorMapClusterPresenter(final EventBus eventBus,
                                    final PagerView view) {
        super(eventBus, view);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setSelectionModel(selectionModel);
        view.setDataWidget(dataGrid);
        dataProvider.addDataDisplay(dataGrid);

        trackButton = view.addButton(SvgPresets.enabled(SvgImage.LOCATE, "Track this entity"));
        trackButton.setEnabled(false);

        initGridColumns();
    }

    @Override
    protected void onBind() {
        super.onBind();

        //noinspection unused e
        registerHandler(selectionModel.addSelectionChangeHandler(e ->
                trackButton.setEnabled(selectionModel.getSelectedObject() != null)));

        registerHandler(trackButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                trackSelected();
            }
        }));

        // Double-click is the shortcut for the button, so a member can be
        // followed without a second aim at the toolbar.
        registerHandler(dataGrid.addCellPreviewHandler((final CellPreviewEvent<ClusterMember> e) -> {
            if ("dblclick".equals(e.getNativeEvent().getType())) {
                selectionModel.setSelected(e.getValue(), true);
                trackSelected();
            }
        }));
    }

    /**
     * Shows the member list for a cluster.
     *
     * @param cluster      the clicked cluster
     * @param nameResolver resolves a member id to its display name, so a name here
     *                     matches the name in every grid; may be {@code null}
     * @param membership   the area-containment snapshot backing the Area column;
     *                     may be {@code null}
     * @param typeOf       resolves a member id to its entity type; may be
     *                     {@code null}, in which case the cluster's own type is
     *                     used for every row (they are all of one type anyway)
     * @param onTrack      called with the chosen member's id
     */
    public void show(final FloorMapCluster cluster,
                     final Function<String, String> nameResolver,
                     final FloorMapAreaMembership membership,
                     final Function<String, String> typeOf,
                     final Consumer<String> onTrack) {
        this.onTrack = onTrack;

        final FloorMapAreaMembership areas = membership != null
                ? membership
                : FloorMapAreaMembership.EMPTY;

        final List<ClusterMember> members = new ArrayList<>(cluster.size());
        for (final String memberId : cluster.getMemberIds()) {
            final String name = nameResolver != null
                    ? nameResolver.apply(memberId)
                    : null;
            final String type = typeOf != null
                    ? typeOf.apply(memberId)
                    : null;
            members.add(new ClusterMember(
                    memberId,
                    name != null && !name.isEmpty()
                            ? name
                            : memberId,
                    type != null && !type.isEmpty()
                            ? type
                            : cluster.getType(),
                    areaNamesFor(memberId, areas, nameResolver)));
        }
        dataProvider.setList(members);
        selectionModel.clear();
        trackButton.setEnabled(false);

        ShowPopupEvent.builder(this)
                .popupType(PopupType.CLOSE_DIALOG)
                .popupSize(POPUP_SIZE)
                // Says what the dialog is a list OF, not just a count.
                .caption(cluster.getLabel() + " in this cluster")
                .fire();
    }

    /** Tracks the selected member and closes, since the map is what to look at next. */
    private void trackSelected() {
        final ClusterMember selected = selectionModel.getSelectedObject();
        if (selected != null && onTrack != null) {
            onTrack.accept(selected.getId());
            HidePopupRequestEvent.builder(this).fire();
        }
    }

    /**
     * The names of every area containing a member, innermost (most specific)
     * first — the same order and the same joining the Tracking panel's Area column
     * uses, so the two never read differently for one entity.
     */
    private static List<String> areaNamesFor(final String memberId,
                                             final FloorMapAreaMembership membership,
                                             final Function<String, String> nameResolver) {
        final List<String> keys = membership.getAreaKeys(memberId);
        final List<String> names = new ArrayList<>(keys.size());
        for (final String key : keys) {
            final String name = nameResolver != null
                    ? nameResolver.apply(key)
                    : null;
            names.add(name != null && !name.isEmpty()
                    ? name
                    : key);
        }
        return names;
    }

    private void initGridColumns() {
        final Column<ClusterMember, String> nameColumn = new TextColumn<>() {
            @Override
            public String getValue(final ClusterMember member) {
                return member.getName();
            }
        };
        dataGrid.addResizableColumn(nameColumn, "Name", 200);

        final Column<ClusterMember, String> typeColumn = new TextColumn<>() {
            @Override
            public String getValue(final ClusterMember member) {
                return member.getType();
            }
        };
        dataGrid.addColumn(typeColumn, "Type");

        // Which area is this member inside? Named innermost first, or a dash.
        // Resizable because area names are user-chosen and can be long.
        final Column<ClusterMember, SafeHtml> areaColumn =
                new Column<>(new SafeHtmlCell()) {
                    @Override
                    public SafeHtml getValue(final ClusterMember member) {
                        return areaCell(member);
                    }
                };
        dataGrid.addResizableColumn(areaColumn, "Area", 200);

        final Column<ClusterMember, String> idColumn = new TextColumn<>() {
            @Override
            public String getValue(final ClusterMember member) {
                return member.getId();
            }
        };
        dataGrid.addColumn(idColumn, "Id");
    }

    /**
     * Renders one Area cell. The cell is a single {@code nowrap} line that
     * ellipsises, so where a member is in several areas the tooltip repeats the
     * list in full, one per line.
     */
    private static SafeHtml areaCell(final ClusterMember member) {
        final List<String> areaNames = member.getAreaNames();
        if (areaNames.isEmpty()) {
            return FloorMapCellHtml.cell(NO_AREA, "Not in a known area at this time");
        }
        final String joined = FloorMapAreaCellText.joinNames(areaNames);
        if (areaNames.size() == 1) {
            return FloorMapCellHtml.cell(joined, "Inside " + joined);
        }
        final StringBuilder tooltip = new StringBuilder("Inside ")
                .append(areaNames.size())
                .append(" areas (innermost first):");
        for (final String name : areaNames) {
            tooltip.append("\n• ").append(name);
        }
        return FloorMapCellHtml.cell(joined, tooltip.toString());
    }

    /**
     * One row: a member of the cluster, with everything the grid shows resolved up
     * front so the columns do no lookups.
     *
     * <p>A plain class rather than a record, matching the rest of the
     * GWT-compiled source.</p>
     */
    public static final class ClusterMember {

        private final String id;
        private final String name;
        private final String type;
        private final List<String> areaNames;

        ClusterMember(final String id,
                      final String name,
                      final String type,
                      final List<String> areaNames) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.areaNames = areaNames;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        /** Containing area names, innermost first; empty when in none. */
        public List<String> getAreaNames() {
            return areaNames;
        }
    }
}
