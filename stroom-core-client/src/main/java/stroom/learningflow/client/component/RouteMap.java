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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.FlowStep;
import stroom.util.shared.NullSafe;
import stroom.widget.text.client.Html;
import stroom.widget.util.client.ElementUtil;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.ui.Composite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// The route map used to navigate the Setup tab ([SetupTab]). It draws a translation's life as stops down one
/// line; choosing a stop ([SetupStop]) shows that part of the setup beside the map.
///
/// The sections are in the order a translation passes through them. Under Learning steps come the steps of
/// the learning flow, each with a summary of its rules. Rules that go back are drawn as arcs on the left, and
/// rules that skip ahead or finish are drawn dashed. After the steps come the fallback step, if there is one,
/// and the learned translation. Each section shows a summary of its settings, and a section or step with a
/// problem shows the problem.
///
/// Every stop is a button: a click, Enter or Space chooses it ([#setOnSelect]), and the arrow keys, Home and
/// End move the focus between stops. Only the chosen stop is in the tab order. If a step may be added after the
/// chosen step, an Add step button ([#setOnAdd]) appears under it, next in the tab order. The SVG itself is
/// built by [RouteMapDrawing].
public class RouteMap extends Composite {

    private static final String STOP_SELECTOR = "[" + RouteMapDrawing.DATA_STOP + "]";

    private final Html html = new Html();
    private Consumer<SetupStop> onSelect = stop -> {
    };
    private Consumer<Element> onAdd = opener -> {
    };

    /// Makes an empty map.
    public RouteMap() {
        initWidget(html);
        html.addStyleName("route-map");
        html.addClickHandler(event -> {
            final Element target = event.getNativeEvent().getEventTarget().cast();
            final Element add = addButtonOf(target);
            if (add != null) {
                onAdd.accept(add);
                return;
            }
            final Element station = stationOf(target);
            if (station != null) {
                choose(station);
            }
        });
        html.addDomHandler(this::onKeyDown, KeyDownEvent.getType());
    }

    /// @param onSelect Called with the stop the user chooses.
    public void setOnSelect(final Consumer<SetupStop> onSelect) {
        this.onSelect = onSelect;
    }

    /// @param onAdd Called with the Add step button's element when it is pressed, so that the menu of kinds of
    ///              step to add can be opened next to it.
    public void setOnAdd(final Consumer<Element> onAdd) {
        this.onAdd = onAdd;
    }

    /// Draws the map. If a stop had the keyboard focus, the same stop is given the focus again after redrawing.
    ///
    /// @param steps        The learning flow's steps.
    /// @param summary      Each section's summary and problem; null before a document has been read.
    /// @param stepProblems The problem with each step, by index; a step without a problem has no entry.
    /// @param selected     The chosen stop.
    /// @param canAdd       Whether a step may be added after the chosen step.
    public void show(final List<FlowStep> steps,
                     final SetupSummary summary,
                     final Map<Integer, String> stepProblems,
                     final SetupStop selected,
                     final boolean canAdd) {
        final String focused = focusedKey();
        html.setHTML(SafeHtmlUtils.fromTrustedString(
                RouteMapDrawing.draw(steps, summary, stepProblems, selected, canAdd)));
        if (focused != null) {
            final Element station = station(focused);
            if (station != null) {
                station.focus();
            }
        }
    }

    // ---- Choosing ----

    private void onKeyDown(final KeyDownEvent event) {
        final Element target = event.getNativeEvent().getEventTarget().cast();
        final Element add = addButtonOf(target);
        if (add != null) {
            final int key = event.getNativeKeyCode();
            if (key == KeyCodes.KEY_ENTER || key == KeyCodes.KEY_SPACE) {
                event.preventDefault();
                onAdd.accept(add);
            }
            return;
        }
        final Element station = stationOf(target);
        if (station == null) {
            return;
        }
        final List<Element> stations = stations();
        final int at = stations.indexOf(station);
        final Element to;
        switch (event.getNativeKeyCode()) {
            case KeyCodes.KEY_ENTER, KeyCodes.KEY_SPACE -> {
                event.preventDefault();
                choose(station);
                return;
            }
            case KeyCodes.KEY_DOWN -> to = stations.get(Math.min(stations.size() - 1, at + 1));
            case KeyCodes.KEY_UP -> to = stations.get(Math.max(0, at - 1));
            case KeyCodes.KEY_HOME -> to = stations.get(0);
            case KeyCodes.KEY_END -> to = stations.get(stations.size() - 1);
            default -> {
                return;
            }
        }
        event.preventDefault();
        to.focus();
    }

    private void choose(final Element station) {
        final SetupStop stop = SetupStop.parse(station.getAttribute(RouteMapDrawing.DATA_STOP));
        if (stop != null) {
            onSelect.accept(stop);
        }
    }

    private Element addButtonOf(final Element target) {
        if (target == null) {
            return null;
        }
        return ElementUtil.findParent(target,
                element -> NullSafe.isNonBlankString(element.getAttribute(RouteMapDrawing.DATA_ADD)), 4);
    }

    private Element stationOf(final Element target) {
        if (target == null) {
            return null;
        }
        return ElementUtil.findParent(target,
                element -> NullSafe.isNonBlankString(element.getAttribute(RouteMapDrawing.DATA_STOP)), 6);
    }

    private List<Element> stations() {
        final List<Element> stations = new ArrayList<>();
        final NodeList<Element> found = querySelectorAll(html.getElement(), STOP_SELECTOR);
        for (int i = 0; i < found.getLength(); i++) {
            stations.add(found.getItem(i));
        }
        return stations;
    }

    private Element station(final String key) {
        for (final Element station : stations()) {
            if (key.equals(station.getAttribute(RouteMapDrawing.DATA_STOP))) {
                return station;
            }
        }
        return null;
    }

    // Gets the key of the stop that has the keyboard focus, or null if none does
    private String focusedKey() {
        final Element active = activeElement();
        if (active == null || !html.getElement().isOrHasChild(active)) {
            return null;
        }
        final Element station = stationOf(active);
        return station == null
                ? null
                : station.getAttribute(RouteMapDrawing.DATA_STOP);
    }

    private static native Element activeElement() /*-{
        return $doc.activeElement;
    }-*/;

    private static native NodeList<Element> querySelectorAll(Element element, String selector) /*-{
        return element.querySelectorAll(selector);
    }-*/;
}
