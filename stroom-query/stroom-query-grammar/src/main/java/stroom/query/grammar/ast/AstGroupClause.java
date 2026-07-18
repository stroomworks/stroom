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

package stroom.query.grammar.ast;

import java.util.List;
import java.util.Objects;

/**
 * {@code group [by] field, ...}. One {@link AstQuery} may contain several of these (one per grouping level -
 * legacy assigns each occurrence an incrementing group depth; see {@code SearchRequestFactory.processGroupBy}),
 * unlike most other clauses - see {@code StroomQL.g4}'s file header.
 *
 * @param fields   never null; never empty.
 * @param position never null.
 */
public record AstGroupClause(List<AstToken> fields, AstPosition position) implements AstClause {

    public AstGroupClause {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(position, "position");
        fields = List.copyOf(fields);
    }
}
