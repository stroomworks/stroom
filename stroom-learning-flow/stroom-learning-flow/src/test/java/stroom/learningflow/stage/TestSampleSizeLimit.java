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

/// Audit finding S4: **one sample size limit, in bytes, that the prompt honours.**
///
/// The sample the model was shown used to be cut twice: once to the Learning Flow's limit, and again by
/// a fixed 6,000 characters inside the prompt. With the default limit of 8,192 the model never saw the
/// last quarter of its own sample, while the parser's split was judged on all of it — and raising the
/// limit to give a strong model more context did nothing at all above 6,000.
class TestSampleSizeLimit {

    @Test
    void theWholeSampleReachesTheQuestion() {
        // 7,000 characters: under the default limit of 8,192, over the 6,000 the prompt used to cut at
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
        // The editor has always said "(bytes)" while the code counted characters, so a feed in a language
        // that does not fit in one byte a character was given up to three times the sample it was set to
        // have — and every model is billed by what it is sent
        final LearningFlowDoc doc = LearningFlowDoc.builder()
                .uuid("d")
                .name("d")
                .sampleSizeLimit(100)
                .heldOutFraction(0.0)
                .build();
        // Three bytes a character, one character a line
        final String data = "€\n".repeat(200);

        final String prefix = LearningPrefix.learningPrefix(data, doc);

        assertThat(Input.bytes(prefix)).isLessThanOrEqualTo(100);
        assertThat(prefix.length()).describedAs("which is far fewer characters than the limit").isLessThan(100);
    }

    @Test
    void aSampleCutFromItsStreamSaysSoWhenAStepGivesUp() {
        // Supervisor visibility item 3: a person told "couldn't write the parser" is owed the fact that
        // the model saw a fraction of the stream
        final Sample cut = new Sample("shown", Map.<String, String>of()).cutFrom(1_000);

        assertThat(cut.notShown()).isEqualTo(995);
        assertThat(new Sample("shown", Map.<String, String>of()).cutFrom(5).notShown())
                .describedAs("nothing held back, nothing to say")
                .isZero();
    }
}
