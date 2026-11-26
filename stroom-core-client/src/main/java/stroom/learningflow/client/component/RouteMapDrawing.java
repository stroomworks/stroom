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
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.Transition;
import stroom.util.shared.NullSafe;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Builds the SVG markup for the Setup tab's route map ([RouteMap]).
///
/// The stations are drawn down one line, sections as squares and steps as circles. Each station is a button
/// whose [#DATA_STOP] attribute holds its stop's key ([SetupStop]). Rules that go back, skip ahead or finish are
/// drawn as arcs. Under each name are lines with a section's summary or a step's rules, followed by any problem.
/// Under the chosen step there may be a button that adds a step after it ([#DATA_ADD]). This is a plain class so
/// that it can be unit tested without a browser.
final class RouteMapDrawing {

    /// The attribute that holds a station's stop key ([SetupStop#key()]).
    static final String DATA_STOP = "data-stop";
    /// The attribute on the Add step button that holds the index in the flow at which a new step is inserted.
    static final String DATA_ADD = "data-add";
    /// The label of the fallback step on the map.
    static final String FALLBACK = "Fallback: marked Unknowns";
    /// The label of the station at the end of the steps, reached when a step finishes the flow.
    static final String LEARNED = "Translation learned";
    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    // Every station is laid out as a box with PAD above its name and below its last line, its mark beside its
    // name, and GAP before the next box. The line runs through the marks, and the arcs are drawn to its left.
    private static final int WIDTH = 360;
    private static final int LINE_X = 46;
    private static final int LABEL_X = LINE_X + 20;
    private static final int AREA_X = LINE_X - 16;
    // Space for the focus ring, which is drawn on the edge of a station's box
    private static final int EDGE = 2;
    private static final int PAD = 8;
    private static final int GAP = 4;
    // The distance from the top of a station's text to its name's baseline, and from there up to its mark's
    // centre
    private static final int LABEL_BASELINE = 12;
    private static final int MARK_RAISE = 4;
    // The distance from the name's baseline to the bottom of its descenders
    private static final int DESCENT = 5;
    private static final int SUB_FIRST = 16;
    private static final int SUB_PITCH = 15;
    private static final int SECTION_HALF = 8;
    private static final int STEP_RADIUS = 7;
    // The size of the Add step button; the width fits its text
    private static final int ADD_HEIGHT = 24;
    private static final int ADD_WIDTH = 86;
    private static final int BACK_X = LINE_X - 30;
    private static final int SKIP_X = LINE_X - 50;
    /// The number of characters that fit on one of the smaller lines of text under a station's name.
    private static final int SUB_CHARS = 50;

    private RouteMapDrawing() {
        // Static utility
    }

    /// Draws the map as SVG markup.
    ///
    /// @param steps        The learning flow's steps.
    /// @param summary      Each section's summary and problem; null before a document has been read.
    /// @param stepProblems The problem with each step, by index; a step without a problem has no entry.
    /// @param selected     The chosen stop.
    /// @param canAdd       Whether to show a button under the chosen step for adding a step after it. The
    ///                     button is never shown under the fallback or a section, whatever this says.
    /// @return The markup, with all text in it HTML-escaped.
    static String draw(final List<FlowStep> steps,
                       final SetupSummary summary,
                       final Map<Integer, String> stepProblems,
                       final SetupStop selected,
                       final boolean canAdd) {
        final List<Station> order = order(steps, summary, stepProblems);
        // Lay out each station's box from top to bottom, leaving room for the Add step button under the chosen
        // step
        Station addUnder = null;
        int addTop = 0;
        int at = EDGE;
        for (final Station station : order) {
            station.top = at;
            station.y = at + PAD + LABEL_BASELINE - MARK_RAISE;
            at += station.height() + GAP;
            if (canAdd && station.kind == Kind.STEP && station.stop.equals(selected)) {
                addUnder = station;
                addTop = at;
                at += ADD_HEIGHT + GAP;
            }
        }
        final int height = at - GAP + EDGE;

        final StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"").append(SVG_NS).append("\" class=\"route-map__svg\" width=\"").append(WIDTH)
                .append("\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(height).append("\">");
        svg.append("<defs aria-hidden=\"true\">")
                .append("<marker id=\"route-map-back\" viewBox=\"0 0 8 8\" refX=\"7\" refY=\"4\" markerWidth=\"7\" ")
                .append("markerHeight=\"7\" orient=\"auto\"><path class=\"route-map__head--back\" ")
                .append("d=\"M0,0 L8,4 L0,8 z\"/></marker>")
                .append("<marker id=\"route-map-skip\" viewBox=\"0 0 8 8\" refX=\"7\" refY=\"4\" markerWidth=\"7\" ")
                .append("markerHeight=\"7\" orient=\"auto\"><path class=\"route-map__head--skip\" ")
                .append("d=\"M0,0 L8,4 L0,8 z\"/></marker>")
                .append("</defs>");

