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

/**
 * Shared reserved row-map keys for the graph-identity functions {@code id(v)} and {@code type(r)}, agreed between
 * the compiler ({@code CypherToLogicalPlan} lowers the calls to a reference to these keys) and the graph executor
 * ({@code GraphTraversalEngine} populates them as it binds each node/edge variable) - a node's own identity and an
 * edge's type are otherwise not present in a traversal row (which carries only {@code "variable.property"} values).
 *
 * <p>The keys are space-prefixed, which a grammar {@code NAME} token can never produce, so they cannot collide with
 * a real {@code "variable.property"} key.</p>
 */
public final class GraphIdentity {

    private GraphIdentity() {
    }

    /** Row-map key carrying node variable {@code variable}'s identity (its external id), for {@code id(variable)}. */
    public static String nodeIdKey(final String variable) {
        return " id " + variable;
    }

    /** Row-map key carrying edge variable {@code variable}'s relationship type, for {@code type(variable)}. */
    public static String edgeTypeKey(final String variable) {
        return " type " + variable;
    }
}
