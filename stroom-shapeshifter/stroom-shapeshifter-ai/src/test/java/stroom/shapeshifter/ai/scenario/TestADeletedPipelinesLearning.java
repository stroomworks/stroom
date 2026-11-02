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

package stroom.shapeshifter.ai.scenario;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.shapeshifter.ai.element.PipelineDeletions;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 06 A120, A134–A137: a pipeline's deletion, as its learning hears of it. The learning is marked
/// deleted and kept to be read; the streams waiting on it are let go, since no task of a deleted pipeline
/// will run them again; an open session is abandoned rather than carried on at the model's expense; and
/// the Supervisor's lists leave it out unless asked for it.
class TestADeletedPipelinesLearning {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";

    @Test
    void aDeletedPipelinesSessionIsAbandonedAndItsStreamsLetGo() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(parked.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(scenarios.deferrals.forDocument(DOC)).hasSize(1);

        deletions(scenarios).deleted(Scenarios.LEARNER.pipelineUuid());

        assertThat(scenarios.deletedPipelines.isDeleted(Scenarios.LEARNER.pipelineUuid())).isTrue();
        assertThat(scenarios.deferrals.forDocument(DOC))
                .describedAs("the waiting stream let go: no task of the pipeline will run it again")
                .isEmpty();

        final Script silent = Script.of();
        final int advanced = scenarios.worker(silent).advance(10);

        assertThat(advanced).isZero();
        assertThat(silent.asked()).describedAs("nothing asked of the model for a pipeline that is gone").isEmpty();
        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow())
                .satisfies(abandoned -> {
                    assertThat(abandoned.status()).isEqualTo(AttemptStatus.ABANDONED);
                    assertThat(abandoned.decision()).contains("pipeline").contains("deleted");
                });
    }

    @Test
    void theSupervisorShowsADeletedPipelinesLearningOnlyWhenAsked() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        scenarios.worker(learning(scenarios)).advance(10);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);

        deletions(scenarios).deleted(Scenarios.LEARNER.pipelineUuid());

        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of(DOC)).attempts())
                .describedAs("its attempts left out")
                .isEmpty();
        assertThat(scenarios.attempts.found(showingDeleted(), List.of(DOC)).attempts())
                .describedAs("until asked for")
                .hasSize(1);
        assertThat(scenarios.learningPipelines
                .learners(List.of(DOC), Set.of(), List.of(), false, 0L, 100).learners())
                .describedAs("and what served it")
                .isEmpty();
        final LearningPipelines.Page shown = scenarios.learningPipelines
                .learners(List.of(DOC), Set.of(), List.of(), true, 0L, 100);
        assertThat(shown.learners())
                .singleElement()
                .satisfies(binding -> assertThat(binding.isPipelineDeleted()).isTrue());
        assertThat(shown.total()).isOne();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("kept, to be read until it ages out")
                .hasSize(1);
    }

    @Test
    void aPipelineThatComesBackIsItsOwnAgain() {
        // An export of the pipeline imported keeps its uuid, and Stroom says it is created.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final PipelineDeletions deletions = deletions(scenarios);
        deletions.onChange(event(EntityAction.DELETE));
        assertThat(scenarios.deletedPipelines.isDeleted(Scenarios.LEARNER.pipelineUuid())).isTrue();

        deletions.onChange(event(EntityAction.CREATE));

        assertThat(scenarios.deletedPipelines.isDeleted(Scenarios.LEARNER.pipelineUuid()))
                .describedAs("not hidden, refused or aged out while it runs")
                .isFalse();
        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of(DOC)).attempts())
                .describedAs("its attempts shown again")
                .hasSize(1);
    }

    @Test
    void aDeletionHeardTwiceIsMarkedOnce() {
        final Scenarios scenarios = new Scenarios();

        assertThat(scenarios.deletedPipelines.deleted("pipeline-1", 1_000L)).isTrue();
        assertThat(scenarios.deletedPipelines.deleted("pipeline-1", 2_000L))
                .describedAs("every node hears the same delete; the first marks it")
                .isFalse();
        assertThat(scenarios.deletedPipelines.deletedOf(List.of("pipeline-1", "pipeline-2")))
                .containsExactly("pipeline-1");
    }

    private static PipelineDeletions deletions(final Scenarios scenarios) {
        return new PipelineDeletions(scenarios.deletedPipelines, scenarios.deferrals);
    }

    private static EntityEvent event(final EntityAction action) {
        return new EntityEvent(new DocRef(PipelineDoc.TYPE, Scenarios.LEARNER.pipelineUuid()), action);
    }

    private static AttemptCriteria showingDeleted() {
        return new AttemptCriteria(null, null, null, null, null, null, true, null);
    }

    private static ShapeshifterAiDoc deferred() {
        return ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), CSV.input());
    }

    private static Script learning(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }
}
