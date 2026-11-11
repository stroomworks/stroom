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

package stroom.dependency.client.component;

import stroom.cell.client.ActionCell;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.button.client.ToolbarToggleButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.layout.client.SplitPane;
import stroom.widget.popup.client.PopupPosition;
import stroom.widget.quickfilter.client.QuickFilter;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.AriaUtil;
import stroom.widget.util.client.CssProperties;
import stroom.widget.util.client.MultiSelectionModelImpl;
import stroom.widget.util.client.SelectionType;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.DivElement;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.event.dom.client.ContextMenuEvent;
import com.google.gwt.event.dom.client.ContextMenuHandler;
import com.google.gwt.event.dom.client.DoubleClickHandler;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownHandler;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.event.dom.client.MouseMoveHandler;
import com.google.gwt.event.dom.client.MouseOutHandler;
import com.google.gwt.event.dom.client.MouseUpHandler;
import com.google.gwt.event.dom.client.MouseWheelHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/// The Structure view's canvas: a folder's dependency graph drawn as SVG, with a toolbar to fit it and
/// a box to find documents in it, a hover card, and a message for the loading, failed and empty states.
///
/// It draws what it is given ([#setSvg]) and changes it only by setting classes and attributes on the
/// elements drawn, which it finds by their `data-id`; [StructureGraphController] decides what to draw.
/// The pointer and keyboard events are its controller's to handle.
public class StructureGraph extends Component implements RequiresResize {

    private static final Binder BINDER = GWT.create(Binder.class);

    private static final String BLOCK = "structure-graph";
    private static final String NODE = BLOCK + "__node";
    private static final String EDGE = BLOCK + "__edge";
    private static final String NAME = BLOCK + "__name";
    private static final String META = BLOCK + "__meta";
    private static final String WORLD = BLOCK + "__world";
    // The attribute the grid marks each row with, holding its index on the page.
    private static final String DATA_ROW = "data-row";

    private static final String LABEL_SCALE_PROPERTY = "--structure-graph__label-scale";
    private static final String HOVER_LEFT_PROPERTY = "--structure-graph__hover-card-left";
    private static final String HOVER_TOP_PROPERTY = "--structure-graph__hover-card-top";

    @UiField
    HTMLPanel root;
    @UiField
    ToolbarButton fitButton;
    @UiField
    ToolbarToggleButton tableButton;
    @UiField
    DataGrid<StructureGraphRow> table;
    @UiField
    QuickFilter searchFilter;
    @UiField
    SplitPane split;
    @UiField
    FocusPanel canvas;
    @UiField
    DivElement canvasKeys;
    @UiField
    DivElement drawing;
    @UiField
    FlowPanel hoverCard;
    @UiField
    Label hoverTitle;
    @UiField
    FlowPanel hoverLines;
    @UiField
    Label message;
    @UiField
    DivElement statusRegion;

    // The elements drawn, found by data-id once per layout so later changes find them directly.
    private final Map<String, Element> nodes = new HashMap<>();
    // Each node's name and type lines, and what they last said, so a zoom rewrites only what changed.
    private final Map<String, Element[]> nodeTexts = new HashMap<>();
    private final Map<String, String[]> nodeTextValues = new HashMap<>();
    private final List<Element> edges = new ArrayList<>();
    private Element world;
    private Element focusedNode;
    private Runnable onResize = () -> {
    };
    private final MultiSelectionModelImpl<StructureGraphRow> tableSelection;
    private final Map<String, StructureGraphRow> rowsById = new HashMap<>();
    private String focusedRowId;
    private Consumer<String> onRowFocus = rowId -> {
    };
    private ActionMenuHandler onShowActionMenu = (id, popupPosition, opener) -> {
    };

    /// Makes an empty canvas.
    public StructureGraph() {
        initWidget(BINDER.createAndBindUi(this));
        // The canvas takes the focus, so it is named, and described by what its keys do (as
        // FloorMapCanvas names its map)
        final String keysId = AriaUtil.uniqueId("structure-graph-keys");
        canvasKeys.setId(keysId);
        canvas.getElement().setAttribute("role", "group");
        canvas.getElement().setAttribute("aria-label", "Graph of the folder's structure");
        canvas.getElement().setAttribute("aria-describedby", keysId);
        hoverCard.setVisible(false);
        message.setVisible(false);
        table.setKeyProvider(StructureGraphRow::id);
        table.setRowName(StructureGraphRow::name);
        tableSelection = table.addDefaultSelectionModel(false);
        addColumns();
        listenForRowFocus(table.getElement());
    }

