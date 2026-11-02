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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What came of asking for a binding to be improved (A46), in the words the stage decided it in.
 * <p>
 * An improvement can end four ways and none of them is a failure: the candidate took over, it was kept
 * back as a draft for review, the incumbent held its place because nothing beat it (A18), or the learner
 * was left alone. The person is told which, and the attempt itself is in the Supervisor's list with
 * every question it asked.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImproveOutcome {

    @JsonProperty
    private final String said;

    @JsonCreator
    public ImproveOutcome(@JsonProperty("said") final String said) {
        this.said = said;
    }

    /**
     * One sentence: what was decided, as {@code Decision.said()} puts it.
     */
    public String getSaid() {
        return said;
    }
}
