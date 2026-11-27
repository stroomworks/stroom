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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestFlowStep {

    @Test
    void stepsThatOfferOtherElementsAreNotEqual() {
        // The Setup tab keeps an edit only where the step changed, so a step's element types must count in
        // its equality or an edit to them would be lost
        final FlowStep chain = FlowStep.of(QuestionKind.CHAIN);
        final FlowStep fewer = chain.withElements(List.of("DSParser", "XSLTFilter"));

        assertThat(fewer).isNotEqualTo(chain);
        assertThat(fewer).isEqualTo(chain.withElements(List.of("DSParser", "XSLTFilter")));
        assertThat(fewer.hashCode()).isEqualTo(chain.withElements(List.of("DSParser", "XSLTFilter")).hashCode());
        // Blank names are no elements, so they change nothing
        assertThat(chain.withElements(List.of(" ", ""))).isEqualTo(chain);
    }

    @Test
    void aFlowWithOtherElementsIsNoLongerTheExample() {
        final List<FlowStep> steps = FlowFixtures.direct();
        final LearningFlow changed = new LearningFlow(steps, null, null)
                .withChainElements(List.of("XMLParser"));

        assertThat(changed.getSteps()).isNotEqualTo(steps);
        assertThat(changed.allowedElements()).containsExactly("XMLParser");
    }
}