    /// @param rows The table's rows, in order.
    public void setRows(final List<StructureGraphRow> rows) {
        rowsById.clear();
        for (final StructureGraphRow row : rows) {
            rowsById.put(row.id(), row);
        }
        focusedRowId = null;
        table.setRowData(0, rows);
        table.setRowCount(rows.size());
    }

    /// Selects a row of the table, as selecting its node does, without moving the focus.
    ///
    /// @param rowId The row's id, or null to clear the selection.
    public void selectRow(final String rowId) {
        final StructureGraphRow row = findRow(rowId);
        if (row == null) {
            tableSelection.clear(false);
        } else {
            tableSelection.setSelected(row, new SelectionType(), false);
        }
    }

    /// @param onRowSelect Given a row's id when it is selected, and whether it was double selected (a
    ///                    double click, or Enter); given null when the selection is cleared.
    public void setOnRowSelect(final BiConsumer<String, Boolean> onRowSelect) {
        tableSelection.addSelectionHandler(event -> {
            final StructureGraphRow row = tableSelection.getSelected();
            onRowSelect.accept(row == null
                            ? null
                            : row.id(),
                    event.getSelectionType().isDoubleSelect());
        });
    }

    /// @param onShowActionMenu Asked to show the actions for a row, when its Actions button is pressed.
    public void setOnShowActionMenu(final ActionMenuHandler onShowActionMenu) {
        this.onShowActionMenu = onShowActionMenu;
    }

    /// @param row A row.
    /// @return the name of its Actions button and of the menu it opens.
    public static String getActionMenuName(final StructureGraphRow row) {
        return "Actions for " + row.type() + " '" + row.name() + "'";
    }

    /// @param onRowFocus Given a row's id when the keyboard focus moves to it, and null when the focus
    ///                   leaves the table.
    public void setOnRowFocus(final Consumer<String> onRowFocus) {
        this.onRowFocus = onRowFocus;
    }

    /// @param shown True to show the table alone; false to show the graph above it.
    public void setTableShown(final boolean shown) {
        tableButton.setPressed(shown);
        split.setPaneHidden(canvas, shown);
    }

    /// @param onTableToggle Given true when the user asks to see the table, false for the graph.
    public void setOnTableToggle(final Consumer<Boolean> onTableToggle) {
        tableButton.setOnToggle(onTableToggle);
    }

    /// Draws a focus ring around the node whose row has the keyboard focus.
    ///
    /// @param id The node's id, or null for none.
    public void setFocusedNode(final String id) {
        if (focusedNode != null) {
            setModifier(focusedNode, NODE, "focused", false);
        }
        focusedNode = id == null
                ? null
                : nodes.get(id);
        if (focusedNode != null) {
            setModifier(focusedNode, NODE, "focused", true);
        }
    }

    /// Replaces the drawing with a new one, as [StructureGraphSvg] builds it.
    ///
    /// @param svg The SVG.
    public void setSvg(final SafeHtml svg) {
        drawing.setInnerSafeHtml(svg);
        nodes.clear();
        nodeTexts.clear();
        nodeTextValues.clear();
        edges.clear();
        world = null;
        focusedNode = null;
        final NodeList<Element> groups = drawing.getElementsByTagName("g");
        for (int i = 0; i < groups.getLength(); i++) {
            final Element group = groups.getItem(i);
            if (hasClass(group, NODE)) {
                final String id = group.getAttribute(StructureGraphSvg.DATA_ID);
                nodes.put(id, group);
                nodeTexts.put(id, textsOf(group));
            } else if (hasClass(group, WORLD)) {
                world = group;
            }
        }
        final NodeList<Element> paths = drawing.getElementsByTagName("path");
        for (int i = 0; i < paths.getLength(); i++) {
            final Element path = paths.getItem(i);
            if (path.hasAttribute(StructureGraphSvg.DATA_A)) {
                edges.add(path);
            }
        }
    }

    /// @param transform The SVG transform that pans and zooms the drawing, e.g. `translate(28,30) scale(0.5)`.
    public void setViewTransform(final String transform) {
        if (world != null) {
            world.setAttribute("transform", transform);
        }
    }

    /// @param labelScale How much to enlarge labels by, so they stay legible when zoomed out.
    public void setLabelScale(final double labelScale) {
        CssProperties.set(root.getElement(), LABEL_SCALE_PROPERTY, String.valueOf(labelScale));
    }

