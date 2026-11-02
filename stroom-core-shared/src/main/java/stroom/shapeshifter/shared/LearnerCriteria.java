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

/// Which learning pipelines the Supervisor lists (design 06 A142): every one the person may read by
/// default, narrowed by document, by state, and to those whose pipeline still exists unless deleted ones
/// are asked for (A137). Most waiting streams first, then the most recent session, a page at a time.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LearnerCriteria extends BaseCriteria {

    @JsonProperty
    private final String docUuid;
    @JsonProperty
    private final List<LearnerState> states;
    @JsonProperty
    private final boolean showDeletedPipelines;

    public LearnerCriteria() {
        this(null, null, null, null, false);
    }

    @JsonCreator
    public LearnerCriteria(@JsonProperty("pageRequest") final PageRequest pageRequest,
                           @JsonProperty("sortList") final List<CriteriaFieldSort> sortList,
                           @JsonProperty("docUuid") final String docUuid,
                           @JsonProperty("states") final List<LearnerState> states,
                           @JsonProperty("showDeletedPipelines") final boolean showDeletedPipelines) {
        super(pageRequest, sortList);
        this.docUuid = docUuid;
        this.states = states == null
                ? List.of()
                : List.copyOf(states);
        this.showDeletedPipelines = showDeletedPipelines;
    }

    /// One document's learners, or those of every document the person may read when null.
    public String getDocUuid() {
        return docUuid;
    }

    /// The states asked for; empty for every state.
    public List<LearnerState> getStates() {
        return states;
    }

    /// Whether the learning of pipelines since deleted is shown too (A137).
    public boolean isShowDeletedPipelines() {
        return showDeletedPipelines;
    }
}
