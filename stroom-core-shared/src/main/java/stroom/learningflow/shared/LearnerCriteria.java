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

/// Criteria for the Supervisor's list of learning pipelines. By default the list holds every learner the person
/// may read whose pipeline still exists. It can be narrowed to one document and to chosen states, and can be
/// widened to include deleted pipelines. Rows come a page at a time, those with the most waiting streams first,
/// then those with the most recent session.
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
                           @JsonProperty("showDeletedPipelines") final Boolean showDeletedPipelines) {
        super(pageRequest, sortList);
        this.docUuid = docUuid;
        this.states = states == null
                ? List.of()
                : List.copyOf(states);
        this.showDeletedPipelines = Objects.requireNonNullElse(showDeletedPipelines, false);
    }

    /// @return The UUID of the document whose learners are listed, or null for every document the person may
    /// read.
    public String getDocUuid() {
        return docUuid;
    }

    /// @return The learner states to list; empty for every state.
    public List<LearnerState> getStates() {
        return states;
    }

    /// @return Whether learners whose pipelines have since been deleted are listed too.
    public boolean isShowDeletedPipelines() {
        return showDeletedPipelines;
    }
}
