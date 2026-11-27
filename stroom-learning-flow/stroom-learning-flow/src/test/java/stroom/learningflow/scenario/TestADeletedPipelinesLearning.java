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

import stroom.docref.DocRef;
import stroom.learningflow.element.PipelineDeletions;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.LearningPipelines;
import stroom.pipeline.shared.PipelineDoc;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests what happens to a learner's learning when its pipeline is deleted.
///
/// The learning is marked as deleted but kept, so it can still be read. The streams waiting on the learner
/// are released, because no task of a deleted pipeline will ever process them again. An open session is
/// abandoned rather than resumed, so no more is spent on the model for a pipeline that is gone. The
/// Supervisor's lists leave the deleted pipeline's learning out unless they are asked to include it.
class TestADeletedPipelinesLearning {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";

    @Test
    void aDeletedPipelinesSessionIsAbandonedAndItsStreamsLetGo() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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
        // Importing an export of the pipeline brings it back with the same UUID, and Stroom raises a CREATE
        // event for it.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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

    private static LearningFlowDoc deferred() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
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
