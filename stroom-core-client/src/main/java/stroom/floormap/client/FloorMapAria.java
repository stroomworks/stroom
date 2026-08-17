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

package stroom.floormap.client;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.UIObject;

/**
 * ARIA attribute helpers for the floor map's views.
 *
 * <p>Exists because the floor map is unusually short of things a
 * {@code <label for>} can point at. A {@code for} attribute may only reference a
 * <em>labelable</em> element — an {@code input}, {@code select}, {@code textarea}
 * or {@code button} — and much of what this feature puts in a form row is not
 * one of those:</p>
 * <ul>
 *   <li>{@code DateTimeBox} and {@code SelectionBox} are composites whose root is
 *       a wrapper {@code div} with the real input nested inside, so
 *       {@code FormGroup.setIdentity()} lands the id on the wrapper and the
 *       association silently fails.</li>
 *   <li>Store pickers arrive as an injected view dropped into a
 *       {@code SimplePanel}, so the row has no control of its own to name.</li>
 *   <li>Rows like <em>Position (X, Y)</em> hold two inputs under one visible
 *       label, which cannot name both.</li>
 * </ul>
 *
 * <p>{@link #label(UIObject, String)} is the fix where the target really is an
 * input; {@link #group(UIObject, String)} is the fix for the composite and
 * multi-control cases, where the honest description of the row is "a named group
 * of controls" rather than "one named control".</p>
 *
 * <p>Prefer {@code FormGroup.setIdentity()} to anything here when the row holds a
 * single plain input: a real {@code <label for>} also makes the visible label
 * click-to-focus, which {@code aria-label} does not.</p>
 */
public final class FloorMapAria {

    private static final String ARIA_LABEL = "aria-label";
    private static final String ARIA_LABELLEDBY = "aria-labelledby";
    private static final String ARIA_DESCRIBEDBY = "aria-describedby";
    private static final String ARIA_HIDDEN = "aria-hidden";
    private static final String ROLE = "role";

    /**
     * Tag names that can take keyboard focus without an explicit tabindex. Used
     * by {@link #focusFirstFocusable(Element)} to find a real control to focus
     * inside a container.
     */
    private static final String[] FOCUSABLE_TAGS = {"input", "select", "textarea", "button", "a"};

    private FloorMapAria() {
        // Utility class.
    }

    /**
     * Names a single control. Use only where the element really is the control —
     * on a wrapper {@code div} an {@code aria-label} is ignored, because an
     * element with no role has nothing to attach a name to. Use
     * {@link #group(UIObject, String)} there instead.
     */
    public static void label(final UIObject uiObject, final String label) {
        if (uiObject != null) {
            uiObject.getElement().setAttribute(ARIA_LABEL, label);
        }
    }

    /** As {@link #label(UIObject, String)}, for a raw element. */
    public static void label(final Element element, final String label) {
        if (element != null) {
            element.setAttribute(ARIA_LABEL, label);
        }
    }

    /**
     * Names the real control nested inside a composite widget, rather than the
     * widget's wrapper element.
     *
     * <p>Use for a composite that wraps exactly one control — {@code CustomCheckBox},
     * whose root is a {@code div.SimpleTickBox} with the {@code <input>} inside it.
     * {@link #label(UIObject, String)} on such a widget lands the name on the wrapper,
     * where it is dropped; {@link #group(UIObject, String)} would work but describes a
     * single checkbox as a group of controls, which is a worse reading than naming the
     * checkbox itself.</p>
     *
     * <p>Prefer this to {@code group()} when there is one control, and {@code group()}
     * when there are several or none.</p>
     *
     * @return {@code true} if an inner control was found and named. A {@code false}
     *         return means the widget's shape is not what the caller assumed, and the
     *         control is still anonymous — worth asserting on rather than ignoring.
     */
    public static boolean labelInnerControl(final UIObject uiObject, final String label) {
        if (uiObject == null) {
            return false;
        }
        for (final String tag : FOCUSABLE_TAGS) {
            final NodeList<Element> candidates = uiObject.getElement().getElementsByTagName(tag);
            if (candidates.getLength() > 0) {
                candidates.getItem(0).setAttribute(ARIA_LABEL, label);
                return true;
            }
        }
        return false;
    }

