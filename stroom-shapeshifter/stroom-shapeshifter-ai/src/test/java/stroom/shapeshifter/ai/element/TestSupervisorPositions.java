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

package stroom.shapeshifter.ai.element;

import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// The two positions a supervised stage can stand in (design 01 §3, §12 item 4), as the roles each
/// element declares.
///
/// The rule being held to is the pipeline editor's, in `StructureValidationUtil`: below a parser it
/// accepts any element that is not itself a reader, a parser or a destination. A supervisor that
/// declares the parser role therefore cannot be placed below one — which is why the `RECORD` position of
/// A1, and the extract-then-transform pair of §3, needed a second element that is a filter.
///
/// Stated here rather than by calling that rule because it lives in the GWT client and a server test
/// cannot reach it. What can drift is the roles, and it is the roles this holds.
class TestSupervisorPositions {

    /// The roles the editor refuses below a parser.
    private static final Set<String> REFUSED_BELOW_A_PARSER = Set.of(
            PipelineElementType.ROLE_READER,
            PipelineElementType.ROLE_PARSER,
            PipelineElementType.ROLE_DESTINATION);

    @Test
    void theExtractionStageIsAParserAndSoCannotStandBelowOne() {
        final ConfigurableElement element = ShapeshifterAiParser.class.getAnnotation(ConfigurableElement.class);
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
