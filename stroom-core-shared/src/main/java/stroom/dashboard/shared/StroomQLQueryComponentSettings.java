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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

/// The settings of a dashboard's StroomQL Query pane: a StroomQL query written in the dashboard, whose
/// results are shown by the StroomQL Table panes that name the pane as their query.
@JsonPropertyOrder({
        "query",
        "automate",
        "selectionHandlers"})
@JsonInclude(Include.NON_NULL)
public final class StroomQLQueryComponentSettings
        extends AbstractQueryComponentSettings
        implements ComponentSettings {

    @JsonPropertyDescription("The StroomQL query's text, which may hold dashboard parameters, e.g. ${user}.")
    @JsonProperty
    private final String query;

    /// @param query             The StroomQL query's text, or null for none.
    /// @param automate          When the query runs by itself, or null for never.
    /// @param selectionHandlers The selections in other panes that narrow the query, or null for none.
    @SuppressWarnings("checkstyle:LineLength")
    @JsonCreator
    public StroomQLQueryComponentSettings(@JsonProperty("query") final String query,
                                          @JsonProperty("automate") final Automate automate,
                                          @JsonProperty("selectionHandlers") final List<ComponentSelectionHandler> selectionHandlers) {
        super(automate, selectionHandlers);
        this.query = query;
    }

    /// @return The StroomQL query's text, or null for none.
    public String getQuery() {
        return query;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final StroomQLQueryComponentSettings that = (StroomQLQueryComponentSettings) o;
        return Objects.equals(query, that.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), query);
    }

    @Override
    public String toString() {
        return "StroomQLQueryComponentSettings{" +
               "query='" + query + '\'' +
               ", automate=" + getAutomate() +
               ", selectionHandlers=" + getSelectionQuery() +
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

    /// Builds [StroomQLQueryComponentSettings].
    public static final class Builder
            extends AbstractQueryComponentSettings.AbstractBuilder<StroomQLQueryComponentSettings, Builder> {

        private String query;

        private Builder() {
            super();
        }

        private Builder(final StroomQLQueryComponentSettings settings) {
            super(settings);
            this.query = settings.query;
        }

        /// @param query The StroomQL query's text, or null for none.
        /// @return This builder.
        public Builder query(final String query) {
            this.query = query;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public StroomQLQueryComponentSettings build() {
            return new StroomQLQueryComponentSettings(
                    query,
                    automate,
                    selectionQuery);
        }
    }
}
