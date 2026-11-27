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

package stroom.learningflow.scenario;

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.RecordedAdvisor;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Guidance.Given;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests guidance: a message a person leaves for a learner, which every question asked after it carries.
///
/// Guidance attaches to the learner rather than to a turn or an attempt, because what a person knows is
/// about the feed, not about one particular turn. So nothing has to be timed, nothing is refused for arriving
/// at the wrong moment, and a hint given before anything has been learned is carried by the first question.
class TestSupervisorGuidance {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void everyQuestionCarriesWhatASupervisorHasSaidToTheLearner() {
        final Scenarios scenarios = new Scenarios();
        final Script script = script(scenarios);
        final Carrying carrying = new Carrying(script);
        final Stage stage = scenarios.stage(document -> carrying, scenarios.learnerBindings);

        final long first = scenarios.guidance.given(Scenarios.LEARNER, "doc-1",
                "This feed's timestamps are local, not UTC.", "jo");
        final long second = scenarios.guidance.given(Scenarios.LEARNER, "doc-1",
                "The fourth column is a terminal id, not a user.", "sam");

        final StageRun learned = scenarios.process(carrying, doc(), stream()).learned();

        assertThat(learned.produced()).describedAs("it still learned").isNotNull();
        assertThat(carrying.carried)
                .describedAs("every question, not just the first: a hint is standing context and not a "
                             + "turn of the conversation")
                .isNotEmpty()
                .allSatisfy(carried -> assertThat(carried)
                        .extracting(Given::message)
                        .containsExactly("This feed's timestamps are local, not UTC.",
                                "The fourth column is a terminal id, not a user."));
        assertThat(carrying.carried.get(0))
                .describedAs("oldest first, and attributed: the model should weigh a person's words as "
                             + "a person's, and a reader has to know whose they were")
                .extracting(Given::author).containsExactly("jo", "sam");
        assertThat(learned.transcript())
                .describedAs("and each turn records which guidance it carried, so that a re-walk (A45) "
                             + "replays what was used rather than what has since been added")
                .allSatisfy(turn -> assertThat(turn.carried()).containsExactly(first, second));
    }

    @Test
    void aLearnerNobodyHasSaidAnythingToCarriesNothing() {
        final Scenarios scenarios = new Scenarios();
        final Carrying carrying = new Carrying(script(scenarios));
        final Stage stage = scenarios.stage(document -> carrying, scenarios.learnerBindings);
        scenarios.guidance.given(new Learner("pipeline-2", Scenarios.LEARNER.elementId()), "doc-1",
                "Said to another pipeline's learner.", "jo");

        final StageRun learned = scenarios.process(carrying, doc(), stream()).learned();

        assertThat(learned.produced()).isNotNull();
        assertThat(carrying.carried)
                .describedAs("a hint is said to one learner, and a neighbour's is not this one's")
                .isNotEmpty()
                .allSatisfy(carried -> assertThat(carried).isEmpty());
        assertThat(learned.transcript()).allSatisfy(turn -> assertThat(turn.carried()).isEmpty());
    }

    @Test
    void aHintTakenBackIsNotCarried() {
        final Scenarios scenarios = new Scenarios();
        final Carrying carrying = new Carrying(script(scenarios));
        final Stage stage = scenarios.stage(document -> carrying, scenarios.learnerBindings);
        final long wrong = scenarios.guidance.given(Scenarios.LEARNER, "doc-1", "Something mistaken.", "jo");
        scenarios.guidance.withdraw("doc-1", wrong);

        scenarios.process(carrying, doc(), stream()).learned();

        assertThat(carrying.carried)
                .describedAs("a hint that turned out to be wrong is worse than no hint: it would be "
                             + "carried into every question about the learner from then on")
                .allSatisfy(carried -> assertThat(carried).isEmpty());
    }

    /// Covers an attempt resumed from its record, as the worker resumes one. Every question goes through an
    /// advisor that wraps the record, so a hint reaches the model only if that wrapper passes it on. A turn
    /// replayed from the record must keep the guidance it was originally asked with, not what stands now, or
    /// replaying would rewrite the attempt's history.
    @Test
    void aResumedAttemptCarriesTheGuidanceStandingWhenItResumes() {
        final Scenarios scenarios = new Scenarios();
        // The chain question is answered from the record, so the script begins where the model is asked.
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final Carrying carrying = new Carrying(script);
        final long before = scenarios.guidance.given(Scenarios.LEARNER, "doc-1",
                "Said before anything was asked.", "jo");

        // One turn is already answered, as for a waiting attempt: the record answers it, and the model is
        // asked the rest.
        final Turn answered = new Turn(1, "chain", 1, QuestionKind.CHAIN,
                "Chain: choose from [DSParser, XSLTFilter]", "DSParser -> XSLTFilter", "the model", null,
                List.of(before));
        final RecordedAdvisor resumed = new RecordedAdvisor(List.of(answered), carrying);

        final long after = scenarios.guidance.given(Scenarios.LEARNER, "doc-1", "Said while it was parked.", "sam");
        final Stage stage = scenarios.stage(document -> resumed, scenarios.learnerBindings);
        final StageRun learned = scenarios.process(resumed, doc(), stream()).learned();

        assertThat(learned.produced()).describedAs("it carried on and bound something").isNotNull();
        assertThat(carrying.carried)
                .describedAs("the questions the model was actually asked carry both, including the one "
                             + "given while the attempt was parked — a wrapper that dropped them would "
                             + "leave the model none the wiser while the record claimed otherwise")
                .isNotEmpty()
                .allSatisfy(carried -> assertThat(carried).extracting(Given::id)
                        .containsExactly(before, after));
        assertThat(learned.transcript().get(0).carried())
                .describedAs("and the replayed turn keeps what it was asked with then, not what has "
                             + "since been added")
                .containsExactly(before);
        assertThat(learned.transcript().get(1).carried())
                .describedAs("while the turns actually put carry what stood when they were put")
                .containsExactly(before, after);
    }

    private Script script(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .build();
    }

    private static Input stream() {
        return new Input(1L, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }


    // --------------------------------------------------------------------------------


    /// An advisor that records the guidance each question was asked with. What the scripted advisor answers
    /// does not matter here; what reaches it does.
    private static final class Carrying implements Advisor {

        private final List<List<Given>> carried = new ArrayList<>();
        private final Advisor answering;

        private Carrying(final Advisor answering) {
            this.answering = answering;
        }

        @Override
        public String ask(final List<Exchange> transcript, final Question question) {
            return ask(transcript, question, List.of());
        }

        @Override
        public String ask(final List<Exchange> transcript,
                          final Question question,
                          final List<Given> guidance) {
            carried.add(List.copyOf(guidance));
            return answering.ask(transcript, question);
        }
    }
}
