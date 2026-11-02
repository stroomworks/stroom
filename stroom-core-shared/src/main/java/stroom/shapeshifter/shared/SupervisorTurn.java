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
 * One turn of an attempt as the Supervisor view shows it (A28): what was asked, what was answered and
 * by whom, and what the answer scored. A turn with no answer is the question the attempt stopped at and
 * is what <em>answer instead</em> answers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SupervisorTurn {

    @JsonProperty
    private final int number;
    @JsonProperty
    private final String stepId;
    @JsonProperty
    private final int candidate;
    @JsonProperty
    private final QuestionKind kind;
    @JsonProperty
    private final String question;
    @JsonProperty
    private final String answer;
    @JsonProperty
    private final String answeredBy;
    @JsonProperty
    private final StepOutcome outcome;
    /// The score of the check that decided it (design 06 A144), where a scorer decided it.
    @JsonProperty
    private final TurnScore score;

    /// A turn no scorer decided.
    public SupervisorTurn(final int number,
                          final String stepId,
                          final int candidate,
                          final QuestionKind kind,
                          final String question,
                          final String answer,
                          final String answeredBy,
                          final StepOutcome outcome) {
        this(number, stepId, candidate, kind, question, answer, answeredBy, outcome, null);
    }

    @JsonCreator
    public SupervisorTurn(@JsonProperty("number") final int number,
                          @JsonProperty("stepId") final String stepId,
                          @JsonProperty("candidate") final int candidate,
                          @JsonProperty("kind") final QuestionKind kind,
                          @JsonProperty("question") final String question,
                          @JsonProperty("answer") final String answer,
                          @JsonProperty("answeredBy") final String answeredBy,
                          @JsonProperty("outcome") final StepOutcome outcome,
                          @JsonProperty("score") final TurnScore score) {
        this.number = number;
        this.stepId = stepId;
        this.candidate = candidate;
        this.kind = kind;
        this.question = question;
        this.answer = answer;
        this.answeredBy = answeredBy;
        this.outcome = outcome;
        this.score = score;
    }

    public int getNumber() {
        return number;
    }

    public String getStepId() {
        return stepId;
    }

    public int getCandidate() {
        return candidate;
    }

    public QuestionKind getKind() {
        return kind;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public String getAnsweredBy() {
        return answeredBy;
    }

    public StepOutcome getOutcome() {
        return outcome;
    }

    /// @return The score of the check that decided it, or null where no scorer did.
    public TurnScore getScore() {
        return score;
    }

    /**
     * Whether this is the question the attempt stopped at: nobody has answered it, and a person may
     * (A28).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isUnanswered() {
        return answer == null;
    }
}
