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

/**
 * The {@code GraphDbDoc} document type (see {@code docs/temporal-cypher-graph-implementation-plan.md}, Task
 * PoC.0): the single document a user creates for a temporal Cypher graph, carrying only the genuine
 * user-configurable choices. Every physical store it owns is internal to {@code stroom-graphdb-impl}.
 */
@NullMarked
package stroom.graphdb.shared;

import org.jspecify.annotations.NullMarked;
