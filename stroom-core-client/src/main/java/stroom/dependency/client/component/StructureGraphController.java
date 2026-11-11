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

import stroom.dependency.client.layout.Dims;
import stroom.dependency.client.layout.GraphViewport;
import stroom.dependency.client.layout.GraphViewport.FitMode;
import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.InstanceKind;
import stroom.dependency.client.layout.LayoutResult;
import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.DependencyGraphRequest;
import stroom.dependency.shared.DependencyGraphResource;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.explorer.client.event.LocateDocEvent;
import stroom.importexport.client.event.ShowDocRefDependenciesEvent;
import stroom.importexport.client.event.ShowDocRefDependenciesEvent.DependencyType;
import stroom.svg.shared.SvgImage;
import stroom.widget.component.client.Controller;
import stroom.widget.menu.client.Item;
import stroom.widget.menu.client.MenuBuilder;
import stroom.widget.menu.client.ShowMenuEvent;
import stroom.widget.popup.client.PopupPosition;
import stroom.widget.util.client.AriaUtil;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Shows a folder's dependency graph in a [StructureGraph]: fetches it, lays it out, and turns the
/// user's pointer, wheel and keys into pans, zooms, selections, collapses and expansions.
///
/// The decisions are [StructureGraphState]'s; this wires the canvas to them and draws the result. A new
/// layout (new data, a collapse, a pin, a detail threshold crossed) draws the SVG again; anything else
/// changes the drawing in place.
public class StructureGraphController extends Controller {

    private static final DependencyGraphResource RESOURCE = GWT.create(DependencyGraphResource.class);

    // A press that moves further than this, in pixels, is a drag rather than a click.
    private static final int CLICK_SLOP = 4;
    // How far each arrow key pans, in pixels.
    private static final double KEY_PAN = 40;
    // How far each key press zooms.
    private static final double KEY_ZOOM_FACTOR = 1.25;
    private static final int KEY_EQUALS = 187;
    private static final int KEY_EQUALS_FIREFOX = 61;
    private static final int KEY_DASH = 189;
    private static final int KEY_DASH_FIREFOX = 173;
    // Where the hover card sits from the node it describes, in pixels.
    private static final int HOVER_CARD_GAP = 12;

    private final StructureGraph view;
    private final RestFactory restFactory;
    private final StructureGraphState state = new StructureGraphState();
    private final String markerId = AriaUtil.uniqueId("structure-graph-arrow");

    private FitMode pendingFit;
    private double textZoom = Double.NaN;
    private String hoverId;
    // The table row with the keyboard focus, whose node the canvas rings.
    private String focusedRowId;

    private boolean pressed;
    private boolean dragged;
    private int pressX;
    private int pressY;
    private int lastX;
    private int lastY;
    private StructureGraphHit pressHit = StructureGraphHit.NONE;

    /// Made by GIN.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The canvas.
    /// @param restFactory Fetches the graph.
    @Inject
    public StructureGraphController(final EventBus eventBus,
                                    final StructureGraph view,
                                    final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        initWidget(view);
        view.setOnFit(() -> fit(FitMode.ALL));
        view.setOnSearch(this::search);
        view.setOnSearchEnter(this::selectFirstMatch);
        view.setOnResize(this::onResize);
        view.setOnTableToggle(view::setTableShown);
        view.setOnRowFocus(this::focusRow);
        view.setOnRowSelect(this::selectRow);
        view.setOnShowActionMenu((rowId, popupPosition, opener) -> {
            final StructureGraphRow row = state.row(rowId);
            if (row != null) {
                showActionMenu(row.name(), row.type(), row.docRef(), state.canvasIdFor(rowId), popupPosition, opener);
            }
        });
        bindPointer();
        bindContextMenu();
        bindKeys();
        draw();
    }

