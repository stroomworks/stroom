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

package stroom.pathways.client.presenter;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.ui.HTML;

import java.util.function.Supplier;

/**
 * How a drawing is looked at: how far it is scaled, where it is scrolled to, and moving it by hand.
 *
 * <p>Kept apart from what is drawn because the two have nothing to say to each other, and because
 * holding them together hid the ways they interfere. Scaling has to happen before the scroll is put
 * back, or the scroll lands outside a box that has not grown yet; putting the scroll back has to be
 * told apart from centring, or the two fight; and both have to wait for the browser to lay the
 * drawing out, which it does after the turn the drawing was made in. None of that was said anywhere
 * — it was a property of the order some lines happened to be written in.
 *
 * <p>Nothing here knows what a pathway is. It is told when a drawing is about to be replaced and when
 * it has been, and it is handed a way of finding the thing to centre on.
 */
class PathwayViewport {

    private static final double ZOOM_STEP = 1.25;
    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 3;
    private static final String DRAGGING_CLASS = "pathway-dragging";
    // How far the pointer has to move before it counts as a drag rather than a click that wandered.
    private static final int DRAG_THRESHOLD = 3;

    private final HTML panel;

    private double zoom = 1;
    private int scrollLeft;
    private int scrollTop;
    // Centring is held as something wanted rather than done, because a drawing is replaced more than
    // once as a pathway opens and the request has to outlive the drawing it was made for.
    private boolean centreWanted;
    private boolean dragging;
    private boolean dragged;
    private int dragX;
    private int dragY;

    PathwayViewport(final HTML panel) {
        this.panel = panel;
    }

    /**
     * Back to the size it is drawn at. For a different model, which may be a different size
     * altogether: the scale set for the last one says nothing about this one.
     */
    void resetZoom() {
        zoom = 1;
    }

    void zoomIn() {
        zoomBy(ZOOM_STEP);
    }

    void zoomOut() {
        zoomBy(1 / ZOOM_STEP);
    }

    /**
     * Remembers where the drawing is scrolled to, before it is replaced by another.
     */
    void beforeDraw() {
        final Element scroller = scroller();
        scrollLeft = scroller == null
                ? 0
                : scroller.getScrollLeft();
        scrollTop = scroller == null
                ? 0
                : scroller.getScrollTop();
    }

    /**
     * @param keepScroll whether the drawing just made is the same model as the one it replaced, in
     *                   which case the reader is left where they were rather than moved.
     * @param centred    whether this drawing opens in the middle rather than at its top left.
     * @param centreOn   what to put in the middle, asked for only once there is something to measure.
     */
    void afterDraw(final boolean keepScroll, final boolean centred, final Supplier<Element> centreOn) {
        // Before the scroll is touched: the scrollbars only reach the scaled size once the box
        // carrying it has been resized.
        applyZoom();

        final Element scroller = scroller();
        if (scroller != null) {
            if (keepScroll) {
                scroller.setScrollLeft(scrollLeft);
                scroller.setScrollTop(scrollTop);
            } else if (centred) {
                centreWanted = true;
            } else {
                scroller.setScrollLeft(0);
                scroller.setScrollTop(0);
            }
        }
        if (centreWanted) {
            Scheduler.get().scheduleDeferred(() -> centre(centreOn));
        }
    }

    boolean isDragging() {
        return dragging;
    }

    void startDrag(final int clientX, final int clientY) {
        dragX = clientX;
        dragY = clientY;
        dragging = true;
        dragged = false;
        Event.setCapture(panel.getElement());
        panel.addStyleName(DRAGGING_CLASS);
    }

