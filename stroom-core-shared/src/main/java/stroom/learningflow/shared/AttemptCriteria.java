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

import stroom.util.shared.BaseCriteria;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.PageRequest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/// Criteria for the learning sessions (attempts) the Supervisor lists. By default sessions of every document
/// are included; they can be narrowed by document, feed, promotion mode, status or learner. Results come newest
/// first, a page at a time.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttemptCriteria extends BaseCriteria {

    @JsonProperty
    private final String docUuid;
    @JsonProperty
    private final String feed;
    @JsonProperty
    private final PromotionMode promotionMode;
    @JsonProperty
    private final List<AttemptStatus> statuses;
    @JsonProperty
    private final boolean showDeletedPipelines;
    @JsonProperty
    private final LearnerRef learner;

    public AttemptCriteria() {
        this(null, null, null, null, null, null);
    }

    /// Creates criteria that leave out the sessions of deleted pipelines, which the Supervisor hides unless
    /// asked to show them.
    public AttemptCriteria(final PageRequest pageRequest,
                           final List<CriteriaFieldSort> sortList,
                           final String docUuid,
                           final String feed,
                           final PromotionMode promotionMode,
                           final List<AttemptStatus> statuses) {
        this(pageRequest, sortList, docUuid, feed, promotionMode, statuses, false, null);
    }

    /// Creates criteria for one learner's sessions, newest first, including those of a deleted pipeline.
    ///
    /// @param pageRequest The page to fetch.
    /// @param learner     The learner whose sessions to list.
    /// @return The criteria.
    public static AttemptCriteria forLearner(final PageRequest pageRequest, final LearnerRef learner) {
        return new AttemptCriteria(pageRequest, null, null, null, null, null, true, learner);
    }

    @JsonCreator
    public AttemptCriteria(@JsonProperty("pageRequest") final PageRequest pageRequest,
                           @JsonProperty("sortList") final List<CriteriaFieldSort> sortList,
                           @JsonProperty("docUuid") final String docUuid,
                           @JsonProperty("feed") final String feed,
                           @JsonProperty("promotionMode") final PromotionMode promotionMode,
                           @JsonProperty("statuses") final List<AttemptStatus> statuses,
                           @JsonProperty("showDeletedPipelines") final Boolean showDeletedPipelines,
                           @JsonProperty("learner") final LearnerRef learner) {
        super(pageRequest, sortList);
        this.showDeletedPipelines = Objects.requireNonNullElse(showDeletedPipelines, false);
        this.learner = learner;
        this.docUuid = docUuid;
        this.feed = feed;
        this.promotionMode = promotionMode;
        this.statuses = statuses == null
                ? List.of()
                : List.copyOf(statuses);
    }

    public String getDocUuid() {
        return docUuid;
    }

    public String getFeed() {
        return feed;
    }

    public PromotionMode getPromotionMode() {
        return promotionMode;
    }

    public List<AttemptStatus> getStatuses() {
        return statuses;
    }

    /// @return Whether the sessions of pipelines since deleted are included too. Such sessions are kept until
    /// they age out, but are left out unless a person asks for them.
    public boolean isShowDeletedPipelines() {
        return showDeletedPipelines;
    }

    /// @return The learner whose sessions alone are listed, or null for every learner's.
    public LearnerRef getLearner() {
        return learner;
    }
}
