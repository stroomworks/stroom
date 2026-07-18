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

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Either a parenthesised sub-expression {@code ( <bracketed> )} or a plain {@link AstTerm}.
 *
 * @param bracketed set iff this is a parenthesised sub-expression; null otherwise.
 * @param term      set iff this is a plain term; null otherwise.
 * @param position  never null.
 */
public record AstPrimary(@Nullable AstOrExpr bracketed, @Nullable AstTerm term, AstPosition position) {

    public AstPrimary {
        Objects.requireNonNull(position, "position");
        if ((bracketed == null) == (term == null)) {
            throw new IllegalArgumentException("exactly one of bracketed/term must be set");
        }
    }
}
