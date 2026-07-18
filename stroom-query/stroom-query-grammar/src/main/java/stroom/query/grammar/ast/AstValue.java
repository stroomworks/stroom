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
 * A single term value: the exact original source text of a run of one or more value tokens (e.g. {@code now()
 * - 2d} is one value, not three). Legacy classifies such a run as a date expression, a signed number, or (only
 * then) a single bare token by inspecting each underlying token's type (see
 * {@code SearchRequestFactory.parseValueTokens} and {@code DateExpressionParser}, both existing/unchanged) - that
 * classification is a Task 1.4 concern: it re-tokenises {@link #sourceText()} with the existing legacy
 * {@code Tokeniser} to get byte-identical results, rather than this grammar/AST re-deriving an equivalent
 * decision independently.
 *
 * @param sourceText never null; the exact original source substring (whitespace preserved), not a
 *                   reconstruction.
 * @param position   never null.
 */
public record AstValue(String sourceText, AstPosition position) {

    public AstValue {
        Objects.requireNonNull(sourceText, "sourceText");
        Objects.requireNonNull(position, "position");
    }
}
