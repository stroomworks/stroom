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


package stroom.shapeshifter.ai.impl.db;

import stroom.db.util.JooqUtil;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterLearnerState;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.Binding;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// A learner's rows brought to the document it runs under (design 06 phase J slice 2b): every table that
/// keeps them, none of another learner's, and once per node until it moves again.
class TestLearnersDao {

    private static final String RUN = String.valueOf(System.nanoTime());

    @Inject
    private LearnersDao learners;
    @Inject
    private LearnerBindingsDao learnerBindings;
    @Inject
    private LearnerStatesDao states;
    @Inject
    private GuidanceDao guidance;
    @Inject
    private DeferralsDao deferrals;
    @Inject
    private ShapeshifterAiDbConnProvider connProvider;

    @Test
    void everyRowOfTheLearnerIsBroughtToItsDocumentAndNoOtherLearnersIs() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        final String before = "before-" + RUN;
        final String after = "after-" + RUN;
        final Learner learner = new Learner("pipeline-moved-" + RUN, "shapeshifterAi");
        final Learner neighbour = new Learner("pipeline-stayed-" + RUN, "shapeshifterAi");
        for (final Learner each : List.of(learner, neighbour)) {
            learnerBindings.append(each, before, Binding.builder()
                    .uuid("rule-" + each.pipelineUuid())
                    .build());
            states.giveUp(each, before, "Nothing to learn");
            deferrals.deferred(each, before, 8L, "DOOR-ACCESS", "Raw Events", null, "waiting", 0);
        }
        final long hint = guidance.given(learner, before, "Times are local.", "jo");

        learners.runsUnder(learner, after);

        assertThat(learnerBindings.byUuid("rule-" + learner.pipelineUuid()))
                .hasValueSatisfying(held -> assertThat(held.docUuid()).isEqualTo(after));
        assertThat(documentsOfStates(learner)).containsExactly(after);
        assertThat(deferrals.forDocument(after))
                .singleElement()
                .satisfies(row -> assertThat(row.learner()).isEqualTo(learner));
        guidance.withdraw(after, hint);
        assertThat(guidance.standing(learner))
                .describedAs("withdrawn by the document it is under now, which is who may change it")
                .isEmpty();

        assertThat(learnerBindings.byUuid("rule-" + neighbour.pipelineUuid()))
                .describedAs("the neighbour runs under the old document still, and stays there")
                .hasValueSatisfying(held -> assertThat(held.docUuid()).isEqualTo(before));
        assertThat(documentsOfStates(neighbour)).containsExactly(before);
        assertThat(deferrals.forDocument(before))
                .singleElement()
                .satisfies(row -> assertThat(row.learner()).isEqualTo(neighbour));
    }

    /// The task decision asks for every stream, so a node does nothing while the document is the one it
    /// last brought the learner to: a row written under another since is left for the next node — or
    /// the next move — to bring.
    @Test
    void aNodeBringsALearnerOnceUntilItMovesAgain() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        final Learner learner = new Learner("pipeline-once-" + RUN, "shapeshifterAi");
        states.giveUp(learner, "first-" + RUN, "Nothing to learn");
        learners.runsUnder(learner, "second-" + RUN);
        states.giveUp(learner, "first-" + RUN, "Written under the old document again");

        learners.runsUnder(learner, "second-" + RUN);

        assertThat(documentsOfStates(learner))
                .describedAs("remembered, so not asked of the database again")
                .containsExactly("first-" + RUN);

        learners.runsUnder(learner, "third-" + RUN);

        assertThat(documentsOfStates(learner))
                .describedAs("and brought as soon as it moves")
                .containsExactly("third-" + RUN);
    }

    private List<String> documentsOfStates(final Learner learner) {
        final ShapeshifterLearnerState state = ShapeshifterLearnerState.SHAPESHIFTER_LEARNER_STATE;
        return JooqUtil.contextResult(connProvider, context -> context
                .select(state.DOC_UUID)
                .from(state)
                .where(state.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(state.ELEMENT_ID.eq(learner.elementId()))
                .fetch(state.DOC_UUID));
    }
}
