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

package stroom.query.planner.logical;

import stroom.query.grammar.ast.AstPosition;

import java.util.Objects;

/**
 * A leaf node reading one datasource - the bound form of a {@code from} clause (or one side of a {@code join}).
 *
 * @param alias          never null; the name later clauses/joins qualify this source's fields by. Defaults to
 *                        {@code dataSourceName} when the query has no explicit {@code as} alias (the binder's
 *                        job, not this record's).
 * @param dataSourceName never null; the raw datasource name/UUID as written in the query - resolving it to a
 *                        {@code DocRef} is left to execution (Phase 5); the bind phase only needs the name to
 *                        look up field metadata via {@code FieldInfoSource}.
 * @param position       never null.
 */
public record Scan(String alias, String dataSourceName, AstPosition position) implements LogicalPlan {

    public Scan {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(dataSourceName, "dataSourceName");
        Objects.requireNonNull(position, "position");
    }
}
