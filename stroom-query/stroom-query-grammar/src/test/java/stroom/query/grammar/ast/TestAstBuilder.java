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

import stroom.query.grammar.antlr.StroomQLLexer;
import stroom.query.grammar.antlr.StroomQLParser;
import stroom.query.grammar.parse.SyntaxException;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-driven {@code text -> AST} tests for {@link AstBuilder} (see
 * docs/query-optimiser-implementation-plan.md, Task 1.2). Complements the syntax-only smoke tests in
 * {@code TestStroomQLGrammar} by asserting the SHAPE of the resulting AST, not just that parsing succeeds.
 */
class TestAstBuilder {

    private AstQuery build(final String query) {
        final StroomQLLexer lexer = new StroomQLLexer(CharStreams.fromString(query));
        final CommonTokenStream tokens = new CommonTokenStream(lexer);
        final StroomQLParser parser = new StroomQLParser(tokens);
        return new AstBuilder(tokens).build(parser.query());
    }

    @Test
    void fromAndSelect_basicShape() {
        final AstQuery query = build("from 'Test Index' select StreamId, EventId");

        assertThat(query.from().source().unescapedText()).isEqualTo("Test Index");
        assertThat(query.from().source().kind()).isEqualTo(AstToken.Kind.SINGLE_QUOTED);
        assertThat(query.from().alias()).isNull();
        assertThat(query.from().joins()).isEmpty();

        assertThat(query.clauses()).hasSize(1);
        final AstSelectClause select = (AstSelectClause) query.clauses().getFirst();
        assertThat(select.items()).hasSize(2);
        final AstSelectField first = (AstSelectField) select.items().get(0);
        assertThat(first.field().unescapedText()).isEqualTo("StreamId");
        assertThat(first.alias()).isNull();
    }

    @Test
    void fromWithDoubleQuotedAlias() {
        final AstQuery query = build("from \"index_view\" select StreamId as \"Stream Id\"");

        assertThat(query.from().source().unescapedText()).isEqualTo("index_view");
        final AstSelectClause select = (AstSelectClause) query.clauses().getFirst();
        final AstSelectField item = (AstSelectField) select.items().getFirst();
        assertThat(item.alias()).isNotNull();
        assertThat(item.alias().unescapedText()).isEqualTo("Stream Id");
    }

    @Test
    void where_andChain_isKeptFlatNotPreFolded() {
        // "a = 1 and b = 2 and c = 3" - the AST keeps this as one flat 3-operand AstAndExpr; Task 1.4 is
        // responsible for folding it pairwise, left-associatively (see AstAndExpr's Javadoc).
        final AstQuery query = build("from X where a = 1 and b = 2 and c = 3 select a");

        final AstWhereClause where = (AstWhereClause) query.clauses().getFirst();
        assertThat(where.expr().operands()).hasSize(1); // single AND-group, no top-level OR
        final AstAndExpr andExpr = where.expr().operands().getFirst();
        assertThat(andExpr.operands()).hasSize(3);
    }

    @Test
    void where_orOfAnds() {
        final AstQuery query = build("from X where a = 1 and b = 2 or c = 3 select a");

        final AstWhereClause where = (AstWhereClause) query.clauses().getFirst();
        assertThat(where.expr().operands()).hasSize(2);
        assertThat(where.expr().operands().get(0).operands()).hasSize(2); // a=1 and b=2
        assertThat(where.expr().operands().get(1).operands()).hasSize(1); // c=3
    }

    @Test
    void where_not() {
        final AstQuery query = build("from X where not a = 1 select a");

        final AstWhereClause where = (AstWhereClause) query.clauses().getFirst();
        final AstNotExpr notExpr = where.expr().operands().getFirst().operands().getFirst();
        assertThat(notExpr.negated()).isTrue();
        assertThat(notExpr.inner()).isNotNull();
        assertThat(notExpr.inner().negated()).isFalse();
    }

    @Test
    void where_bracketedSubExpression() {
        final AstQuery query = build("from X where a = 1 and (b = 2 or c = 3) select a");

        final AstWhereClause where = (AstWhereClause) query.clauses().getFirst();
        final AstAndExpr andExpr = where.expr().operands().getFirst();
        assertThat(andExpr.operands()).hasSize(2);
        final AstPrimary secondPrimary = andExpr.operands().get(1).primary();
        assertThat(secondPrimary.bracketed()).isNotNull();
        assertThat(secondPrimary.bracketed().operands()).hasSize(2); // b=2 or c=3
    }

    @Test
    void where_comparisonTerm() {
        final AstQuery query = build("from X where UserId = user5 select a");

        final AstTerm term = onlyTerm(query);
        assertThat(term).isInstanceOf(AstComparisonTerm.class);
        final AstComparisonTerm comparisonTerm = (AstComparisonTerm) term;
        assertThat(comparisonTerm.field().unescapedText()).isEqualTo("UserId");
        assertThat(comparisonTerm.cond()).isEqualTo(AstComparisonCond.EQUALS);
        assertThat(comparisonTerm.value().sourceText()).isEqualTo("user5");
    }

