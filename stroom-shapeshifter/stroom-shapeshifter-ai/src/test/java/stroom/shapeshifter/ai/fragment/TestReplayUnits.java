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

package stroom.shapeshifter.ai.fragment;

import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.extraction.XmlFragmentStep;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.ReplayUnit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ruling A1 (design 01 §4): what a chain can be replayed over is decided by the chain, not declared.
 * A parser in it means the stream, because the bytes above a parser cannot be replayed from a record;
 * no parser means one record, which is what the elements below a parser are given anyway.
 */
class TestReplayUnits {

    private static final Map<String, StepRunner> RUNNERS = List.of(
                    new DataSplitterStep(null), new JsonStep(), new XmlFragmentStep(), new XsltStep())
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

    private static ReplayUnit unitOf(final List<String> chain) {
        return ReplayUnits.ofElements(chain, type -> RUNNERS.containsKey(type) && RUNNERS.get(type).parser());
    }
}
