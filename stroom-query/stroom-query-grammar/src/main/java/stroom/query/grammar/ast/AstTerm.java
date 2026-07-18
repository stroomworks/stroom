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

/**
 * A single boolean-expression leaf term. See {@link AstIsNullTerm}'s own Javadoc for why it is implemented
 * (unlike legacy, which always rejects {@code is [not] null} due to an unimplemented-feature bug).
 */
public sealed interface AstTerm
        permits AstComparisonTerm, AstBetweenTerm, AstInTerm, AstInDictionaryTerm, AstIsNullTerm {

    /** @return never null; the field/param this term tests. */
    AstToken field();

    AstPosition position();
}
