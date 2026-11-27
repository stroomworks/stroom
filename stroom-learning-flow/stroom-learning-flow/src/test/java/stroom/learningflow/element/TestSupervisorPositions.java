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

package stroom.learningflow.element;

import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the Smart Pipeline element declares the parser role, so it can only stand at a parser's position
/// in a pipeline.
///
/// The pipeline editor's rule, in `StructureValidationUtil`, accepts below a parser any element that is not
/// itself a reader, a parser or a destination. An element that declares the parser role therefore cannot be
/// placed below another parser.
///
/// The rule is restated here rather than called, because it lives in the GWT client where a server test
/// cannot reach it. What could drift is the roles the element declares, so that is what this test checks.
class TestSupervisorPositions {

    /// The roles the pipeline editor refuses below a parser.
    private static final Set<String> REFUSED_BELOW_A_PARSER = Set.of(
            PipelineElementType.ROLE_READER,
            PipelineElementType.ROLE_PARSER,
            PipelineElementType.ROLE_DESTINATION);

    @Test
    void theExtractionStageIsAParserAndSoCannotStandBelowOne() {
        final ConfigurableElement element = LearningFlowParser.class.getAnnotation(ConfigurableElement.class);
        assertThat(element.category()).isEqualTo(Category.PARSER);
        assertThat(roles(element))
                .describedAs("it is given the stream, so it stands where a parser stands (A1)")
                .contains(PipelineElementType.ROLE_PARSER);
        assertThat(roles(element))
                .describedAs("and that is exactly what the editor refuses below a parser")
                .containsAnyElementsOf(REFUSED_BELOW_A_PARSER);
    }

    private static List<String> roles(final ConfigurableElement element) {
        return List.of(element.roles());
    }
}
