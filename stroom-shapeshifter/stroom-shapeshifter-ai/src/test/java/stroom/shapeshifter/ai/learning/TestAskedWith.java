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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Decision N9, option D extended: a run records the words it was asked with, so that it resumes in
/// them rather than in whatever the Learning Flow says by the time the worker reaches it.
class TestAskedWith {

    private static final Sample SAMPLE = new Sample("a,b,c", Map.of());

    @Test
    void itRecordsEveryTemplateResolvedAndTheInstructions() {
        final ShapeshifterAiDoc doc = doc("Badge readers.", "Pick one:\n${elements}${sample}${feedback}");

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
        // The point of the whole thing. A session is a conversation: every turn before this one was put
        // in the words the run began with, and the model is shown its own history as it resumes.
        final ShapeshifterAiDoc opened = doc("Badge readers.", "Pick one:\n${elements}${sample}${feedback}");
        final AskedWith asked = AskedWith.of(opened, "7.13-beta.3");

        // Somebody edits the Learning Flow while the attempt is parked.
        final ShapeshifterAiDoc edited = doc("Turnstiles now.", "Choose:\n${elements}${sample}${feedback}");

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
        // Every attempt opened before N9. Saying "unknown" and using the document is honest; offering
        // today's words as though they were the ones used would not be.
        final ShapeshifterAiDoc doc = doc("Turnstiles now.", "Choose:\n${elements}${sample}${feedback}");

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
        // What makes a rebuilt prompt worth a caveat: the checks' wording travels with the built-ins.
        final AskedWith older = new AskedWith(Templates.VERSION - 1,
                Map.of(Template.CHAIN, "Pick one:${elements}${sample}${feedback}"), null, "7.12");

        assertThat(older.olderThanBuiltIn()).isTrue();
        assertThat(AskedWith.of(doc(null, null), "7.13").olderThanBuiltIn()).isFalse();
    }

    @Test
    void aTemplateThisBuildDoesNotKnowFallsBackToTheBuiltIn() {
        // A recording made before a template was added names everything but it; rendering must not fail.
        final AskedWith partial = new AskedWith(Templates.VERSION,
                Map.of(Template.CHAIN, "Pick one:${elements}${sample}${feedback}"), null, "7.13");

        assertThat(partial.asTemplates().text(Template.CHAIN)).startsWith("Pick one:");
        assertThat(partial.asTemplates().text(Template.SPLIT))
                .describedAs("one the recording does not name is the built-in, not nothing")
                .isEqualTo(Templates.builtIn(Template.SPLIT));
    }

    private static ShapeshifterAiDoc doc(final String instructions, final String chain) {
        final LearningFlow flow = chain == null
                ? FlowExamples.flow(FlowExample.TARGET_FIRST)
                : FlowExamples.flow(FlowExample.TARGET_FIRST).withTemplates(Map.of(Template.CHAIN, chain));
        return ShapeshifterAiDoc.builder()
                .uuid("doc-1")
                .name("door-access")
                .instructions(instructions)
                .flow(flow)
                .build();
    }
}
