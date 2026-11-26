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

package stroom.learningflow.learning;

import stroom.learningflow.learning.Question.Chain;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.Template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests `AskedWith`, the record of the question text a learning run was asked with.
///
/// A run records its templates and instructions when it opens, so that when it resumes it asks in the same
/// words, rather than in whatever the Learning Flow says by the time the worker reaches it. The record also
/// lets a turn's prompt be rebuilt later.
class TestAskedWith {

    private static final Sample SAMPLE = new Sample("a,b,c", Map.of());

    @Test
    void itRecordsEveryTemplateResolvedAndTheInstructions() {
        final LearningFlowDoc doc = doc("Badge readers.", "Pick one:\n${elements}${sample}${feedback}");

        final AskedWith asked = AskedWith.of(doc, "7.13-beta.3");

        assertThat(asked.known()).isTrue();
        assertThat(asked.builtInVersion()).isEqualTo(Templates.VERSION);
        assertThat(asked.instructions()).isEqualTo("Badge readers.");
        assertThat(asked.build()).isEqualTo("7.13-beta.3");
        assertThat(asked.templates())
                .describedAs("every template, not only the one the flow overrode: a prompt has to be "
                             + "rebuildable after an upgrade without the old built-ins still in the code")
                .containsOnlyKeys(Template.values());
        assertThat(asked.templates().get(Template.CHAIN)).startsWith("Pick one:");
        assertThat(asked.templates().get(Template.SPLIT))
                .describedAs("a template the flow did not override is recorded at its built-in text")
                .isEqualTo(Templates.builtIn(Template.SPLIT));
    }

    @Test
    void aRunResumesInItsOwnWordsAfterTheFlowIsEdited() {
        // This is the main reason for recording the words. A session is a conversation: every earlier turn
        // was asked in the words the run began with, and the model is shown that history when it resumes.
        final LearningFlowDoc opened = doc("Badge readers.", "Pick one:\n${elements}${sample}${feedback}");
        final AskedWith asked = AskedWith.of(opened, "7.13-beta.3");

        // Somebody edits the Learning Flow while the attempt is parked.
        final LearningFlowDoc edited = doc("Turnstiles now.", "Choose:\n${elements}${sample}${feedback}");

        final Chain chain = new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of());
        assertThat(QuestionText.of(edited, asked).render(chain))
                .describedAs("asked in the words it began in")
                .startsWith("Pick one:");
        assertThat(QuestionText.of(edited, asked).system())
                .describedAs("with the instructions it had, not the ones it would have now")
                .contains("Badge readers.")
                .doesNotContain("Turnstiles now.");

        assertThat(QuestionText.of(edited).render(chain))
                .describedAs("while a run opening now is asked in the words that stand")
                .startsWith("Choose:");
    }

    @Test
    void aRunWhoseWordsWereNotRecordedFallsBackToTheFlow() {
        // Attempts opened by an earlier version did not record their words. Saying "unknown" and using the
        // document's words is honest; presenting today's words as the ones that were used would not be.
        final LearningFlowDoc doc = doc("Turnstiles now.", "Choose:\n${elements}${sample}${feedback}");

        assertThat(AskedWith.UNKNOWN.known()).isFalse();
        assertThat(AskedWith.UNKNOWN.olderThanBuiltIn())
                .describedAs("nothing to compare, so nothing to caveat")
                .isFalse();
        assertThat(QuestionText.of(doc, AskedWith.UNKNOWN).render(
                new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of())))
                .startsWith("Choose:");
    }

    @Test
    void aRunAskedAgainstAnOlderBuiltInSaysSo() {
        // A rebuilt prompt needs a caveat when the built-in version has changed since the run was asked,
        // because the wording of the checks comes from the current built-in text.
        final AskedWith older = new AskedWith(Templates.VERSION - 1,
                Map.of(Template.CHAIN, "Pick one:${elements}${sample}${feedback}"), null, "7.12");

        assertThat(older.olderThanBuiltIn()).isTrue();
        assertThat(AskedWith.of(doc(null, null), "7.13").olderThanBuiltIn()).isFalse();
    }

    @Test
    void aTemplateThisBuildDoesNotKnowFallsBackToTheBuiltIn() {
        // A recording made before a template was added lacks that template, and rendering must not fail.
        final AskedWith partial = new AskedWith(Templates.VERSION,
                Map.of(Template.CHAIN, "Pick one:${elements}${sample}${feedback}"), null, "7.13");

        assertThat(partial.asTemplates().text(Template.CHAIN)).startsWith("Pick one:");
        assertThat(partial.asTemplates().text(Template.SPLIT))
                .describedAs("one the recording does not name is the built-in, not nothing")
                .isEqualTo(Templates.builtIn(Template.SPLIT));
    }

    private static LearningFlowDoc doc(final String instructions, final String chain) {
        final LearningFlow flow = chain == null
                ? FlowExamples.flow(FlowExample.TARGET_FIRST)
                : FlowExamples.flow(FlowExample.TARGET_FIRST).withTemplates(Map.of(Template.CHAIN, chain));
        return LearningFlowDoc.builder()
                .uuid("doc-1")
                .name("door-access")
                .instructions(instructions)
                .flow(flow)
                .build();
    }
}