    /// Fetches and shows a folder's graph.
    ///
    /// @param folder The folder.
    public void read(final DocRef folder) {
        view.setMessage("Loading the folder's structure…");
        restFactory
                .create(RESOURCE)
                .method(resource -> resource.fetchGraph(new DependencyGraphRequest(folder, null, null)))
                .onSuccess(this::show)
                .onFailure(error -> {
                    // Said in the tab rather than as an alert: the tab is a folder's default, so an alert
                    // would interrupt every opening of a folder while the server is unwell.
                    view.setMessage("The folder's structure could not be loaded: " + error.getMessage());
                    view.announce("The folder's structure could not be loaded");
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    /// Shows a graph, fitted to the canvas so its first lanes are readable.
    ///
    /// @param graph The graph.
    public void show(final DependencyGraph graph) {
        state.setGraph(graph);
        view.setMessage(state.note());
        hoverId = null;
        focusedRowId = null;
        view.hideHoverCard();
        view.setRows(state.getRows());
        draw();
        pendingFit = FitMode.READ;
        // The canvas has no size until it is attached and laid out.
        Scheduler.get().scheduleDeferred(() -> {
            if (!isDisposed() && pendingFit != null) {
                fit(pendingFit);
            }
        });
    }

    private void bindPointer() {
        own(view.addMouseDownHandler(event -> {
            if (event.getNativeButton() != NativeEvent.BUTTON_LEFT) {
                return;
            }
            hideHoverCard();
            pressed = true;
            dragged = false;
            pressX = event.getX();
            pressY = event.getY();
            lastX = pressX;
            lastY = pressY;
            // No preventDefault: the press must still move the focus to the canvas, or its keys (Escape,
            // + and -) would go elsewhere. The drawing's CSS stops a drag selecting its text.
            pressHit = view.hitAt(event.getNativeEvent().getEventTarget());
        }));
        own(view.addMouseMoveHandler(event -> {
            final int x = event.getX();
            final int y = event.getY();
            if (pressed) {
                if (!dragged && Math.abs(x - pressX) + Math.abs(y - pressY) > CLICK_SLOP) {
                    dragged = true;
                    view.setDragging(true);
                }
                if (dragged) {
                    state.getViewport().pan(x - lastX, y - lastY);
                    applyViewport();
                }
                lastX = x;
                lastY = y;
            } else if (!view.isInHoverCard(event.getNativeEvent().getEventTarget())) {
                // Over the card itself it stays, so it can be read and its text selected
                hover(view.hitAt(event.getNativeEvent().getEventTarget()).id());
            }
        }));
        own(view.addMouseUpHandler(event -> {
            if (pressed && !dragged) {
                click(pressHit);
            }
            endPress();
        }));
        own(view.addMouseOutHandler(event -> {
            // Moving between the canvas's own elements fires this too; only leaving the canvas counts.
            if (!view.isInCanvas(event.getRelatedTarget())) {
                hideHoverCard();
                endPress();
            }
        }));
        own(view.addMouseWheelHandler(event -> {
            event.preventDefault();
            hideHoverCard();
            if (state.zoomByWheel(event.getX(), event.getY(), event.getNativeDeltaY())) {
                draw();
            } else {
                applyViewport();
            }
        }));
        own(view.addDoubleClickHandler(event -> {
            final Instance instance = state.instance(view.hitAt(event.getNativeEvent().getEventTarget()).id());
            if (instance != null && instance.getNode() != null) {
                open(instance.getNode().getDocRef());
            }
        }));
    }

    private void bindContextMenu() {
        own(view.addContextMenuHandler(event -> {
            final Instance instance = state.instance(view.hitAt(event.getNativeEvent().getEventTarget()).id());
            if (instance == null || instance.getNode() == null || !isDocument(instance.getNode().getDocRef())) {
                // The browser's own menu for the background and summaries, which act on no document
                return;
            }
            event.preventDefault();
            event.stopPropagation();
            hideHoverCard();
            showActionMenu(instance.getName(),
                    StructureGraphTypes.label(instance.getType()),
                    instance.getNode().getDocRef(),
                    instance.getId(),
                    new PopupPosition(event.getNativeEvent().getClientX(), event.getNativeEvent().getClientY()),
                    null);
        }));
    }

    // The document's actions, and what can be done to how it is drawn: the only way to expand a pipeline
    // or collapse a lane from the keyboard, which works in the table.
    private void showActionMenu(final String name,
                                final String type,
                                final DocRef docRef,
                                final String canvasId,
                                final PopupPosition popupPosition,
                                final Element opener) {
        if (!isDocument(docRef)) {
            return;
        }
        final List<Item> items = new ArrayList<>(buildActionMenu(docRef));
        items.addAll(buildViewMenu(canvasId));
        ShowMenuEvent.builder()
                .name("Actions for " + type + " '" + name + "'")
                .items(items)
                .popupPosition(popupPosition)
                .opener(opener)
                .fire(this);
    }

    private List<Item> buildActionMenu(final DocRef docRef) {
        return MenuBuilder.builder()
                .withIconMenuItem(itemBuilder -> itemBuilder
                        .icon(SvgImage.OPEN)
                        .text("Open")
                        .command(() -> open(docRef)))
                .withIconMenuItem(itemBuilder -> itemBuilder
                        .icon(SvgImage.LOCATE)
                        .text("Locate in Explorer")
                        .command(() -> LocateDocEvent.fire(this, docRef)))
                .withIconMenuItem(itemBuilder -> itemBuilder
                        .icon(SvgImage.DEPENDENCIES)
                        .text("Show Dependencies")
                        .command(() -> ShowDocRefDependenciesEvent.fire(this, docRef, DependencyType.DEPENDENCY)))
                .withIconMenuItem(itemBuilder -> itemBuilder
                        .icon(SvgImage.DEPENDENCIES)
                        .text("Show Dependants")
                        .command(() -> ShowDocRefDependenciesEvent.fire(this, docRef, DependencyType.DEPENDANT)))
                .build();
    }

    private List<Item> buildViewMenu(final String canvasId) {
        final Instance instance = state.instance(canvasId);
        final MenuBuilder menu = MenuBuilder.builder();
        if (instance == null) {
            return menu.build();
        }
        if (instance.getKind() == InstanceKind.LANE_SUMMARY || instance.getKind() == InstanceKind.REFERENCE_SUMMARY) {
            // Folded into a summary: open it, as clicking the summary does
            menu.withIconMenuItem(itemBuilder -> itemBuilder
                    .icon(SvgImage.EXPAND_DOWN)
                    .text(instance.getKind() == InstanceKind.LANE_SUMMARY
                            ? "Show Its Lane"
                            : "Show Its Lane's References")
                    .command(() -> {
                        if (state.openSummary(canvasId)) {
                            draw();
                        }
                    }));
            return menu.build();
        }
        if (instance.isExpandable()) {
            final boolean expanded = state.getLayout().getExpanded().contains(canvasId);
            menu.withIconMenuItem(itemBuilder -> itemBuilder
                    .icon(expanded
                            ? SvgImage.COLLAPSE_UP
                            : SvgImage.EXPAND_DOWN)
                    .text(expanded
                            ? "Hide Pipeline Elements"
                            : "Show Pipeline Elements")
                    .command(() -> {
                        state.toggleExpanded(canvasId);
                        draw();
                    }));
        }
        menu.withIconMenuItem(itemBuilder -> itemBuilder
                .icon(SvgImage.COLLAPSE_UP)
                .text("Collapse Lane")
                .command(() -> {
                    state.collapseLane(instance.getLane());
                    draw();
                    view.announce("Lane collapsed");
                }));
        return menu.build();
    }

    // Whether a reference is to a Stroom document, which can be opened and located; an endpoint outside
    // Stroom is drawn, but is not one.
    private static boolean isDocument(final DocRef docRef) {
        return docRef != null && !Instance.ENDPOINT_TYPE.equals(docRef.getType());
    }

    private void bindKeys() {
        own(view.addKeyDownHandler(event -> {
            final int key = event.getNativeKeyCode();
            if (key == KeyCodes.KEY_ESCAPE) {
                if (view.isHoverCardVisible()) {
                    hideHoverCard();
                } else if (state.getSelectedId() != null) {
                    select(null);
                }
            } else if (key == KeyCodes.KEY_LEFT || key == KeyCodes.KEY_RIGHT
                       || key == KeyCodes.KEY_UP || key == KeyCodes.KEY_DOWN) {
                event.preventDefault();
                hideHoverCard();
                panByKey(key);
            } else if (isZoomIn(key)) {
                event.preventDefault();
                zoomAboutCentre(KEY_ZOOM_FACTOR);
            } else if (isZoomOut(key)) {
                event.preventDefault();
                zoomAboutCentre(1 / KEY_ZOOM_FACTOR);
            }
        }));
    }

    private void click(final StructureGraphHit hit) {
        if (StructureGraphSvg.ACTION_EXPAND.equals(hit.action())) {
            state.toggleExpanded(hit.id());
            draw();
        } else if (StructureGraphSvg.ACTION_COLLAPSE_LANE.equals(hit.action()) && hit.lane() != null) {
            state.collapseLane(Integer.parseInt(hit.lane()));
            draw();
            view.announce("Lane collapsed");
        } else if (StructureGraphSvg.ACTION_OPEN_SUMMARY.equals(hit.action())) {
            if (state.openSummary(hit.id())) {
                draw();
                view.announce("Opened");
            }
        } else if (hit.id() != null) {
            select(hit.id());
            final Instance selected = state.instance(state.getSelectedId());
            if (selected != null) {
                view.announce(selected.getName() + " selected");
            }
        } else if (state.getSelectedId() != null) {
            select(null);
        }
    }

    // Selects a node on the canvas, and its row in the table.
    private void select(final String id) {
        state.setSelectedId(id);
        applySelection();
        view.selectRow(state.rowIdFor(state.getSelectedId()));
    }

    // The keyboard focus moved to a row: ring its node, or the summary it is folded into, and bring it
    // into view.
    private void focusRow(final String rowId) {
        // Null when the focus has left the table, so the ring goes with it
        focusedRowId = rowId;
        final String canvasId = state.canvasIdFor(rowId);
        view.setFocusedNode(canvasId);
        bringIntoView(canvasId);
    }

    private void selectRow(final String rowId, final boolean open) {
        if (open) {
            final StructureGraphRow row = state.row(rowId);
            if (row != null) {
                open(row.docRef());
            }
            return;
        }
        // A row whose node is folded into a summary selects nothing on the canvas: the summary is not
        // the document.
        final String canvasId = state.canvasIdFor(rowId);
        state.setSelectedId(rowId != null && rowId.equals(canvasId)
                ? rowId
                : null);
        applySelection();
    }

    private void open(final DocRef docRef) {
        if (isDocument(docRef)) {
            OpenDocumentEvent.fire(this, docRef, true);
        }
    }

    // Pans so a node is on the canvas, if it is not already.
    private void bringIntoView(final String canvasId) {
        final Instance instance = state.instance(canvasId);
        final int width = view.getCanvasWidth();
        final int height = view.getCanvasHeight();
        if (instance == null || instance.getX() == null || width <= 0 || height <= 0) {
            return;
        }
        final Dims dims = state.getLayout().dims(instance);
        final GraphViewport viewport = state.getViewport();
        final double[] topLeft = viewport.graphToScreen(instance.getX(), instance.getY());
        final double[] bottomRight = viewport.graphToScreen(instance.getX() + dims.w(), instance.getY() + dims.h());
        if (topLeft[0] < 0 || topLeft[1] < 0 || bottomRight[0] > width || bottomRight[1] > height) {
            viewport.centreOn(instance.getX() + dims.w() / 2.0, instance.getY() + dims.h() / 2.0, width, height);
            applyViewport();
        }
    }

    private void hover(final String id) {
        if (id == null) {
            hideHoverCard();
            return;
        }
        if (id.equals(hoverId)) {
            return;
        }
        final Instance instance = state.instance(id);
        if (instance == null || instance.getX() == null) {
            hideHoverCard();
            return;
        }
        hoverId = id;
        final LayoutResult layout = state.getLayout();
        final Dims dims = layout.dims(instance);
        final double[] topRight = state.getViewport().graphToScreen(instance.getX() + dims.w(), instance.getY());
        view.showHoverCard(instance.getName(),
                state.describe(instance),
                topRight[0] + HOVER_CARD_GAP,
                topRight[1]);
    }

    private void hideHoverCard() {
        hoverId = null;
        view.hideHoverCard();
    }

    private void search(final String text) {
        final Set<String> matches = state.search(text);
        view.setMatches(matches);
        if (text == null || text.trim().isEmpty()) {
            view.announce("");
        } else if (matches.isEmpty()) {
            view.announce("No documents match");
        } else {
            view.announce(matches.size() + (matches.size() == 1
                    ? " document matches"
                    : " documents match"));
        }
    }

    private void selectFirstMatch() {
        if (state.getMatches().isEmpty()) {
            return;
        }
        final String first = state.getMatches().iterator().next();
        final Instance instance = state.instance(first);
        if (instance != null && instance.getX() != null) {
            final Dims dims = state.getLayout().dims(instance);
            state.getViewport().centreOn(instance.getX() + dims.w() / 2.0,
                    instance.getY() + dims.h() / 2.0,
                    view.getCanvasWidth(),
                    view.getCanvasHeight());
            applyViewport();
        }
        select(first);
    }

    private void fit(final FitMode mode) {
        final int width = view.getCanvasWidth();
        final int height = view.getCanvasHeight();
        if (width <= 0 || height <= 0) {
            // Not laid out yet: fit when it has a size.
            pendingFit = mode;
            return;
        }
        pendingFit = null;
        hideHoverCard();
        if (state.fit(width, height, mode)) {
            draw();
        } else {
            applyViewport();
        }
    }

    private void onResize() {
        if (pendingFit != null) {
            fit(pendingFit);
        }
    }

    private void panByKey(final int key) {
        final double dx;
        final double dy;
        if (key == KeyCodes.KEY_LEFT) {
            dx = KEY_PAN;
            dy = 0;
        } else if (key == KeyCodes.KEY_RIGHT) {
            dx = -KEY_PAN;
            dy = 0;
        } else if (key == KeyCodes.KEY_UP) {
            dx = 0;
            dy = KEY_PAN;
        } else {
            dx = 0;
            dy = -KEY_PAN;
        }
        state.getViewport().pan(dx, dy);
        applyViewport();
    }

    private void zoomAboutCentre(final double factor) {
        hideHoverCard();
        if (state.zoomBy(view.getCanvasWidth() / 2.0, view.getCanvasHeight() / 2.0, factor)) {
            draw();
        } else {
            applyViewport();
        }
    }

    // Draws the current layout from scratch.
    private void draw() {
        final GraphViewport viewport = state.getViewport();
        view.setSvg(StructureGraphSvg.build(state.getLayout(), markerId, viewport.getZoom()));
        textZoom = viewport.getZoom();
        applyViewport();
        applySelection();
        view.setMatches(state.getMatches());
        view.setFocusedNode(focusedRowId == null
                ? null
                : state.canvasIdFor(focusedRowId));
        view.setSummary(state.getRows().isEmpty()
                ? state.summary()
                : state.summary() + ". Its documents are listed in the table that follows.");
    }

    // Moves the drawing to the current pan and zoom, and fits the labels to the zoom.
    private void applyViewport() {
        final GraphViewport viewport = state.getViewport();
        final double zoom = viewport.getZoom();
        view.setViewTransform(viewport.toSvgTransform());
        view.setLabelScale(viewport.getLabelScale());
        view.setTextVisible(StructureGraphText.isNameVisible(zoom), viewport.isMetaVisible());
        if (zoom != textZoom) {
            final LayoutResult layout = state.getLayout();
            for (final Instance instance : layout.getInstances()) {
                if (instance.getX() != null) {
                    view.setNodeText(instance.getId(),
                            StructureGraphText.name(layout, instance, zoom),
                            StructureGraphText.meta(layout, instance, zoom));
                }
            }
            textZoom = zoom;
        }
    }

    private void applySelection() {
        view.setSelection(state.getSelectedId(), state.getScope());
    }

    private void endPress() {
        pressed = false;
        dragged = false;
        pressHit = StructureGraphHit.NONE;
        view.setDragging(false);
    }


    // The key codes of = (with +) and -, which differ in Firefox, as FloorMapCanvasController has them.
    private static boolean isZoomIn(final int key) {
        return key == KEY_EQUALS || key == KEY_EQUALS_FIREFOX || key == KeyCodes.KEY_NUM_PLUS;
    }

    private static boolean isZoomOut(final int key) {
        return key == KEY_DASH || key == KEY_DASH_FIREFOX || key == KeyCodes.KEY_NUM_MINUS;
    }
}
