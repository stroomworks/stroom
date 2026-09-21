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
import stroom.pathways.client.presenter.PathwayTreePresenter.PathwayTreeView;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.htree.client.treelayout.Point;
import stroom.widget.util.client.ElementUtil;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;
import stroom.widget.util.client.MySingleSelectionModel;
import stroom.widget.util.client.SafeHtmlUtil;

import com.google.gwt.dom.client.Element;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class PathwayTreePresenter
        extends MyPresenterWidget<PathwayTreeView> {

    private static final int ROW_HEIGHT = 22;
    private static final int INDENT = 20;
    private static final int INFO_MIN_WIDTH = 240;
    private static final int TREE_MIN_WIDTH = 240;
    private static final String ATTRIBUTE_PREFIX = "attribute.";
    private static final String SELECTED_CLASS = "pathway-nodeName--selected";

    private final ButtonView newButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;

    private final HTML html;
    private final HTML side;
    private final Glass glass;
    private final MySingleSelectionModel<PathNode> selectionModel = new MySingleSelectionModel<PathNode>();

    private Pathway pathway;
    private boolean showNodeInfo = true;
    private Element selectedElement;
    private PathNode selectedNode;
    private boolean readOnly = true;
    private final Map<String, PathNode> nodeMap = new HashMap<>();

    private int infoWidth = 340;
    private boolean resizingPanel;
    private int startX;

    @Inject
    public PathwayTreePresenter(final EventBus eventBus,
                                final PathwayTreeView view,
                                final DefaultResources resources) {
        super(eventBus, view);
        newButton = view.addButton(SvgPresets.NEW_ITEM);
        editButton = view.addButton(SvgPresets.EDIT);
        removeButton = view.addButton(SvgPresets.DELETE);
        enableButtons();

        glass = new Glass(resources.dataGridStyle().resizeGlass());

        html = new HTML();
        html.addStyleName("max");
        view.setDataWidget(html);

        // Docked beside the toolbar and the tree together, not inside them, so the panel starts at
        // the very top of the view.
        side = new HTML();
        view.setSideWidget(side);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(html.addClickHandler(e -> {
            final Element target = e.getNativeEvent().getEventTarget().cast();
            if (target == null) {
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
        enableButtons();
        showInfo();
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

    public void read(final Pathway pathway,
                     final boolean readOnly) {
        this.pathway = pathway;
        this.readOnly = readOnly;
        this.selectedNode = null;
        selectionModel.clear();
        enableButtons();
        refresh();
    }

    private static String uuid(final PathNode pathNode) {
        return pathNode == null
                ? null
                : pathNode.getUuid();
    }

    // Draws the whole thing. Only a new pathway needs this; selecting a node does not.
    private void refresh() {
        nodeMap.clear();
        selectedElement = null;

        final HtmlBuilder hb = new HtmlBuilder();
        hb.div(div -> {
            if (pathway != null) {
                addNode(pathway.getRoot());

                // Draw bezier curves.
                final HtmlBuilder svgBuilder = new HtmlBuilder();
                final HtmlBuilder nodeBuilder = new HtmlBuilder();
                final AtomicInteger rowNum = new AtomicInteger();
                final AtomicInteger width = new AtomicInteger();
                final AtomicInteger height = new AtomicInteger();

                append(nodeBuilder,
                        pathway.getRoot(),
                        svgBuilder,
                        0,
                        rowNum,
                        width,
                        height);

                div.div(d -> {
                    d.elem(rootSvgElement -> rootSvgElement.append(svgBuilder.toSafeHtml()),
                            SafeHtmlUtil.from("svg"),
                            new Attribute("width", String.valueOf(width.get() + 10)),
                            new Attribute("height", String.valueOf(height.get() + 10)),
                            new Attribute("xmlns", "http://www.w3.org/2000/svg"));
                }, Attribute.className("pathway-curves"));
                div.div(d -> d.append(nodeBuilder.toSafeHtml()), Attribute.className("pathway-nodes"));
            }
        }, Attribute.className("pathway"));
        html.setHTML(hb.toSafeHtml());
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

    private void append(final HtmlBuilder hb,
                        final PathNode node,
                        final HtmlBuilder svg,
                        final int nodeDepth,
                        final AtomicInteger rowNum,
                        final AtomicInteger width,
                        final AtomicInteger height) {
        final int sourceRowNum = rowNum.incrementAndGet();

        // Render node icon and text.
        hb.div(nodeDiv -> {
            nodeDiv.div(icon ->
                            icon.appendTrustedString(SvgImage.PATHWAYS_NODE.getSvg()),
                    Attribute.className("pathway-nodeIcon svgIcon " +
                                        SvgImage.PATHWAYS_NODE.getClassName()));
            nodeDiv.div(n -> n.append(node.getName()),
                    Attribute.className("pathway-nodeName"), new Attribute("uuid", node.getUuid()));
        }, Attribute.className("pathway-node"));

        // Add the things seen beneath this node.
        final List<PathNode> children = NullSafe.list(node.getChildren());
        if (!children.isEmpty()) {
            // Add bezier curve to child set.
            appendBezier(svg, nodeDepth, sourceRowNum, rowNum.get(), width, height);

            addChildren(hb, children, svg, nodeDepth + 1, rowNum, width, height);
        }
    }

    private void addChildren(final HtmlBuilder hb,
                             final List<PathNode> children,
                             final HtmlBuilder svg,
                             final int nodeDepth,
                             final AtomicInteger rowNum,
                             final AtomicInteger width,
                             final AtomicInteger height) {
        if (!children.isEmpty()) {
            final int sourceRowNum = rowNum.get();

            // Add child set.
            final String choiceCss = "pathway-nodeIcon svgIcon " +
                                     SvgImage.PATHWAYS_SEQUENCE.getClassName();

            hb.div(targetDiv -> {
                targetDiv.div(icon -> icon.appendTrustedString(SvgImage.PATHWAYS_SEQUENCE.getSvg()),
                        Attribute.className(choiceCss));

                targetDiv.div(o -> {

                    o.div(targetsDiv -> {
                        children.forEach(pathNode -> {
                            // Add quadratic curve to this node.
                            appendQuadratic(svg, nodeDepth + 1, sourceRowNum, rowNum.get(), width, height);

                            // Add node div.
                            append(targetsDiv,
                                    pathNode,
                                    svg,
                                    nodeDepth + 2,
                                    rowNum,
                                    width,
                                    height);
                        });
                    }, Attribute.className("pathway-target-inner"));
                }, Attribute.className("pathway-targets-inner"));


            }, Attribute.className("pathway-target"));
        }
    }

    private void appendBezier(final HtmlBuilder svg,
                              final int depth,
                              final int startRow,
                              final int endRow,
                              final AtomicInteger width,
                              final AtomicInteger height) {
        final int startX = (depth * INDENT) + 8;
        final int startY = (startRow * ROW_HEIGHT) - 4;
        final int endX = (depth * INDENT) + 18;
        final int endY = (endRow * ROW_HEIGHT) + 8;
        Bezier.curve(svg, new Point(startX, startY), new Point(endX, endY));

        if (endX > width.get()) {
            width.set(endX);
        }
        if (endY > height.get()) {
            height.set(endY);
        }
    }

    private void appendQuadratic(final HtmlBuilder svg,
                                 final int depth,
                                 final int startRow,
                                 final int endRow,
                                 final AtomicInteger width,
                                 final AtomicInteger height) {
        final int startX = (depth * INDENT) - 2;
        final int startY = (startRow * ROW_HEIGHT) + 8;
        final int endX = (depth * INDENT) + 18;
        final int endY = (endRow * ROW_HEIGHT) + 8;
        Bezier.quadratic(svg, new Point(startX, startY), new Point(endX, endY));
        if (endX > width.get()) {
            width.set(endX);
        }
        if (endY > height.get()) {
            height.set(endY);
        }
    }

    private void enableButtons() {
        newButton.setEnabled(!readOnly);
        final PathNode pathNode = selectionModel.getSelectedObject();
        if (!readOnly) {
            final boolean enabled = pathNode != null;
            editButton.setEnabled(enabled);
            removeButton.setEnabled(enabled);
        } else {
            editButton.setEnabled(false);
            removeButton.setEnabled(false);
        }
        if (readOnly) {
            newButton.setTitle("New path disabled as read only");
            editButton.setTitle("Edit path disabled as read only");
            removeButton.setTitle("Remove path disabled as read only");
        } else {
            newButton.setTitle("New Path");
            editButton.setTitle("Edit Path");
            removeButton.setTitle("Remove Path");
        }
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
