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

/// An assessment a QA person records of a Smart Pipeline's translated output (its learner).
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssessmentRequest {

    @JsonProperty
    private final LearnerRef learner;
    @JsonProperty
    private final AssessmentVerdict verdict;
    @JsonProperty
    private final String comment;

    @JsonCreator
    public AssessmentRequest(@JsonProperty("learner") final LearnerRef learner,
                             @JsonProperty("verdict") final AssessmentVerdict verdict,
                             @JsonProperty("comment") final String comment) {
        this.learner = learner;
        this.verdict = verdict;
        this.comment = comment;
    }

    /// @return The learner it is of: the pipeline and its AI Translator element.
    public LearnerRef getLearner() {
        return learner;
    }

    /// @return What is said of it.
    public AssessmentVerdict getVerdict() {
        return verdict;
    }

    /// @return What the person writes, required for an issue.
    public String getComment() {
        return comment;
    }
}
