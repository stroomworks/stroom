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

import java.util.Objects;

/// One row of the Supervisor's list of learning pipelines: a Smart Pipeline element (learner) that has
/// learned or is learning, with where it stands, what serves it, and what waits on it. A person acts on the
/// learner, and on its serving binding, from this row.
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
    /// Why it was given up, where it has been.
    @JsonProperty
    private final String givenUpReason;
    /// The serving binding's uuid, or null where nothing serves.
    @JsonProperty
    private final String bindingUuid;
    @JsonProperty
    private final DocRef fragment;
    /// What the binding scored when it was promoted.
    @JsonProperty
    private final Double promotedScore;
    /// What it has been scoring lately, over the learner's rolling memory; null until it has served.
    @JsonProperty
    private final Double rollingScore;
    @JsonProperty
    private final int records;
    /// How many of those records the translation declared it deliberately skipped rather than translated.
    /// There is deliberately no limit on the skip rate, so showing this count is the only thing that stops a
    /// translation quietly skipping its awkward records.
    @JsonProperty
    private final int skips;
    /// How many streams wait on the learner.
    @JsonProperty
    private final int waiting;
    /// When the longest waiting of them first waited.
    @JsonProperty
    private final Long oldestWaitMs;
    /// Whether that wait is past the document's maximum deferral age.
    @JsonProperty
    private final boolean raised;
    /// When its most recent session opened.
    @JsonProperty
    private final Long lastSessionMs;
    /// How many guidance notes people have left for it.
    @JsonProperty
    private final int guidanceCount;
    @JsonProperty
    private final boolean pipelineDeleted;
    /// Whether its recent score is below its document's relearn threshold. This flag is the only use of the
    /// threshold, which does not trigger anything by itself.
    @JsonProperty
    private final boolean belowThreshold;
    /// Whether its document's target schema has no marked Unknown event, so that *Finish with Unknowns*
    /// doesn't apply to it. True only for a target schema that isn't event-logging.
    @JsonProperty
    private final boolean fallbackUnavailable;
    /// Why no new learning session may open for it now, where a spend ceiling has been reached. This is the
    /// spend breaker's own message, saying whose ceiling it is and that it clears when the hour rolls over.
    /// Null where nothing stops a session.
    @JsonProperty
    private final String spendPaused;
    /// The zone in which timestamps are read for its streams that have no `TimeZone` header, where the
    /// request asked for it; null where it didn't.
    @JsonProperty
    private final String timeZone;
    /// Where that zone comes from: the pipeline's property, or the UTC default where it sets none.
    @JsonProperty
    private final TimeZoneSource timeZoneSource;
    /// What is wrong with the pipeline's time zone, where something is; null where nothing is.
    @JsonProperty
    private final String timeZoneProblem;

    @JsonCreator
    public SupervisorLearner(@JsonProperty("learner") final LearnerRef learner,
                             @JsonProperty("pipeline") final DocRef pipeline,
                             @JsonProperty("doc") final DocRef doc,
                             @JsonProperty("state") final LearnerState state,
                             @JsonProperty("givenUpReason") final String givenUpReason,
                             @JsonProperty("bindingUuid") final String bindingUuid,
                             @JsonProperty("fragment") final DocRef fragment,
                             @JsonProperty("promotedScore") final Double promotedScore,
                             @JsonProperty("rollingScore") final Double rollingScore,
                             @JsonProperty("records") final Integer records,
                             @JsonProperty("skips") final Integer skips,
                             @JsonProperty("waiting") final Integer waiting,
                             @JsonProperty("oldestWaitMs") final Long oldestWaitMs,
                             @JsonProperty("raised") final Boolean raised,
                             @JsonProperty("lastSessionMs") final Long lastSessionMs,
                             @JsonProperty("guidanceCount") final Integer guidanceCount,
                             @JsonProperty("pipelineDeleted") final Boolean pipelineDeleted,
                             @JsonProperty("belowThreshold") final Boolean belowThreshold,
                             @JsonProperty("fallbackUnavailable") final Boolean fallbackUnavailable,
                             @JsonProperty("spendPaused") final String spendPaused,
                             @JsonProperty("timeZone") final String timeZone,
                             @JsonProperty("timeZoneSource") final TimeZoneSource timeZoneSource,
                             @JsonProperty("timeZoneProblem") final String timeZoneProblem) {
        this.learner = learner;
        this.pipeline = pipeline;
        this.doc = doc;
        this.state = state;
        this.givenUpReason = givenUpReason;
        this.bindingUuid = bindingUuid;
        this.fragment = fragment;
        this.promotedScore = promotedScore;
        this.rollingScore = rollingScore;
        this.records = Objects.requireNonNullElse(records, 0);
        this.skips = Objects.requireNonNullElse(skips, 0);
        this.waiting = Objects.requireNonNullElse(waiting, 0);
        this.oldestWaitMs = oldestWaitMs;
        this.raised = Objects.requireNonNullElse(raised, false);
        this.lastSessionMs = lastSessionMs;
        this.guidanceCount = Objects.requireNonNullElse(guidanceCount, 0);
        this.pipelineDeleted = Objects.requireNonNullElse(pipelineDeleted, false);
        this.belowThreshold = Objects.requireNonNullElse(belowThreshold, false);
        this.fallbackUnavailable = Objects.requireNonNullElse(fallbackUnavailable, false);
        this.spendPaused = spendPaused;
        this.timeZone = timeZone;
        this.timeZoneSource = timeZoneSource;
        this.timeZoneProblem = timeZoneProblem;
    }

    /// Creates a row as the database returns it, before the service adds what it knows about the document's
    /// target schema, spend limits and time zone.
    ///
    /// @param learner         The learner.
    /// @param pipeline        Its pipeline.
    /// @param doc             The document it runs under.
    /// @param state           Where it stands.
    /// @param givenUpReason   Why it was given up, where it has been.
    /// @param bindingUuid     What serves it, or null.
    /// @param fragment        The fragment that binding names.
    /// @param promotedScore   What it scored when it was promoted.
    /// @param rollingScore    What it has been scoring lately.
    /// @param records         How many records that score rests on.
    /// @param skips           How many of those records were declared skips.
    /// @param waiting         How many streams wait on it.
    /// @param oldestWaitMs    When the longest waiting of them first waited.
    /// @param raised          Whether that wait is overdue.
    /// @param lastSessionMs   When its most recent session opened.
    /// @param guidanceCount   How many guidance notes people have left for it.
    /// @param pipelineDeleted Whether its pipeline has been deleted.
    /// @param belowThreshold  Whether it scores below its document's relearn threshold.
    public SupervisorLearner(final LearnerRef learner,
                             final DocRef pipeline,
                             final DocRef doc,
                             final LearnerState state,
                             final String givenUpReason,
                             final String bindingUuid,
                             final DocRef fragment,
                             final Double promotedScore,
                             final Double rollingScore,
                             final int records,
                             final int skips,
                             final int waiting,
                             final Long oldestWaitMs,
                             final boolean raised,
                             final Long lastSessionMs,
                             final int guidanceCount,
                             final boolean pipelineDeleted,
                             final boolean belowThreshold) {
        this(learner, pipeline, doc, state, givenUpReason, bindingUuid, fragment, promotedScore,
                rollingScore, records, skips, waiting, oldestWaitMs, raised, lastSessionMs, guidanceCount,
                pipelineDeleted, belowThreshold, false, null, null, null, null);
    }

    /// Creates a row with no declared skips, as tests build it.
    ///
    /// @param learner         The learner.
    /// @param pipeline        Its pipeline.
    /// @param doc             The document it runs under.
    /// @param state           Where it stands.
    /// @param givenUpReason   Why it was given up, where it has been.
    /// @param bindingUuid     What serves it, or null.
    /// @param fragment        The fragment that binding names.
    /// @param promotedScore   What it scored when it was promoted.
    /// @param rollingScore    What it has been scoring lately.
    /// @param records         How many records that score rests on.
    /// @param waiting         How many streams wait on it.
    /// @param oldestWaitMs    When the longest waiting of them first waited.
    /// @param raised          Whether that wait is overdue.
    /// @param lastSessionMs   When its most recent session opened.
    /// @param guidanceCount   How many guidance notes people have left for it.
    /// @param pipelineDeleted Whether its pipeline has been deleted.
    /// @param belowThreshold  Whether it scores below its document's relearn threshold.
    public SupervisorLearner(final LearnerRef learner,
                             final DocRef pipeline,
                             final DocRef doc,
                             final LearnerState state,
                             final String givenUpReason,
                             final String bindingUuid,
                             final DocRef fragment,
                             final Double promotedScore,
                             final Double rollingScore,
                             final int records,
                             final int waiting,
                             final Long oldestWaitMs,
                             final boolean raised,
                             final Long lastSessionMs,
                             final int guidanceCount,
                             final boolean pipelineDeleted,
                             final boolean belowThreshold) {
        this(learner, pipeline, doc, state, givenUpReason, bindingUuid, fragment, promotedScore,
                rollingScore, records, 0, waiting, oldestWaitMs, raised, lastSessionMs, guidanceCount,
                pipelineDeleted, belowThreshold);
    }

    /// Adds what the service knows beyond the database: the pipeline's and document's names, whether the
    /// wait is past the document's maximum deferral age, whether the score is below the document's relearn
    /// threshold, whether the fallback applies, and whether a spend ceiling stops learning.
    ///
    /// @param pipeline            Its pipeline, named.
    /// @param doc                 Its document, named.
    /// @param raised              Whether its wait is overdue.
    /// @param belowThreshold      Whether it scores below its document's relearn threshold.
    /// @param fallbackUnavailable Whether its document's target schema has no marked Unknown.
    /// @param spendPaused         Why no learning session may open for it now, or null where nothing stops one.
    /// @return A copy of this row with those values.
    public SupervisorLearner named(final DocRef pipeline,
                                   final DocRef doc,
                                   final boolean raised,
                                   final boolean belowThreshold,
                                   final boolean fallbackUnavailable,
                                   final String spendPaused) {
        return new SupervisorLearner(learner, pipeline, doc, state, givenUpReason, bindingUuid, fragment,
                promotedScore, rollingScore, records, skips, waiting, oldestWaitMs, raised,
                lastSessionMs, guidanceCount, pipelineDeleted, belowThreshold, fallbackUnavailable, spendPaused,
                timeZone, timeZoneSource, timeZoneProblem);
    }

    /// Adds the zone its pipeline reads timestamps in, which the service adds for a pipeline's Learning
    /// Status tab.
    ///
    /// @param zone    The zone a stream with no `TimeZone` header is read in, e.g. `Europe/London`.
    /// @param source  Where it comes from.
    /// @param problem What is wrong with the pipeline's time zone, or null where nothing is.
    /// @return A copy of this row with the zone.
    public SupervisorLearner withTimeZone(final String zone, final TimeZoneSource source, final String problem) {
        return new SupervisorLearner(learner, pipeline, doc, state, givenUpReason, bindingUuid, fragment,
                promotedScore, rollingScore, records, skips, waiting, oldestWaitMs, raised,
                lastSessionMs, guidanceCount, pipelineDeleted, belowThreshold, fallbackUnavailable, spendPaused,
                zone, source, problem);
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

    public Double getPromotedScore() {
        return promotedScore;
    }

    public Double getRollingScore() {
        return rollingScore;
    }

    public int getRecords() {
        return records;
    }

    /// @return How many of those records the translation declared it deliberately skipped.
    public int getSkips() {
        return skips;
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

    /// @return How many notes people have left the learner that it still carries.
    public int getGuidanceCount() {
        return guidanceCount;
    }

    public boolean isPipelineDeleted() {
        return pipelineDeleted;
    }

    public boolean isBelowThreshold() {
        return belowThreshold;
    }

    /// @return Whether *Finish with Unknowns* doesn't apply, as its document's target schema has no marked
    ///         Unknown.
    public boolean isFallbackUnavailable() {
        return fallbackUnavailable;
    }

    /// @return Why no new learning session may open for it now, where a spend ceiling has been reached; null
    ///         where nothing stops one.
    public String getSpendPaused() {
        return spendPaused;
    }

    /// @return The zone a stream with no `TimeZone` header is read in; null where the request didn't ask.
    public String getTimeZone() {
        return timeZone;
    }

    /// @return Where that zone comes from; null where the request didn't ask.
    public TimeZoneSource getTimeZoneSource() {
        return timeZoneSource;
    }

    /// @return What is wrong with the pipeline's time zone, or null where nothing is.
    public String getTimeZoneProblem() {
        return timeZoneProblem;
    }

    @Override
    public String toString() {
        return learner + " " + state;
    }
}
