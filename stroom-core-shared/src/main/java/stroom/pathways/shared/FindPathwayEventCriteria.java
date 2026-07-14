/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.pathways.shared;

import stroom.docref.DocRef;
import stroom.util.shared.BaseCriteria;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.PageRequest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

/**
 * Criteria for recalling pathway events for a given pathways doc, optionally filtered
 * by pathway name, a free-text filter, and a time range (inclusive from / exclusive to, in ms).
 */
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder(alphabetic = true)
public class FindPathwayEventCriteria extends BaseCriteria {

    @JsonProperty
    private final DocRef dataSourceRef;
    @JsonProperty
    private final String pathwayName;
    @JsonProperty
    private final String filter;
    @JsonProperty
    private final Long fromMs;
    @JsonProperty
    private final Long toMs;

    @JsonCreator
    public FindPathwayEventCriteria(@JsonProperty("pageRequest") final PageRequest pageRequest,
                                    @JsonProperty("sortList") final List<CriteriaFieldSort> sortList,
                                    @JsonProperty("dataSourceRef") final DocRef dataSourceRef,
                                    @JsonProperty("pathwayName") final String pathwayName,
                                    @JsonProperty("filter") final String filter,
                                    @JsonProperty("fromMs") final Long fromMs,
                                    @JsonProperty("toMs") final Long toMs) {
        super(pageRequest, sortList);
        this.dataSourceRef = dataSourceRef;
        this.pathwayName = pathwayName;
        this.filter = filter;
        this.fromMs = fromMs;
        this.toMs = toMs;
    }

    public DocRef getDataSourceRef() {
        return dataSourceRef;
    }

    public String getPathwayName() {
        return pathwayName;
    }

    public String getFilter() {
        return filter;
    }

    public Long getFromMs() {
        return fromMs;
    }

    public Long getToMs() {
        return toMs;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final FindPathwayEventCriteria that)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        return Objects.equals(dataSourceRef, that.dataSourceRef) &&
               Objects.equals(pathwayName, that.pathwayName) &&
               Objects.equals(filter, that.filter) &&
               Objects.equals(fromMs, that.fromMs) &&
               Objects.equals(toMs, that.toMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dataSourceRef, pathwayName, filter, fromMs, toMs);
    }
}
