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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

/**
 * The advisors a node under test is bound to: whichever {@link Script} the running scenario has set,
 * whatever the document. A Tier 2 scenario cannot hand its script to the element, which the pipeline
 * factory builds, so it hands it to this and the element finds it here.
 */
public final class AdvisorHolder implements Advisors {

    private volatile Advisor advisor = Script.of();

    public void set(final Advisor advisor) {
        this.advisor = advisor;
    }

    @Override
    public Advisor of(final ShapeshifterAiDoc doc) {
        return advisor;
    }
}
