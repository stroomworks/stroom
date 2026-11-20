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

import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.SpendBreaker;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Track 2 step 3 (ruling A24): **the spend breaker stops new sessions, and the streams wait.**
///
/// An attempt's own budgets bound one session. A feed that keeps opening them was bounded by nothing —
/// and unattended learning without a ceiling has no upper bound on cost at all
/// (`simplification-options.md` §5).
class TestTheSpendBreakerStopsNewSessions {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");

    @Test
    void aStreamWaitsRatherThanOpeningASessionThatWouldCostMoreThanTheCeiling() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        // The hour's allowance is already gone, spent by sessions this document opened earlier
        scenarios.spend.record(DOC, 1_000L, 13, SpendBreaker.WINDOW_MS);
        final Script silent = Script.of();

        final StageRun run = scenarios.stage(document -> silent, scenarios.learnerBindings)
                .decide(doc, Scenarios.LEARNER, stream(1L));

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) run.decision()).reason())
                .contains("This Learning Flow spent 1000 token(s) in the last hour")
                .contains("No new learning starts until the hour rolls");
        assertThat(silent.asked()).describedAs("nothing is put to the model").isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10))
                .describedAs("and no session is opened to be paid for")
                .isEmpty();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 1L))
                .describedAs("the stream waits, and is tried again: nothing is lost by refusing")
                .isPresent();
    }

    @Test
    void theSameStreamIsLearnedOnceTheHourHasRolled() {
        // The breaker is a pause, not a refusal: what it stops costs nothing and comes back by itself
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.spend.record(DOC, 1_000L, 13, SpendBreaker.WINDOW_MS);
        final Script silent = Script.of();
        scenarios.stage(document -> silent, scenarios.learnerBindings).decide(doc, Scenarios.LEARNER, stream(1L));

        // A window of nothing is a window that has always just rolled
        scenarios.spend.record(DOC, 0L, 0, 0L);
        final StageRun again = scenarios.stage(document -> silent, scenarios.learnerBindings)
                .decide(doc, Scenarios.LEARNER, stream(1L));

        assertThat(((Deferred) again.decision()).reason())
                .describedAs("it waits on the session it has now opened, not on the breaker")
                .contains("Awaiting the model");
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(1);
    }

    @Test
    void aFeedWithNoCeilingOfItsOwnIsBoundedByTheClusters() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = DemoDocument.configure(Scenarios.document())
                .uuid(DOC).name("door").heldOutFraction(0.0).build();
        scenarios.breaker = new SpendBreaker(scenarios.spend, 500L, 0);
        scenarios.spend.record("some-other-document", 500L, 1, SpendBreaker.WINDOW_MS);
        final Script silent = Script.of();

        final StageRun run = scenarios.stage(document -> silent, scenarios.learnerBindings)
                .decide(doc, Scenarios.LEARNER, stream(1L));

        assertThat(((Deferred) run.decision()).reason())
                .contains("Smart Pipelines on this cluster spent 500 token(s)");
        assertThat(scenarios.attempts.forDocument(DOC, 10)).isEmpty();
    }

    /// The demo document with a ceiling of its own.
    private static ShapeshifterAiDoc doc() {
        return DemoDocument.configure(Scenarios.document())
                .uuid(DOC)
                .name("door")
                .heldOutFraction(0.0)
                .spendTokensPerHour(1_000L)
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), MIXED, "pipeline-1");
    }
}
