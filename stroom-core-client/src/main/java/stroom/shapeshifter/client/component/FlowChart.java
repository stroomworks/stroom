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

import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowExit;
import stroom.shapeshifter.shared.FlowPath;
import stroom.shapeshifter.shared.FlowPath.Visit;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.shapeshifter.shared.Transition;
import stroom.util.shared.NullSafe;
import stroom.widget.text.client.Html;
import stroom.widget.util.client.ElementUtil;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.FocusEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.ui.Composite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// The learning flow drawn as a flowchart (design 05 §3; A84, A89, A90): the steps down a vertical
/// spine in their order, each followed by the decision that judges its candidates; the defaults the
/// grammar does not write drawn dashed — a pass down to the next step, a shortfall re-asking the step,
/// spent candidates leaving by the step's exit — and the transitions the flow declares drawn solid in
/// lanes, back edges on the left and forward jumps on the right; Start above, End below, and the exits
/// as the connector symbols P and G. Inline SVG from the flow alone: positions follow the order and are
/// never stored. A problem paints the step it names; a recorded attempt's turns draw its path over the
/// flow, the visits numbered and the edges taken highlighted.
///
/// From the keyboard it is one tab stop, a list of Start, the steps and End (`listbox`): the arrow keys,
/// Home and End move among them, and Enter or Space selects one, as a click does. The edges and the
/// visits are left to the mouse, as the step's transitions' list and the transcript beside the chart
/// choose them too.
public class FlowChart extends Composite {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final int WIDTH = 780;
    private static final int CX = 380;
    private static final int HALF = 110;
    private static final int BOX_H = 46;
    private static final int PITCH = 150;
    private static final int DIA = 28;
    // Wide enough for "START  ·  a learner with no binding", and clear of the lanes either side
    private static final int TERMINAL_HALF = 130;
    private static final int TERMINAL_H = 34;
    private static final int TOP = 24;
    private static final int FIRST = 96;
    private static final int LANE_GAP = 48;
    private static final int LANE_PITCH = 30;
    private static final String DATA_STEP = "data-step";
    private static final String DATA_EDGE = "data-edge";
    private static final String DATA_NODE = "data-node";
    private static final String DATA_TURN = "data-turn";
    public static final String START = "start";
    public static final String END = "end";
    private static final String ACTIVE = "flow-chart__node--active";

    private final Html html = new Html();
    /// Prefixes the ids of the chart's options, which its list names as the active one.
    private final String idPrefix = Document.get().createUniqueId();
    private List<FlowStep> steps = new ArrayList<>();
    private List<FlowProblem> problems = new ArrayList<>();
    private List<SupervisorTurn> turns = new ArrayList<>();
    private Integer selected;
    /// The turn chosen in a transcript beside the chart, whose visit is ringed (A116).
    private Integer selectedTurn;
    private String selectedTerminal;
    private Consumer<Integer> onSelect;
    private Consumer<String> onTerminalSelect;
    private BiConsumer<Integer, String> onEdgeSelect;
    private Consumer<Integer> onTurnSelect;
    /// The option the keyboard is on: [#START], [#END] or a step's index; null before the list has the
    /// focus.
    private String active;

    public FlowChart() {
        initWidget(html);
        html.addStyleName("flow-chart");
        final Element root = html.getElement();
        root.setTabIndex(0);
        root.setAttribute("role", "listbox");
        root.setAttribute("aria-label", "Learning flow");
        html.addDomHandler(event -> {
            if (active == null || !stops().contains(active)) {
                setActive(selectedStop());
            }
        }, FocusEvent.getType());
        html.addDomHandler(this::onKeyDown, KeyDownEvent.getType());
        // One handler for every step: the step clicked is the nearest group above the target that names
        // one, as the pathways tree does.
        html.addClickHandler(event -> {
            final Element target = event.getNativeEvent().getEventTarget().cast();
            if (target == null) {
                return;
            }
            // A visit's number is a turn of the attempt drawn over the flow (A116): the turn, not its step.
            final Element visit = ElementUtil.findParent(target,
                    element -> NullSafe.isNonBlankString(element.getAttribute(DATA_TURN)), 4);
            if (visit != null && onTurnSelect != null) {
                onTurnSelect.accept(Integer.parseInt(visit.getAttribute(DATA_TURN)));
                return;
            }
            final Element edge = ElementUtil.findParent(target,
                    element -> NullSafe.isNonBlankString(element.getAttribute(DATA_EDGE)), 6);
            if (edge != null && onEdgeSelect != null) {
                onEdgeSelect.accept(Integer.parseInt(edge.getAttribute(DATA_STEP)), edge.getAttribute(DATA_EDGE));
                return;
            }
            final Element node = ElementUtil.findParent(target,
                    element -> NullSafe.isNonBlankString(element.getAttribute(DATA_STEP)), 6);
            if (node != null && onSelect != null) {
                setActive(node.getAttribute(DATA_STEP));
                onSelect.accept(Integer.parseInt(node.getAttribute(DATA_STEP)));
                return;
            }
            final Element terminal = ElementUtil.findParent(target,
                    element -> NullSafe.isNonBlankString(element.getAttribute(DATA_NODE)), 6);
            if (terminal != null && onTerminalSelect != null) {
                setActive(terminal.getAttribute(DATA_NODE));
                onTerminalSelect.accept(terminal.getAttribute(DATA_NODE));
            }
        });
    }

