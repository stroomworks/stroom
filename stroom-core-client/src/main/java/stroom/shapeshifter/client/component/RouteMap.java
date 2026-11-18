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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.shared.FlowStep;
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

/// The Setup tab's route map, which is how the tab is found around ([SetupTab]): a translation's life down
/// one line, each stop a place the tab shows beside the map ([SetupStop]). The sections come in the order a
/// translation meets them; under Learning steps, the steps of the learning flow, each with what its rules do,
/// rules that go back drawn as arcs on the left, rules that skip ahead or finish dashed, then the fallback,
/// where there is one, and the translation learned. A section says what it is set to, and a section or a
/// step what is wrong in it.
///
/// Every stop is a button: a click or Enter chooses it ([#setOnSelect]), and the arrow keys, Home and End
/// move between them. Only the stop chosen is in the tab order. Under the step chosen, where a step may be
/// added after it, is an Add step button ([#setOnAdd]), next in the tab order. The map is drawn by
/// [RouteMapDrawing].
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

    /// @param onSelect Told the stop chosen.
    public void setOnSelect(final Consumer<SetupStop> onSelect) {
        this.onSelect = onSelect;
    }

    /// @param onAdd Given the Add step button under the step chosen, where the menu of the kinds of step to add
    ///              after it opens.
    public void setOnAdd(final Consumer<Element> onAdd) {
        this.onAdd = onAdd;
    }

    /// Draws the map. Where a stop had the focus, the same stop has it again.
    ///
    /// @param steps        The learning flow's steps.
    /// @param summary      What each section is set to and what is wrong in it; null before a document is read.
    /// @param stepProblems What is wrong with a step, by its index; a step that is fine has none.
    /// @param selected     The stop chosen.
    /// @param canAdd       Whether a step may be added after the step chosen.
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

    // The key of the stop that has the focus, or null where none does
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
