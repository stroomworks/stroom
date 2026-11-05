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

package stroom.query.shared;

import stroom.query.api.GroupSelection;
import stroom.query.api.OffsetRange;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// Asks for one copy of a StroomQL query's table, under a component ID of its own, e.g. for one of the
/// several dashboard tables that show the same query. Each copy has its own table preferences (sorts,
/// filters, formats), page and open groups, so the tables can differ while sharing one search.
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(Include.NON_NULL)
public class QueryComponentRequest {

    @JsonPropertyDescription("The ID the copy of the query's table is returned under. Must be unique within the " +
                             "search request.")
    @JsonProperty
    private final String componentId;
    @JsonProperty
    private final QueryTablePreferences queryTablePreferences;
    @JsonProperty
    private final OffsetRange requestedRange;
    @JsonProperty
    private final GroupSelection groupSelection;

    /// @param componentId           The ID the copy of the table is returned under.
    /// @param queryTablePreferences The preferences applied to the copy, or null for none.
    /// @param requestedRange        The rows of the copy to return, or null for the default.
    /// @param groupSelection        The groups of the copy that are open, or null for none.
    @JsonCreator
    public QueryComponentRequest(
            @JsonProperty("componentId") final String componentId,
            @JsonProperty("queryTablePreferences") final QueryTablePreferences queryTablePreferences,
            @JsonProperty("requestedRange") final OffsetRange requestedRange,
            @JsonProperty("groupSelection") final GroupSelection groupSelection) {
        this.componentId = componentId;
        this.queryTablePreferences = queryTablePreferences;
        this.requestedRange = requestedRange;
        this.groupSelection = groupSelection;
    }

    /// @return The ID the copy of the table is returned under.
    public String getComponentId() {
        return componentId;
    }

    /// @return The preferences applied to the copy, or null for none.
    public QueryTablePreferences getQueryTablePreferences() {
        return queryTablePreferences;
    }

    /// @return The rows of the copy to return, or null for the default.
    public OffsetRange getRequestedRange() {
        return requestedRange;
    }

    /// @return The groups of the copy that are open, or null for none.
    public GroupSelection getGroupSelection() {
        return groupSelection;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final QueryComponentRequest that = (QueryComponentRequest) o;
        return Objects.equals(componentId, that.componentId) &&
               Objects.equals(queryTablePreferences, that.queryTablePreferences) &&
               Objects.equals(requestedRange, that.requestedRange) &&
               Objects.equals(groupSelection, that.groupSelection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(componentId, queryTablePreferences, requestedRange, groupSelection);
    }

    @Override
    public String toString() {
        return "QueryComponentRequest{" +
               "componentId='" + componentId + '\'' +
               ", queryTablePreferences=" + queryTablePreferences +
               ", requestedRange=" + requestedRange +
               ", groupSelection=" + groupSelection +
               '}';
    }
}
