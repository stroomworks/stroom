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


package stroom.learningflow.state;

import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.Learners;

import jakarta.inject.Inject;

/// An in-memory [Learners], which updates a learner's bindings, guidance, waiting streams and state to the
/// document the learner runs under.
public final class InMemoryLearners implements Learners {

    private final InMemoryLearnerBindings learnerBindings;
    private final InMemoryGuidance guidance;
    private final InMemoryDeferrals deferrals;
    private final InMemoryLearnerStates states;

    @Inject
    public InMemoryLearners(final InMemoryLearnerBindings learnerBindings,
                            final InMemoryGuidance guidance,
                            final InMemoryDeferrals deferrals,
                            final InMemoryLearnerStates states) {
        this.learnerBindings = learnerBindings;
        this.guidance = guidance;
        this.deferrals = deferrals;
        this.states = states;
    }

    @Override
    public void runsUnder(final Learner learner, final String docUuid) {
        learnerBindings.runsUnder(learner, docUuid);
        guidance.runsUnder(learner, docUuid);
        deferrals.runsUnder(learner, docUuid);
        states.runsUnder(learner, docUuid);
    }
}
