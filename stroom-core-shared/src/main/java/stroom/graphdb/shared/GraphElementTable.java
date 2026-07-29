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

package stroom.graphdb.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

/**
 * A {@code RETURN GRAPH} element-row table (columns {@code kind,id,labels,source,target,properties}, one row per
 * node/edge) carried to the client for additive rendering - the result of the Data tab's graph "Expand neighbours"
 * action, which merges these rows into the already-loaded Cytoscape graph rather than replacing it.
 *
 * <p><b>A class rather than a record</b>, for the reason given on {@link GraphNodeTypeMapping}.
 */
@JsonPropertyOrder({
        "columns",
        "rows"
})
@JsonInclude(Include.NON_NULL)
public class GraphElementTable {

    @JsonProperty
    private final List<String> columns;
    @JsonProperty
    private final List<List<String>> rows;

    /**
     * <b>Preconditions:</b> none; a null list is read as empty.
     * <b>Postconditions:</b> both lists are unmodifiable copies.
     * <b>Null status:</b> both parameters are nullable; neither accessor returns null.
     *
     * @param columns the element-row column names, in order.
     * @param rows    one row per element; each cell a display string (null where absent, e.g. a node's
     *                source/target).
     */
    @JsonCreator
    public GraphElementTable(@JsonProperty("columns") final List<String> columns,
                             @JsonProperty("rows") final List<List<String>> rows) {
        this.columns = columns == null
                ? List.of()
                : List.copyOf(columns);
        // Outer copy is safe (row lists are non-null); individual cells may be null (e.g. a node's source/target),
        // which the inner lists retain.
        this.rows = rows == null
                ? List.of()
                : List.copyOf(rows);
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<String>> getRows() {
        return rows;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final GraphElementTable that = (GraphElementTable) o;
        return Objects.equals(columns, that.columns) && Objects.equals(rows, that.rows);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns, rows);
    }

    @Override
    public String toString() {
        return "GraphElementTable{" +
               "columns=" + columns +
               ", rows=" + rows.size() +
               '}';
    }
}