    /// @param namesVisible False to draw nodes without their names, when they are too small to read.
    /// @param metaVisible  False to draw nodes without their second line of text.
    public void setTextVisible(final boolean namesVisible, final boolean metaVisible) {
        setModifier(root.getElement(), BLOCK, "names-hidden", !namesVisible);
        setModifier(root.getElement(), BLOCK, "meta-hidden", !metaVisible);
    }

    /// @param id   A node's id.
    /// @param name Its name, as it fits at the current zoom.
    /// @param meta Its second line, as it fits at the current zoom.
    public void setNodeText(final String id, final String name, final String meta) {
        final Element[] texts = nodeTexts.get(id);
        if (texts == null) {
            return;
        }
        final String[] values = nodeTextValues.computeIfAbsent(id, k -> new String[2]);
        if (texts[0] != null && !name.equals(values[0])) {
            texts[0].setInnerText(name);
            values[0] = name;
        }
        if (texts[1] != null && !meta.equals(values[1])) {
            texts[1].setInnerText(meta);
            values[1] = meta;
        }
    }

    // A node's name and type line elements.
    private static Element[] textsOf(final Element node) {
        final Element[] texts = new Element[2];
        final NodeList<Element> elements = node.getElementsByTagName("text");
        for (int i = 0; i < elements.getLength(); i++) {
            final Element text = elements.getItem(i);
            if (hasClass(text, NAME)) {
                texts[0] = text;
            } else if (hasClass(text, META)) {
                texts[1] = text;
            }
        }
        return texts;
    }

    /// Highlights a selected node and its chain, and dims everything else.
    ///
    /// @param selectedId The selected node's id, or null for no selection.
    /// @param scope      The ids of the nodes in its chain, or null to dim nothing.
    public void setSelection(final String selectedId, final Set<String> scope) {
        for (final Map.Entry<String, Element> entry : nodes.entrySet()) {
            final String id = entry.getKey();
            setModifier(entry.getValue(), NODE, "selected", id.equals(selectedId));
            setModifier(entry.getValue(), NODE, "dimmed", scope != null && !scope.contains(id));
        }
        for (final Element edge : edges) {
            final boolean inScope = scope != null
                                    && scope.contains(edge.getAttribute(StructureGraphSvg.DATA_A))
                                    && scope.contains(edge.getAttribute(StructureGraphSvg.DATA_B));
            setModifier(edge, EDGE, "highlighted", inScope);
            setModifier(edge, EDGE, "dimmed", scope != null && !inScope);
        }
    }

    /// @param ids The ids of the nodes that match a search, to mark; empty for none.
    public void setMatches(final Set<String> ids) {
        for (final Map.Entry<String, Element> entry : nodes.entrySet()) {
            setModifier(entry.getValue(), NODE, "match", ids.contains(entry.getKey()));
        }
    }

    /// Shows the hover card beside a node.
    ///
    /// @param title What the node is.
    /// @param lines A line each of what else there is to say about it.
    /// @param left  Where the card's left edge goes, in pixels from the canvas's left.
    /// @param top   Where the card's top edge goes, in pixels from the canvas's top.
    public void showHoverCard(final String title, final List<String> lines, final double left, final double top) {
        hoverTitle.setText(title);
        hoverLines.clear();
        for (final String line : lines) {
            final Label label = new Label();
            label.setText(line);
            label.addStyleName("structure-graph__hover-line");
            hoverLines.add(label);
        }
        CssProperties.set(hoverCard.getElement(), HOVER_LEFT_PROPERTY, Math.max(0, Math.round(left)) + "px");
        CssProperties.set(hoverCard.getElement(), HOVER_TOP_PROPERTY, Math.max(0, Math.round(top)) + "px");
        hoverCard.setVisible(true);
    }

    /// Hides the hover card.
    public void hideHoverCard() {
        hoverCard.setVisible(false);
    }

    /// @return true if the hover card is shown.
    public boolean isHoverCardVisible() {
        return hoverCard.isVisible();
    }

    /// @param text A message to show over the canvas, e.g. that the graph is loading; null for none.
    public void setMessage(final String text) {
        message.setText(text == null
                ? ""
                : text);
        message.setVisible(text != null);
    }

    /// @param summary What the graph shows, in words, which names the drawing for a screen reader.
    public void setSummary(final String summary) {
        drawing.setAttribute("aria-label", summary);
    }

    /// Says something to a screen reader, politely.
    ///
    /// @param text What to say.
    public void announce(final String text) {
        statusRegion.setInnerText(text);
    }

    /// @param dragging True while the user is dragging the graph, so the pointer shows it.
    public void setDragging(final boolean dragging) {
        setModifier(root.getElement(), BLOCK, "dragging", dragging);
    }

