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

import stroom.data.grid.client.DefaultResources;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.pathways.client.presenter.PathwayTreePresenter.PathwayTreeView;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.PathwayUsage;
import stroom.svg.client.Preset;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.InlineSvgToggleButton;
import stroom.widget.util.client.ElementUtil;
import stroom.widget.util.client.MySingleSelectionModel;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Node;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.dom.client.Style.Position;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PathwayTreePresenter
        extends MyPresenterWidget<PathwayTreeView> {

    private static final int ROW_HEIGHT = 22;
    private static final int INDENT = 20;
    private static final String SELECTED_CLASS = "pathway-nodeName--selected";
    private static final String HIGHLIGHT_CLASS = "pathway-node--changed";
    // The same marking, over once rather than over and over. While the model is being stepped
    // through, the next step arrives before a long run has finished and every node is caught part
    // way through one; a single pass finishes and settles before the drawing is replaced.
    private static final String HIGHLIGHT_ONCE_CLASS = "pathway-node--changed-once";
    // How long the walk waits between one node and the next. The same for every route however many
    // nodes it has: worked out from the length instead, a long route would travel so fast that the
    // walk could not be followed, which is the whole of what it is for.
    private static final int ROUTE_STAGGER_MS = 400;
    private static final String GRAPH_TITLE = "Show as a graph";
    private static final String TREE_TITLE = "Show as a tree";
    private static final int MAX_HISTORY = 20000;
    private static final int HISTORY_DELAY_MILLIS = 400;
    private static final PathwaysResource PATHWAYS_RESOURCE = GWT.create(PathwaysResource.class);

    private final InlineSvgToggleButton viewButton;
    private final RestFactory restFactory;
    private final PathwayViewport viewport;
    private Runnable viewChangeHandler;
    private boolean historyPending;
    private boolean blanked;
    private String wantedSelection;
    private final Timer historyRequest = new Timer() {
        @Override
        public void run() {
            fetchHistoryNow();
        }
    };
    private boolean stepping;

    private final HTML html;
    private final MySingleSelectionModel<PathNode> selectionModel = new MySingleSelectionModel<PathNode>();
    private final PathwayRenderer treeRenderer = new PathwayTreeRenderer();
    private final PathwayRenderer graphRenderer = new PathwayGraphRenderer();
    // The graph to begin with: it says how much has gone through each part of the model and how
    // recently, which is what a reader opening a pathway is usually after. The tree is a click away
    // for reading the structure itself.
    private PathwayRenderer renderer = graphRenderer;
    // How much each node has changed and when it last did, by uuid. Worked out from the stored changes
    // rather than held on the node: the changes already say it, and holding it twice would let the two
    // differ.
    private MutationCounts counts = MutationCounts.of(Collections.emptyList(), 0);
    // The moment being looked at. Zero while the model on show is the current one, in which case it
    // is the time now.
    private long asAt;
    // Every node the model has ever held, which the drawing places from so that a node appearing does
    // not move the ones around it. Null where the model on show is the current one and is its own
    // answer.
    private PathNode layout;
    private List<PathwayUsage> usage = Collections.emptyList();
    // The change being wound back to, which says which reading applies.
    private long upTo;
    private List<PathwayMutation> history = Collections.emptyList();
    private long changeCeiling;
    // Nodes to draw attention to, each path key against its place in the order they were given in.
    // Held rather than applied once, because the drawing is rebuilt whenever the model is and the
    // elements it was put on go with it.
    private Map<String, Integer> highlighted = Collections.emptyMap();
    // Whether what is picked out is a walk, which is shown one node after another and once, rather
    // than a change, which moved every node it touched at the same moment.
    private boolean highlightedRoute;
    private boolean showKey;
    // Where to ask for the changes if nothing hands them over. The view around this one may already
    // hold them, in which case it gives them and nothing is fetched.
    private DocRef docRef;
    private String historyFor;

    private Pathway pathway;
    private Element selectedElement;
    private PathNode selectedNode;
    private final Map<String, PathNode> nodeMap = new HashMap<>();

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
        // Turned on to match the drawing it starts on, so the button and what is on show agree.
        viewButton.setState(true);
        viewButton.setTitle(TREE_TITLE);
        view.addButton(viewButton);

        html = new HTML();
        html.addStyleName("max");
        // What the graph's key is placed against. The drawing inside scrolls and is scaled; the key
        // is neither, so it hangs off the panel rather than off the drawing.
        html.getElement().getStyle().setPosition(Position.RELATIVE);
        viewport = new PathwayViewport(html);
        view.setDataWidget(html);
    }

    @Override
    protected void onBind() {
        super.onBind();
        // Follows the button rather than deciding for itself which clicks count: the button turns
        // itself over on any click it accepts, and a drawing that disagreed with the icon on it would
        // be worse than a drawing swapped by an unusual click.
        registerHandler(viewButton.addClickHandler(e -> swapView()));
        registerHandler(html.addMouseDownHandler(e -> {
            // Only the graph is dragged. The tree scrolls a row at a time and reads top to bottom;
            // there is nothing to move around in it.
            if (!renderer.isPannable()) {
                return;
            }

            final Element target = e.getNativeEvent().getEventTarget().cast();
            // Not from the buttons sitting over the drawing: they are there to be pressed.
            if (target != null && ElementUtil.findParent(target, element ->
                    NullSafe.isNonBlankString(element.getId()), 3) != null) {
                return;
            }
            viewport.startDrag(e.getClientX(), e.getClientY());
        }));

        registerHandler(html.addMouseMoveHandler(e -> viewport.drag(e.getClientX(), e.getClientY())));

        registerHandler(html.addMouseUpHandler(e -> viewport.endDrag()));

        registerHandler(html.addClickHandler(e -> {
            // A drag ends in a click. Selecting whatever the pointer happened to come to rest on would
            // be a surprise, so the click that ends one is let go.
            if (viewport.wasDragged()) {
                return;
            }

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

    }

    // Selecting must not redraw the tree. Rebuilding it throws away where the view is scrolled to, so
    // the picture jumps back to the top every time a node is clicked. Only the two nodes whose state
    // changed are touched.
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
        // Where the drawing arriving was last left, rather than the middle. Only the first sight of a
        // model opens in the middle; after that the reader put it where they wanted it.
        viewport.swapDrawing();
        refresh(true);
        if (viewChangeHandler != null) {
            viewChangeHandler.run();
        }
    }

    // Only the graph needs the changes, and only for colour, so they are asked for when it is first
    // shown rather than with every pathway opened. A page of them is large enough that the pathway
    // list was unopenable while it read them.
    // Asked for a moment after the reader stops moving, not as they move. Running down a list of
    // pathways with the arrow keys would otherwise ask for the whole history of every one passed
    // over, and every answer but the last would be thrown away.
    private void fetchHistory() {
        historyRequest.cancel();
        if (renderer.usesHistory() && docRef != null) {
            historyRequest.schedule(HISTORY_DELAY_MILLIS);
        }
    }

    private void fetchHistoryNow() {
        final String name = NullSafe.get(pathway, Pathway::getName);
        if (!renderer.usesHistory() || docRef == null || name == null || name.equals(historyFor)) {
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
                        // A page of them rather than all of them says nothing dependable about how
                        // much a node has changed or when it last did. Better to colour nothing than
                        // to colour it from part of the story with no way of saying so.
                        final long total = NullSafe.getOrElse(
                                result.getPageResponse(), PageResponse::getTotal, 0L);
                        setHistory(result.getValues().size() >= total
                                ? result.getValues()
                                : Collections.emptyList());
                        refresh(true);
                    }
                })
                .onFailure(new DefaultErrorHandler(this, () -> {
                    // Asked and not answered. Drawn with nothing known rather than left blank, which
                    // would look like a model with no nodes in it.
                    setHistory(Collections.emptyList());
                    refresh(true);
                }))
                .taskMonitorFactory(this)
                .exec();
    }

    // @return whether the click was on one of the drawing's own controls rather than on the drawing.
    private boolean onControl(final String id) {
        return renderer.onControl(id, new PathwayControls() {
            @Override
            public void zoomIn() {
                viewport.zoomIn();
            }

            @Override
            public void zoomOut() {
                viewport.zoomOut();
            }

            @Override
            public void toggleLegend() {
                // Drawn again rather than opened in place: the drawing owns its own markup, and it is
                // rebuilt on every step through the history anyway.
                showKey = !showKey;
                refresh(true);
            }
        });
    }

    /**
     * Puts the drawing back in the middle, for a panel that has changed size around it. Only for a
     * drawing that opens in the middle: a tree is read from its top left and would jump.
     */
    public void centre() {
        if (renderer.opensCentred()) {
            viewport.recentre(this::rootElement);
        }
    }

    /**
     * Whether the model is being shown as a graph rather than as a tree.
     */
    public boolean isGraphShown() {
        return renderer == graphRenderer;
    }

    /**
     * Whether the model is being stepped through rather than looked at a change at a time. What
     * changed is marked once and left, instead of going on drawing the eye to a node that is about
     * to be replaced.
     */
    public void setStepping(final boolean stepping) {
        this.stepping = stepping;
    }

    /**
     * Told whenever the drawing is swapped for the other one, so a view around this one can offer
     * whatever only makes sense against one of them.
     */
    public void setViewChangeHandler(final Runnable viewChangeHandler) {
        this.viewChangeHandler = viewChangeHandler;
    }

    public void read(final Pathway pathway) {
        // Reading the same pathway again is the model being wound back, and the reader is looking at
        // the same tree, so it stays where they left it. A different pathway starts at the top.
        final boolean samePathway = this.pathway != null
                                    && pathway != null
                                    && Objects.equals(this.pathway.getName(), pathway.getName());

        if (!samePathway) {
            // A different model, which may be a different size altogether. Keeping the zoom set for
            // the last one would show this one at whatever suited that, so it starts as drawn.
            viewport.resetZoom();
        }

        // Whether there is a node to go back to. A reader stepping through the model's changes has
        // one and keeps it; one who has just been handed this model has none, whether or not it is
        // the same model they were shown last time.
        final boolean nothingSelected = selectedNode == null && wantedSelection == null;

        this.pathway = pathway;
        // Held changes belong to whichever pathway they were fetched for. Where that is not this one,
        // a drawing that needs them waits: either the fetch below answers, or whoever opened this
        // hands them over.
        historyPending = !Objects.equals(NullSafe.get(pathway, Pathway::getName), historyFor);
        selectionModel.clear();
        refresh(samePathway);
        if (!samePathway || nothingSelected) {
            // The root is the one node every model has, and what the whole pathway is named after, so
            // a model just opened has it picked out rather than nothing.
            selectRoot();
        }
        // A different pathway was picked, so the changes behind the one on show are not the ones held.
        fetchHistory();
    }

    /**
     * The nodes to draw attention to when the model is next read — the ones a change being looked at
     * touched. They are picked out for a moment rather than marked, because it is what just happened
     * that is worth seeing, not a state the node is in.
     *
     * <p>All at once, because a change moved all of them at the same moment. A route did not, and has
     * {@link #setHighlightedRoute(List)} instead.
     */
    public void setHighlighted(final List<List<String>> paths) {
        highlighted = new HashMap<>();
        highlightedRoute = false;
        NullSafe.list(paths).forEach(path -> highlighted.putIfAbsent(key(path), 0));
    }

    /**
     * The nodes a route ran, in the sequence it ran them. Picked out one after another rather than
     * together, so the walk can be followed rather than only seen.
     */
    public void setHighlightedRoute(final List<List<String>> paths) {
        highlighted = new HashMap<>();
        highlightedRoute = true;
        // Where each node comes in the walk, counting the ones kept rather than the ones given, so a
        // node reached twice keeps its first place and the places run without gaps.
        NullSafe.list(paths).forEach(path -> highlighted.putIfAbsent(key(path), highlighted.size()));
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
        historyPending = false;
        this.history = NullSafe.list(history);
        counts = MutationCounts.of(this.history, upTo);

        // What the sizes are measured against, taken over the whole history rather than the part
        // being shown. Measured against the part being shown, the largest node of the moment would
        // always be drawn at full size, so winding back would rescale the picture rather than shrink
        // it. Worked out once here, because the history is what it depends on and that does not
        // change as the reader moves through it.
        changeCeiling = MutationCounts.of(this.history, 0).getMostChangedNode();
    }

    /**
     * Readings of how much each node had been used, one per trace that changed the model. Given
     * rather than fetched, like the changes themselves.
     */
    public void setUsage(final List<PathwayUsage> usage) {
        this.usage = NullSafe.list(usage);
    }

    // The reading taken for the trace the change being looked at belongs to, which is the first at or
    // after it: a reading is written once the trace is done, numbered with the last change it made.
    // Taking the one before instead would report the model as it stood a whole trace earlier, so a
    // node could read as changed more often than it had been used.
    private Map<String, NodeUsage> usageAsAt() {
        final Map<String, NodeUsage> byUuid = new HashMap<>();
        if (asAt <= 0 || upTo <= 0) {
            return byUuid;
        }

        PathwayUsage reading = null;
        for (final PathwayUsage candidate : usage) {
            if (candidate.getSequence() >= upTo
                && (reading == null || candidate.getSequence() < reading.getSequence())) {
                reading = candidate;
            }
        }
        if (reading != null) {
            NullSafe.list(reading.getNodes()).forEach(node -> byUuid.put(node.getNodeUuid(), node));
        }
        return byUuid;
    }

    /**
     * The model to work the positions out from, which is the model as it stands now rather than the
     * one being shown. Null where they are the same.
     */
    public void setLayout(final PathNode layout) {
        this.layout = layout;
    }

    /**
     * The moment the model on show stood at, or null where it is the model as it stands now. How long
     * ago a node changed is measured from here, so winding back an hour does not age every node by an
     * hour.
     */
    public void setAsAt(final NanoTime asAt, final Long upTo) {
        this.asAt = asAt == null
                ? 0L
                : asAt.toEpochMillis();
        this.upTo = upTo == null
                ? 0L
                : upTo;
        counts = MutationCounts.of(history, this.upTo);
    }

    private static String key(final List<String> path) {
        return String.join("\u0000", NullSafe.list(path));
    }

    private void selectRoot() {
        final PathNode root = NullSafe.get(pathway, Pathway::getRoot);
        if (root != null) {
            if (waiting()) {
                // Nothing is drawn, so nothing is picked out. Remembered, and done once there is.
                wantedSelection = root.getUuid();
            } else {
                reselect(root.getUuid());
            }
        }
    }

    // Whether the drawing on show is made from the model's changes and has not been given them.
    private boolean waiting() {
        return renderer.usesHistory() && historyPending;
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
        // What was selected, so it can be picked out again once the drawing has been rebuilt. Kept
        // here rather than at each place that redraws: a node keeps its uuid when the model is wound
        // back, so the same node is still the same node, and a redraw that forgot to put the
        // selection back would leave the node marked in one place and not in the other.
        final String was = uuid(selectedNode);
        selectedNode = null;
        nodeMap.clear();
        selectedElement = null;

        if (pathway != null && pathway.getRoot() != null) {
            addNode(pathway.getRoot());
        }

        // A drawing that says how much each node has changed cannot be made before that is known.
        // Drawn anyway, every node would appear at its smallest and in the colour of never having
        // changed, and then jump as the answer arrived. Nothing is better than something wrong, so
        // nothing is drawn until the changes arrive, and whichever node is to be picked out waits
        // with it.
        if (waiting()) {
            html.setHTML(SafeHtmlUtils.EMPTY_SAFE_HTML);
            blanked = true;
            wantedSelection = was;
            selectionModel.clear();
            return;
        }

        // Whatever the caller says, a drawing that follows an empty panel is the first one made of
        // this model, so it opens where a first drawing opens rather than where the last one of some
        // other model was left.
        final boolean keep = keepScroll && !blanked;
        blanked = false;

        // Either what was on show a moment ago, or what was asked for while there was nothing to
        // show it on.
        final String restore = was != null
                ? was
                : wantedSelection;
        wantedSelection = null;

        // The element that scrolls is the one being rebuilt below, so where it had got to has to be
        // taken off it first and put back on the one that replaces it.
        viewport.beforeDraw();

        // Worked out before the drawing is built as well as after it: the drawing is made faint
        // around the nodes a route ran, and then they are marked on it.
        final Map<String, Integer> wanted = wanted();

        html.setHTML(renderer.render(new RenderRequest(pathway, layout, byUuid(), usageAsAt(),
                asAt > 0
                        ? asAt
                        : System.currentTimeMillis(),
                changeCeiling,
                mostUsed(layout == null
                        ? NullSafe.get(pathway, Pathway::getRoot)
                        : layout),
                showKey,
                // Only a route says which nodes did not run. A change touched the nodes it touched
                // and says nothing about the rest, so nothing is faded for one.
                highlightedRoute
                        ? wanted.keySet()
                        : Collections.emptySet())));
        viewport.afterDraw(keep, renderer.opensCentred(), renderer.isZoomable(),
                this::rootElement);
        reselect(restore);
        if (!wanted.isEmpty()) {
            mark(html.getElement(), wanted, highlightClass(), stagger());
        }
    }

    // The most any one node has been used, over the model as it stands rather than the part on show,
    // so that winding back thins the lines rather than rescaling them.
    private static long mostUsed(final PathNode node) {
        if (node == null) {
            return 0L;
        }
        long most = node.getTimesUsed();
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            most = Math.max(most, mostUsed(child));
        }
        return most;
    }

    /**
     * Puts the middle of the drawing in the middle of the view.
     *
     * <p>Deferred, because the panel has no width in the turn the drawing is put on the page, and
     * centring against a width of nothing puts the middle of the drawing against the left edge.
     *
     * <p>Held as something wanted rather than done to one element, because opening a pathway draws it
     * three times — once for the model, again when its changes arrive, and again when the readings do
     * — and the drawing is rebuilt each time. Pinned to the element it was asked for, it would fire
     * against one already thrown away, which is why the middle was only sometimes found.
     */
    private Element rootElement() {
        final PathNode root = layout == null
                ? NullSafe.get(pathway, Pathway::getRoot)
                : layout;
        if (root == null) {
            return null;
        }
        return ElementUtil.findChild(html.getElement(), element ->
                root.getUuid().equals(element.getAttribute("uuid")));
    }

    // The changes against the nodes the model actually holds, worked out once the model has been read
    // and its nodes are known.
    private Map<String, NodeChange> byUuid() {
        final Map<String, NodeChange> byUuid = new HashMap<>();
        nodeMap.values().forEach(node -> {
            final NodeChange change = counts.node(node.getPath());
            if (change != null) {
                byUuid.put(node.getUuid(), change);
            }
        });
        return byUuid;
    }

    // The nodes being picked out, by uuid and where each comes in the order. Looked up this way round
    // rather than a search of the drawing for each of them in turn: picking a trace marks most of the
    // model at once, and a search that starts again at the top for every node walks the whole drawing
    // as many times as there are nodes in it.
    private Map<String, Integer> wanted() {
        if (highlighted.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, Integer> wanted = new HashMap<>();
        nodeMap.values().forEach(node -> {
            final Integer place = highlighted.get(key(node.getPath()));
            if (place != null) {
                wanted.put(node.getUuid(), place);
            }
        });
        return wanted;
    }

    // How far apart to start the nodes of the route on show. Nothing for a change, whose nodes all
    // moved at once.
    private int stagger() {
        return highlightedRoute
                ? ROUTE_STAGGER_MS
                : 0;
    }

    private String highlightClass() {
        // A route is walked once: its nodes are held off one after another to show the walk, and
        // running that over and over would replay it rather than show it.
        return stepping || highlightedRoute
                ? HIGHLIGHT_ONCE_CLASS
                : HIGHLIGHT_CLASS;
    }

    private static void mark(final Element element,
                             final Map<String, Integer> wanted,
                             final String highlightClass,
                             final int stagger) {
        final Integer place = wanted.get(element.getAttribute("uuid"));
        if (place != null) {
            // Held off by where the node comes in the walk, so a route travels along itself. Set here
            // rather than in the stylesheet, which cannot know the order, and left to go with the
            // drawing: the next read builds the markup again, so nothing has to take it back off.
            element.getStyle().setProperty("animationDelay", (place * stagger) + "ms");
            element.addClassName(highlightClass);
        }
        final NodeList<Node> children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node node = children.getItem(i);
            if (Element.is(node)) {
                mark(Element.as(node), wanted, highlightClass, stagger);
            }
        }
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
    }
}
