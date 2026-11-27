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

package stroom.learningflow.shared;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/// One learning session (attempt) as the Supervisor shows it: when and on which node it ran, under which
/// promotion mode, what it was learning from, what it came to, and who or what decided. The turns are empty
/// when sessions are listed, and filled in when one session is opened in detail.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SupervisorAttempt {

    @JsonProperty
    private final long id;
    @JsonProperty
    private final DocRef doc;
    @JsonProperty
    private final LearnerRef learner;
    @JsonProperty
    private final String feed;
    @JsonProperty
    private final String type;
    @JsonProperty
    private final Long inputId;
    @JsonProperty
    private final String node;
    @JsonProperty
    private final PromotionMode promotionMode;
    @JsonProperty
    private final AttemptStatus status;
    @JsonProperty
    private final String decision;
    @JsonProperty
    private final String bindingUuid;
    @JsonProperty
    private final Double score;
    @JsonProperty
    private final long tokensSpent;
    /// What a person granted the session beyond the document's budgets: tokens here, and milliseconds in
    /// `grantedMs`.
    @JsonProperty
    private final long grantedTokens;
    @JsonProperty
    private final long grantedMs;
    /// Whether a person sent the session to the fallback step, and the worker has not yet resumed it.
    @JsonProperty
    private final boolean exhaustNext;
    @JsonProperty
    private final long createTimeMs;
    @JsonProperty
    private final long updateTimeMs;
    @JsonProperty
    private final List<SupervisorTurn> turns;
    /// Whether the pipeline it was learning for has been deleted. Such a session is kept to be read, but
    /// nothing is offered that would change it.
    @JsonProperty
    private final boolean pipelineDeleted;
    /// Why it stopped, in parts (error, details, suggestions), where it stopped short of a translation; null
    /// otherwise, and for a session that stopped before reports were kept.
    @JsonProperty
    private final StopReport report;
    /// How the translation it learned did on each check that ran, as evidence for a person deciding whether
    /// to approve it; empty for a session that learned nothing.
    @JsonProperty
    private final List<CheckResult> evidence;
    /// Who approved or rejected what it learned, and when; null where nobody did.
    @JsonProperty
    private final String reviewedBy;
    @JsonProperty
    private final Long reviewedTimeMs;

    /// Creates a session with no stop report, evidence or review.
    public SupervisorAttempt(final long id,
                             final DocRef doc,
                             final LearnerRef learner,
                             final String feed,
                             final String type,
                             final Long inputId,
                             final String node,
                             final PromotionMode promotionMode,
                             final AttemptStatus status,
                             final String decision,
                             final String bindingUuid,
                             final Double score,
                             final long tokensSpent,
                             final long grantedTokens,
                             final long grantedMs,
                             final boolean exhaustNext,
                             final long createTimeMs,
                             final long updateTimeMs,
                             final List<SupervisorTurn> turns,
                             final boolean pipelineDeleted) {
        this(id, doc, learner, feed, type, inputId, node, promotionMode, status, decision, bindingUuid, score,
                tokensSpent, grantedTokens, grantedMs, exhaustNext, createTimeMs, updateTimeMs, turns,
                pipelineDeleted, null);
    }

    /// Creates a session with no evidence and no review.
    public SupervisorAttempt(final long id,
                             final DocRef doc,
                             final LearnerRef learner,
                             final String feed,
                             final String type,
                             final Long inputId,
                             final String node,
                             final PromotionMode promotionMode,
                             final AttemptStatus status,
                             final String decision,
                             final String bindingUuid,
                             final Double score,
                             final long tokensSpent,
                             final long grantedTokens,
                             final long grantedMs,
                             final boolean exhaustNext,
                             final long createTimeMs,
                             final long updateTimeMs,
                             final List<SupervisorTurn> turns,
                             final boolean pipelineDeleted,
                             final StopReport report) {
        this(id, doc, learner, feed, type, inputId, node, promotionMode, status, decision, bindingUuid, score,
                tokensSpent, grantedTokens, grantedMs, exhaustNext, createTimeMs, updateTimeMs, turns,
                pipelineDeleted, report, List.of(), null, null);
    }

    @JsonCreator
    public SupervisorAttempt(@JsonProperty("id") final Long id,
                             @JsonProperty("doc") final DocRef doc,
                             @JsonProperty("learner") final LearnerRef learner,
                             @JsonProperty("feed") final String feed,
                             @JsonProperty("type") final String type,
                             @JsonProperty("inputId") final Long inputId,
                             @JsonProperty("node") final String node,
                             @JsonProperty("promotionMode") final PromotionMode promotionMode,
                             @JsonProperty("status") final AttemptStatus status,
                             @JsonProperty("decision") final String decision,
                             @JsonProperty("bindingUuid") final String bindingUuid,
                             @JsonProperty("score") final Double score,
                             @JsonProperty("tokensSpent") final Long tokensSpent,
                             @JsonProperty("grantedTokens") final Long grantedTokens,
                             @JsonProperty("grantedMs") final Long grantedMs,
                             @JsonProperty("exhaustNext") final Boolean exhaustNext,
                             @JsonProperty("createTimeMs") final Long createTimeMs,
                             @JsonProperty("updateTimeMs") final Long updateTimeMs,
                             @JsonProperty("turns") final List<SupervisorTurn> turns,
                             @JsonProperty("pipelineDeleted") final Boolean pipelineDeleted,
                             @JsonProperty("report") final StopReport report,
                             @JsonProperty("evidence") final List<CheckResult> evidence,
                             @JsonProperty("reviewedBy") final String reviewedBy,
                             @JsonProperty("reviewedTimeMs") final Long reviewedTimeMs) {
        this.id = Objects.requireNonNullElse(id, 0L);
        this.doc = doc;
        this.learner = learner;
        this.feed = feed;
        this.type = type;
        this.inputId = inputId;
        this.node = node;
        this.promotionMode = promotionMode;
        this.status = status;
        this.decision = decision;
        this.bindingUuid = bindingUuid;
        this.score = score;
        this.tokensSpent = Objects.requireNonNullElse(tokensSpent, 0L);
        this.grantedTokens = Objects.requireNonNullElse(grantedTokens, 0L);
        this.grantedMs = Objects.requireNonNullElse(grantedMs, 0L);
        this.exhaustNext = Objects.requireNonNullElse(exhaustNext, false);
        this.createTimeMs = Objects.requireNonNullElse(createTimeMs, 0L);
        this.updateTimeMs = Objects.requireNonNullElse(updateTimeMs, 0L);
        this.turns = turns == null
                ? List.of()
                : List.copyOf(turns);
        this.pipelineDeleted = Objects.requireNonNullElse(pipelineDeleted, false);
        this.report = report;
        this.evidence = evidence == null
                ? List.of()
                : List.copyOf(evidence);
        this.reviewedBy = reviewedBy;
        this.reviewedTimeMs = reviewedTimeMs;
    }

    public long getId() {
        return id;
    }

    public DocRef getDoc() {
        return doc;
    }

    /// @return The learner the session is learning for, which is also what guidance is kept against.
    public LearnerRef getLearner() {
        return learner;
    }

    public String getFeed() {
        return feed;
    }

    public String getType() {
        return type;
    }

    public Long getInputId() {
        return inputId;
    }

    public String getNode() {
        return node;
    }

    public PromotionMode getPromotionMode() {
        return promotionMode;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public String getDecision() {
        return decision;
    }

    public String getBindingUuid() {
        return bindingUuid;
    }

    public Double getScore() {
        return score;
    }

    public long getTokensSpent() {
        return tokensSpent;
    }

    public long getGrantedTokens() {
        return grantedTokens;
    }

    public long getGrantedMs() {
        return grantedMs;
    }

    public boolean isExhaustNext() {
        return exhaustNext;
    }

    public long getCreateTimeMs() {
        return createTimeMs;
    }

    public long getUpdateTimeMs() {
        return updateTimeMs;
    }

    public boolean isPipelineDeleted() {
        return pipelineDeleted;
    }

    public List<SupervisorTurn> getTurns() {
        return turns;
    }

    /// @return Why it stopped, in parts, where it stopped short of a translation; null otherwise.
    public StopReport getReport() {
        return report;
    }

    /// @return How the translation it learned did on each check that ran; empty where it learned nothing,
    ///         or ran before the evidence was kept.
    public List<CheckResult> getEvidence() {
        return evidence;
    }

    /// @return Who approved or rejected what it learned; null where nobody did.
    public String getReviewedBy() {
        return reviewedBy;
    }

    /// @return When they did; null where nobody did.
    public Long getReviewedTimeMs() {
        return reviewedTimeMs;
    }
}