    /// @param target Where a pointer event happened, or null.
    /// @return true if it is on the canvas, rather than e.g. the table below it.
    public boolean isInCanvas(final EventTarget target) {
        return Element.is(target) && canvas.getElement().isOrHasChild(Element.as(target));
    }

    /// @param target Where a pointer event happened, or null.
    /// @return true if it is on the hover card.
    public boolean isInHoverCard(final EventTarget target) {
        return hoverCard.isVisible()
               && Element.is(target)
               && hoverCard.getElement().isOrHasChild(Element.as(target));
    }

    /// @return the canvas's width, in pixels; 0 before it is shown.
    public int getCanvasWidth() {
        return canvas.getElement().getClientWidth();
    }

    /// @return the canvas's height, in pixels; 0 before it is shown.
    public int getCanvasHeight() {
        return canvas.getElement().getClientHeight();
    }

    /// @param target Where a pointer event happened.
    /// @return what in the graph is there: a control, a node, or nothing.
    public StructureGraphHit hitAt(final EventTarget target) {
        if (!Element.is(target)) {
            return StructureGraphHit.NONE;
        }
        Element element = Element.as(target);
        String action = null;
        String lane = null;
        while (element != null && element != drawing) {
            if (action == null && element.hasAttribute(StructureGraphSvg.DATA_ACTION)) {
                action = element.getAttribute(StructureGraphSvg.DATA_ACTION);
                if (element.hasAttribute(StructureGraphSvg.DATA_LANE)) {
                    lane = element.getAttribute(StructureGraphSvg.DATA_LANE);
                }
            }
            if (element.hasAttribute(StructureGraphSvg.DATA_ID)) {
                return new StructureGraphHit(element.getAttribute(StructureGraphSvg.DATA_ID), action, lane);
            }
            element = element.getParentElement();
        }
        return action == null
                ? StructureGraphHit.NONE
                : new StructureGraphHit(null, action, lane);
    }

    /// @param onFit Run when Fit is pressed.
    public void setOnFit(final Runnable onFit) {
        fitButton.setOnClick(onFit);
    }

    /// @param onSearch Given the search text as it changes.
    public void setOnSearch(final Consumer<String> onSearch) {
        searchFilter.setOnChange(onSearch);
    }

