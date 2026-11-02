/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.ai.fragment;

import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.svg.shared.SvgImage;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judgement at the heart of the check, against element types built by hand with the roles the real
 * elements declare. The real registry needs a node's Guice graph to build; what it says about a type is a
 * set of role strings, and that is what is exercised here.
 */
class TestFragmentCheckImpl {

    private static final Map<String, PipelineElementType> TYPES = Map.of(
            "Source", type("Source", Category.INTERNAL, PipelineElementType.ROLE_SOURCE),
            "DSParser", type("DSParser", Category.PARSER, PipelineElementType.ROLE_PARSER,
                    PipelineElementType.ROLE_TARGET, PipelineElementType.ROLE_HAS_TARGETS),
            "XSLTFilter", type("XSLTFilter", Category.FILTER,
                    PipelineElementType.ROLE_TARGET, PipelineElementType.ROLE_HAS_TARGETS),
            "XMLWriter", type("XMLWriter", Category.WRITER, PipelineElementType.ROLE_TARGET,
                    PipelineElementType.ROLE_HAS_TARGETS, PipelineElementType.ROLE_WRITER),
            "FileAppender", type("FileAppender", Category.DESTINATION,
                    PipelineElementType.ROLE_TARGET, PipelineElementType.ROLE_DESTINATION));
    private static final Function<String, PipelineElementType> LOOKUP = TYPES::get;

    @Test
    void aChainEndingAtAFilterIsAFragment() {
        assertThat(FragmentCheckImpl.offendingElement(chain("Source", "DSParser", "XSLTFilter"), LOOKUP))
                .isEmpty();
    }

    @Test
    void aWriterIsNotAllowed() {
        assertThat(FragmentCheckImpl.offendingElement(chain("Source", "XSLTFilter", "XMLWriter"), LOOKUP))
                .map(PipelineElement::getId)
                .hasValue("xMLWriter");
    }

    @Test
    void aDestinationIsNotAllowed() {
        assertThat(FragmentCheckImpl.offendingElement(
                chain("Source", "XSLTFilter", "XMLWriter", "FileAppender"), LOOKUP))
                .map(PipelineElement::getType)
                .describedAs("the first offender is named, in chain order")
                .hasValue("XMLWriter");
    }

    @Test
    void anUnknownElementTypeIsLeftToThePipelineValidator() {
        assertThat(FragmentCheckImpl.offendingElement(chain("Source", "NotAnElement"), LOOKUP)).isEmpty();
    }

    private static List<PipelineElement> chain(final String... types) {
        return Arrays.stream(types)
                .map(type -> new PipelineElement(Character.toLowerCase(type.charAt(0)) + type.substring(1), type))
                .toList();
    }

    private static PipelineElementType type(final String name, final Category category, final String... roles) {
        return new PipelineElementType(name, name, category, roles, SvgImage.PIPELINE_FILE);
    }
}