    @Test
    void where_betweenTerm() {
        final AstQuery query = build(
                "from X where EventTime between 2022-05-05T00:00:00.000Z and 2023-05-05T00:00:00.000Z select a");

        final AstTerm term = onlyTerm(query);
        assertThat(term).isInstanceOf(AstBetweenTerm.class);
        final AstBetweenTerm betweenTerm = (AstBetweenTerm) term;
        assertThat(betweenTerm.lower().sourceText()).isEqualTo("2022-05-05T00:00:00.000Z");
        assertThat(betweenTerm.upper().sourceText()).isEqualTo("2023-05-05T00:00:00.000Z");
    }

    @Test
    void where_inTerm() {
        final AstQuery query = build("from X where StreamId in (123, 456) select a");

        final AstTerm term = onlyTerm(query);
        assertThat(term).isInstanceOf(AstInTerm.class);
        final AstInTerm inTerm = (AstInTerm) term;
        assertThat(inTerm.values()).extracting(AstValue::sourceText).containsExactly("123", "456");
    }

    @Test
    void where_inDictionaryTerm() {
        final AstQuery query = build("from X where StreamId in dictionary \"my_dictionary\" select a");

        final AstTerm term = onlyTerm(query);
        assertThat(term).isInstanceOf(AstInDictionaryTerm.class);
        assertThat(((AstInDictionaryTerm) term).dictionaryName().unescapedText()).isEqualTo("my_dictionary");
    }

    @Test
    void where_isNullTerm_bothVariants() {
        final AstTerm isNull = onlyTerm(build("from X where Status is null select a"));
        assertThat(isNull).isInstanceOf(AstIsNullTerm.class);
        assertThat(((AstIsNullTerm) isNull).negated()).isFalse();

        final AstTerm isNotNull = onlyTerm(build("from X where Status is not null select a"));
        assertThat(isNotNull).isInstanceOf(AstIsNullTerm.class);
        assertThat(((AstIsNullTerm) isNotNull).negated()).isTrue();
    }

    @Test
    void eval_capturesExactExpressionText() {
        final AstQuery query = build("from X eval comp = max(toFloat(day()-10d)) select comp");

        final AstEvalClause eval = (AstEvalClause) query.clauses().getFirst();
        assertThat(eval.name().unescapedText()).isEqualTo("comp");
        assertThat(eval.expressionText()).isEqualTo("max(toFloat(day()-10d))");
    }

    @Test
    void select_functionItem_defaultsAliasToNull_andCapturesRawText() {
        final AstQuery query = build("from X select upperCase(UserId)");

        final AstSelectClause select = (AstSelectClause) query.clauses().getFirst();
        final AstSelectFunction item = (AstSelectFunction) select.items().getFirst();
        assertThat(item.expressionText()).isEqualTo("upperCase(UserId)");
        assertThat(item.alias()).isNull();
    }

    @Test
    void select_andOrNotAreValidFunctionNames() {
        // Matches the golden corpus's `eval bool = and(idx1 >= 0, idx2 >= 0)` construct - see StroomQL.g4's file
        // header on why AND/OR/NOT must be accepted here even though they are also reserved boolean keywords.
        final AstQuery query = build("from X select and(a, b)");

        final AstSelectClause select = (AstSelectClause) query.clauses().getFirst();
        final AstSelectFunction item = (AstSelectFunction) select.items().getFirst();
        assertThat(item.expressionText()).isEqualTo("and(a, b)");
    }

    @Test
    void select_star() {
        final AstQuery query = build("from X select *");

        final AstSelectClause select = (AstSelectClause) query.clauses().getFirst();
        assertThat(select.items().getFirst()).isInstanceOf(AstSelectStar.class);
    }

    @Test
    void select_param() {
        final AstQuery query = build("from X select ${Stream Id}");

        final AstSelectClause select = (AstSelectClause) query.clauses().getFirst();
        final AstSelectParam item = (AstSelectParam) select.items().getFirst();
        assertThat(item.field().kind()).isEqualTo(AstToken.Kind.PARAM);
        assertThat(item.field().unescapedText()).isEqualTo("Stream Id");
    }

    @Test
    void sort_capturesFieldAndDirection() {
        final AstQuery query = build("from X sort by StreamId asc, EventTime desc select a");

        final AstSortClause sort = (AstSortClause) query.clauses().getFirst();
        assertThat(sort.items()).hasSize(2);
        assertThat(sort.items().get(0).field().unescapedText()).isEqualTo("StreamId");
        assertThat(sort.items().get(0).direction().unescapedText()).isEqualTo("asc");
        assertThat(sort.items().get(1).direction().unescapedText()).isEqualTo("desc");
    }