    /// Scale the drawing down to the pane's width where the pane is narrower than the drawing, as the
    /// Supervisor's is; by default the drawing keeps its size and the pane scrolls.
    public void fitToPane() {
        html.addStyleName("flow-chart--fit");
    }

    /// Who is told which step was clicked, by its index.
    public void setOnSelect(final Consumer<Integer> onSelect) {
        this.onSelect = onSelect;
    }

    /// Who is told that Start or End was clicked: [#START] or [#END].
    public void setOnTerminalSelect(final Consumer<String> onTerminalSelect) {
        this.onTerminalSelect = onTerminalSelect;
    }

    /// Who is told which declared transition was clicked: the step's index and the outcome's name, or
    /// `spent`.
    public void setOnEdgeSelect(final BiConsumer<Integer, String> onEdgeSelect) {
        this.onEdgeSelect = onEdgeSelect;
    }

    /// Who is told which of a recorded attempt's visits was clicked, by its turn's number (A116).
    public void setOnTurnSelect(final Consumer<Integer> onTurnSelect) {
        this.onTurnSelect = onTurnSelect;
    }

    /// Draw the flow, with what is wrong with it painted on the steps it names, and a recorded attempt's
    /// path over it where turns are given.
    public void show(final List<FlowStep> steps,
                     final List<FlowProblem> problems,
                     final List<SupervisorTurn> turns,
                     final Integer selected) {
        this.steps = new ArrayList<>(NullSafe.list(steps));
        this.problems = new ArrayList<>(NullSafe.list(problems));
        this.turns = new ArrayList<>(NullSafe.list(turns));
        this.selected = selected;
        this.selectedTurn = null;
        render();
    }

    /// Paint one step as the selected one, the rest as they were, and bring it into view where the
    /// drawing is longer than its pane: a step chosen from elsewhere — a turn in the Supervisor's
    /// transcript, a step in a form — may be far down a long flow.
    public void setSelected(final Integer selected) {
        this.selected = selected;
        this.selectedTerminal = null;
        this.selectedTurn = null;
        render();
        reveal();
    }

    /// Paint the step a recorded turn was asked at as the selected one, ring that turn's visit among the
    /// step's others, and bring it into view (A116). A turn the drawing has no visit for selects nothing.
    ///
    /// @param turn The turn's number, or null for none.
    public void setSelectedTurn(final Integer turn) {
        this.selectedTurn = turn;
        this.selectedTerminal = null;
        this.selected = turn == null
                ? null
                : FlowPath.visits(steps, turns).stream()
                        .filter(visit -> visit.getTurn() == turn)
                        .map(Visit::getStep)
                        .findFirst()
                        .orElse(null);
        render();
        reveal();
    }

    private void reveal() {
        final String stop = selectedTerminal != null
                ? selectedTerminal
                : NullSafe.get(selected, String::valueOf);
        final Element node = stop == null
                ? null
                : Document.get().getElementById(optionId(stop));
        if (node != null) {
            ElementUtil.scrollIntoViewNearest(node);
        }
    }

    /// Paint Start or End as the selected one, and no step.
    public void setSelectedTerminal(final String terminal) {
        this.selected = null;
        this.selectedTurn = null;
        this.selectedTerminal = terminal;
        render();
    }

