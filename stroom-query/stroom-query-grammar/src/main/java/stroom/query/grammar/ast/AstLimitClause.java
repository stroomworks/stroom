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
 * {@code limit n, n, ...} - one cap per grouping level. Legacy accepts a quoted/bare numeric-looking string here
 * too (parsed with {@code Long.parseLong}), not only bare {@code NUMBER} tokens - see
 * {@code SearchRequestFactory.processLimit} - so each value is carried as an {@link AstToken} (kind
 * {@link AstToken.Kind#BAREWORD} for a bare {@code NUMBER}) rather than a parsed {@code long}; parsing/validation
 * is a Task 1.4 concern.
 *
 * @param values   never null; never empty.
 * @param position never null.
 */
public record AstLimitClause(List<AstToken> values, AstPosition position) implements AstClause {

    public AstLimitClause {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(position, "position");
        values = List.copyOf(values);
    }
}
