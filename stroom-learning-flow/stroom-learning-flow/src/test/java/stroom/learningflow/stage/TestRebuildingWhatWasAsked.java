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

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.scenario.QuestionMatcher;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scenario.Script;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.stage.Attempts.Attempt;
import stroom.learningflow.stage.Attempts.Recorded;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Decision N9, option D extended, Track 2 step 4: the prompt a turn was put to the model with is rebuilt
/// when someone asks for it, from the words the run recorded and the sample read back from its stream,
/// rather than stored. The rebuild asks nobody and writes nothing.
class TestRebuildingWhatWasAsked {

    private static final String DOC = "doc-1";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Input STREAM = new Input(1L, "DOOR-ACCESS", "Raw Events", Map.of(), CSV.input());

    @Test
    void eachTurnIsRebuiltInTheRunsOwnWordsOverItsSample() {
        final Scenarios scenarios = new Scenarios();
        final Recorded run = learned(scenarios);
        // The Learning Flow is edited after the run: the rebuild must say what was asked then
        final LearningFlowDoc edited = doc("Turnstiles now.");
        scenarios.documents.put(edited);
        final int turnsBefore = run.turns().size();

        final Rebuilt rebuilt = scenarios.stage(Script.of()).rebuild(edited, run.id(), STREAM).orElseThrow();

        assertThat(rebuilt.prompts()).containsKey(1);
        assertThat(rebuilt.prompts().get(1))
                .describedAs("the run's instructions, not the document's as it stands")
                .contains("Badge readers.")
                .doesNotContain("Turnstiles now.")
                .describedAs("and the sample, read back from the stream")
                .contains(CSV.input().lines().findFirst().orElseThrow());
        assertThat(rebuilt.prompts().keySet()).describedAs("every turn of the walk")
                .containsAll(run.turns().stream().map(Attempts.Turn::number).toList());
        assertThat(rebuilt.olderThanBuiltIn()).isFalse();
        assertThat(scenarios.attempts.byId(run.id()).orElseThrow().turns())
                .describedAs("and nothing was written").hasSize(turnsBefore);
    }

    @Test
    void aRunWhoseWordsWereNotKeptIsNotRebuilt() {
        // Rather than shown today's words as though they were the ones used
        final Scenarios scenarios = new Scenarios();
        scenarios.documents.put(doc("Badge readers."));
        final long id = scenarios.attempts.opened(new Attempt(Scenarios.LEARNER, DOC, "DOOR-ACCESS",
                "Raw Events", 1L, "node-1", PromotionMode.AUTOMATIC, Long.MAX_VALUE, AskedWith.UNKNOWN),
                Scenarios.NOW.toEpochMilli()).orElseThrow();

        assertThat(scenarios.stage(Script.of()).rebuild(doc("Badge readers."), id, STREAM)).isEmpty();
    }

    // A run that learned the stream, its turns recorded with the words it was asked in
    private static Recorded learned(final Scenarios scenarios) {
        final LearningFlowDoc doc = doc("Badge readers.");
        scenarios.documents.put(doc);
        scenarios.inputs.put(STREAM);
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        scenarios.process(script, doc, STREAM);
        final Recorded run = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(run.attempt().askedWith().known()).isTrue();
        assertThat(run.turns()).isNotEmpty();
        return run;
    }

    private static LearningFlowDoc doc(final String instructions) {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .instructions(instructions)
                .build();
    }
}
