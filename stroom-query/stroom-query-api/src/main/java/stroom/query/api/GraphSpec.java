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

package stroom.query.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Carried on {@link Query#getGraphSpec()} when a compiled query is a Cypher graph query (see
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.6) - the original Cypher source text.
 * {@code Query.dataSource} still points directly at the target {@code GraphDbDoc} (a Cypher query has no
 * {@code FROM}-equivalent clause; the graph it runs against is always the doc it was submitted to), so unlike
 * {@link JoinSpec} this carries no sub-{@link SearchRequest}s of its own.
 *
 * <p>Deliberately holds only the raw Cypher text, not a serialised {@code LogicalPlan} - the compiled plan type
 * lives in {@code stroom-query-planner}, which itself depends on this module ({@code stroom-query-api}), so
 * embedding it here would be a circular module dependency. The text is cheap to re-parse and re-compile (a
 * single-hop grammar), so the {@code GraphSearchProvider} that reads this back simply repeats the
 * {@code CypherCompiler}'s parse + compile step at execution time.</p>
 *
 * <p>A plain Jackson-annotated class with a {@code Builder}, not a record - matching every other wire type in
 * this GWT-compiled module (see {@link ExplainPlan}'s Javadoc for why).</p>
 */
@JsonPropertyOrder({"cypher"})
@JsonInclude(Include.NON_NULL)
@Schema(name = "GraphSpec", description = "The original Cypher source text of a compiled graph query")
public class GraphSpec {

    @Schema(description = "The original Cypher query text", required = true)
    @JsonProperty
    private final String cypher;

    @JsonCreator
    public GraphSpec(@JsonProperty("cypher") final String cypher) {
        this.cypher = Objects.requireNonNull(cypher, "cypher");
    }

    public String getCypher() {
        return cypher;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final GraphSpec that)) {
            return false;
        }
        return Objects.equals(cypher, that.cypher);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cypher);
    }

    @Override
    public String toString() {
        return "GraphSpec{" +
               "cypher='" + cypher + '\'' +
               '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String cypher;

        private Builder() {
        }

        public Builder cypher(final String cypher) {
            this.cypher = cypher;
            return this;
        }

        public GraphSpec build() {
            return new GraphSpec(cypher);
        }
    }
}
