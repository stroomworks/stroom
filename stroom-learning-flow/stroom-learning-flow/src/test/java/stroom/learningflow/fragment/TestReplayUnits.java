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

package stroom.learningflow.fragment;

import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.extraction.XmlFragmentStep;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.shared.ReplayUnit;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.pipeline.factory.ElementRegistry;
import stroom.pipeline.shared.data.PipelineElementType;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests how `ReplayUnits` works out what a chain can be replayed over, which is decided from the chain's
/// elements rather than declared.
///
/// A chain with a parser is replayed over the whole stream, because the raw bytes a parser reads cannot be
/// rebuilt from one record. A chain with no parser is replayed over one record, which is what elements after
/// a parser are given anyway. The element's position check uses this to report a chain that does not fit where
/// the Smart Pipeline element stands.
class TestReplayUnits {

    private static final Map<String, StepRunner> RUNNERS = List.of(
                    new DataSplitterStep(null), new JsonStep(), new XmlFragmentStep(), new StandInXsltStep())
            .stream()
            .collect(Collectors.toMap(StepRunner::elementType, Function.identity()));


    @Test
    void aChainThatParsesIsReplayedOverTheStream() {
        assertThat(unitOf(List.of("DSParser", "XSLTFilter"))).isEqualTo(ReplayUnit.STREAM);
        assertThat(unitOf(List.of("JSONParser", "XSLTFilter"))).isEqualTo(ReplayUnit.STREAM);
        assertThat(unitOf(List.of("XMLFragmentParser", "XSLTFilter"))).isEqualTo(ReplayUnit.STREAM);
    }

    @Test
    void aChainThatBeginsWithATransformIsReplayedOverOneRecord() {
        assertThat(unitOf(List.of("XSLTFilter"))).isEqualTo(ReplayUnit.RECORD);
        assertThat(unitOf(List.of("XSLTFilter", "XSLTFilter"))).isEqualTo(ReplayUnit.RECORD);
    }

    @Test
    void aMismatchSaysWhatTheStageIsGivenAndWhatTheVariantExpected() {
        assertThat(ReplayUnits.mismatch(ReplayUnit.RECORD, "Pipeline x"))
                .isEqualTo("Pipeline x has no parser, but this stage is fed by the source and is given "
                           + "raw data: something must parse it");
        assertThat(ReplayUnits.mismatch(ReplayUnit.STREAM, "Pipeline y"))
                .isEqualTo("Pipeline y has a parser, but the document's allowed elements have none: its "
                           + "stage expects markup it does not parse");
    }

    @Test
    void anElementParsesWhereTheRegistryGivesItTheParserRole() {
        final ElementRegistry registry = Mockito.mock(ElementRegistry.class);
        Mockito.when(registry.getElementType("DSParser")).thenReturn(type("DSParser",
                PipelineElementType.ROLE_PARSER, PipelineElementType.ROLE_HAS_TARGETS));
        Mockito.when(registry.getElementType("XSLTFilter")).thenReturn(type("XSLTFilter",
                PipelineElementType.ROLE_TARGET, PipelineElementType.ROLE_HAS_TARGETS));

        assertThat(ReplayUnits.parses(registry, "DSParser")).isTrue();
        assertThat(ReplayUnits.parses(registry, "XSLTFilter")).isFalse();
        assertThat(ReplayUnits.parses(registry, "NotAnElement"))
                .describedAs("a type the registry does not know does not parse")
                .isFalse();
    }

    private static PipelineElementType type(final String name, final String... roles) {
        return new PipelineElementType(name, name, null, roles, null);
    }

    private static ReplayUnit unitOf(final List<String> chain) {
        return ReplayUnits.ofElements(chain, type -> RUNNERS.containsKey(type) && RUNNERS.get(type).parser());
    }
}
