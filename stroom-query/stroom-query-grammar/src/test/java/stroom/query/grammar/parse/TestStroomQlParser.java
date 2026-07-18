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

package stroom.query.grammar.parse;

import stroom.query.grammar.ast.AstQuery;
import stroom.query.grammar.ast.AstSelectClause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests for {@link StroomQlParser} / {@link ThrowingSyntaxErrorListener} (see
 * docs/query-optimiser-implementation-plan.md, Task 1.3): precise, structured syntax errors for malformed
 * queries, and that well-formed queries still parse cleanly end-to-end through the facade.
 */
class TestStroomQlParser {

    @Test
    void validQuery_parsesToAst() {
        final AstQuery query = StroomQlParser.parse("from 'Test Index' select StreamId, EventId");

        assertThat(query.from().source().unescapedText()).isEqualTo("Test Index");
        assertThat(query.clauses()).hasSize(1);
        assertThat(query.clauses().getFirst()).isInstanceOf(AstSelectClause.class);
    }

    @Test
    void missingFrom_reportsPreciseLineAndColumn() {
        // No `from` at all - the very first token is where the parser expects one.
        final SyntaxException e = catchThrowableOfType(SyntaxException.class, () -> StroomQlParser.parse("select a"));

        assertThat(e.getLine()).isEqualTo(1);
        assertThat(e.getColumn()).isEqualTo(0);
        // ANTLR only infers a quoted display name (e.g. "'from'") for a lexer rule written as one literal
        // string; ours is built from case-insensitive fragments (F R O M), so the vocabulary falls back to the
        // symbolic token name.
        assertThat(e.getExpectedTokens()).contains("FROM");
    }

    @Test
    void unclosedBracket_reportsPreciseLineAndColumn() {
        final SyntaxException e = catchThrowableOfType(
                SyntaxException.class, () -> StroomQlParser.parse("from X where (a = 1 select a"));

        // The parser discovers the problem at `select` - the token it hits while still expecting `)` or more
        // boolean-expression syntax inside the open bracket from `where (`.
        assertThat(e.getLine()).isEqualTo(1);
        assertThat(e.getColumn()).isEqualTo(20);
    }

    @Test
    void missingFrom_onSecondLine_reportsLineTwo() {
        final SyntaxException e = catchThrowableOfType(
                SyntaxException.class, () -> StroomQlParser.parse("// a comment\nselect a"));

        assertThat(e.getLine()).isEqualTo(2);
        assertThat(e.getColumn()).isEqualTo(0);
    }

    @Test
    void trailingGarbageAfterQuery_isRejected() {
        // The `query` rule requires EOF immediately after the last clause.
        assertThatThrownBy(() -> StroomQlParser.parse("from X select a )) blah"))
                .isInstanceOf(SyntaxException.class);
    }

    @Test
    void clauseReordering_isNotASyntaxError() {
        // Deliberately documents the design decision in StroomQL.g4's file header: clause order/cardinality is
        // a semantic (binder) concern, not a grammar concern, so legacy's "Unexpected token LIMIT after SELECT"
        // rejection of `select ... limit N` does NOT happen here - both orders parse identically at this layer.
        final AstQuery query = StroomQlParser.parse("from X select a limit 10");
        assertThat(query.clauses()).hasSize(2);
    }

    @Test
    void emptyQuery_isRejectedAsASyntaxError() {
        assertThatThrownBy(() -> StroomQlParser.parse(""))
                .isInstanceOf(SyntaxException.class);
    }

    @Test
    void parseRejectsNullQuery() {
        assertThatThrownBy(() -> StroomQlParser.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