    void drag(final int clientX, final int clientY) {
        final Element scroller = scroller();
        if (!dragging || scroller == null) {
            return;
        }

        // Dragging moves the drawing with the pointer, so the view moves the opposite way.
        final int dx = dragX - clientX;
        final int dy = dragY - clientY;
        if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
            dragged = true;
        }
        scroller.setScrollLeft(scroller.getScrollLeft() + dx);
        scroller.setScrollTop(scroller.getScrollTop() + dy);
        dragX = clientX;
        dragY = clientY;
    }

    void endDrag() {
        if (dragging) {
            dragging = false;
            Event.releaseCapture(panel.getElement());
            panel.removeStyleName(DRAGGING_CLASS);
        }
    }

    /**
     * Whether the gesture that just ended moved the drawing, in which case the click it ends in is
     * not a click on anything. Answers once: asking clears it.
     */
    boolean wasDragged() {
        final boolean was = dragged;
        dragged = false;
        return was;
    }

    private void zoomBy(final double by) {
        final double was = zoom;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * by));

        // Whatever was in the middle of the view stays in the middle of it. Scaling moves everything
        // away from the drawing's top left corner, so without this the picture slides out from under
        // the reader towards the corner they are not looking at.
        final Element scroller = scroller();
        if (scroller == null || was <= 0) {
            applyZoom();
            return;
        }

        // Where the middle of the view falls on the drawing, at the size it is drawn rather than the
        // size it is shown at.
        final double middleX = (scroller.getScrollLeft() + (scroller.getClientWidth() / 2.0)) / was;
        final double middleY = (scroller.getScrollTop() + (scroller.getClientHeight() / 2.0)) / was;

        applyZoom();
        scroller.setScrollLeft((int) Math.round((middleX * zoom) - (scroller.getClientWidth() / 2.0)));
        scroller.setScrollTop((int) Math.round((middleY * zoom) - (scroller.getClientHeight() / 2.0)));
    }

    private void applyZoom() {
        final Element canvas = canvas();
        if (canvas == null) {
            return;
        }

        // Read off the canvas rather than remembered, because scaling does not change what a thing
        // measures and this stays the drawing's own size however far it has been zoomed.
        final int width = canvas.getOffsetWidth();
        final int height = canvas.getOffsetHeight();
        if (width <= 0 || height <= 0) {
            // Nothing laid out to measure yet. Sizing the box to nothing would leave the drawing with
            // nowhere to scroll, so this waits and asks again rather than settling on zero.
            Scheduler.get().scheduleDeferred(this::applyZoom);
            return;
        }
        canvas.getStyle().setProperty("transform", "scale(" + zoom + ")");
        canvas.getParentElement().getStyle().setWidth(width * zoom, Unit.PX);
        canvas.getParentElement().getStyle().setHeight(height * zoom, Unit.PX);
    }

    private void centre(final Supplier<Element> centreOn) {
        final Element scroller = scroller();
        if (scroller == null || scroller.getClientWidth() <= 0) {
            // Nothing laid out to measure against yet. Still wanted, so the next draw tries again.
            return;
        }

        // On the thing asked for rather than on the middle of the drawing. The drawing is only as
        // large as what was placed in it, and a model that reaches further one way than another does
        // not put its root in the middle of that.
        final Element on = centreOn == null
                ? null
                : centreOn.get();
        if (on == null) {
            scroller.setScrollLeft((scroller.getScrollWidth() - scroller.getClientWidth()) / 2);
            scroller.setScrollTop((scroller.getScrollHeight() - scroller.getClientHeight()) / 2);
        } else {
            // Scaled, because what an element measures is what it was drawn at rather than what it is
            // shown at.
            final double x = (on.getOffsetLeft() + (on.getOffsetWidth() / 2.0)) * zoom;
            final double y = (on.getOffsetTop() + (on.getOffsetHeight() / 2.0)) * zoom;
            scroller.setScrollLeft((int) Math.round(x - (scroller.getClientWidth() / 2.0)));
            scroller.setScrollTop((int) Math.round(y - (scroller.getClientHeight() / 2.0)));
        }
        centreWanted = false;
    }

    // What scrolls is the drawing's own outer element, which the renderer promises is the only one.
    private Element scroller() {
        return panel.getElement().getFirstChildElement();
    }

    // What is scaled sits inside the box that carries the scaled size.
    private Element canvas() {
        final Element scroller = scroller();
        final Element sizer = scroller == null
                ? null
                : scroller.getFirstChildElement();
        return sizer == null
                ? null
                : sizer.getFirstChildElement();
    }
}
