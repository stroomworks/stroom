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

package stroom.gwt.workbench.client.app.dependency;

import stroom.gwt.workbench.client.app.rest.JsonValues;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Checks that the Structure view stories' fixtures are still the layout's test scenarios, so the stories
/// draw what the layout is tested on.
class TestStructureGraphFixtures {

    private static final Path GOLDEN = Path.of("../..")
            .resolve("stroom-core-client/src/test/resources/stroom/dependency/client/layout/golden")
            .toAbsolutePath()
            .normalize();

    @TestFactory
    Stream<DynamicTest> testFixturesMatchTheLayoutScenarios() {
        return Stream.of(
                        fixture("events", StructureGraphFixtures.EVENTS),
                        fixture("deep", StructureGraphFixtures.DEEP),
                        fixture("cycle", StructureGraphFixtures.CYCLE),
                        fixture("wide12", StructureGraphFixtures.WIDE),
                        fixture("single", StructureGraphFixtures.SINGLE),
                        fixture("empty", StructureGraphFixtures.EMPTY))
                .map(fixture -> DynamicTest.dynamicTest(fixture.scenario(), () -> {
                    final Map<?, ?> expected = (Map<?, ?>) ((Map<?, ?>) parse(golden(fixture.scenario())))
                            .get("graph");
                    final Map<?, ?> actual = (Map<?, ?>) parse(fixture.json());

                    assertThat(nodes(actual)).containsExactlyElementsOf(nodes(expected));
                    assertThat(edges(actual)).containsExactlyElementsOf(edges(expected));
                }));
    }

    @Test
    void testHandWrittenFixturesParse() {
        assertThat(parse(StructureGraphFixtures.AMBIGUOUS)).isInstanceOf(Map.class);
        assertThat(parse(StructureGraphFixtures.LIMIT_REACHED)).isInstanceOf(Map.class);
    }

    private static Fixture fixture(final String scenario, final String json) {
        return new Fixture(scenario, json);
    }

    private static List<String> nodes(final Map<?, ?> graph) {
        final List<String> lines = new ArrayList<>();
        for (final Object item : (List<?>) graph.get("nodes")) {
            final Map<?, ?> node = (Map<?, ?>) item;
            final Map<?, ?> docRef = (Map<?, ?>) node.get("docRef");
            lines.add(String.join(" | ",
                    text(node.get("id")),
                    text(docRef.get("type")),
                    text(docRef.get("uuid")),
                    text(docRef.get("name")),
                    text(node.get("role")),
                    text(node.get("streamType")),
                    text(node.get("elementCount"))));
        }
        return lines;
    }

    private static List<String> edges(final Map<?, ?> graph) {
        final List<String> lines = new ArrayList<>();
        for (final Object item : (List<?>) graph.get("edges")) {
            final Map<?, ?> edge = (Map<?, ?>) item;
            lines.add(String.join(" | ",
                    text(edge.get("from")),
                    text(edge.get("to")),
                    text(edge.get("kind")),
                    text(edge.get("certainty")),
                    text(edge.get("element"))));
        }
        return lines;
    }

    private static String text(final Object value) {
        return String.valueOf(value);
    }

    private static Object parse(final String json) {
        return JsonValues.parse(json);
    }

    private static String golden(final String scenario) {
        try {
            return Files.readString(GOLDEN.resolve(scenario + ".json"), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new AssertionError("Cannot read the reference file for " + scenario, e);
        }
    }

    private record Fixture(String scenario, String json) {

    }
}
