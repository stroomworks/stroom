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
 * A clause that may follow {@code from} in any order and (subject to the shared
 * {@code stroom.query.api.token.TokenType} ordering/cardinality maps re-applied by the binder in Task 1.4) any
 * number of times - see {@code StroomQL.g4}'s file header for why order/cardinality are not encoded in the
 * grammar itself.
 */
public sealed interface AstClause
        permits AstWhereClause, AstEvalClause, AstWindowClause, AstFilterClause, AstSortClause, AstGroupClause,
        AstHavingClause, AstSelectClause, AstLimitClause, AstShowClause {

    AstPosition position();
}
