/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.element.KeptLearning;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.CorpusIndex;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.LearnerStates;
import stroom.shapeshifter.ai.stage.Learners;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.Snapshots;
import stroom.shapeshifter.ai.stage.Spend;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

/// The runtime state in memory, for a harness that has no database: the node binds these to the tables of
/// A26 through `stroom-shapeshifter-ai-impl-db` instead. What is in memory is one JVM's and lasts as long
/// as it does, which is why the tables exist.
public class InMemoryStateModule extends AbstractModule {

    @Override
    protected void configure() {
        super.configure();
        bind(Attempts.class).to(InMemoryAttempts.class).in(Scopes.SINGLETON);
        // One instance behind each seam and the class itself: the serving view walks every binding, and what
        // brings a learner's rows to its document reaches past the seams to the rows behind them.
        bind(InMemoryLearnerBindings.class).in(Scopes.SINGLETON);
        bind(LearnerBindings.class).to(InMemoryLearnerBindings.class);
        bind(InMemoryGuidance.class).in(Scopes.SINGLETON);
        bind(Guidance.class).to(InMemoryGuidance.class);
        bind(InMemoryDeferrals.class).in(Scopes.SINGLETON);
        bind(Deferrals.class).to(InMemoryDeferrals.class);
        bind(Learners.class).to(InMemoryLearners.class).in(Scopes.SINGLETON);
        bind(LearnerStates.class).to(InMemoryLearnerStates.class).in(Scopes.SINGLETON);
        bind(InMemoryAttempts.class).in(Scopes.SINGLETON);
        bind(InMemoryLearnerStates.class).in(Scopes.SINGLETON);
        bind(LearningPipelines.class).to(InMemoryLearningPipelines.class).in(Scopes.SINGLETON);
        bind(Outputs.class).to(InMemoryOutputs.class).in(Scopes.SINGLETON);
        bind(Spend.class).to(InMemorySpend.class).in(Scopes.SINGLETON);
        bind(CorpusIndex.class).to(InMemoryCorpusIndex.class).in(Scopes.SINGLETON);
        bind(Snapshots.class).to(InMemorySnapshots.class).in(Scopes.SINGLETON);
        // One set of marks, which the attempts and the serving view read to leave a deleted pipeline out.
        bind(InMemoryDeletedPipelines.class).in(Scopes.SINGLETON);
        bind(DeletedPipelines.class).to(InMemoryDeletedPipelines.class);
        bind(KeptLearning.class).to(InMemoryKeptLearning.class).in(Scopes.SINGLETON);
    }
}
