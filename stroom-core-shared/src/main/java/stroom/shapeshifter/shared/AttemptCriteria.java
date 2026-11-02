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

import stroom.util.shared.BaseCriteria;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.PageRequest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Which attempts the Supervisor view is asking for (A28): across every document by default, and
 * narrowed by any of the things a person looking for one would know — which document, which feed, which
 * mode it ran in, or what it came to. Newest first, a page at a time.
 */
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

    /// Without the attempts of deleted pipelines, as the Supervisor shows them unless asked (A137).
    public AttemptCriteria(final PageRequest pageRequest,
                           final List<CriteriaFieldSort> sortList,
                           final String docUuid,
                           final String feed,
                           final PromotionMode promotionMode,
                           final List<AttemptStatus> statuses) {
        this(pageRequest, sortList, docUuid, feed, promotionMode, statuses, false, null);
    }

    /// One learner's sessions, newest first (design 06 A143), whatever their pipeline's state.
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
                           @JsonProperty("showDeletedPipelines") final boolean showDeletedPipelines,
                           @JsonProperty("learner") final LearnerRef learner) {
        super(pageRequest, sortList);
        this.showDeletedPipelines = showDeletedPipelines;
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

    /// Whether the attempts of pipelines since deleted are shown too (design 06 A137): kept to be read
    /// until they age out, and left out unless a person asks for them.
    public boolean isShowDeletedPipelines() {
        return showDeletedPipelines;
    }

    /// One learner's sessions only, or every learner's when null (design 06 A143).
    public LearnerRef getLearner() {
        return learner;
    }
}