    /// Nothing drawn.
    public void clear() {
        steps = new ArrayList<>();
        problems = new ArrayList<>();
        turns = new ArrayList<>();
        selected = null;
        selectedTurn = null;
        active = null;
        html.setHTML("");
        showActive();
    }

    private void render() {
        final int count = steps.size();
        final int endY = FIRST + count * PITCH;
        final int height = endY + TERMINAL_H + 40;
        final StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"").append(SVG_NS)
                .append("\" role=\"none\" class=\"flow-chart__drawing\" width=\"").append(WIDTH)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(WIDTH).append(' ')
                .append(height).append("\">");

        final Map<String, Integer> idIndex = new HashMap<>();
        for (int i = 0; i < count; i++) {
            idIndex.put(steps.get(i).effectiveId(), i);
        }
        final List<Visit> visits = FlowPath.visits(steps, turns);
        final Set<String> pathKeys = new HashSet<>();
        final Set<Integer> visited = new HashSet<>();
        final Map<Integer, List<Integer>> visitNumbers = new HashMap<>();
        for (final Visit visit : visits) {
            visited.add(visit.getStep());
            if (visit.getEdge() != null) {
                pathKeys.add(visit.getEdge());
            }
            visitNumbers.computeIfAbsent(visit.getStep(), key -> new ArrayList<>()).add(visit.getTurn());
        }
        final Map<Integer, List<String>> problemsByStep = new HashMap<>();
        for (final FlowProblem problem : problems) {
            if (problem.getStep() != null) {
                problemsByStep.computeIfAbsent(problem.getStep(), key -> new ArrayList<>()).add(problem.getMessage());
            }
        }

        // Start, and its edge down to the first step.
        terminal(svg, TOP, START, "START  ·  a learner with no binding");
        if (count > 0) {
            edge(svg, "start", false, pathKeys, "M" + CX + ' ' + (TOP + TERMINAL_H) + " V" + FIRST, null, 0, 0,
                    null, CX, FIRST, "down");
        }
        terminal(svg, endY, END, "END  ·  bound and served");

