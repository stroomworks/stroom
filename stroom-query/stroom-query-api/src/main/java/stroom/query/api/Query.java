/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.query.api;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * {@value #CLASS_DESC}
 */
@JsonPropertyOrder({"dataSource", "expression", "params", "timeRange", "joinSpec", "graphSpec"})
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = Query.CLASS_DESC)
public final class Query {

    public static final String CLASS_DESC = "The query terms for the search";

    @Schema(required = true)
    @JsonProperty
    private final DocRef dataSource;

    @Schema(description = "The root logical operator in the query expression tree",
            required = true)
    @JsonProperty
    private final ExpressionOperator expression;

    @JsonPropertyDescription("A list of key/value pairs that provide additional information about the query")
    @JsonProperty
    private final List<Param> params;

    @JsonPropertyDescription("High level time range filter to apply to the query used to filter shards and add " +
            "narrow the query expression")
    @JsonProperty
    private final TimeRange timeRange;

    @JsonPropertyDescription("Present only when dataSource is the sentinel join type - see " +
            ".1a. Never set for an ordinary, single-source " +
            "query.")
    @JsonProperty
    private final JoinSpec joinSpec;

    @JsonPropertyDescription("Present only when dataSource is a GraphDbDoc and the query was compiled from " +
            "Cypher.6. Never set for an " +
            "ordinary StroomQL query.")
    @JsonProperty
    private final GraphSpec graphSpec;

    @JsonCreator
    public Query(@JsonProperty("dataSource") final DocRef dataSource,
                 @JsonProperty("expression") final ExpressionOperator expression,
                 @JsonProperty("params") final List<Param> params,
                 @JsonProperty("timeRange") final TimeRange timeRange,
                 @JsonProperty("joinSpec") final JoinSpec joinSpec,
                 @JsonProperty("graphSpec") final GraphSpec graphSpec) {
        this.dataSource = dataSource;
        this.expression = expression;
        this.params = params;
        this.timeRange = timeRange;
        this.joinSpec = joinSpec;
        this.graphSpec = graphSpec;
    }

    public Query(final DocRef dataSource,
                 final ExpressionOperator expression,
                 final List<Param> params,
                 final TimeRange timeRange) {
        this(dataSource, expression, params, timeRange, null, null);
    }

    public DocRef getDataSource() {
        return dataSource;
    }

    public ExpressionOperator getExpression() {
        return expression;
    }

    public List<Param> getParams() {
        return params;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    public JoinSpec getJoinSpec() {
        return joinSpec;
    }

    public GraphSpec getGraphSpec() {
        return graphSpec;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Query query = (Query) o;
        return Objects.equals(dataSource, query.dataSource) &&
                Objects.equals(expression, query.expression) &&
                Objects.equals(params, query.params) &&
                Objects.equals(timeRange, query.timeRange) &&
                Objects.equals(joinSpec, query.joinSpec) &&
                Objects.equals(graphSpec, query.graphSpec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataSource, expression, params, timeRange, joinSpec, graphSpec);
    }

    @Override
    public String toString() {
        return "Query{" +
                "dataSource=" + dataSource +
                ", expression=" + expression +
                ", params=" + params +
                ", timeRange=" + timeRange +
                ", joinSpec=" + joinSpec +
                ", graphSpec=" + graphSpec +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder copy() {
        return new Builder(this);
    }

    /**
     * Builder for constructing a {@link Query}
     */
    public static final class Builder {

        private DocRef dataSource;
        private ExpressionOperator expression;
        private List<Param> params;
        private TimeRange timeRange;
        private JoinSpec joinSpec;
        private GraphSpec graphSpec;

        private Builder() {
        }

        private Builder(final Query query) {
            this.dataSource = query.dataSource;
            this.expression = query.expression;
            this.params = query.params;
            this.timeRange = query.timeRange;
            this.joinSpec = query.joinSpec;
            this.graphSpec = query.graphSpec;
        }

        /**
         * @param value A DocRef that points to the data source of the query
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder dataSource(final DocRef value) {
            this.dataSource = value;
            return this;
        }

//        /**
//         * Shortcut function for creating the datasource {@link DocRef} in one go
//         * @param type The type of the datasource
//         * @param uuid The UUID of the datasource
//         * @param name The name of the datasource
//         * @return this builder, with the completed datasource added.
//         */
//        public Builder dataSource(final String type, final String uuid, final String name) {
//            return this.dataSource(DocRef.builder.type(type).uuid(uuid).name(name).build);
//        }

        /**
         * @param value he root logical addOperator in the query expression tree
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder expression(final ExpressionOperator value) {
            this.expression = value;
            return this;
        }

        /**
         * Shortcut function to add a parameter and go straight back to building the query
         *
         * @param key   The parameter key
         * @param value The parameter value
         * @return this builder with the completed parameter added.
         */
        public Builder addParam(final String key, final String value) {
            return addParams(Param.builder().key(key).value(value).build());
        }

        /**
         * @param values A list of key/value pairs that provide additional information about the query
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder addParams(final Param... values) {
            if (this.params == null) {
                params = new ArrayList<>();
            }
            this.params.addAll(Arrays.asList(values));
            return this;
        }

        public Builder params(final List<Param> params) {
            this.params = params;
            return this;
        }

        public Builder timeRange(final TimeRange timeRange) {
            this.timeRange = timeRange;
            return this;
        }

        /**
         * @param joinSpec see {@link Query#getJoinSpec} - never set for an ordinary, single-source query.
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder joinSpec(final JoinSpec joinSpec) {
            this.joinSpec = joinSpec;
            return this;
        }

        /**
         * @param graphSpec see {@link Query#getGraphSpec} - never set for an ordinary StroomQL query.
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder graphSpec(final GraphSpec graphSpec) {
            this.graphSpec = graphSpec;
            return this;
        }

        public Query build() {
            return new Query(dataSource, expression, params, timeRange, joinSpec, graphSpec);
        }
    }
}
