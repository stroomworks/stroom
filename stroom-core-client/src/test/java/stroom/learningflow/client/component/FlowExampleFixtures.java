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

import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.Transition;

import java.util.List;

/// Copies of the built-in learning flows (default setups) for client unit tests.
///
/// The real ones are JSON resources on the server that reach the client over REST, which a unit test cannot
/// do, so they are built here. Their exact content is tested on the server by `TestFlowExamples`; these copies
/// exist to test the client's own work of matching a document's steps to a built-in flow and loading one.
final class FlowExampleFixtures {

    private FlowExampleFixtures() {
    }

    /// Gets every built-in flow, as the server would give them to the client.
    ///
    /// @return The flows, in the order `FlowExample` declares them.
    static List<BuiltInFlow> all() {
        return List.of(
                flow(FlowExample.DIRECT, direct()),
                flow(FlowExample.TARGET_FIRST, targetFirst()),
                flow(FlowExample.ESCALATING, escalating()));
    }

    /// @param example The built-in flow.
    /// @return The flow's steps.
    static List<FlowStep> steps(final FlowExample example) {
        for (final BuiltInFlow flow : all()) {
            if (flow.getExample() == example) {
                return flow.getSteps();
            }
        }
        throw new IllegalArgumentException("No setup " + example);
    }

    private static BuiltInFlow flow(final FlowExample example, final List<FlowStep> steps) {
        return new BuiltInFlow(example, example.getDisplayValue(), steps);
    }

    private static List<FlowStep> direct() {
        return List.of(
                step(null, QuestionKind.CHAIN, null, null, null, List.of()),
                step(null, QuestionKind.CONFIGURE, ConfigureRole.PARSER, null, null, List.of()),
                step(null, QuestionKind.CONFIGURE, ConfigureRole.TRANSFORM, null, null,
                        List.of(new Transition(null, "admit"))),
                step(null, QuestionKind.ADMIT, null, null, null, List.of()));
    }

    private static List<FlowStep> targetFirst() {
        return List.of(
                step(null, QuestionKind.CHAIN, null, null, null, List.of()),
                step(null, QuestionKind.SPLIT, null, null, null, List.of()),
                step(null, QuestionKind.TARGET, null, null, FlowStep.DEFAULT_KINDS, List.of()),
                step(null, QuestionKind.CONFIGURE, ConfigureRole.PARSER, null, null, List.of()),
                step(null, QuestionKind.CONFIGURE, ConfigureRole.TRANSFORM, null, null,
                        List.of(new Transition(StepOutcome.PRESERVATION_SHORT, "parser"),
                                new Transition(null, "admit"))),
                step(null, QuestionKind.ADMIT, null, null, null, List.of()));
    }

    private static List<FlowStep> escalating() {
        return List.of(
                step(null, QuestionKind.CHAIN, null, null, null, List.of()),
                step(null, QuestionKind.SPLIT, null, null, null, List.of()),
                step(null, QuestionKind.CONFIGURE, ConfigureRole.PARSER, null, null,
                        List.of(new Transition(StepOutcome.YIELD_SHORT, "split"))),
                step("first", QuestionKind.CONFIGURE, ConfigureRole.TRANSFORM, 2, null,
                        List.of(new Transition(StepOutcome.PASSED, "end"),
                                new Transition(null, "target"))),
                step(null, QuestionKind.TARGET, null, null, FlowStep.DEFAULT_KINDS, List.of()),
                step("again", QuestionKind.CONFIGURE, ConfigureRole.PARSER, null, null, List.of()),
                step(null, QuestionKind.CONFIGURE, ConfigureRole.TRANSFORM, null, null,
                        List.of(new Transition(StepOutcome.PRESERVATION_SHORT, "again"),
                                new Transition(null, "admit"))),
                step(null, QuestionKind.ADMIT, null, null, null, List.of()));
    }

    private static FlowStep step(final String id,
                                 final QuestionKind kind,
                                 final ConfigureRole role,
                                 final Integer candidates,
                                 final Integer kinds,
                                 final List<Transition> transitions) {
        return new FlowStep(id, kind, role, StepGuard.ALWAYS, candidates, kinds, transitions);
    }
}
