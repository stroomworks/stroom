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

package stroom.learningflow.shared;

import java.util.List;

/// Two of the built-in example flows, for the tests in this module that need them.
///
/// **This is a deliberate copy.** The examples are defined once, as JSON read by `FlowExamples` in
/// `stroom-learning-flow`, and `TestFlowExamples` checks their shape. A test in `stroom-core-shared` cannot
/// reach that module (the dependency runs the other way), so a test here that needs the shape of an example
/// builds it.
///
/// Only the `FlowStep` and `LearningFlowDoc` tests need this. A test that merely wants some valid flow should
/// build the steps it means, which says more clearly what it is testing.
final class FlowFixtures {

    private FlowFixtures() {
    }

    /// `Direct`: the chain, the parser and transform configuration steps, and the fallback step.
    ///
    /// @return Its steps.
    static List<FlowStep> direct() {
        return List.of(
                step(null, QuestionKind.CHAIN, null, null, null, List.of()),
                step(null, QuestionKind.CONFIGURE, ConfigureRole.PARSER, null, null, List.of()),
                step(null, QuestionKind.CONFIGURE, ConfigureRole.TRANSFORM, null, null,
                        List.of(new Transition(null, "admit"))),
                step(null, QuestionKind.ADMIT, null, null, null, List.of()));
    }

    /// `Target first`: the record boundary and the target events settled before any configuration, with a
    /// transition back to the parser when the records lack a value the targets need.
    ///
    /// @return Its steps.
    static List<FlowStep> targetFirst() {
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

    private static FlowStep step(final String id,
                                 final QuestionKind kind,
                                 final ConfigureRole role,
                                 final Integer candidates,
                                 final Integer kinds,
                                 final List<Transition> transitions) {
        return new FlowStep(id, kind, role, StepGuard.ALWAYS, candidates, kinds, transitions);
    }
}