        int rightLane = 0;
        int leftLane = 0;
        for (int i = 0; i < count; i++) {
            final FlowStep step = steps.get(i);
            final int boxX = CX - HALF;
            final int boxY = FIRST + i * PITCH;
            final int boxMidY = boxY + BOX_H / 2;
            final int diaY = boxY + BOX_H + 46;
            final boolean last = i == count - 1;

            // The step.
            final StringBuilder classes = new StringBuilder("flow-chart__node flow-chart__node--step");
            if (selected != null && selected == i) {
                classes.append(" flow-chart__node--selected");
            }
            if (problemsByStep.containsKey(i)) {
                classes.append(" flow-chart__node--bad");
            }
            if (visited.contains(i)) {
                classes.append(" flow-chart__node--on-path");
            }
            svg.append("<g class=\"").append(classes).append("\" ").append(DATA_STEP).append("=\"").append(i)
                    .append('"');
            option(svg, String.valueOf(i), selected != null && selected == i, stepName(i, step,
                    problemsByStep.get(i), visited.contains(i)));
            svg.append('>');
            if (problemsByStep.containsKey(i)) {
                svg.append("<title>").append(esc(String.join("; ", problemsByStep.get(i)))).append("</title>");
            }
            svg.append("<rect class=\"flow-chart__box\" x=\"").append(boxX).append("\" y=\"").append(boxY)
                    .append("\" width=\"").append(HALF * 2).append("\" height=\"").append(BOX_H)
                    .append("\" rx=\"3\"/>");
            text(svg, boxX + 12, boxY + 19, "start", "flow-chart__title", title(step));
            text(svg, boxX + 12, boxY + 36, "start", "flow-chart__subtitle", subtitle(step));
            text(svg, boxX + HALF * 2 - 10, boxY + 15, "end", "flow-chart__badge", step.getCandidates() != null
                    ? "×" + step.getCandidates()
                    : "× max");
            svg.append("</g>");

            // Down to the decision.
            edge(svg, FlowPath.edgeKey(i, "judge"), false, pathKeys,
                    "M" + CX + ' ' + (boxY + BOX_H) + " V" + (diaY - DIA), null, 0, 0, null, CX, diaY - DIA, "down");
            svg.append("<g aria-hidden=\"true\" class=\"flow-chart__decision").append(visited.contains(i)
                    ? " flow-chart__decision--on-path"
                    : "").append("\" ").append(DATA_STEP).append("=\"").append(i).append("\">");
            svg.append("<polygon points=\"").append(CX).append(',').append(diaY - DIA).append(' ')
                    .append(CX + DIA + 8).append(',').append(diaY).append(' ').append(CX).append(',')
                    .append(diaY + DIA).append(' ').append(CX - DIA - 8).append(',').append(diaY).append("\"/>");
            text(svg, CX, diaY + 4, "middle", "flow-chart__decision-text", "judged");
            svg.append("</g>");

            final Transition onPassed = step.transitionOn(StepOutcome.PASSED);
            final Transition onSpent = step.transitionOnSpent();
            // A pass falls through to the next step, or to End from the last.
            if (onPassed == null) {
                final int targetY = last
                        ? endY
                        : boxY + PITCH;
                edge(svg, FlowPath.edgeKey(i, FlowPath.PASSED), false, pathKeys,
                        "M" + CX + ' ' + (diaY + DIA) + " V" + targetY, "passed", CX + 8, diaY + DIA + 11,
                        "start", CX, targetY, "down");
            }
            // A shortfall re-asks the step with its next candidate.
            if (step.getCandidates() == null || step.getCandidates() != 1) {
                final int loopX = boxX - 22;
                edge(svg, FlowPath.edgeKey(i, FlowPath.REASK), false, pathKeys,
                        "M" + (CX - DIA - 8) + ' ' + diaY + " H" + loopX + " V" + (boxY + 12) + " H" + boxX,
                        "shortfall: re-ask", loopX - 6, (diaY + boxY + 12) / 2, "end", boxX, boxY + 12, "right");
            }
            // Spent candidates leave by the step's default exit, where the flow names none. Each exit
            // from one decision takes the next place down, so two never overlap.
            int stubs = 0;
            if (onSpent == null) {
                exitStub(svg, FlowPath.edgeKey(i, FlowPath.SPENT), false, pathKeys, diaY, stubs++,
                        defaultExit(step), FlowPath.SPENT, true);
            }
            // The transitions the flow declares.
            for (final Transition transition : step.getTransitions()) {
                final String name = transition.onSpent()
                        ? FlowPath.SPENT
                        : transition.getOn().getDisplayValue();
                final String key = FlowPath.edgeKey(i, name);
                if (transition.exits()) {
                    final FlowExit named = transition.namedExit();
                    exitStub(svg, key, named != null, pathKeys, diaY, stubs++, named != null
                            ? named
                            : defaultExit(step), name, named == null);
                    continue;
                }
                if (Transition.END.equals(transition.getGoTo())) {
                    final int lane = CX + HALF + LANE_GAP + (rightLane++) * LANE_PITCH;
                    final int targetY = endY + TERMINAL_H / 2;
                    edge(svg, key, true, pathKeys, "M" + (CX + DIA + 8) + ' ' + diaY + " H" + lane + " V" + targetY
                                                   + " H" + (CX + TERMINAL_HALF), name + " → end", lane + 6,
                            (diaY + targetY) / 2, "start", CX + TERMINAL_HALF, targetY, "left");
                    continue;
                }
                final Integer target = idIndex.get(transition.getGoTo());
                if (target == null) {
                    continue;
                }
                final int targetBoxY = FIRST + target * PITCH;
                if (target > i) {
                    final int lane = CX + HALF + LANE_GAP + (rightLane++) * LANE_PITCH;
                    final int targetY = targetBoxY + BOX_H / 2;
                    edge(svg, key, true, pathKeys, "M" + (CX + DIA + 8) + ' ' + diaY + " H" + lane + " V" + targetY
                                                   + " H" + (CX + HALF), name + " → " + transition.getGoTo(),
                            lane + 6, (diaY + targetY) / 2, "start", CX + HALF, targetY, "left");
                } else {
                    final int lane = CX - HALF - LANE_GAP - (leftLane++) * LANE_PITCH;
                    final int targetY = targetBoxY + 34;
                    edge(svg, key, true, pathKeys, "M" + (CX - DIA - 8) + ' ' + diaY + " H" + lane + " V" + targetY
                                                   + " H" + boxX, name + " → " + transition.getGoTo(), lane - 6,
                            (diaY + targetY) / 2, "end", boxX, targetY, "right");
                }
            }
            // The attempt's visits, numbered down the step's left edge.
            final List<Integer> numbers = visitNumbers.get(i);
            if (numbers != null) {
                for (int j = 0; j < numbers.size(); j++) {
                    final int cy = boxY + 10 + j * 18;
                    svg.append("<g aria-hidden=\"true\" class=\"flow-chart__visit")
                            .append(numbers.get(j).equals(selectedTurn)
                            ? " flow-chart__visit--selected"
                            : "").append("\" ").append(DATA_TURN).append("=\"").append(numbers.get(j))
                            .append("\"><title>Turn ").append(numbers.get(j)).append("</title><circle cx=\"")
                            .append(boxX - 10).append("\" cy=\"").append(cy).append("\" r=\"8\"/>");
                    text(svg, boxX - 10, cy + 3, "middle", "flow-chart__visit-text", String.valueOf(numbers.get(j)));
                    svg.append("</g>");
                }
            }
        }
        svg.append("</svg>");
        html.setHTML(SafeHtmlUtils.fromTrustedString(svg.toString()));
        if (active != null && !stops().contains(active)) {
            active = null;
        }
        showActive();
    }

    // An option's id, role, name and selected state, for the list the chart is to the keyboard
    private void option(final StringBuilder svg, final String stop, final boolean isSelected, final String name) {
        svg.append(" id=\"").append(optionId(stop)).append("\" role=\"option\" aria-selected=\"")
                .append(isSelected).append("\" aria-label=\"").append(esc(name)).append('"');
    }

    // A step's name as the keyboard's list reads it: its place, what it is, and what is wrong with it
    private static String stepName(final int index,
                                   final FlowStep step,
                                   final List<String> stepProblems,
                                   final boolean onPath) {
        final StringBuilder name = new StringBuilder("Step ").append(index + 1).append(": ")
                .append(title(step)).append(", ").append(subtitle(step));
        if (onPath) {
            name.append(", on the path taken");
        }
        if (!NullSafe.isEmptyCollection(stepProblems)) {
            name.append(". ").append(String.join("; ", stepProblems));
        }
        return name.toString();
    }

    private String optionId(final String stop) {
        return idPrefix + "-" + stop;
    }

    // Start, the steps by index, and End: the list's options in order
    private List<String> stops() {
        final List<String> stops = new ArrayList<>();
        stops.add(START);
        for (int i = 0; i < steps.size(); i++) {
            stops.add(String.valueOf(i));
        }
        stops.add(END);
        return stops;
    }

    // Where the keyboard starts: what is selected, else Start
    private String selectedStop() {
        if (selectedTerminal != null) {
            return selectedTerminal;
        }
        if (selected != null && selected >= 0 && selected < steps.size()) {
            return String.valueOf(selected);
        }
        return START;
    }

    private void onKeyDown(final KeyDownEvent event) {
        final List<String> stops = stops();
        final int at = Math.max(0, stops.indexOf(active == null
                ? selectedStop()
                : active));
        final int key = event.getNativeKeyCode();
        final String next;
        if (key == KeyCodes.KEY_DOWN || key == KeyCodes.KEY_RIGHT) {
            next = stops.get(Math.min(at + 1, stops.size() - 1));
        } else if (key == KeyCodes.KEY_UP || key == KeyCodes.KEY_LEFT) {
            next = stops.get(Math.max(at - 1, 0));
        } else if (key == KeyCodes.KEY_HOME) {
            next = stops.get(0);
        } else if (key == KeyCodes.KEY_END) {
            next = stops.get(stops.size() - 1);
        } else if (key == KeyCodes.KEY_ENTER || key == KeyCodes.KEY_SPACE) {
            event.preventDefault();
            choose(stops.get(at));
            return;
        } else {
            return;
        }
        event.preventDefault();
        setActive(next);
    }

    // As a click on the option does
    private void choose(final String stop) {
        setActive(stop);
        if (START.equals(stop) || END.equals(stop)) {
            if (onTerminalSelect != null) {
                onTerminalSelect.accept(stop);
            }
        } else if (onSelect != null) {
            onSelect.accept(Integer.parseInt(stop));
        }
    }

    private void setActive(final String stop) {
        active = stop;
        showActive();
        final Element option = Document.get().getElementById(optionId(stop));
        if (option != null) {
            ElementUtil.scrollIntoViewNearest(option);
        }
    }

    // Names the active option to the list (aria-activedescendant) and marks it. The options are SVG,
    // whose className isn't a string, so their class attribute is changed rather than GWT's className
    private void showActive() {
        final Element root = html.getElement();
        for (final String stop : stops()) {
            final Element option = Document.get().getElementById(optionId(stop));
            if (option != null) {
                markActive(option, stop.equals(active));
            }
        }
        if (active == null || Document.get().getElementById(optionId(active)) == null) {
            root.removeAttribute("aria-activedescendant");
        } else {
            root.setAttribute("aria-activedescendant", optionId(active));
        }
    }

    private static void markActive(final Element option, final boolean isActive) {
        final List<String> classes = new ArrayList<>();
        for (final String name : NullSafe.string(option.getAttribute("class")).split(" ")) {
            if (!name.isEmpty() && !ACTIVE.equals(name)) {
                classes.add(name);
            }
        }
        if (isActive) {
            classes.add(ACTIVE);
        }
        option.setAttribute("class", String.join(" ", classes));
    }

    private static FlowExit defaultExit(final FlowStep step) {
        return step.getRole() == ConfigureRole.PARSER
                ? FlowExit.GIVE_UP
                : FlowExit.PARK;
    }

    private static String title(final FlowStep step) {
        final String kind = step.getKind() == QuestionKind.CONFIGURE && step.getRole() != null
                ? "Configure " + step.getRole().getDisplayValue()
                : step.getKind().getDisplayValue();
        return step.getId() != null
                ? kind + "   (" + step.getId() + ")"
                : kind;
    }

    private static String subtitle(final FlowStep step) {
        if (step.getKind() == QuestionKind.CHAIN) {
            return step.getElements().isEmpty()
                    ? "chooses from the default elements"
                    : "chooses from " + String.join(", ", step.getElements());
        }
        if (!step.getChecks().isEmpty()) {
            final List<String> names = new ArrayList<>();
            step.getChecks().forEach(check -> names.add(check.getDisplayValue()));
            return "checks: " + String.join(", ", names);
        }
        if (step.getKind() == QuestionKind.TARGET && step.getKinds() != null) {
            return step.getKinds() + " record kinds";
        }
        if (step.getWhen() != null && step.getWhen() != StepGuard.ALWAYS) {
            return "when " + step.getWhen().getDisplayValue();
        }
        return "judged by its kind’s checks";
    }

    private void terminal(final StringBuilder svg, final int y, final String which, final String label) {
        svg.append("<g class=\"flow-chart__node flow-chart__node--terminal flow-chart__node--").append(which)
                .append(which.equals(selectedTerminal)
                        ? " flow-chart__node--selected"
                        : "").append("\" ").append(DATA_NODE).append("=\"").append(which).append('"');
        option(svg, which, which.equals(selectedTerminal), label.replace("  ·  ", ": ")
                .replace("START", "Start")
                .replace("END", "End"));
        svg.append("><rect class=\"flow-chart__box\" x=\"")
                .append(CX - TERMINAL_HALF).append("\" y=\"").append(y).append("\" width=\"").append(TERMINAL_HALF * 2)
                .append("\" height=\"").append(TERMINAL_H).append("\" rx=\"17\"/>");
        text(svg, CX, y + 21, "middle", "flow-chart__title", label);
        svg.append("</g>");
    }

    /// An edge: its path, an arrowhead where it arrives, and a label beside it where it has one.
    private static void edge(final StringBuilder svg,
                             final String key,
                             final boolean declared,
                             final Set<String> pathKeys,
                             final String d,
                             final String label,
                             final int labelX,
                             final int labelY,
                             final String anchor,
                             final int arrowX,
                             final int arrowY,
                             final String direction) {
        svg.append("<g aria-hidden=\"true\" class=\"flow-chart__edge ").append(declared
                ? "flow-chart__edge--declared"
                : "flow-chart__edge--implied").append(pathKeys.contains(key)
                ? " flow-chart__edge--on-path"
                : "").append('"');
        edgeData(svg, key, declared);
        svg.append("><path d=\"").append(d).append("\"/>");
        arrow(svg, arrowX, arrowY, direction);
        if (label != null) {
            label(svg, labelX, labelY, anchor, label);
        }
        svg.append("</g>");
    }

    /// A short line from the decision to the exit, named: park, or give-up. Where the exit is the step's
    /// own default rather than one the flow names, the name says so.
    private static void exitStub(final StringBuilder svg,
                                 final String key,
                                 final boolean declared,
                                 final Set<String> pathKeys,
                                 final int diaY,
                                 final int place,
                                 final FlowExit exit,
                                 final String label,
                                 final boolean byDefault) {
        final int x = CX + DIA + 8;
        final int ex = x + 46 + place * 12;
        final int ey = diaY + 26 + place * 22;
        final String name = exit.getDisplayValue() + (byDefault
                ? " (default)"
                : "");
        final int width = (int) (name.length() * 6.2) + 14;
        svg.append("<g aria-hidden=\"true\" class=\"flow-chart__edge ").append(declared
                ? "flow-chart__edge--declared"
                : "flow-chart__edge--implied").append(pathKeys.contains(key)
                ? " flow-chart__edge--on-path"
                : "").append('"');
        edgeData(svg, key, declared);
        svg.append("><path d=\"M").append(x).append(' ').append(diaY).append(" L").append(ex - 8)
                .append(' ').append(ey - 4).append("\"/>");
        svg.append("<g class=\"flow-chart__exit").append(exit == FlowExit.GIVE_UP
                ? " flow-chart__exit--give-up"
                : "").append("\"><rect x=\"").append(ex - 6).append("\" y=\"").append(ey - 9).append("\" width=\"")
                .append(width).append("\" height=\"18\" rx=\"9\"/>");
        text(svg, ex - 6 + width / 2, ey + 3, "middle", "flow-chart__exit-text", name);
        svg.append("</g>");
        text(svg, ex - 6 + width + 6, ey + 3, "start", "flow-chart__label", label);
        svg.append("</g>");
    }

    /// A declared edge says which step and which transition it is, so that a click on it can select the
    /// transition in the step's form.
    private static void edgeData(final StringBuilder svg, final String key, final boolean declared) {
        if (!declared) {
            return;
        }
        final int bar = key.indexOf('|');
        svg.append(' ').append(DATA_STEP).append("=\"").append(esc(key.substring(0, bar))).append("\" ")
                .append(DATA_EDGE).append("=\"").append(esc(key.substring(bar + 1))).append('"');
    }

    private static void arrow(final StringBuilder svg, final int x, final int y, final String direction) {
        final String d;
        switch (direction) {
            case "down":
                d = "M" + (x - 4) + ' ' + (y - 6) + " L" + x + ' ' + y + " L" + (x + 4) + ' ' + (y - 6);
                break;
            case "left":
                d = "M" + (x + 6) + ' ' + (y - 4) + " L" + x + ' ' + y + " L" + (x + 6) + ' ' + (y + 4);
                break;
            case "right":
                d = "M" + (x - 6) + ' ' + (y - 4) + " L" + x + ' ' + y + " L" + (x - 6) + ' ' + (y + 4);
                break;
            default:
                d = "M" + (x - 4) + ' ' + (y + 6) + " L" + x + ' ' + y + " L" + (x + 4) + ' ' + (y + 6);
                break;
        }
        svg.append("<path class=\"flow-chart__arrow\" d=\"").append(d).append("\"/>");
    }

    /// A label with a backing so that it reads over whatever edge it sits beside.
    private static void label(final StringBuilder svg,
                              final int x,
                              final int y,
                              final String anchor,
                              final String label) {
        final int width = (int) (label.length() * 5.6) + 8;
        final int rectX = "end".equals(anchor)
                ? x - width + 2
                : "middle".equals(anchor)
                        ? x - width / 2
                        : x - 4;
        svg.append("<rect class=\"flow-chart__label-back\" x=\"").append(rectX).append("\" y=\"").append(y - 8)
                .append("\" width=\"").append(width).append("\" height=\"16\" rx=\"3\"/>");
        text(svg, x, y + 3, anchor, "flow-chart__label", label);
    }

    private static void text(final StringBuilder svg,
                             final int x,
                             final int y,
                             final String anchor,
                             final String cls,
                             final String content) {
        svg.append("<text class=\"").append(cls).append("\" x=\"").append(x).append("\" y=\"").append(y)
                .append("\" text-anchor=\"").append(anchor).append("\">").append(esc(content)).append("</text>");
    }

    private static String esc(final String text) {
        return SafeHtmlUtils.htmlEscape(text == null
                ? ""
                : text);
    }
}
