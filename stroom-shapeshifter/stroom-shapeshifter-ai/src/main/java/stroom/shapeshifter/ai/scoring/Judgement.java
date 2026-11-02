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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.shared.ScorerSetting;

/**
 * A score against the document's setting for it: whether it met the threshold, and if it is a gate,
 * whether the step can pass at all.
 */
public record Judgement(ScorerSetting setting, Score score) {

    public boolean metThreshold() {
        return score.value() >= setting.getThreshold();
    }

    public boolean failedGate() {
        return setting.isGate() && !metThreshold();
    }
}
