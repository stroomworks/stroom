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

package stroom.dashboard.shared;

import stroom.query.shared.QueryTablePreferences;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The settings of a dashboard's StroomQL Table pane: the results of a StroomQL Query pane's query, as a
/// table with its own sorts, filters and formats.
@JsonPropertyOrder({
        "queryId",
        "queryTablePreferences",
        "selectionFilter"})
@JsonInclude(Include.NON_NULL)
public final class StroomQLTableComponentSettings implements ComponentSettings, HasSelectionFilter {

    @JsonPropertyDescription("The ID of the StroomQL Query pane whose results the table shows.")
    @JsonProperty
    private final String queryId;
    @JsonPropertyDescription("The table's columns' sorts, filters, widths and formats, and its conditional " +
                             "formatting.")
    @JsonProperty
    private final QueryTablePreferences queryTablePreferences;
    @JsonPropertyDescription("The selections in other panes that filter the table's rows.")
    @JsonProperty
    private final List<ComponentSelectionHandler> selectionFilter;

    /// @param queryId               The ID of the StroomQL Query pane whose results the table shows, or null
    ///                              for none.
    /// @param queryTablePreferences The table's preferences, or null for none.
    /// @param selectionFilter       The selections in other panes that filter the table's rows, or null for
    ///                              none.
    @SuppressWarnings("checkstyle:LineLength")
    @JsonCreator
    public StroomQLTableComponentSettings(@JsonProperty("queryId") final String queryId,
                                          @JsonProperty("queryTablePreferences") final QueryTablePreferences queryTablePreferences,
                                          @JsonProperty("selectionFilter") final List<ComponentSelectionHandler> selectionFilter) {
        this.queryId = queryId;
        this.queryTablePreferences = queryTablePreferences;
        this.selectionFilter = selectionFilter;
    }

    /// @return The ID of the StroomQL Query pane whose results the table shows, or null for none.
    public String getQueryId() {
        return queryId;
    }

    /// @return The table's preferences, or null for none.
    public QueryTablePreferences getQueryTablePreferences() {
        return queryTablePreferences;
    }

    @Override
    public List<ComponentSelectionHandler> getSelectionFilter() {
        return selectionFilter;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final StroomQLTableComponentSettings that = (StroomQLTableComponentSettings) o;
        return Objects.equals(queryId, that.queryId) &&
               Objects.equals(queryTablePreferences, that.queryTablePreferences) &&
               Objects.equals(selectionFilter, that.selectionFilter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryId, queryTablePreferences, selectionFilter);
    }

    @Override
    public String toString() {
        return "StroomQLTableComponentSettings{" +
               "queryId='" + queryId + '\'' +
               ", queryTablePreferences=" + queryTablePreferences +
               ", selectionFilter=" + selectionFilter +
               '}';
    }

    /// @return A builder for new settings.
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Builder copy() {
        return new Builder(this);
    }

    /// Builds [StroomQLTableComponentSettings].
    public static final class Builder
            extends ComponentSettings.AbstractBuilder<StroomQLTableComponentSettings, Builder>
            implements HasSelectionFilterBuilder<StroomQLTableComponentSettings, Builder> {

        private String queryId;
        private QueryTablePreferences queryTablePreferences;
        private List<ComponentSelectionHandler> selectionFilter;

        private Builder() {
        }

        private Builder(final StroomQLTableComponentSettings settings) {
            this.queryId = settings.queryId;
            this.queryTablePreferences = settings.queryTablePreferences == null
                    ? null
                    : settings.queryTablePreferences.copy().build();
            this.selectionFilter = settings.selectionFilter == null
                    ? null
                    : new ArrayList<>(settings.selectionFilter);
        }

        /// @param queryId The ID of the StroomQL Query pane whose results the table shows, or null for none.
        /// @return This builder.
        public Builder queryId(final String queryId) {
            this.queryId = queryId;
            return self();
        }

        /// @param queryTablePreferences The table's preferences, or null for none.
        /// @return This builder.
        public Builder queryTablePreferences(final QueryTablePreferences queryTablePreferences) {
            this.queryTablePreferences = queryTablePreferences;
            return self();
        }

        @Override
        public Builder selectionFilter(final List<ComponentSelectionHandler> selectionFilter) {
            this.selectionFilter = selectionFilter;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public StroomQLTableComponentSettings build() {
            return new StroomQLTableComponentSettings(
                    queryId,
                    queryTablePreferences,
                    selectionFilter);
        }
    }
}
