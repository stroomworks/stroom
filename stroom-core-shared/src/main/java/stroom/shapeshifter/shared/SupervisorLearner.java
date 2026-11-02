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

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/// One row of the Supervisor's learning pipelines list (design 06 A115): a pipeline's Shapeshifter AI
/// element that has learned or is learning, where it stands, what serves it, and what waits on it. The
/// pipeline-level actions are taken from it (A141).
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SupervisorLearner {

    @JsonProperty
    private final LearnerRef learner;
    /// The pipeline, named; its name is null where it has been deleted.
    @JsonProperty
    private final DocRef pipeline;
    /// The document the learner runs under.
    @JsonProperty
    private final DocRef doc;
    @JsonProperty
    private final LearnerState state;
    /// Why it gave up, where it has.
    @JsonProperty
    private final String givenUpReason;
    /// The serving binding's uuid, or null where nothing serves.
    @JsonProperty
    private final String bindingUuid;
    @JsonProperty
    private final DocRef fragment;
    @JsonProperty
    private final boolean provisional;
    /// What the binding scored when it was promoted.
    @JsonProperty
    private final Double promotedScore;
    /// What it has been scoring lately, over the learner's rolling memory; null until it has served.
    @JsonProperty
    private final Double rollingScore;
    @JsonProperty
    private final int records;
    /// How many streams wait on the learner.
    @JsonProperty
    private final int waiting;
    /// When the longest waiting of them first waited.
    @JsonProperty
    private final Long oldestWaitMs;
    /// Whether that wait is past the document's maximum deferral age (A57).
    @JsonProperty
    private final boolean raised;
    /// When its most recent session opened.
    @JsonProperty
    private final Long lastSessionMs;
    /// How much a supervisor has said to it (A46).
    @JsonProperty
    private final int guidance;
    @JsonProperty
    private final boolean pipelineDeleted;
    /// Whether what it scores lately is below its document's relearn threshold: the one place the
    /// threshold is read, since it triggers nothing by itself (design 04 §6).
    @JsonProperty
    private final boolean belowThreshold;

    @JsonCreator
    public SupervisorLearner(@JsonProperty("learner") final LearnerRef learner,
                             @JsonProperty("pipeline") final DocRef pipeline,
                             @JsonProperty("doc") final DocRef doc,
                             @JsonProperty("state") final LearnerState state,
                             @JsonProperty("givenUpReason") final String givenUpReason,
                             @JsonProperty("bindingUuid") final String bindingUuid,
                             @JsonProperty("fragment") final DocRef fragment,
                             @JsonProperty("provisional") final boolean provisional,
                             @JsonProperty("promotedScore") final Double promotedScore,
                             @JsonProperty("rollingScore") final Double rollingScore,
                             @JsonProperty("records") final int records,
                             @JsonProperty("waiting") final int waiting,
                             @JsonProperty("oldestWaitMs") final Long oldestWaitMs,
                             @JsonProperty("raised") final boolean raised,
                             @JsonProperty("lastSessionMs") final Long lastSessionMs,
                             @JsonProperty("guidance") final int guidance,
                             @JsonProperty("pipelineDeleted") final boolean pipelineDeleted,
                             @JsonProperty("belowThreshold") final boolean belowThreshold) {
        this.learner = learner;
        this.pipeline = pipeline;
        this.doc = doc;
        this.state = state;
        this.givenUpReason = givenUpReason;
        this.bindingUuid = bindingUuid;
        this.fragment = fragment;
        this.provisional = provisional;
        this.promotedScore = promotedScore;
        this.rollingScore = rollingScore;
        this.records = records;
        this.waiting = waiting;
        this.oldestWaitMs = oldestWaitMs;
        this.raised = raised;
        this.lastSessionMs = lastSessionMs;
        this.guidance = guidance;
        this.pipelineDeleted = pipelineDeleted;
        this.belowThreshold = belowThreshold;
    }

    /// The same row with its pipeline and document named, its wait judged against its document's maximum
    /// age, and its score against the document's relearn threshold: what the resource adds to what the
    /// database answers.
    public SupervisorLearner named(final DocRef pipeline,
                                   final DocRef doc,
                                   final boolean raised,
                                   final boolean belowThreshold) {
        return new SupervisorLearner(learner, pipeline, doc, state, givenUpReason, bindingUuid, fragment,
                provisional, promotedScore, rollingScore, records, waiting, oldestWaitMs, raised, lastSessionMs,
                guidance, pipelineDeleted, belowThreshold);
    }

    public LearnerRef getLearner() {
        return learner;
    }

    public DocRef getPipeline() {
        return pipeline;
    }

    public DocRef getDoc() {
        return doc;
    }

    public LearnerState getState() {
        return state;
    }

    public String getGivenUpReason() {
        return givenUpReason;
    }

    public String getBindingUuid() {
        return bindingUuid;
    }

    public DocRef getFragment() {
        return fragment;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public Double getPromotedScore() {
        return promotedScore;
    }

    public Double getRollingScore() {
        return rollingScore;
    }

    public int getRecords() {
        return records;
    }

    public int getWaiting() {
        return waiting;
    }

    public Long getOldestWaitMs() {
        return oldestWaitMs;
    }

    public boolean isRaised() {
        return raised;
    }

    public Long getLastSessionMs() {
        return lastSessionMs;
    }

    public int getGuidance() {
        return guidance;
    }

    public boolean isPipelineDeleted() {
        return pipelineDeleted;
    }

    public boolean isBelowThreshold() {
        return belowThreshold;
    }

    @Override
    public String toString() {
        return learner + " " + state;
    }
}
