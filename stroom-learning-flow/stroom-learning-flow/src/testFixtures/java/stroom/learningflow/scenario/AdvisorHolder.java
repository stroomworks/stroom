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

package stroom.learningflow.scenario;

import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.shared.LearningFlowDoc;

/// The [Advisors] that a Stroom node under test is bound to. It returns whichever advisor (usually a
/// [Script]) the running scenario has set, whatever the document.
///
/// A scenario that runs in a real node cannot pass its script to the Smart Pipeline element directly,
/// because Stroom's pipeline factory creates the element. So the scenario sets the script here, and the
/// element finds it here.
public final class AdvisorHolder implements Advisors {

    private volatile Advisor advisor = Script.of();

    public void set(final Advisor advisor) {
        this.advisor = advisor;
    }

    @Override
    public Advisor of(final LearningFlowDoc doc) {
        return advisor;
    }
}
