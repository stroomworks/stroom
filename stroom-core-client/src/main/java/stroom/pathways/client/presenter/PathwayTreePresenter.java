/*
 * Copyright 2025 Crown Copyright
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

package stroom.pathways.client.presenter;

import stroom.data.client.presenter.CopyTextUtil;
import stroom.data.grid.client.DefaultResources;
import stroom.data.grid.client.Glass;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.pathways.client.presenter.PathwayTreePresenter.PathwayTreeView;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.svg.client.Preset;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.InlineSvgToggleButton;
import stroom.widget.util.client.ElementUtil;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;
import stroom.widget.util.client.MySingleSelectionModel;
import stroom.widget.util.client.SafeHtmlUtil;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style.Position;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class PathwayTreePresenter
        extends MyPresenterWidget<PathwayTreeView> {

    private static final int ROW_HEIGHT = 22;
    private static final int INDENT = 20;
    private static final int INFO_MIN_WIDTH = 240;
    private static final int TREE_MIN_WIDTH = 240;
    private static final String ATTRIBUTE_PREFIX = "attribute.";
    private static final String SELECTED_CLASS = "pathway-nodeName--selected";
    private static final String GRAPH_TITLE = "Show as a graph";
    private static final String TREE_TITLE = "Show as a tree";
    private static final double ZOOM_STEP = 1.25;
    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 3;
    private static final int MAX_HISTORY = 20000;
    private static final PathwaysResource PATHWAYS_RESOURCE = GWT.create(PathwaysResource.class);

    private final InlineSvgToggleButton viewButton;
    private final RestFactory restFactory;

    private final HTML html;
    private final HTML side;
    private final Glass glass;
    private final MySingleSelectionModel<PathNode> selectionModel = new MySingleSelectionModel<PathNode>();
    private final PathwayRenderer treeRenderer = new PathwayTreeRenderer();
    private final PathwayRenderer graphRenderer = new PathwayGraphRenderer();
    private PathwayRenderer renderer = treeRenderer;
    // When each node last changed, by uuid. Worked out from the stored changes rather than held on the
    // node: the changes already say it, and holding it twice would let the two differ.
    private Map<String, NanoTime> updateTimes = Collections.emptyMap();
    private double zoom = 1;
    private boolean showKey;
    // Where to ask for the changes if nothing hands them over. The view around this one may already
    // hold them, in which case it gives them and nothing is fetched.
    private DocRef docRef;
    private String historyFor;

    private Pathway pathway;
    private boolean showNodeInfo = true;
    private Element selectedElement;
    private PathNode selectedNode;
    private final Map<String, PathNode> nodeMap = new HashMap<>();

    private int infoWidth = 340;
    private boolean resizingPanel;
    private int startX;

    @Inject
    public PathwayTreePresenter(final EventBus eventBus,
                                final PathwayTreeView view,
                                final RestFactory restFactory,
                                final DefaultResources resources) {
        super(eventBus, view);
        this.restFactory = restFactory;
        // Which way the model is drawn, not what is drawn, so it sits with the tree rather than with
        // the buttons that change the model. It holds which drawing is on show, so nothing else has to.
        viewButton = new InlineSvgToggleButton();
        viewButton.setSvg(SvgImage.NODES);
        viewButton.setTitle(GRAPH_TITLE);
        view.addButton(viewButton);

        glass = new Glass(resources.dataGridStyle().resizeGlass());

        html = new HTML();
        html.addStyleName("max");
        // What the graph's key is placed against. The drawing inside scrolls and is scaled; the key
        // is neither, so it hangs off the panel rather than off the drawing.
        html.getElement().getStyle().setPosition(Position.RELATIVE);
        view.setDataWidget(html);

        // Docked beside the toolbar and the tree together, not inside them, so the panel starts at
        // the very top of the view.
        side = new HTML();
        view.setSideWidget(side);
    }

    @Override
    protected void onBind() {
        super.onBind();
        // Follows the button rather than deciding for itself which clicks count: the button turns
        // itself over on any click it accepts, and a drawing that disagreed with the icon on it would
        // be worse than a drawing swapped by an unusual click.
        registerHandler(viewButton.addClickHandler(e -> swapView()));
        registerHandler(html.addClickHandler(e -> {
            final Element target = e.getNativeEvent().getEventTarget().cast();
            if (target == null) {
                return;
            }

            // The drawing carries its own controls, so a click is theirs before it is a node's.
            final Element button = ElementUtil.findParent(target, element ->
                    NullSafe.isNonBlankString(element.getId()), 3);
            if (button != null && onControl(button.getId())) {
                return;
            }

            final Element node = ElementUtil.findParent(target, element ->
                    NullSafe.isNonBlankString(element.getAttribute("uuid")), 3);
            if (node != null) {
                select(nodeMap.get(node.getAttribute("uuid")), node);
            }
        }));

        registerHandler(side.addClickHandler(e -> {
            final Element target = e.getNativeEvent().getEventTarget().cast();
            if (target != null && ElementUtil.findParent(target, element ->
                    "closePathwayInfo".equals(element.getId()), 3) != null) {
                select(null, null);
            }
        }));

        registerHandler(side.addMouseDownHandler(e -> {
            final Element target = e.getNativeEvent().getEventTarget().cast();
            if (target == null) {
                return;
            }

            // Copying a value is handled here rather than on click: CopyTextUtil.onClick acts only on
            // a mousedown, and it also raises the right-click menu.
            if (ElementUtil.findParent(target, "docRefLinkContainer", 5) != null) {
                CopyTextUtil.onClick(e.getNativeEvent(), this);
                return;
            }

            // Drag the panel's left edge to resize it.
            if ("pathwayInfoResize".equals(target.getId())) {
                startX = e.getClientX();
                glass.show();
                Event.setCapture(side.getElement());
                resizingPanel = true;
            }
        }));
        registerHandler(side.addMouseMoveHandler(e -> {
            if (resizingPanel) {
                // Moving left widens the panel, right narrows it. The room to grow into is the tree
                // and the panel together, which does not change as the panel is dragged.
                final int delta = startX - e.getClientX();
                startX = e.getClientX();
                final int max = Math.max(INFO_MIN_WIDTH,
                        (html.getElement().getClientWidth() + side.getElement().getClientWidth())
                        - TREE_MIN_WIDTH);
                infoWidth = Math.max(INFO_MIN_WIDTH, Math.min(max, infoWidth + delta));

                final Element panel = ElementUtil.findChild(side.getElement(), "pathway-info-panel");
                if (panel != null) {
                    panel.getStyle().setWidth(infoWidth, Unit.PX);
                }
            }
        }));
        registerHandler(side.addMouseUpHandler(e -> {
            if (resizingPanel) {
                glass.hide();
                Event.releaseCapture(side.getElement());
                resizingPanel = false;
            }
        }));
    }

    // Selecting must not redraw the tree. Rebuilding it throws away where the view is scrolled to, so
    // the picture jumps back to the top every time a node is clicked. Only the two nodes whose state
    // changed and the side panel are touched.
    private void select(final PathNode pathNode, final Element element) {
        if (Objects.equals(uuid(selectedNode), uuid(pathNode))) {
            return;
        }

        if (selectedElement != null) {
            selectedElement.removeClassName(SELECTED_CLASS);
        }
        selectedElement = element;
        if (selectedElement != null) {
            selectedElement.addClassName(SELECTED_CLASS);
        }

        selectedNode = pathNode;
        if (pathNode == null) {
            selectionModel.clear();
        } else {
            selectionModel.setSelected(pathNode, true);
        }
        showInfo();
    }

    // The two drawings answer different questions of the same model, so which one is on show is not
    // worth a redraw of anything but the drawing. What is selected is kept: a node is the same node
    // however it is drawn.
    //
    // The button has already turned itself over by the time this runs — it handles its own click
    // before the one registered here — so which drawing to use is read from it rather than tracked.
    private void swapView() {
        renderer = viewButton.getState()
                ? graphRenderer
                : treeRenderer;
        viewButton.setTitle(viewButton.getState()
                ? TREE_TITLE
                : GRAPH_TITLE);

        fetchHistory();

        final String was = uuid(selectedNode);
        this.selectedNode = null;
        refresh(false);
        reselect(was);
    }

    // Only the graph needs the changes, and only for colour, so they are asked for when it is first
    // shown rather than with every pathway opened. A page of them is large enough that the pathway
    // list was unopenable while it read them.
    private void fetchHistory() {
        final String name = NullSafe.get(pathway, Pathway::getName);
        if (!renderer.isCentred() || docRef == null || name == null || name.equals(historyFor)) {
            return;
        }

        historyFor = name;
        final FindPathwayMutationCriteria criteria = new FindPathwayMutationCriteria(
                new PageRequest(0, MAX_HISTORY),
                List.of(new CriteriaFieldSort(PathwayMutation.FIELD_TIME, false, false)),
                docRef,
                name);
        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.findMutations(criteria))
                .onSuccess(result -> {
                    // Another pathway may have been picked while this was in flight, and colouring one
                    // model by another model's changes would be worse than not colouring it at all.
                    if (name.equals(NullSafe.get(pathway, Pathway::getName))) {
                        setHistory(result.getValues());
                        refresh(true);
                    }
                })
                .onFailure(new DefaultErrorHandler(this, null))
                .taskMonitorFactory(this)
                .exec();
    }

    // @return whether the click was on one of the drawing's own controls rather than on the drawing.
    private boolean onControl(final String id) {
        if (PathwayGraphRenderer.ZOOM_IN_ID.equals(id)) {
            zoom(ZOOM_STEP);
        } else if (PathwayGraphRenderer.ZOOM_OUT_ID.equals(id)) {
            zoom(1 / ZOOM_STEP);
        } else if (PathwayGraphRenderer.KEY_ID.equals(id)) {
            showKey = !showKey;
            applyKey();
        } else {
            return false;
        }
        return true;
    }

    // Held here rather than in the drawing, which is rebuilt whenever the model is, so that opening
    // the key does not close itself again the next time a trace moves the model on.
    private void applyKey() {
        final Element key = ElementUtil.findChild(html.getElement(), element ->
                PathwayGraphRenderer.KEY_PANEL_ID.equals(element.getId()));
        if (key != null) {
            if (showKey) {
                key.addClassName(PathwayGraphRenderer.KEY_SHOWN_CLASS);
            } else {
                key.removeClassName(PathwayGraphRenderer.KEY_SHOWN_CLASS);
            }
        }
    }

    // Scaling the drawing rather than drawing it again: nothing about the model has changed, and a
    // redraw would lose what is selected and where the view had got to.
    private void zoom(final double by) {
        final double wanted = zoom * by;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, wanted));
        applyZoom();
    }

    private void applyZoom() {
        final Element root = html.getElement().getFirstChildElement();
        final Element sizer = root == null
                ? null
                : root.getFirstChildElement();
        final Element canvas = sizer == null
                ? null
                : sizer.getFirstChildElement();
        if (canvas == null) {
            return;
        }

        // Read off the canvas rather than remembered, because scaling does not change what a thing
        // measures and this stays the drawing's own size however far it has been zoomed.
        final int width = canvas.getOffsetWidth();
        final int height = canvas.getOffsetHeight();
        canvas.getStyle().setProperty("transform", "scale(" + zoom + ")");
        sizer.getStyle().setWidth(width * zoom, Unit.PX);
        sizer.getStyle().setHeight(height * zoom, Unit.PX);
    }

    /**
     * Whether clicking a node opens the Node Info panel beside the tree. Off where the view around the
     * tree already shows what the node holds, so the two do not say the same thing twice.
     */
    public void setShowNodeInfo(final boolean showNodeInfo) {
        this.showNodeInfo = showNodeInfo;
        showInfo();
    }

    private void showInfo() {
        if (selectedNode == null || !showNodeInfo) {
            side.setHTML(SafeHtmlUtils.EMPTY_SAFE_HTML);
        } else {
            final HtmlBuilder hb = new HtmlBuilder();
            appendInfo(hb, selectedNode);
            side.setHTML(hb.toSafeHtml());
        }
    }

    public void read(final Pathway pathway) {
        // What was selected before, so it can be picked out again afterwards. A node keeps its uuid
        // when the model is wound back, so the same node is still the same node — and where it is not
        // in the model being read, nothing is selected, which is the right answer.
        final String was = uuid(selectedNode);

        // Reading the same pathway again is the model being wound back, and the reader is looking at
        // the same tree, so it stays where they left it. A different pathway starts at the top.
        final boolean samePathway = this.pathway != null
                                    && pathway != null
                                    && Objects.equals(this.pathway.getName(), pathway.getName());

        this.pathway = pathway;
        this.selectedNode = null;
        selectionModel.clear();
        refresh(samePathway);
        reselect(was);
        // A different pathway was picked, so the changes behind the one on show are not the ones held.
        fetchHistory();
    }

    /**
     * Which pathways document the model on show belongs to, so the changes behind it can be asked for
     * where nothing hands them over.
     */
    public void setDocRef(final DocRef docRef) {
        this.docRef = docRef;
    }

    /**
     * Every change made to this pathway, which is where the graph gets the colour of a node from.
     * Handing them over stops them being asked for. Takes effect on the next read.
     */
    public void setHistory(final List<PathwayMutation> history) {
        historyFor = NullSafe.get(pathway, Pathway::getName);
        final Map<String, Long> newest = new HashMap<>();
        final Map<String, NanoTime> times = new HashMap<>();
        for (final PathwayMutation mutation : NullSafe.list(history)) {
            final String key = key(mutation.getPath());
            final Long seen = newest.get(key);
            // By sequence rather than by time, because every change a trace made shares one timestamp.
            if (seen == null || mutation.getSequence() > seen) {
                newest.put(key, mutation.getSequence());
                times.put(key, mutation.getTime());
            }
        }

        // Keyed by uuid for the renderer, which knows nodes by the uuid it draws against them.
        updateTimes = new HashMap<>();
        nodeMap.values().forEach(node -> {
            final NanoTime time = times.get(key(node.getPath()));
            if (time != null) {
                updateTimes.put(node.getUuid(), time);
            }
        });
    }

    private static String key(final List<String> path) {
        return String.join("\u0000", NullSafe.list(path));
    }

    /**
     * Forgets what was selected, so the next read starts clean rather than picking the same node out
     * again. Reading keeps a selection on purpose, which is what lets the model be wound back with the
     * node being looked at staying put.
     */
    public void clearSelection() {
        selectedNode = null;
        selectedElement = null;
        selectionModel.clear();
        showInfo();
    }

    private void reselect(final String uuid) {
        if (uuid != null) {
            final PathNode node = nodeMap.get(uuid);
            if (node != null) {
                select(node, ElementUtil.findChild(html.getElement(),
                        element -> uuid.equals(element.getAttribute("uuid"))));
            }
        }
    }

    private static String uuid(final PathNode pathNode) {
        return pathNode == null
                ? null
                : pathNode.getUuid();
    }

    // Draws the whole thing. Only a new pathway needs this; selecting a node does not.
    private void refresh(final boolean keepScroll) {
        nodeMap.clear();
        selectedElement = null;

        // The element that scrolls is the one being rebuilt below, so where it had got to has to be
        // taken off it first and put back on the one that replaces it.
        final Element scrolling = html.getElement().getFirstChildElement();
        final int scrollLeft = keepScroll && scrolling != null
                ? scrolling.getScrollLeft()
                : 0;
        final int scrollTop = keepScroll && scrolling != null
                ? scrolling.getScrollTop()
                : 0;

        if (pathway != null && pathway.getRoot() != null) {
            addNode(pathway.getRoot());
        }
        html.setHTML(renderer.render(pathway, updateTimes));
        if (renderer.isCentred()) {
            applyZoom();
            applyKey();
        }

        final Element rebuilt = html.getElement().getFirstChildElement();
        if (rebuilt != null) {
            if (keepScroll) {
                rebuilt.setScrollLeft(scrollLeft);
                rebuilt.setScrollTop(scrollTop);
            } else if (renderer.isCentred()) {
                // Waits for the browser to lay the drawing out. Read in the same turn as it is put on
                // the page, the panel has no width yet, and centring on a width of nothing puts the
                // middle of the drawing against the left edge rather than in the middle of the view.
                Scheduler.get().scheduleDeferred(() -> {
                    rebuilt.setScrollLeft((rebuilt.getScrollWidth() - rebuilt.getClientWidth()) / 2);
                    rebuilt.setScrollTop((rebuilt.getScrollHeight() - rebuilt.getClientHeight()) / 2);
                });
            } else {
                rebuilt.setScrollLeft(0);
                rebuilt.setScrollTop(0);
            }
        }
        showInfo();
    }

    // The Node Info side panel: the selected node's name, where it sits in the pathway, and everything
    // the model has learnt about it. Closed by the header button (id closePathwayInfo), resized by
    // dragging the left edge (id pathwayInfoResize).
    private void appendInfo(final HtmlBuilder hb, final PathNode node) {
        hb.div(panel -> {
            panel.div("", Attribute.className("pathway-info-resize"), Attribute.id("pathwayInfoResize"));
            panel.div(header -> {
                header.span("Node Info", Attribute.className("pathway-info-title"));
                header.div("×",
                        Attribute.className("pathway-info-close"),
                        Attribute.id("closePathwayInfo"),
                        Attribute.title("Close"));
            }, Attribute.className("pathway-info-header"));

            panel.div(body -> {
                body.div(node.getName(), Attribute.className("pathway-info-name"));
                body.div(String.join(" › ", NullSafe.list(node.getPath())),
                        Attribute.className("pathway-info-path"));

                final List<Constraint> constraints = new ArrayList<>(
                        NullSafe.map(node.getConstraints()).values());
                constraints.sort(Comparator.comparing(Constraint::getName));

                appendSection(body, "Constraints", constraints, false);
                appendSection(body, "Attributes", constraints, true);
            }, Attribute.className("pathway-info-body"));
        }, Attribute.className("pathway-info-panel"),
                Attribute.id("pathwayInfo"),
                Attribute.style("width: " + infoWidth + "px;"));
    }

    // The span's own OTel attributes are listed separately from what the model works out about the
    // node itself, the way the Span Info panel splits them.
    private void appendSection(final HtmlBuilder hb,
                               final String title,
                               final List<Constraint> constraints,
                               final boolean attributes) {
        final List<Constraint> section = constraints
                .stream()
                .filter(constraint -> constraint.getName().startsWith(ATTRIBUTE_PREFIX) == attributes)
                .collect(Collectors.toList());
        if (section.isEmpty()) {
            return;
        }

        hb.div(title, Attribute.className("pathway-info-section"));
        section.forEach(constraint -> {
            final String name = attributes
                    ? constraint.getName().substring(ATTRIBUTE_PREFIX.length())
                    : constraint.getName();
            hb.div(row -> {
                row.span(name + ": ", Attribute.className("pathway-info-key"));
                CopyTextUtil.render(constraintValue(constraint), row, false);
                if (constraint.isOptional()) {
                    row.span("optional", Attribute.className("pathway-info-optional"));
                }
            }, Attribute.className("pathway-info-row"), Attribute.title(constraintType(constraint)));
        });
    }

    private static String constraintValue(final Constraint constraint) {
        return constraint.getValue() == null
                ? ""
                : constraint.getValue().toString();
    }

    private static String constraintType(final Constraint constraint) {
        return constraint.getValue() == null
                ? ""
                : constraint.getValue().valueType().getDisplayValue();
    }

    private void addNode(final PathNode node) {
        nodeMap.put(node.getUuid(), node);
        NullSafe.list(node.getChildren()).forEach(this::addNode);
    }

    public MySingleSelectionModel<PathNode> getSelectionModel() {
        return selectionModel;
    }

    public interface PathwayTreeView extends View {

        ButtonView addButton(Preset preset);

        void addButton(ButtonView buttonView);

        void setDataWidget(Widget widget);

        void setSideWidget(Widget widget);
    }
}
