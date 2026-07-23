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
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A {@code RETURN GRAPH} element-row table (columns {@code kind,id,labels,source,target,properties}, one row per
 * node/edge) carried to the client for additive rendering - the result of the Data tab's graph "Expand neighbours"
 * action, which merges these rows into the already-loaded Cytoscape graph rather than replacing it.
 *
 * @param columns the element-row column names, in order.
 * @param rows    one row per element; each cell a display string (null where absent, e.g. a node's source/target).
 */
public record GraphElementTable(
        @JsonProperty("columns") List<String> columns,
        @JsonProperty("rows") List<List<String>> rows) {

    @JsonCreator
    public GraphElementTable {
        columns = columns == null ? List.of() : List.copyOf(columns);
        // Outer copy is safe (row lists are non-null); individual cells may be null (e.g. a node's source/target),
        // which the inner lists retain.
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
