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

package stroom.learningflow.learning;

import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.util.json.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// The built-in default setups, as JSON beside this class (Track 2 step 1c): the steps of `Direct`,
/// `Target first` and `Escalating`, read with the same Jackson mapper a stored document is read with,
/// so a built-in setup and a saved Learning Flow are one format.
///
/// They live on the server rather than in [FlowExample] because the enum is compiled to JavaScript for
/// the client, which has neither classpath resources nor Jackson. The client is given them by
/// `LearningFlowResource.flowExamples()`, as it is given the built-in question templates; a document
/// it opens already carries its own steps, so it needs these only to name which setup a document matches
/// and to start a new one from.
///
/// The flow DSL that used to spell these out is gone: this is the one place a built-in setup is defined.
public final class FlowExamples {

    private static final String PATH = "flow/";
    private static final Map<FlowExample, String> FILES = new EnumMap<>(Map.of(
            FlowExample.DIRECT, "direct.json",
            FlowExample.TARGET_FIRST, "target-first.json",
            FlowExample.ESCALATING, "escalating.json"));

    /// Read once: the files are on the classpath and never change while a node runs.
    private static final Map<FlowExample, List<FlowStep>> STEPS = read();

    private FlowExamples() {
    }

    /// @param example The setup.
    /// @return Its steps, in order.
    public static List<FlowStep> steps(final FlowExample example) {
        final List<FlowStep> steps = STEPS.get(example);
        if (steps == null) {
            throw new IllegalArgumentException("No built-in setup " + example);
        }
        return steps;
    }

    /// One built-in setup as a flow, for a new document and for a test that wants a working one.
    ///
    /// @param example The setup.
    /// @return A flow of its steps, with no template overrides.
    public static LearningFlow flow(final FlowExample example) {
        return new LearningFlow(steps(example), null, null);
    }

    /// Every built-in setup with its steps, for the client.
    ///
    /// @return Them, in the order the enum declares.
    public static List<BuiltInFlow> all() {
        return java.util.Arrays.stream(FlowExample.values())
                .map(example -> new BuiltInFlow(example, example.getDisplayValue(), steps(example)))
                .toList();
    }

    private static Map<FlowExample, List<FlowStep>> read() {
        final Map<FlowExample, List<FlowStep>> read = new EnumMap<>(FlowExample.class);
        FILES.forEach((example, file) -> read.put(example, steps(file)));
        return Map.copyOf(read);
    }

    private static List<FlowStep> steps(final String file) {
        try (InputStream in = FlowExamples.class.getResourceAsStream(PATH + file)) {
            if (in == null) {
                throw new IllegalStateException("The built-in setup " + file + " is not on the classpath");
            }
            // A built-in setup that cannot be read is a broken build, not a run-time condition: every
            // new document starts from one, so failing here is better than a document with no steps.
            return List.of(JsonUtil.getMapper().readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), FlowStep[].class));
        } catch (final IOException e) {
            throw new UncheckedIOException("The built-in setup " + file + " cannot be read", e);
        }
    }
}
