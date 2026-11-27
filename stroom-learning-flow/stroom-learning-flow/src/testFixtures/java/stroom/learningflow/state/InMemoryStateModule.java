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

package stroom.learningflow.state;

import stroom.learningflow.element.KeptLearning;
import stroom.learningflow.quality.Volumes;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.CorpusIndex;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearnerStates;
import stroom.learningflow.stage.Learners;
import stroom.learningflow.stage.LearningPipelines;
import stroom.learningflow.stage.Outputs;
import stroom.learningflow.stage.Snapshots;
import stroom.learningflow.stage.Spend;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

/// The Guice module that binds the Learning Flow's state interfaces to in-memory implementations, for tests
/// that have no database. A real node binds them to the database tables through
/// `stroom-learning-flow-impl-db` instead, because in-memory state belongs to one JVM and lasts only as
/// long as it does.
public class InMemoryStateModule extends AbstractModule {

    @Override
    protected void configure() {
        super.configure();
        bind(Attempts.class).to(InMemoryAttempts.class).in(Scopes.SINGLETON);
        // The interface and the concrete class share one instance, because the learning pipelines list
        // reads every binding, and InMemoryLearners updates the rows directly rather than through the
        // interfaces.
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
        bind(Volumes.class).to(InMemoryVolumes.class).in(Scopes.SINGLETON);
        // One set of marks, which the attempts and the learning pipelines list read to leave deleted
        // pipelines out.
        bind(InMemoryDeletedPipelines.class).in(Scopes.SINGLETON);
        bind(DeletedPipelines.class).to(InMemoryDeletedPipelines.class);
        bind(KeptLearning.class).to(InMemoryKeptLearning.class).in(Scopes.SINGLETON);
    }
}
