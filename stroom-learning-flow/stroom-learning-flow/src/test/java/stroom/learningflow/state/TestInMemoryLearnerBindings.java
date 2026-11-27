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


package stroom.learningflow.state;

import stroom.learningflow.shared.Binding;
import stroom.learningflow.stage.Learner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests that the in-memory learner bindings enforce the same rules as the database table: a UUID identifies one
/// binding across every learner, and a write must name the learner the binding belongs to. Otherwise a scenario
/// test that wrote another learner's binding by mistake would pass in memory and fail on a real node.
class TestInMemoryLearnerBindings {

    private static final Learner LEARNER = new Learner("pipeline-1", "learningFlow");
    private static final Learner NEIGHBOUR = new Learner("pipeline-2", "learningFlow");

    @Test
    void anotherLearnersBindingIsNotThisOnesToReplaceOrRemove() {
        final InMemoryLearnerBindings learnerBindings = new InMemoryLearnerBindings();
        final Binding bound = learnerBindings.append(NEIGHBOUR, "doc-1", Binding.builder().uuid("rule-1").build());

        assertThatThrownBy(() -> learnerBindings.replace(LEARNER, "doc-1", bound.copy().score(1.0).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("binding of pipeline-2");
        learnerBindings.remove(LEARNER, "rule-1");

        assertThat(learnerBindings.forLearner(NEIGHBOUR)).containsExactly(bound);
        assertThat(learnerBindings.forLearner(LEARNER)).isEmpty();
    }

    @Test
    void aLearnersBindingsAreBroughtToItsDocumentAndNoOtherLearnersAre() {
        final InMemoryLearnerBindings learnerBindings = new InMemoryLearnerBindings();
        learnerBindings.append(LEARNER, "doc-1", Binding.builder().uuid("rule-1").build());
        learnerBindings.append(NEIGHBOUR, "doc-1", Binding.builder().uuid("rule-2").build());

        learnerBindings.runsUnder(LEARNER, "doc-2");

        assertThat(learnerBindings.byUuid("rule-1")).hasValueSatisfying(held -> assertThat(held.docUuid())
                .isEqualTo("doc-2"));
        assertThat(learnerBindings.byUuid("rule-2")).hasValueSatisfying(held -> assertThat(held.docUuid())
                .isEqualTo("doc-1"));
    }
}
