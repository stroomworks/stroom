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

package stroom.query.planner.cypher;

import java.util.Objects;

/**
 * A non-aggregate {@code RETURN} item that doubles as an implicit {@code GROUP BY} key, per Cypher's rule that
 * every non-aggregate item in a {@code RETURN} mixing an aggregate becomes one (see
 * {@link CypherToLogicalPlan}'s class Javadoc). Only ever built from a property access (e.g. {@code a.id}) - a
 * bare pattern variable is rejected at compile time (see {@code CypherToLogicalPlan.compileOutputColumn}), since a
 * whole matched node/edge has no single value to group by.
 *
 * @param rowKey never null; the {@code "variable.property"} key a traversal row carries this value under (see
 *               {@code GraphTraversalEngine.rowFor}) - the same key {@link stroom.query.planner.logical.ProjectField}
 *               resolves via its {@code ${variable.property}} raw expression.
 */
public record GroupKeyColumn(String rowKey) implements OutputColumn {

    public GroupKeyColumn {
        Objects.requireNonNull(rowKey, "rowKey");
    }
}
