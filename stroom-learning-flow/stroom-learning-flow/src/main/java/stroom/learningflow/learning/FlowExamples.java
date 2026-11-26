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

/// The built-in learning flow setups (`Direct`, `Target first` and `Escalating`), which a new Learning Flow
/// document starts from. Their steps are JSON files beside this class, read with the same Jackson mapper as a
/// stored document, so a built-in setup and a saved Learning Flow have the same format.
///
/// They are held on the server rather than in [FlowExample] because that enum is compiled to JavaScript for
/// the client, which has neither classpath resources nor Jackson. The client gets them from
/// `LearningFlowResource.flowExamples()`, as it gets the built-in question templates. A document it opens
/// already has its own steps, so the client needs these only to show which setup a document matches and to
/// start a new one.
///
/// This is the only place a built-in setup is defined.
public final class FlowExamples {

    private static final String PATH = "flow/";
    private static final Map<FlowExample, String> FILES = new EnumMap<>(Map.of(
            FlowExample.DIRECT, "direct.json",
            FlowExample.TARGET_FIRST, "target-first.json",
            FlowExample.ESCALATING, "escalating.json"));

    /// Read once, because the files are on the classpath and never change while a node runs.
    private static final Map<FlowExample, List<FlowStep>> STEPS = read();

    private FlowExamples() {
    }

    /// Returns the steps of a built-in setup.
    ///
    /// @param example The setup.
    /// @return Its steps, in order.
    public static List<FlowStep> steps(final FlowExample example) {
        final List<FlowStep> steps = STEPS.get(example);
        if (steps == null) {
            throw new IllegalArgumentException("No built-in setup " + example);
        }
        return steps;
    }

    /// Returns one built-in setup as a flow, for a new document or for a test that needs a working one.
    ///
    /// @param example The setup.
    /// @return A flow of its steps, with no template overrides.
    public static LearningFlow flow(final FlowExample example) {
        return new LearningFlow(steps(example), null, null);
    }

    /// Returns every built-in setup with its steps, for the client.
    ///
    /// @return The setups, in the order [FlowExample] declares them.
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
            // A built-in setup that cannot be read means the build is broken. Every new document starts
            // from one, so failing here is better than creating a document with no steps.
            return List.of(JsonUtil.getMapper().readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), FlowStep[].class));
        } catch (final IOException e) {
            throw new UncheckedIOException("The built-in setup " + file + " cannot be read", e);
        }
    }
}