    /**
     * Marks {@code uiObject} as a named group of controls.
     *
     * <p>The explicit {@code role="group"} is the load-bearing half: without it
     * the name has nothing to attach to and is dropped. With it, a screen reader
     * announces the group on entry and then each control inside, which is the
     * best available reading of a row like <em>Position (X, Y)</em> — or of a
     * composite widget whose inner input a {@code <label for>} cannot reach.</p>
     */
    public static void group(final UIObject uiObject, final String label) {
        if (uiObject != null) {
            uiObject.getElement().setAttribute(ROLE, "group");
            uiObject.getElement().setAttribute(ARIA_LABEL, label);
        }
    }

    /** Points {@code uiObject}'s accessible name at the element with {@code id}. */
    public static void labelledBy(final UIObject uiObject, final String id) {
        if (uiObject != null) {
            uiObject.getElement().setAttribute(ARIA_LABELLEDBY, id);
        }
    }

    /**
     * Names {@code target} after the text already sitting in a {@link Grid} cell.
     *
     * <p>Several of the floor map's dialogs lay out label/control pairs as a
     * two-column {@code Grid}, which puts the label text in a {@code <td>}. A
     * {@code <td>} is not a {@code <label>}, so that text names nothing —
     * visually it reads as a form, but to a screen reader the controls are
     * anonymous.</p>
     *
     * <p>This wires the two together via {@code aria-labelledby} rather than by
     * swapping the cell for a real {@code <label for>}, because {@code aria-labelledby}
     * leaves the existing markup and CSS ({@code td:first-child} alignment and
     * colour rules) untouched. The trade-off is that clicking the text does not
     * focus the control the way a real {@code <label>} would.</p>
     *
     * @param grid   the grid holding the label text
     * @param row    the label cell's row
     * @param column the label cell's column
     * @param target the control to name
     */
    public static void labelledByCell(final Grid grid,
                                      final int row,
                                      final int column,
                                      final UIObject target) {
        if (grid == null || target == null) {
            return;
        }
        final Element cell = grid.getCellFormatter().getElement(row, column);
        // Reuse an id if the cell already has one — labelling two controls from
        // the same cell (a value box and its unit list, say) must not renumber it.
        String id = cell.getId();
        if (id == null || id.isEmpty()) {
            id = uniqueId("floormap-label");
            cell.setId(id);
        }
        labelledBy(target, id);
    }

    /** Points {@code element}'s accessible description at the element with {@code id}. */
    public static void describedBy(final Element element, final String id) {
        if (element != null) {
            element.setAttribute(ARIA_DESCRIBEDBY, id);
        }
    }

    /**
     * Hides {@code uiObject} from assistive technology while leaving it visible.
     *
     * <p>Only correct when the content is genuinely redundant — decoration, or
     * text already carried by an adjacent control's accessible name. Hiding
     * anything else simply deletes it for screen-reader users.</p>
     */
    public static void hide(final UIObject uiObject) {
        if (uiObject != null) {
            uiObject.getElement().setAttribute(ARIA_HIDDEN, "true");
        }
    }

    /** A document-unique id, for wiring up {@code for} / {@code aria-labelledby}. */
    public static String uniqueId(final String prefix) {
        return prefix + "-" + DOM.createUniqueId();
    }

    /**
     * Focuses the first natively-focusable descendant of {@code container}, and
     * reports whether it found one.
     *
     * <p>Calling {@code focus()} on a plain {@code div} is a silent no-op — the
     * element is not focusable, so the browser simply leaves focus where it was.
     * A dialog that "focuses" a wrapper panel therefore opens with focus still
     * back on whatever the user was last on, which is the failure this
     * exists to avoid.</p>
     *
     * @return {@code true} if something was focused
     */
    public static boolean focusFirstFocusable(final Element container) {
        if (container == null) {
            return false;
        }
        for (final String tag : FOCUSABLE_TAGS) {
            final NodeList<Element> candidates = container.getElementsByTagName(tag);
            for (int i = 0; i < candidates.getLength(); i++) {
                final Element candidate = candidates.getItem(i);
                // A disabled or explicitly-removed control is not a focus target;
                // focusing it would be another silent no-op.
                if (!candidate.hasAttribute("disabled")
                        && !"-1".equals(candidate.getAttribute("tabindex"))) {
                    candidate.focus();
                    return true;
                }
            }
        }
        return false;
    }
}
