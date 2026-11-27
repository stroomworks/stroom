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

package stroom.learningflow.stage;

import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.QuestionText;
import stroom.learningflow.learning.Sample;
import stroom.learningflow.shared.LearningFlowDoc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that there is one sample size limit, measured in bytes, and that the prompt honours it.
///
/// The sample is cut only to the Learning Flow's limit. A second, fixed cut inside the prompt would hide part
/// of the sample from the model while the parser's split was still judged on all of it, and would make raising
/// the limit to give a strong model more context do nothing.
class TestSampleSizeLimit {

    @Test
    void theWholeSampleReachesTheQuestion() {
        // 7,000 characters: under the default limit of 8,192, and over the 6,000 at which an earlier version
        // of the prompt cut the sample
        final String sample = "x".repeat(7_000);

        assertThat(QuestionText.builtIn(null).render(new Question.Chain(new Sample(sample, Map.of()),
                java.util.List.of("DSParser"), java.util.List.of())))
                .describedAs("not cut, and no note saying it was")
                .contains(sample)
                .doesNotContain("(cut after");
    }

    @Test
    void aSampleOverTheLimitIsCutWithANoteInBytes() {
        final String sample = "x".repeat(20_000);

        assertThat(QuestionText.builtIn(null).render(new Question.Chain(new Sample(sample, Map.of()),
                java.util.List.of("DSParser"), java.util.List.of())))
                .contains("(cut after 8192 of 20000 bytes)");
    }

    @Test
    void theLimitIsCountedInBytesNotCharacters() {
        // The editor describes the limit in bytes. Counting characters instead would give a feed whose
        // characters take more than one byte up to three times the sample it was set to have, and every model
        // is billed by what it is sent
        final LearningFlowDoc doc = LearningFlowDoc.builder()
                .uuid("d")
                .name("d")
                .sampleSizeLimit(100)
                .heldOutFraction(0.0)
                .build();
        // Three bytes per character, one character per line
        final String data = "€\n".repeat(200);

        final String prefix = LearningPrefix.learningPrefix(data, doc);

        assertThat(Input.bytes(prefix)).isLessThanOrEqualTo(100);
        assertThat(prefix.length()).describedAs("which is far fewer characters than the limit").isLessThan(100);
    }

    @Test
    void aSampleCutFromItsStreamSaysSoWhenAStepGivesUp() {
        // A person told "couldn't write the parser" needs to know that the model saw only part of the stream
        final Sample cut = new Sample("shown", Map.<String, String>of()).cutFrom(1_000);

        assertThat(cut.notShown()).isEqualTo(995);
        assertThat(new Sample("shown", Map.<String, String>of()).cutFrom(5).notShown())
                .describedAs("nothing held back, nothing to say")
                .isZero();
    }
}