        // Draw the line, dashed into the fallback, since the fallback is reached only by a rule
        svg.append("<g aria-hidden=\"true\">");
        for (int n = 1; n < order.size(); n++) {
            line(svg, order.get(n - 1).y, order.get(n).y, order.get(n).kind == Kind.FALLBACK);
        }
        svg.append("</g>");

        for (final Station station : order) {
            station(svg, station, station.stop != null && station.stop.equals(selected));
        }
        if (addUnder != null) {
            addButton(svg, addUnder.stop.getStep() + 1, addTop);
        }

        // Draw the arcs on top of the stations' boxes, so that the highlighted box of the chosen station
        // doesn't hide where an arc starts and ends
        svg.append("<g class=\"route-map__arcs\" aria-hidden=\"true\">");
        arcs(svg, steps, order);
        svg.append("</g>");
        svg.append("</svg>");
        return svg.toString();
    }

    // Lists every station in order down the line
    private static List<Station> order(final List<FlowStep> steps,
                                       final SetupSummary summary,
                                       final Map<Integer, String> stepProblems) {
        final List<Station> order = new ArrayList<>();
        order.add(section(SetupSection.LEARNING, summary));
        order.add(section(SetupSection.APPROACH, summary));
        Station fallback = null;
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep step = steps.get(i);
            final String problem = stepProblems.get(i);
            if (step.getKind() == QuestionKind.ADMIT) {
                fallback = new Station(Kind.FALLBACK, SetupStop.step(i), FALLBACK, FALLBACK, problem);
            } else {
                final String name = FlowWords.stepName(steps, i);
                final Station station = new Station(Kind.STEP, SetupStop.step(i), (i + 1) + " " + name,
                        "Step " + (i + 1) + ": " + name, problem);
                for (final String rule : ruleLines(steps, i)) {
                    station.lines.addAll(wrap(rule));
                }
                order.add(station);
            }
        }
        if (fallback != null) {
            order.add(fallback);
        }
        order.add(new Station(Kind.LEARNED, null, LEARNED, LEARNED, null));
        order.add(section(SetupSection.QUALITY, summary));
        order.add(section(SetupSection.GOING_LIVE, summary));
        order.add(section(SetupSection.IN_SERVICE, summary));
        return order;
    }

    private static Station section(final SetupSection section, final SetupSummary summary) {
        final String name = section.getDisplayValue();
        final String problem = summary == null
                ? null
                : summary.problem(section);
        final String said = summary == null
                ? ""
                : NullSafe.string(summary.summary(section));
        final Station station = new Station(Kind.SECTION, SetupStop.of(section), name, problem == null
                ? name + ": " + said
                : name + ": needs attention. " + problem, problem);
        if (problem == null && !said.isEmpty()) {
            station.lines.addAll(wrap(said));
        }
        return station;
    }

    // Draws the rules that go back, skip ahead or finish as arcs. A rule that goes on to the next step needs no
    // arc, as the line itself shows it.
    private static void arcs(final StringBuilder svg, final List<FlowStep> steps, final List<Station> order) {
        final int[] y = new int[steps.size() + 1];
        final List<Integer> line = new ArrayList<>();
        for (final Station station : order) {
            if (station.kind == Kind.STEP || station.kind == Kind.FALLBACK) {
                y[station.stop.getStep()] = station.y;
                line.add(station.stop.getStep());
            } else if (station.kind == Kind.LEARNED) {
                y[steps.size()] = station.y;
            }
        }
        for (final int index : line) {
            for (final Transition rule : steps.get(index).getTransitions()) {
                final Integer to = target(steps, rule.getGoTo());
                if (to == null) {
                    continue;
                }
                final boolean back = y[to] < y[index];
                final boolean next = to != steps.size() && line.indexOf(to) == line.indexOf(index) + 1;
                if (!back && next) {
                    continue;
                }
                final int x = back
                        ? BACK_X
                        : SKIP_X;
                svg.append("<path class=\"").append(back
                                ? "route-map__arc route-map__arc--back"
                                : "route-map__arc route-map__arc--skip")
                        .append("\" d=\"M").append(LINE_X - 9).append(',').append(y[index])
                        .append(" C").append(x).append(',').append(y[index]).append(' ')
                        .append(x).append(',').append(y[to]).append(' ')
                        .append(LINE_X - 11).append(',').append(y[to])
                        .append("\" marker-end=\"url(#").append(back
                                ? "route-map-back"
                                : "route-map-skip").append(")\"/>");
            }
        }
    }

    private static void station(final StringBuilder svg, final Station station, final boolean selected) {
        final List<String> problemLines = station.problemLines;
        if (station.stop == null) {
            svg.append("<g>");
        } else {
            svg.append("<g class=\"route-map__station")
                    .append(selected
                            ? " route-map__station--selected"
                            : "")
                    .append("\" ").append(DATA_STOP).append("=\"").append(station.stop.key())
                    .append("\" role=\"button\" tabindex=\"").append(selected
                            ? "0"
                            : "-1")
                    .append("\" aria-label=\"").append(esc(station.problem == null || station.kind == Kind.SECTION
                            ? station.ariaLabel
                            : station.ariaLabel + ". Needs attention: " + station.problem)).append('"')
                    .append(selected
                            ? " aria-current=\"location\""
                            : "")
                    .append('>');
            // The clickable area, which is also highlighted when the station is chosen, hovered or focused
            svg.append("<rect class=\"route-map__area\" x=\"").append(AREA_X)
                    .append("\" y=\"").append(station.top)
                    .append("\" width=\"").append(WIDTH - EDGE - AREA_X)
                    .append("\" height=\"").append(station.height())
                    .append("\" rx=\"6\"/>");
        }
        stop(svg, station);
        final int baseline = station.top + PAD + LABEL_BASELINE;
        svg.append("<text class=\"").append(station.kind.labelClass).append("\" x=\"").append(LABEL_X)
                .append("\" y=\"").append(baseline).append("\">")
                .append(esc(station.label)).append("</text>");
        int subY = baseline + SUB_FIRST;
        for (final String line : station.lines) {
            svg.append("<text class=\"route-map__sub\" x=\"").append(LABEL_X).append("\" y=\"").append(subY)
                    .append("\">").append(esc(line)).append("</text>");
            subY += SUB_PITCH;
        }
        for (int i = 0; i < problemLines.size(); i++) {
            svg.append("<text class=\"route-map__sub route-map__sub--problem\" x=\"").append(LABEL_X)
                    .append("\" y=\"").append(subY).append("\">");
            if (i == 0) {
                svg.append("<tspan class=\"route-map__mark\">! </tspan>");
            }
            svg.append(esc(problemLines.get(i))).append("</text>");
            subY += SUB_PITCH;
        }
        svg.append("</g>");
    }

    // Draws the Add step button under the chosen step. It is a separate button rather than a stop, so the
    // arrow keys skip it and Tab reaches it after the chosen stop.
    private static void addButton(final StringBuilder svg, final int at, final int top) {
        svg.append("<g class=\"route-map__add\" ").append(DATA_ADD).append("=\"").append(at)
                .append("\" role=\"button\" tabindex=\"0\" aria-haspopup=\"menu\" ")
                .append("aria-label=\"Add a step after step ").append(at).append("\">");
        svg.append("<rect class=\"route-map__add-area\" x=\"").append(LABEL_X - 8)
                .append("\" y=\"").append(top)
                .append("\" width=\"").append(ADD_WIDTH)
                .append("\" height=\"").append(ADD_HEIGHT)
                .append("\" rx=\"4\"/>");
        svg.append("<text class=\"route-map__add-label\" x=\"").append(LABEL_X).append("\" y=\"")
                .append(top + 16).append("\">+ Add step</text>");
        svg.append("</g>");
    }

    // Draws a station's mark on the line: a square for a section, and a circle for a step, the fallback or the
    // learned translation. CSS fills the chosen station's mark rather than enlarging it, so that every station
    // keeps the same spacing.
    private static void stop(final StringBuilder svg, final Station station) {
        final String problem = station.problem == null
                ? ""
                : " route-map__stop--problem";
        switch (station.kind) {
            case SECTION -> svg.append("<rect class=\"route-map__stop route-map__stop--section").append(problem)
                    .append("\" x=\"").append(LINE_X - SECTION_HALF)
                    .append("\" y=\"").append(station.y - SECTION_HALF)
                    .append("\" width=\"").append(SECTION_HALF * 2)
                    .append("\" height=\"").append(SECTION_HALF * 2)
                    .append("\" rx=\"3\"/>");
            case LEARNED -> svg.append("<circle class=\"route-map__stop route-map__stop--learned\" cx=\"")
                    .append(LINE_X).append("\" cy=\"").append(station.y).append("\" r=\"").append(STEP_RADIUS)
                    .append("\"/>");
            default -> svg.append("<circle class=\"route-map__stop").append(station.kind == Kind.FALLBACK
                            ? " route-map__stop--fallback"
                            : "").append(problem).append("\" cx=\"").append(LINE_X).append("\" cy=\"")
                    .append(station.y).append("\" r=\"").append(STEP_RADIUS).append("\"/>");
        }
    }

    private static void line(final StringBuilder svg, final int from, final int to, final boolean dashed) {
        svg.append("<line class=\"route-map__line").append(dashed
                        ? " route-map__line--dashed"
                        : "").append("\" x1=\"").append(LINE_X).append("\" y1=\"").append(from)
                .append("\" x2=\"").append(LINE_X).append("\" y2=\"").append(to).append("\"/>");
    }

    // ---- What the rules say ----

    // Gets the station a rule goes to: a step's index, steps.size() for the learned translation, or null for
    // park, give up or an unknown step
    private static Integer target(final List<FlowStep> steps, final String goTo) {
        if (goTo == null || Transition.PARK.equals(goTo) || Transition.GIVE_UP.equals(goTo)) {
            return null;
        }
        if (Transition.END.equals(goTo)) {
            return steps.size();
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).effectiveId().equals(goTo)) {
                return i;
            }
        }
        return null;
    }

    // Describes a step's rules, one short line each, e.g. "Too few or too many records: back to 2"
    private static List<String> ruleLines(final List<FlowStep> steps, final int index) {
        final List<String> lines = new ArrayList<>();
        for (final Transition rule : steps.get(index).getTransitions()) {
            final String when = rule.onSpent()
                    ? "Out of tries"
                    : PlainWords.result(rule.getOn());
            lines.add(when + ": " + where(steps, index, rule));
        }
        return lines;
    }

    private static String where(final List<FlowStep> steps, final int index, final Transition rule) {
        final String goTo = rule.getGoTo();
        if (goTo == null) {
            return FlowWords.usualExit(steps.get(index));
        }
        if (Transition.END.equals(goTo)) {
            return "finish";
        }
        if (Transition.PARK.equals(goTo)) {
            return "pause for a person";
        }
        if (Transition.GIVE_UP.equals(goTo)) {
            return "stop learning";
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).effectiveId().equals(goTo)) {
                if (steps.get(i).getKind() == QuestionKind.ADMIT) {
                    return "fallback";
                }
                return (i < index
                        ? "back to "
                        : "on to ") + (i + 1);
            }
        }
        return "'" + goTo + "', not a step";
    }

    /// Wraps text at spaces into lines short enough to fit under a station's name. A word longer than a line
    /// goes on a line of its own.
    ///
    /// @param text The text.
    /// @return The wrapped lines.
    static List<String> wrap(final String text) {
        final List<String> lines = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        for (final String word : text.trim().split("\\s+")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > SUB_CHARS) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }


    private static String esc(final String text) {
        return SafeHtmlUtils.htmlEscape(text);
    }


    // --------------------------------------------------------------------------------


    /// The kinds of station on the line.
    private enum Kind {
        /// A section of the tab.
        SECTION("route-map__label route-map__label--section"),
        /// A step of the learning flow.
        STEP("route-map__label"),
        /// The fallback step, which is reached only by a rule.
        FALLBACK("route-map__label"),
        /// The learned translation, where the steps end. It cannot be chosen.
        LEARNED("route-map__label route-map__label--learned");

        /// The CSS classes for the station's name.
        private final String labelClass;

        Kind(final String labelClass) {
            this.labelClass = labelClass;
        }
    }


    // --------------------------------------------------------------------------------


    /// A station as it is drawn: its stop, its name, its button's accessible label, the lines under its name,
    /// and its problem, if any, wrapped into further lines beneath those.
    private static final class Station {

        private final Kind kind;
        private final SetupStop stop;
        private final String label;
        private final String ariaLabel;
        private final String problem;
        private final List<String> lines = new ArrayList<>();
        private final List<String> problemLines;
        // The y position of the top of its box, and of the centre of its mark
        private int top;
        private int y;

        private Station(final Kind kind,
                        final SetupStop stop,
                        final String label,
                        final String ariaLabel,
                        final String problem) {
            this.kind = kind;
            this.stop = stop;
            this.label = label;
            this.ariaLabel = ariaLabel;
            this.problem = problem;
            this.problemLines = problem == null
                    ? List.of()
                    : wrap(problem);
        }

        // The height of its box: its name and every line under it, with PAD above and below
        private int height() {
            final int under = lines.size() + problemLines.size();
            final int words = LABEL_BASELINE + DESCENT + (under == 0
                    ? 0
                    : SUB_FIRST + SUB_PITCH * (under - 1));
            return PAD + words + PAD;
        }
    }
}