    @Test
    void sort_directionIsOptional() {
        final AstQuery query = build("from X sort by StreamId select a");

        final AstSortClause sort = (AstSortClause) query.clauses().getFirst();
        assertThat(sort.items().getFirst().direction()).isNull();
    }

    @Test
    void group_capturesFieldsInOrder_andCanRepeat() {
        final AstQuery query = build("from X group by StreamId group by EventTime select StreamId, EventTime");

        assertThat(query.clauses()).hasSize(3); // 2 group-by clauses + 1 select clause
        assertThat(query.clauses().get(0)).isInstanceOf(AstGroupClause.class);
        assertThat(query.clauses().get(1)).isInstanceOf(AstGroupClause.class);
        assertThat(query.clauses().get(2)).isInstanceOf(AstSelectClause.class);
        assertThat(((AstGroupClause) query.clauses().get(0)).fields())
                .extracting(AstToken::unescapedText)
                .containsExactly("StreamId");
    }

    @Test
    void having_capturesExpr() {
        final AstQuery query = build("from X having ${Stream Id} > -2 select ${Stream Id}");

        final AstHavingClause having = (AstHavingClause) query.clauses().getFirst();
        final AstComparisonTerm term = (AstComparisonTerm) having.expr().operands().getFirst().operands()
                .getFirst().primary().term();
        assertThat(term.field().unescapedText()).isEqualTo("Stream Id");
        assertThat(term.cond()).isEqualTo(AstComparisonCond.GREATER_THAN);
        assertThat(term.value().sourceText()).isEqualTo("-2");
    }

    @Test
    void window_capturesAllParts() {
        final AstQuery query = build("from X window EventTime by 1h advance 10m using max select a");

        final AstWindowClause window = (AstWindowClause) query.clauses().getFirst();
        assertThat(window.field().unescapedText()).isEqualTo("EventTime");
        assertThat(window.windowSize().rawText()).isEqualTo("1h");
        assertThat(window.advanceSize()).isNotNull();
        assertThat(window.advanceSize().rawText()).isEqualTo("10m");
        assertThat(window.usingFunction()).isNotNull();
        assertThat(window.usingFunction().unescapedText()).isEqualTo("max");
    }

    @Test
    void window_advanceAndUsingAreOptional() {
        final AstQuery query = build("from X window EventTime by 1h select a");

        final AstWindowClause window = (AstWindowClause) query.clauses().getFirst();
        assertThat(window.advanceSize()).isNull();
        assertThat(window.usingFunction()).isNull();
    }

    @Test
    void limit_capturesValues() {
        final AstQuery query = build("from X select a limit 10, 20");

        final AstLimitClause limit = (AstLimitClause) query.clauses().getLast();
        assertThat(limit.values()).extracting(AstToken::unescapedText).containsExactly("10", "20");
    }

    @Test
    void limit_capturesAQuotedValue() {
        // A quoted limit value exercises buildLimitValue's nameToken branch (legacy accepts quoted numeric
        // strings here, not just NUMBER tokens - see the grammar's limitValue rule and processLimit).
        final AstQuery query = build("from X select a limit '10'");

        final AstLimitClause limit = (AstLimitClause) query.clauses().getLast();
        assertThat(limit.values()).extracting(AstToken::unescapedText).containsExactly("10");
        assertThat(limit.values().getFirst().kind()).isEqualTo(AstToken.Kind.SINGLE_QUOTED);
    }

    @Test
    void filter_capturesExpr() {
        // The `filter` clause (post-hoc, in-memory predicates) - distinct from `where` and previously exercised
        // by no AstBuilder test.
        final AstQuery query = build("from X filter StreamId = 1 select a");

        final AstFilterClause filter = (AstFilterClause) query.clauses().getFirst();
        assertThat(filter.expr()).isNotNull();
    }

    @Test
    void show_capturesName() {
        final AstQuery query = build("from X select a show as chart");

        final AstShowClause show = (AstShowClause) query.clauses().getLast();
        assertThat(show.name().unescapedText()).isEqualTo("chart");
    }

    @Test
    void join_namedSource_capturesTypeSourceAliasAndConditions() {
        final AstQuery query = build(
                "from Events as e left join UserState as u on e.userId = u.userId select e.userId");

        assertThat(query.from().alias().unescapedText()).isEqualTo("e");
        assertThat(query.from().joins()).hasSize(1);
        final AstJoin join = query.from().joins().getFirst();
        assertThat(join.joinType()).isEqualTo(AstJoin.JoinType.LEFT);
        assertThat(join.source()).isInstanceOf(AstNamedJoinSource.class);
        assertThat(((AstNamedJoinSource) join.source()).token().unescapedText()).isEqualTo("UserState");
        assertThat(join.alias().unescapedText()).isEqualTo("u");
        assertThat(join.conditions()).hasSize(1);
        assertThat(join.conditions().getFirst().left().unescapedText()).isEqualTo("e.userId");
        assertThat(join.conditions().getFirst().right().unescapedText()).isEqualTo("u.userId");
    }

