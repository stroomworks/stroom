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

import stroom.floormap.client.presenter.FloorMapLayersPresenter.FloorMapLayersView;
import stroom.floormap.shared.TypeStyle;
import stroom.floormap.shared.TypeStyle.Shape;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.InlineSvgButton;

import com.google.gwt.dom.client.DataTransfer.DropEffect;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.DragEndEvent;
import com.google.gwt.event.dom.client.DragLeaveEvent;
import com.google.gwt.event.dom.client.DragOverEvent;
import com.google.gwt.event.dom.client.DragStartEvent;
import com.google.gwt.event.dom.client.DropEvent;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The <strong>Layers</strong> panel — a dock tab listing the floor map's type
 * layers (front-to-back = z-order), each with a three-state visibility control.
 *
 * <p>Layers map to record types; the ordered list comes from the document's
 * {@link TypeStyle} entries (via {@link #setLayers(List)}). Per-layer visibility
 * is a <em>transient</em> client-side overlay (not persisted): each layer cycles
 * <em>full → 30% opacity → off</em>. Changes are pushed to the canvas through the
 * {@link #setChangeHandler(Runnable) change handler}; the host presenter reads
 * {@link #getHiddenTypes()} / {@link #getDimmedTypes()} and forwards them to
 * {@code FloorMapCanvasPresenter.setLayerVisibility(...)}.</p>
 *
 * <p>Editor-only controls (reorder, lock, appearance) will be added here in a
 * later increment; {@link #setEditorMode(boolean)} distinguishes the tabs.</p>
 */
public class FloorMapLayersPresenter extends MyPresenterWidget<FloorMapLayersView> {

    /** Visibility state per type: 0 = hidden, 1 = 30% opacity, 2 = full. */
    private static final int OFF = 0;
    private static final int DIM = 1;
    private static final int FULL = 2;

    private final Map<String, Integer> visibilityByType = new LinkedHashMap<>();
    /** Transient per-type lock state (Editor only): locked items can't be moved. */
    private final Set<String> lockedTypes = new HashSet<>();
    /** Types seen in the loaded data but not yet a saved layer (Editor only). */
    private final Set<String> seenTypes = new HashSet<>();
    private final FlowPanel list = new FlowPanel();

    private List<TypeStyle> layers = new ArrayList<>();
    private boolean editorMode;
    private Runnable changeHandler;
    private Consumer<List<TypeStyle>> typeStylesEditHandler;
    /** Opens the appearance dialog for a layer and calls back with the edited style. */
    private BiConsumer<TypeStyle, Consumer<TypeStyle>> styleEditor;
    /** Runs a full-store type discovery scan (Editor only). */
    private Runnable discoverHandler;

    /** Highlight colour for the drag-reorder drop indicator. */
    private static final String DROP_INDICATOR = "#2196f3";
    /** Source row index during a drag-reorder, or -1 when not dragging. */
    private int dragFromIndex = -1;

    @Inject
    public FloorMapLayersPresenter(final EventBus eventBus,
                                   final FloorMapLayersView view) {
        super(eventBus, view);
        list.addStyleName("floormap-layers-list");
        view.setList(list);
    }

    /**
     * @param editorMode {@code true} on the Editor tab (enables authoring
     *                   controls in later increments); {@code false} on the Map tab
     */
    public void setEditorMode(final boolean editorMode) {
        this.editorMode = editorMode;
    }

    /**
     * @param changeHandler run whenever a layer's visibility changes, so the host
     *                      can push {@link #getHiddenTypes()}/{@link #getDimmedTypes()}
     *                      to the canvas
     */
    public void setChangeHandler(final Runnable changeHandler) {
        this.changeHandler = changeHandler;
    }

    /**
     * @param handler receives the new ordered type-styles list whenever the
     *                panel reorders layers (Editor only), so the host can apply
     *                and persist the change. The list order is the z-order.
     */
    public void setTypeStylesEditHandler(final Consumer<List<TypeStyle>> handler) {
        this.typeStylesEditHandler = handler;
    }

    /**
     * @param styleEditor opens the appearance dialog for a layer's {@link TypeStyle}
     *                    and invokes the callback with the edited style (Editor only)
     */
    public void setStyleEditor(final BiConsumer<TypeStyle, Consumer<TypeStyle>> styleEditor) {
        this.styleEditor = styleEditor;
    }

    /**
     * @param discoverHandler runs a full facts-store type-discovery scan; when
     *                        set (Editor only) a Discover action is shown
     */
    public void setDiscoverHandler(final Runnable discoverHandler) {
        this.discoverHandler = discoverHandler;
        rebuild();
    }

    /**
     * Merges the given discovered types into the saved layers (new types are
     * appended alphabetically) and persists via the edit handler.
     *
     * @param types the discovered type names
     */
    public void mergeDiscovered(final Collection<String> types) {
        final int before = layers.size();
        final List<TypeStyle> newList = TypeStyle.merge(new ArrayList<>(layers), types);
        if (newList.size() == before) {
            return;
        }
        layers = newList;
        rebuild();
        if (typeStylesEditHandler != null) {
            typeStylesEditHandler.accept(new ArrayList<>(newList));
        }
    }

    /**
     * Sets the types observed in the currently-loaded data. Any that aren't yet
     * a saved layer are shown (Editor only) as provisional rows the user can add.
     *
     * @param types the observed types; {@code null} treated as empty
     */
    public void setSeenTypes(final Set<String> types) {
        final Set<String> next = types != null
                ? types
                : Collections.emptySet();
        if (!next.equals(seenTypes)) {
            seenTypes.clear();
            seenTypes.addAll(next);
            rebuild();
        }
    }

    /**
     * Sets the ordered layers from the document's type styles. Existing per-type
     * visibility is preserved; newly-appearing types default to fully visible,
     * and state for types no longer present is dropped.
     *
     * @param typeStyles the ordered type styles; {@code null} treated as empty
     */
    public void setLayers(final List<TypeStyle> typeStyles) {
        layers = typeStyles != null
                ? typeStyles
                : new ArrayList<>();

        final Map<String, Integer> next = new LinkedHashMap<>();
        final Set<String> present = new HashSet<>();
        for (final TypeStyle ts : layers) {
            final String type = ts.getType();
            if (type != null) {
                next.put(type, visibilityByType.getOrDefault(type, FULL));
                present.add(type);
            }
        }
        visibilityByType.clear();
        visibilityByType.putAll(next);
        lockedTypes.retainAll(present);
        rebuild();
    }

    /**
     * @return types that are currently hidden (not drawn or hit-tested)
     */
    public Set<String> getHiddenTypes() {
        return typesInState(OFF);
    }

    /**
     * @return types that are currently dimmed to 30% opacity
     */
    public Set<String> getDimmedTypes() {
        return typesInState(DIM);
    }

    /**
     * @return types currently locked against movement (Editor only)
     */
    public Set<String> getLockedTypes() {
        return new HashSet<>(lockedTypes);
    }

    private Set<String> typesInState(final int state) {
        final Set<String> result = new HashSet<>();
        for (final Map.Entry<String, Integer> entry : visibilityByType.entrySet()) {
            if (entry.getValue() == state) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private void rebuild() {
        list.clear();
        final Set<String> saved = new HashSet<>();
        for (int i = 0; i < layers.size(); i++) {
            final TypeStyle ts = layers.get(i);
            if (ts.getType() != null) {
                saved.add(ts.getType());
                list.add(buildRow(ts, i));
            }
        }

        // Editor-only: types seen in the data but not yet saved appear as
        // provisional rows the user can add as layers.
        if (editorMode) {
            for (final String type : new TreeSet<>(seenTypes)) {
                if (!saved.contains(type)) {
                    list.add(buildProvisionalRow(type));
                }
            }

            // A Discover action that scans the whole facts store for types.
            if (discoverHandler != null) {
                list.add(buildDiscoverFooter());
            }
        }
    }

    private Widget buildDiscoverFooter() {
        final FlowPanel footer = new FlowPanel();
        footer.addStyleName("floormap-layers-footer");

        final InlineSvgButton discover = new InlineSvgButton();
        discover.setSvg(SvgImage.REFRESH);
        discover.setTitle("Discover types from the facts store");
        discover.addClickHandler(event -> discoverHandler.run());
        footer.add(discover);

        final Label label = new Label("Discover types");
        label.addStyleName("floormap-layers-footer-label");
        label.addClickHandler(event -> discoverHandler.run());
        footer.add(label);
        return footer;
    }

    /** A discovered-but-unsaved type: name + an add button to make it a layer. */
    private Widget buildProvisionalRow(final String type) {
        final FlowPanel row = new FlowPanel();
        row.addStyleName("floormap-layer-row");
        row.addStyleName("floormap-layer-row--provisional");

        final Label name = new Label(type);
        name.addStyleName("floormap-layer-name");
        name.setTitle("Discovered type — not yet a saved layer");
        row.add(name);

        final InlineSvgButton add = new InlineSvgButton();
        add.addStyleName("floormap-layer-add");
        add.setSvg(SvgImage.ADD);
        add.setTitle("Add this discovered type as a layer");
        add.addClickHandler(event -> promote(type));
        row.add(add);
        return row;
    }

    /** Promotes a discovered type to a saved layer and persists via the handler. */
    private void promote(final String type) {
        final List<TypeStyle> newList = TypeStyle.merge(
                new ArrayList<>(layers), Collections.singletonList(type));
        layers = newList;
        rebuild();
        if (typeStylesEditHandler != null) {
            typeStylesEditHandler.accept(new ArrayList<>(newList));
        }
    }

    /** Replaces the style for {@code edited}'s type and persists via the handler. */
    private void applyStyle(final TypeStyle edited) {
        final List<TypeStyle> newList = new ArrayList<>(layers);
        for (int k = 0; k < newList.size(); k++) {
            if (Objects.equals(newList.get(k).getType(), edited.getType())) {
                newList.set(k, edited);
                break;
            }
        }
        layers = newList;
        rebuild();
        if (typeStylesEditHandler != null) {
            typeStylesEditHandler.accept(new ArrayList<>(newList));
        }
    }

    /**
     * Moves the layer from index {@code from} to the given insertion index
     * (0..size, in current-list terms), then notifies the edit handler with the
     * new order (Editor only). List order is z-order. {@code insertionIndex ==
     * size} drops at the very bottom.
     */
    private void reorderTo(final int from, final int insertionIndex) {
        if (from < 0 || from >= layers.size()) {
            return;
        }
        final List<TypeStyle> newList = new ArrayList<>(layers);
        final TypeStyle moved = newList.remove(from);
        // Removing the dragged item shifts everything after it down by one.
        int insert = from < insertionIndex
                ? insertionIndex - 1
                : insertionIndex;
        insert = Math.max(0, Math.min(insert, newList.size()));
        newList.add(insert, moved);
        if (newList.equals(layers)) {
            // Dropped back in the same place — nothing changed.
            return;
        }
        layers = newList;
        rebuild();
        if (typeStylesEditHandler != null) {
            typeStylesEditHandler.accept(new ArrayList<>(newList));
        }
    }

    /** True if the pointer is below the vertical midpoint of the given row. */
    private static boolean isBelowMidpoint(final int clientY, final Element rowEl) {
        return clientY > rowEl.getAbsoluteTop() + (rowEl.getOffsetHeight() / 2);
    }

    private Widget buildRow(final TypeStyle ts, final int index) {
        final String type = ts.getType();
        final FlowPanel row = new FlowPanel();
        row.addStyleName("floormap-layer-row");

        // Editor-only: drag a row to reorder the layers (z-order). List order is
        // the z-order — earlier = painted behind, later = painted in front.
        if (editorMode) {
            // Visible drag handle so the row reads as grabbable.
            final Label grip = new Label();
            grip.addStyleName("floormap-layer-grip");
            grip.setTitle("Drag to reorder");
            row.add(grip);

            row.getElement().setDraggable(Element.DRAGGABLE_TRUE);
            row.addDomHandler(event -> {
                dragFromIndex = index;
                event.setData("text", String.valueOf(index));
                row.getElement().getStyle().setOpacity(0.4);
            }, DragStartEvent.getType());
            row.addDomHandler(event -> {
                event.preventDefault();
                event.getNativeEvent().getDataTransfer().setDropEffect(DropEffect.MOVE);
                // Indicate drop-above vs drop-below so the last position is reachable.
                final boolean below = isBelowMidpoint(
                        event.getNativeEvent().getClientY(), row.getElement());
                row.getElement().getStyle().setProperty("boxShadow", below
                        ? "inset 0 -2px 0 0 " + DROP_INDICATOR
                        : "inset 0 2px 0 0 " + DROP_INDICATOR);
            }, DragOverEvent.getType());
            row.addDomHandler(event ->
                    row.getElement().getStyle().clearProperty("boxShadow"), DragLeaveEvent.getType());
            row.addDomHandler(event -> {
                event.preventDefault();
                row.getElement().getStyle().clearProperty("boxShadow");
                final boolean below = isBelowMidpoint(
                        event.getNativeEvent().getClientY(), row.getElement());
                reorderTo(dragFromIndex, index + (below ? 1 : 0));
            }, DropEvent.getType());
            row.addDomHandler(event -> {
                row.getElement().getStyle().clearOpacity();
                dragFromIndex = -1;
            }, DragEndEvent.getType());
        }

        final int state = visibilityByType.getOrDefault(type, FULL);

        final Label name = new Label(type);
        name.addStyleName("floormap-layer-name");
        applyNameState(name, state);

        final InlineSvgButton eye = new InlineSvgButton();
        eye.addStyleName("floormap-layer-eye");
        applyEyeState(eye, state);
        eye.addClickHandler(event -> {
            // Cycle full → 30% → off → full.
            final int next = (visibilityByType.getOrDefault(type, FULL) + 2) % 3;
            visibilityByType.put(type, next);
            applyEyeState(eye, next);
            applyNameState(name, next);
            if (changeHandler != null) {
                changeHandler.run();
            }
        });

        row.add(eye);

        // Editor-only: a lock toggle. Locked layers stay visible but their items
        // can't be moved on the canvas. Transient (per session).
        if (editorMode) {
            final InlineSvgButton lock = new InlineSvgButton();
            lock.addStyleName("floormap-layer-lock");
            applyLockState(lock, lockedTypes.contains(type));
            lock.addClickHandler(event -> {
                final boolean nowLocked = !lockedTypes.contains(type);
                if (nowLocked) {
                    lockedTypes.add(type);
                } else {
                    lockedTypes.remove(type);
                }
                applyLockState(lock, nowLocked);
                if (changeHandler != null) {
                    changeHandler.run();
                }
            });
            row.add(lock);
        }

        row.add(name);

        // Editor-only: a swatch previewing the layer's shape in its colour,
        // which opens the appearance dialog (shape + colour) for this layer.
        if (editorMode) {
            final HTML swatch = new HTML(shapeSwatchHtml(ts.getShape(), ts.getColour()));
            swatch.addStyleName("floormap-layer-swatch");
            swatch.setTitle("Edit appearance (shape & colour)");
            swatch.addDomHandler(event -> {
                if (styleEditor != null) {
                    styleEditor.accept(ts, this::applyStyle);
                }
            }, ClickEvent.getType());
            row.add(swatch);
        }

        return row;
    }

    /** Fallback swatch fill when a layer has no configured colour. */
    private static final String DEFAULT_SWATCH_COLOUR = "#90a4ae";

    /** A 16×16 inline-SVG preview of the shape filled with the layer's colour. */
    private SafeHtml shapeSwatchHtml(final Shape shape, final String colour) {
        final String fill = isValidColour(colour)
                ? colour
                : DEFAULT_SWATCH_COLOUR;
        return SafeHtmlUtils.fromTrustedString(
                "<svg width=\"16\" height=\"16\" viewBox=\"0 0 16 16\" "
                + "xmlns=\"http://www.w3.org/2000/svg\">"
                + shapeSvg(shape, fill)
                + "</svg>");
    }

    private static boolean isValidColour(final String colour) {
        return colour != null && colour.matches("^#[0-9a-fA-F]{3,8}$");
    }

    private static String shapeSvg(final Shape shape, final String fill) {
        if (shape == null) {
            // The default graphic for imageless facts is a rectangle.
            return "<rect x=\"2.5\" y=\"4.5\" width=\"11\" height=\"7\" rx=\"1.5\" fill=\"" + fill + "\"/>";
        }
        //noinspection EnhancedSwitchMigration
        switch (shape) {
            case SQUARE:
                return "<rect x=\"3\" y=\"3\" width=\"10\" height=\"10\" rx=\"1\" fill=\"" + fill + "\"/>";
            case TRIANGLE:
                return "<polygon points=\"8,2.5 13.5,13 2.5,13\" fill=\"" + fill + "\"/>";
            case DIAMOND:
                return "<polygon points=\"8,2 14,8 8,14 2,8\" fill=\"" + fill + "\"/>";
            case PIN:
                return "<path d=\"M8 14 C4 9 4.2 3.5 8 3.5 C11.8 3.5 12 9 8 14 Z\" fill=\"" + fill + "\"/>"
                        + "<circle cx=\"8\" cy=\"6.3\" r=\"1.6\" fill=\"#ffffff\"/>";
            case CIRCLE:
            default:
                return "<circle cx=\"8\" cy=\"8\" r=\"5\" fill=\"" + fill + "\"/>";
        }
    }

    private void applyLockState(final InlineSvgButton lock, final boolean locked) {
        lock.setSvg(locked
                ? SvgImage.LOCKED_DEFAULT_COLOUR
                : SvgImage.UNLOCKED_DEFAULT_COLOUR);
        lock.setTitle(locked
                ? "Locked — items can’t be moved (click to unlock)"
                : "Unlocked — items can be moved (click to lock)");
        lock.getElement().getStyle().setOpacity(locked ? 0.95 : 0.5);
    }

    private void applyEyeState(final InlineSvgButton eye, final int state) {
        switch (state) {
            case OFF:
                eye.setSvg(SvgImage.EYE_OFF);
                eye.setTitle("Hidden — click to show");
                eye.getElement().getStyle().setOpacity(0.6);
                break;
            case DIM:
                eye.setSvg(SvgImage.EYE);
                eye.setTitle("Dimmed to 30% — click to hide");
                eye.getElement().getStyle().setOpacity(0.45);
                break;
            default:
                eye.setSvg(SvgImage.EYE);
                eye.setTitle("Visible — click to dim to 30%");
                eye.getElement().getStyle().setOpacity(1.0);
                break;
        }
    }

    private void applyNameState(final Label name, final int state) {
        final double opacity = state == OFF
                ? 0.55
                : state == DIM
                        ? 0.75
                        : 1.0;
        name.getElement().getStyle().setOpacity(opacity);
    }

    /**
     * View contract: a toolbar area (header/actions) above a scrolling list.
     */
    public interface FloorMapLayersView extends View {

        void setList(Widget listWidget);
    }
}