    /// @param onSearchEnter Run when Enter is pressed in the search box.
    public void setOnSearchEnter(final Runnable onSearchEnter) {
        searchFilter.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                onSearchEnter.run();
            }
        });
    }

    /// @param onResize Run when the canvas changes size.
    public void setOnResize(final Runnable onResize) {
        this.onResize = onResize;
    }

    @Override
    public void onResize() {
        onResize.run();
    }

    /// @param handler Given each mouse press on the canvas.
    /// @return its registration.
    public HandlerRegistration addMouseDownHandler(final MouseDownHandler handler) {
        return canvas.addMouseDownHandler(handler);
    }

    /// @param handler Given each mouse movement over the canvas.
    /// @return its registration.
    public HandlerRegistration addMouseMoveHandler(final MouseMoveHandler handler) {
        return canvas.addMouseMoveHandler(handler);
    }

    /// @param handler Given each mouse release over the canvas.
    /// @return its registration.
    public HandlerRegistration addMouseUpHandler(final MouseUpHandler handler) {
        return canvas.addMouseUpHandler(handler);
    }

    /// @param handler Given each time the mouse leaves an element of the canvas.
    /// @return its registration.
    public HandlerRegistration addMouseOutHandler(final MouseOutHandler handler) {
        return canvas.addMouseOutHandler(handler);
    }

    /// @param handler Given each mouse wheel movement over the canvas.
    /// @return its registration.
    public HandlerRegistration addMouseWheelHandler(final MouseWheelHandler handler) {
        return canvas.addMouseWheelHandler(handler);
    }

    /// @param handler Given each double click on the canvas.
    /// @return its registration.
    public HandlerRegistration addDoubleClickHandler(final DoubleClickHandler handler) {
        return canvas.addDoubleClickHandler(handler);
    }

    /// @param handler Given each request for a context menu on the canvas, e.g. a right click.
    /// @return its registration.
    public HandlerRegistration addContextMenuHandler(final ContextMenuHandler handler) {
        return canvas.addDomHandler(handler, ContextMenuEvent.getType());
    }

    /// @param handler Given each key pressed while the canvas has the focus.
    /// @return its registration.
    public HandlerRegistration addKeyDownHandler(final KeyDownHandler handler) {
        return canvas.addKeyDownHandler(handler);
    }

    private void addColumns() {
        // Each row's actions, as a button the keyboard reaches, named for its row
        final ActionCell<StructureGraphRow> actionCell = new ActionCell<>(this::showActionMenu,
                StructureGraph::getActionMenuName);
        final Column<StructureGraphRow, StructureGraphRow> actionColumn = DataGridUtil.columnBuilder(
                (StructureGraphRow row) -> row,
                () -> actionCell).build();
        table.addColumn(actionColumn, DataGrid.hiddenHeader("Actions"), 30);
        addTextColumn("Name", StructureGraphRow::name, 220);
        addTextColumn("Type", StructureGraphRow::type, 110);
        addTextColumn("Lane", StructureGraphRow::laneName, 200);
        addTextColumn("Upstream", StructureGraphRow::upstream, 200);
        addTextColumn("Downstream", StructureGraphRow::downstream, 200);
        addTextColumn("References", StructureGraphRow::references, 200);
        addTextColumn("Referenced By", StructureGraphRow::referencedBy, 200);
        addTextColumn("Notes", StructureGraphRow::notes, 300);
    }

    private void addTextColumn(final String name,
                               final Function<StructureGraphRow, String> value,
                               final int width) {
        final Column<StructureGraphRow, String> column = DataGridUtil.textColumnBuilder(value).build();
        table.addResizableColumn(column, name, width);
    }

    private void showActionMenu(final StructureGraphRow row, final NativeEvent event) {
        final PopupPosition popupPosition = new PopupPosition(event.getClientX() + 10, event.getClientY());
        final Element opener = Element.is(event.getEventTarget())
                ? Element.as(event.getEventTarget())
                : null;
        onShowActionMenu.show(row.id(), popupPosition, opener);
    }

    private StructureGraphRow findRow(final String rowId) {
        return rowId == null
                ? null
                : rowsById.get(rowId);
    }

    // The grid's keyboard model moves the focus between rows itself, without an event of its own, so
    // the row is found from where the focus lands.
    private void onFocusIn(final Element target) {
        Element element = target;
        while (element != null && element != table.getElement()) {
            if (element.hasAttribute(DATA_ROW)) {
                final int index = parseIndex(element.getAttribute(DATA_ROW));
                final StructureGraphRow row = index >= 0 && index < table.getVisibleItemCount()
                        ? table.getVisibleItem(index)
                        : null;
                if (row != null && !Objects.equals(row.id(), focusedRowId)) {
                    focusedRowId = row.id();
                    onRowFocus.accept(row.id());
                }
                return;
            }
            element = element.getParentElement();
        }
    }

    // The focus left the table altogether (not just moved between its cells), so no row has it.
    private void onFocusOut(final Element relatedTarget) {
        if ((relatedTarget == null || !table.getElement().isOrHasChild(relatedTarget)) && focusedRowId != null) {
            focusedRowId = null;
            onRowFocus.accept(null);
        }
    }

    private static int parseIndex(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private native void listenForRowFocus(Element container) /*-{
        var self = this;
        container.addEventListener('focusin', function (event) {
            self.@stroom.dependency.client.component.StructureGraph::onFocusIn(*)(event.target);
        });
        container.addEventListener('focusout', function (event) {
            var related = event.relatedTarget instanceof Element ? event.relatedTarget : null;
            self.@stroom.dependency.client.component.StructureGraph::onFocusOut(*)(related);
        });
    }-*/;

    // SVG elements' className is an SVGAnimatedString, not a string, so classes are read and written
    // through the class attribute.
    private static boolean hasClass(final Element element, final String className) {
        final String classes = " " + element.getAttribute("class") + " ";
        return classes.contains(" " + className + " ");
    }

    private static void setModifier(final Element element,
                                    final String block,
                                    final String modifier,
                                    final boolean on) {
        final String className = block + "--" + modifier;
        final boolean has = hasClass(element, className);
        if (on && !has) {
            element.setAttribute("class", (element.getAttribute("class") + " " + className).trim());
        } else if (!on && has) {
            final String classes = (" " + element.getAttribute("class") + " ")
                    .replace(" " + className + " ", " ")
                    .trim();
            element.setAttribute("class", classes);
        }
    }

    interface Binder extends UiBinder<Widget, StructureGraph> {

    }

    /// Shows the actions for a document.
    public interface ActionMenuHandler {

        /// @param id            The id of the row or node whose actions to show.
        /// @param popupPosition Where to show the menu.
        /// @param opener        The button pressed, or null, to give the focus back to.
        void show(String id, PopupPosition popupPosition, Element opener);
    }
}
