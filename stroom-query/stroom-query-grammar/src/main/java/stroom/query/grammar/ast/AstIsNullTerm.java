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

import java.util.Objects;

/**
 * {@code field is [not] null}. Legacy's tokeniser emits {@code IS_NULL}/{@code IS_NOT_NULL} tokens but
 * {@code SearchRequestFactory.createTerm}'s 3-token minimum check always rejects them with "Incomplete term" -
 * an unimplemented-feature bug, not a deliberate rejection (see docs/query-optimiser-known-differences.md), so
 * the binder (Task 1.4) implements this properly rather than reproducing legacy's rejection.
 *
 * @param field    never null.
 * @param negated  true for {@code is not null}, false for {@code is null}.
 * @param position never null.
 */
public record AstIsNullTerm(AstToken field, boolean negated, AstPosition position) implements AstTerm {

    public AstIsNullTerm {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(position, "position");
    }
}