    @Test
    void join_withNoExplicitType_hasNullJoinType() {
        final AstQuery query = build("from Events join UserState on userId = userId select a");

        assertThat(query.from().joins().getFirst().joinType()).isNull();
    }

    // ------------------------------------------------------------------------------------------------------
    // sub-query join source (docs/graphdb-stroomql-join-implementation-plan.md, Phase P1)
    // ------------------------------------------------------------------------------------------------------

    @Test
    void join_subQuerySource_capturesRawTextAndAlias() {
        final AstQuery query = build(
                "from AuthEvents as e inner join ( from \"CorpGraph\" match (u:User)-[:MEMBER_OF]->(g:Group) "
                + "return u.id as userId, g.name as groupName ) as ident on e.user = ident.userId "
                + "select e.time, ident.groupName");

        assertThat(query.from().joins()).hasSize(1);
        final AstJoin join = query.from().joins().getFirst();
        assertThat(join.joinType()).isEqualTo(AstJoin.JoinType.INNER);
        assertThat(join.source()).isInstanceOf(AstSubQueryJoinSource.class);
        final AstSubQueryJoinSource subQuery = (AstSubQueryJoinSource) join.source();
        assertThat(subQuery.rawText()).contains("from \"CorpGraph\"", "match (u:User)-[:MEMBER_OF]->(g:Group)",
                "return u.id as userId, g.name as groupName");
        assertThat(join.alias().unescapedText()).isEqualTo("ident");
        assertThat(join.conditions()).hasSize(1);
        assertThat(join.conditions().getFirst().left().unescapedText()).isEqualTo("e.user");
        assertThat(join.conditions().getFirst().right().unescapedText()).isEqualTo("ident.userId");
    }

    @Test
    void join_subQuerySource_nestedParenthesesInsideBodyAreBalancedCorrectly() {
        // The graph pattern's own brackets `(u:User)`/`(g:Group)` must not be mistaken for the join source's
        // closing bracket - proves subQueryBody's nested-bracket balancing finds the REAL closing bracket.
        final AstQuery query = build(
                "from AuthEvents inner join ( from \"G\" match (a)-[:R]->(b) return a.id as x ) as g "
                + "on user = g.x select x");

        // Leading/trailing whitespace immediately touching the brackets is not part of the captured span (the
        // rule's own start/stop tokens are its first/last CONTENT tokens, not the surrounding hidden-channel
        // whitespace) - only interior whitespace between content tokens survives.
        final AstSubQueryJoinSource subQuery = (AstSubQueryJoinSource) query.from().joins().getFirst().source();
        assertThat(subQuery.rawText()).isEqualTo(
                "from \"G\" match (a)-[:R]->(b) return a.id as x");
    }

    @Test
    void join_subQuerySource_withoutAlias_isAPositionedSyntaxError() {
        assertThatThrownBy(() -> build(
                "from AuthEvents inner join ( from \"G\" match (a) return a.id as x ) on user = x select x"))
                .isInstanceOf(SyntaxException.class)
                .hasMessageContaining("alias");
    }

    @Test
    void unescaping_singleAndDoubleQuotedStrings_matchLegacyAlgorithm() {
        final AstQuery query = build("from X where 'my.name' = 'foo bar' select a");
        final AstComparisonTerm term = (AstComparisonTerm) onlyTerm(query);
        assertThat(term.field().unescapedText()).isEqualTo("my.name");
        assertThat(term.field().kind()).isEqualTo(AstToken.Kind.SINGLE_QUOTED);
    }

    @Test
    void positionsArePopulated_lineAndColumn() {
        final AstQuery query = build("from X\nwhere a = 1\nselect a");

        // `where` starts on line 2, column 0 (0-based, matching ANTLR's convention - see AstPosition's Javadoc).
        final AstWhereClause where = (AstWhereClause) query.clauses().getFirst();
        assertThat(where.position()).isEqualTo(new AstPosition(2, 0));
    }

    @Test
    void constructorsRejectNullArguments() {
        assertThatThrownBy(() -> new AstBuilder(null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * @return the single {@link AstTerm} of the first {@code where} clause in {@code query} - a convenience for
     *         tests that only care about one term's shape.
     */
    private AstTerm onlyTerm(final AstQuery query) {
        final AstWhereClause where = (AstWhereClause) query.clauses().getFirst();
        return where.expr().operands().getFirst().operands().getFirst().primary().term();
    }
}
