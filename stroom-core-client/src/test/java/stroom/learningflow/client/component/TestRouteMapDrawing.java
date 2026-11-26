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

import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.LearningFlowDoc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TestRouteMapDrawing {

    private static final Pattern BUTTON = Pattern.compile(
            "<g class=\"([^\"]*)\" data-stop=\"([^\"]*)\" role=\"button\" tabindex=\"(-?\\d)\" "
            + "aria-label=\"([^\"]*)\"( aria-current=\"location\")?>");
    // A document built with no settings: learning off, no model and no steps, so there are problems under
    // AI model and instructions and under Learning steps
    private static final SetupSummary NEW = SetupSummary.of(LearningFlowDoc.builder().uuid("new").name("New")
            .build(), FlowExampleFixtures.all());
    // The flow most of these tests use: the Chain step, the two Configure steps, and the fallback step
    private static final List<FlowStep> DIRECT = FlowExampleFixtures.steps(FlowExample.DIRECT);

    @Test
    void theStopsAreTheSectionsInOrderWithTheStepsUnderLearningSteps() {
        // "Straight to it" (Direct) has three steps, then the fallback
        assertThat(DIRECT).hasSize(4);
        final List<Button> buttons = buttons(RouteMapDrawing.draw(DIRECT, NEW, Map.of(),
                SetupStop.of(SetupSection.LEARNING), false));

        assertThat(buttons).extracting(button -> button.key).containsExactly(
                "section:LEARNING", "section:APPROACH", "step:0", "step:1", "step:2", "step:3", "section:QUALITY",
                "section:GOING_LIVE", "section:IN_SERVICE");
        assertThat(buttons.get(2).label).isEqualTo("Step 1: " + FlowWords.stepName(DIRECT, 0));
        assertThat(buttons.get(5).label).isEqualTo(RouteMapDrawing.FALLBACK);
    }

    @Test
    void onlyTheStopChosenIsCurrentAndInTheTabOrder() {
        final List<Button> buttons = buttons(RouteMapDrawing.draw(DIRECT, NEW, Map.of(),
                SetupStop.step(1), false));

        for (final Button button : buttons) {
            final boolean chosen = button.key.equals("step:1");
            assertThat(button.current).as(button.key).isEqualTo(chosen);
            assertThat(button.tabIndex).as(button.key).isEqualTo(chosen
                    ? "0"
                    : "-1");
            assertThat(button.classes.contains("route-map__station--selected")).as(button.key).isEqualTo(chosen);
        }
    }

    @Test
    void aSectionSaysWhatItIsSetToOrWhatIsWrongInIt() {
        final List<Button> buttons = buttons(RouteMapDrawing.draw(DIRECT, NEW, Map.of(),
                SetupStop.of(SetupSection.LEARNING), false));

        assertThat(label(buttons, "section:LEARNING")).isEqualTo(
                "AI model and instructions: needs attention. Learning is switched off, so streams of new feeds "
                + "wait.");
        assertThat(label(buttons, "section:IN_SERVICE")).startsWith("Monitoring translations in use: Flagged below");
    }

    @Test
    void aStepSaysWhatIsWrongWithIt() {
        final String svg = RouteMapDrawing.draw(DIRECT, NEW, Map.of(1, "It has no tries"),
                SetupStop.of(SetupSection.LEARNING), false);

        assertThat(label(buttons(svg), "step:1")).endsWith(". Needs attention: It has no tries");
        assertThat(svg).contains("route-map__sub--problem");
    }

    @Test
    void everyStopHasTheSameRoomAboutItsWordsAndItsMark() {
        // Includes stops with no lines under their names, with one line, and with problems longer than a line
        final String svg = RouteMapDrawing.draw(FlowExampleFixtures.steps(FlowExample.ESCALATING), NEW,
                Map.of(1, "This step is wrong in a way that takes more than one line of the map to say"),
                SetupStop.of(SetupSection.QUALITY), false);
        final Pattern rect = Pattern.compile("<rect class=\"route-map__area\" x=\"\\d+\" y=\"(\\d+)\" "
                                             + "width=\"\\d+\" height=\"(\\d+)\"");
        final Pattern text = Pattern.compile("<text class=\"[^\"]*\" x=\"\\d+\" y=\"(\\d+)\"");
        final Pattern mark = Pattern.compile("<(?:circle|rect) class=\"route-map__stop[^\"]*\" "
                                             + "(?:cx=\"\\d+\" cy=\"(\\d+)\" r|x=\"\\d+\" y=\"(\\d+)\" width)");

        final List<Integer> above = new ArrayList<>();
        final List<Integer> below = new ArrayList<>();
        final List<Integer> markMiddles = new ArrayList<>();
        final List<Integer> tops = new ArrayList<>();
        final List<Integer> bottoms = new ArrayList<>();
        for (final String drawn : svg.split("<g class=\"route-map__station")) {
            // The station's own markup, up to the end of its group
            final String station = drawn.substring(0, Math.max(0, drawn.indexOf("</g>")));
            final Matcher area = rect.matcher(station);
            if (!area.find()) {
                continue;
            }
            final int top = Integer.parseInt(area.group(1));
            final int bottom = top + Integer.parseInt(area.group(2));
            final List<Integer> lines = new ArrayList<>();
            final Matcher line = text.matcher(station);
            while (line.find()) {
                lines.add(Integer.parseInt(line.group(1)));
            }
            final Matcher stop = mark.matcher(station);
            assertThat(stop.find()).isTrue();
            final int middle = stop.group(1) != null
                    ? Integer.parseInt(stop.group(1))
                    // A section's mark is a square 16 high, so its middle is 8 below its top
                    : Integer.parseInt(stop.group(2)) + 8;
            above.add(lines.getFirst() - top);
            below.add(bottom - lines.getLast());
            markMiddles.add(middle - top);
            tops.add(top);
            bottoms.add(bottom);
        }

        assertThat(above).hasSizeGreaterThan(10);
        assertThat(above).as("from a stop's top to its name").containsOnly(above.getFirst());
        assertThat(below).as("from a stop's last line to its bottom").containsOnly(below.getFirst());
        assertThat(markMiddles).as("from a stop's top to its mark").containsOnly(markMiddles.getFirst());
        // Consecutive stops are the same distance apart. (Translation learned is not a stop, and the larger
        // gaps around it are filtered out.)
        final List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < tops.size(); i++) {
            gaps.add(tops.get(i) - bottoms.get(i - 1));
        }
        assertThat(gaps).filteredOn(gap -> gap < 20).hasSizeGreaterThan(8).containsOnly(gaps.getFirst());
    }

    @Test
    void addStepIsAButtonUnderTheStepChosenThatTheArrowKeysPassBy() {
        // With the Direct flow's second step chosen, a step added after it would be third
        final String svg = RouteMapDrawing.draw(DIRECT, NEW, Map.of(), SetupStop.step(1),
                true);

        assertThat(count(svg, RouteMapDrawing.DATA_ADD + "=")).isEqualTo(1);
        assertThat(svg).contains(RouteMapDrawing.DATA_ADD + "=\"2\" role=\"button\" tabindex=\"0\" "
                                 + "aria-haspopup=\"menu\" aria-label=\"Add a step after step 2\"");
        assertThat(svg).contains(">+ Add step</text>");
        // The button is not a stop: the stops, which the arrow keys move between, are the same as without it
        assertThat(buttons(svg)).extracting(button -> button.key)
                .isEqualTo(buttons(RouteMapDrawing.draw(DIRECT, NEW, Map.of(),
                        SetupStop.step(1), false)).stream().map(button -> button.key).toList());
        // It is drawn between the chosen step and the next, which moves down to make room for it
        final int add = svg.indexOf(RouteMapDrawing.DATA_ADD + "=");
        assertThat(add).isGreaterThan(svg.indexOf("data-stop=\"step:1\""));
        final String without = RouteMapDrawing.draw(DIRECT, NEW, Map.of(), SetupStop.step(1),
                false);
        assertThat(boxTop(svg, "step:2")).isGreaterThan(boxTop(without, "step:2"));
    }

    @Test
    void addStepIsOfferedUnderAStepAloneAndNotUnderTheFallback() {
        final int fallback = DIRECT.size() - 1;
        assertThat(RouteMapDrawing.draw(DIRECT, NEW, Map.of(), SetupStop.step(fallback), true))
                .doesNotContain(RouteMapDrawing.DATA_ADD + "=");
        assertThat(RouteMapDrawing.draw(DIRECT, NEW, Map.of(), SetupStop.of(SetupSection.APPROACH), true))
                .doesNotContain(RouteMapDrawing.DATA_ADD + "=");
        // Nor when the document can't have a step added
        assertThat(RouteMapDrawing.draw(DIRECT, NEW, Map.of(), SetupStop.step(0), false))
                .doesNotContain(RouteMapDrawing.DATA_ADD + "=");
    }

    @Test
    void everyLineOfWordsHasALinesHeightToItself() {
        // Problems under two sections and a step, each longer than one line
        final String svg = RouteMapDrawing.draw(DIRECT, NEW,
                Map.of(1, "This step is wrong in a way that takes more than one line of the map to say"),
                SetupStop.of(SetupSection.LEARNING), false);

        final Matcher matcher = Pattern.compile("<text class=\"[^\"]*\" x=\"\\d+\" y=\"(\\d+)\"").matcher(svg);
        // The lines under a stop's name are 15 apart, so no line may be closer than that to the one above it
        int last = Integer.MIN_VALUE / 2;
        int lines = 0;
        while (matcher.find()) {
            final int y = Integer.parseInt(matcher.group(1));
            assertThat(y).as("line " + lines).isGreaterThanOrEqualTo(last + 15);
            last = y;
            lines++;
        }
        assertThat(lines).isGreaterThan(15);
    }

    @Test
    void theLineIsDashedIntoTheFallbackOnly() {
        final String withFallback = RouteMapDrawing.draw(DIRECT, NEW, Map.of(),
                SetupStop.of(SetupSection.LEARNING), false);
        final List<FlowStep> without = ApproachFlow.withFallback(DIRECT, false);
        final String withoutFallback = RouteMapDrawing.draw(without, NEW, Map.of(),
                SetupStop.of(SetupSection.LEARNING), false);

        assertThat(count(withFallback, "route-map__line--dashed")).isEqualTo(1);
        assertThat(count(withoutFallback, "route-map__line--dashed")).isZero();
        assertThat(withFallback).contains(RouteMapDrawing.LEARNED);
    }

    @Test
    void beforeADocumentIsReadTheSectionsAreNamedAlone() {
        final List<Button> buttons = buttons(RouteMapDrawing.draw(List.of(), null, Map.of(),
                SetupStop.of(SetupSection.LEARNING), false));

        assertThat(buttons).extracting(button -> button.key).containsExactly("section:LEARNING",
                "section:APPROACH", "section:QUALITY", "section:GOING_LIVE", "section:IN_SERVICE");
        assertThat(label(buttons, "section:QUALITY")).isEqualTo("Quality checks: ");
    }

    @Test
    void whatIsSaidIsEscaped() {
        final String svg = RouteMapDrawing.draw(DIRECT, NEW, Map.of(0, "<b>&</b>"),
                SetupStop.of(SetupSection.LEARNING), false);

        assertThat(svg).doesNotContain("<b>").contains("&lt;b&gt;&amp;&lt;/b&gt;");
    }

    @Test
    void aShortLineIsOneLine() {
        assertThat(RouteMapDrawing.wrap("Own checks · 2 checks")).containsExactly("Own checks · 2 checks");
    }

    @Test
    void aLongLineBreaksAtSpacesWithinTheWidth() {
        final String text = "Choose a model: learning can't ask the AI until the document names the model it uses";
        final List<String> lines = RouteMapDrawing.wrap(text);
        assertThat(lines).hasSizeGreaterThan(1);
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(50));
        assertThat(String.join(" ", lines)).isEqualTo(text);
    }

    @Test
    void aWordLongerThanALineIsALineOfItsOwn() {
        final String word = "x".repeat(60);
        assertThat(RouteMapDrawing.wrap("before " + word + " after")).containsExactly("before", word, "after");
    }

    @Test
    void spacesAroundAndBetweenWordsAreOne() {
        assertThat(RouteMapDrawing.wrap("  Pass mark   80%  ")).containsExactly("Pass mark 80%");
    }

    private static List<Button> buttons(final String svg) {
        final List<Button> buttons = new ArrayList<>();
        final Matcher matcher = BUTTON.matcher(svg);
        while (matcher.find()) {
            buttons.add(new Button(matcher.group(1), matcher.group(2), matcher.group(3),
                    matcher.group(4).replace("&amp;", "&").replace("&#39;", "'"), matcher.group(5) != null));
        }
        return buttons;
    }

    private static String label(final List<Button> buttons, final String key) {
        return buttons.stream().filter(button -> button.key.equals(key)).findFirst().orElseThrow().label;
    }

    // Gets the y position of the top of a stop's box
    private static int boxTop(final String svg, final String key) {
        final Matcher matcher = Pattern.compile("data-stop=\"" + Pattern.quote(key)
                                                + "\"[^>]*><rect class=\"route-map__area\" x=\"\\d+\" y=\"(\\d+)\"")
                .matcher(svg);
        assertThat(matcher.find()).as("stop " + key).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static int count(final String text, final String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }


    // --------------------------------------------------------------------------------


    // A stop's button, as parsed from the drawn SVG
    private record Button(String classes, String key, String tabIndex, String label, boolean current) {

    }
}
