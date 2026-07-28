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

import stroom.query.grammar.ast.cypher.AstAggregateExpr;
import stroom.query.grammar.ast.cypher.AstAggregateFunction;
import stroom.query.grammar.ast.cypher.AstAround;
import stroom.query.grammar.ast.cypher.AstAsOf;
import stroom.query.grammar.ast.cypher.AstBetween;
import stroom.query.grammar.ast.cypher.AstComparisonOp;
import stroom.query.grammar.ast.cypher.AstComparisonPredicate;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.ast.cypher.AstCypherStatement;
import stroom.query.grammar.ast.cypher.AstDiff;
import stroom.query.grammar.ast.cypher.AstDiffAccessorExpr;
import stroom.query.grammar.ast.cypher.AstDiffSide;
import stroom.query.grammar.ast.cypher.AstEdgeDirection;
import stroom.query.grammar.ast.cypher.AstInPredicate;
import stroom.query.grammar.ast.cypher.AstMatch;
import stroom.query.grammar.ast.cypher.AstNodePattern;
import stroom.query.grammar.ast.cypher.AstPropertyAccessExpr;
import stroom.query.grammar.ast.cypher.AstReadingClause;
import stroom.query.grammar.ast.cypher.AstWith;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The golden corpus for the locked v1 Cypher subset (see {@code Cypher.g4}'s file header and
 * Task 0.2 outcome / Task PoC.1): every listed
 * in-subset construct must parse, and every listed out-of-subset construct must throw {@link SyntaxException} -
 * mirroring the parity-test discipline the query-optimiser project used for StroomQL (Task 1.6).
 */
class TestCypherQueryParser {

    // ------------------------------------------------------------------------------------------------------
    // MUST parse: in-subset
    // ------------------------------------------------------------------------------------------------------

    @Test
    void singleHop_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");

        assertThat(query.readingClauses()).hasSize(1);
        final AstReadingClause clause = query.readingClauses().getFirst();
        assertThat(clause).isInstanceOf(AstMatch.class);
        final AstMatch match = (AstMatch) clause;
        assertThat(match.pattern().anchor().labels()).containsExactly("Device");
        assertThat(match.pattern().hops()).hasSize(1);
        assertThat(match.pattern().hops().getFirst().edge().type()).isEqualTo("CONNECTED_TO");
        assertThat(match.pattern().hops().getFirst().edge().direction()).isEqualTo(AstEdgeDirection.OUT);
        assertThat(query.returnClause().items()).hasSize(1);
        assertThat(query.returnClause().items().getFirst().expression())
                .isInstanceOf(AstPropertyAccessExpr.class);
    }

    @Test
    void fixedLengthChain_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (d:Device)-[:CONNECTED_TO]->(a:Account)<-[:OWNS]-(o:Owner) "
                + "RETURN a.id, o.name");

        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        assertThat(match.pattern().hops()).hasSize(2);
        assertThat(match.pattern().hops().get(0).edge().direction()).isEqualTo(AstEdgeDirection.OUT);
        assertThat(match.pattern().hops().get(1).edge().direction()).isEqualTo(AstEdgeDirection.IN);
        assertThat(query.returnClause().items()).hasSize(2);
    }

    @Test
    void boundedVarLengthPath_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (u:User)-[:MEMBER_OF*1..3]->(g:Group) RETURN g.id");

        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        final var edge = match.pattern().hops().getFirst().edge();
        assertThat(edge.varLength()).isNotNull();
        assertThat(edge.varLength().min()).isEqualTo(1);
        assertThat(edge.varLength().max()).isEqualTo(3);
    }

    @Test
    void whereOnProperties_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (a:Account) WHERE a.balance > 100 AND a.active = true RETURN a.id");

        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        assertThat(match.where()).isNotNull();
    }

    @Test
    void stringPredicates_parseToTheirComparisonOps() {
        assertComparisonOp("MATCH (a:Account) WHERE a.name STARTS WITH 'Pow' RETURN a.id",
                AstComparisonOp.STARTS_WITH);
        assertComparisonOp("MATCH (a:Account) WHERE a.name CONTAINS 'ow' RETURN a.id",
                AstComparisonOp.CONTAINS);
        assertComparisonOp("MATCH (a:Account) WHERE a.name ENDS WITH 'ell' RETURN a.id",
                AstComparisonOp.ENDS_WITH);
        assertComparisonOp("MATCH (a:Account) WHERE a.name =~ 'Pow.*' RETURN a.id",
                AstComparisonOp.REGEX);
    }

    private static void assertComparisonOp(final String cypher, final AstComparisonOp expected) {
        final AstCypherQuery query = CypherQueryParser.parse(cypher);
        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        final AstComparisonPredicate predicate = (AstComparisonPredicate) match.where().expr();
        assertThat(predicate.op()).isEqualTo(expected);
    }

    @Test
    void arithmeticExpressions_parse() {
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) RETURN a.balance * 1.2, a.x + a.y, (a.hi - a.lo) / 2, 2 ^ 3, a.n % 3"))
                .doesNotThrowAnyException();
        // Regression: '-' and '*' still work as pattern tokens alongside arithmetic.
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a)-[:R*1..2]->(b) RETURN a.x - b.y")).doesNotThrowAnyException();
    }

    @Test
    void existsSubqueries_parse() {
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (p:Person) WHERE EXISTS { (p)-[:PARTY_TO]->(:Crime) } RETURN p.id"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (p:Person) WHERE NOT EXISTS { (p)-[:PARTY_TO]->(c:Crime {type: 'theft'}) } RETURN p.id"))
                .doesNotThrowAnyException();
        // Combined with other predicates.
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (p:Person) WHERE p.age > 30 AND EXISTS { (p)-[:OWNS]->(:Car) } RETURN p.id"))
                .doesNotThrowAnyException();
    }

    @Test
    void caseExpressions_parse() {
        // Searched form (WHEN <boolean> THEN <value>), with and without ELSE, and a boolean-combined condition.
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) RETURN CASE WHEN a.balance > 0 THEN 'credit' ELSE 'debit' END"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) RETURN CASE WHEN a.x > 0 AND a.y < 5 THEN 'a' WHEN a.z = 1 THEN 'b' END"))
                .doesNotThrowAnyException();
        // Simple form (CASE <input> WHEN <value> THEN <value>).
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) RETURN CASE a.status WHEN 1 THEN 'on' WHEN 0 THEN 'off' ELSE 'unknown' END"))
                .doesNotThrowAnyException();
        // CASE nested inside a function argument and combined with arithmetic.
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) RETURN stroom.upperCase(CASE WHEN a.n > 0 THEN 'p' ELSE 'n' END), a.n % 2"))
                .doesNotThrowAnyException();
    }

    @Test
    void unionStatements_parse() {
        // parseStatement returns all branches; unionAll flags one entry per UNION operator.
        final AstCypherStatement union = CypherQueryParser.parseStatement(
                "MATCH (a:Account) RETURN a.id AS id UNION MATCH (d:Device) RETURN d.id AS id");
        assertThat(union.branches()).hasSize(2);
        assertThat(union.unionAll()).containsExactly(false);
        assertThat(union.isSingle()).isFalse();

        final AstCypherStatement unionAll = CypherQueryParser.parseStatement(
                "MATCH (a:Account) RETURN a.id AS id UNION ALL MATCH (a:Account) RETURN a.id AS id");
        assertThat(unionAll.unionAll()).containsExactly(true);

        // A three-branch statement mixing UNION and UNION ALL.
        final AstCypherStatement mixed = CypherQueryParser.parseStatement(
                "MATCH (a:Account) RETURN a.id AS id "
                + "UNION MATCH (d:Device) RETURN d.id AS id "
                + "UNION ALL MATCH (a:Account) RETURN a.id AS id");
        assertThat(mixed.branches()).hasSize(3);
        assertThat(mixed.unionAll()).containsExactly(false, true);

        // A plain query is a one-branch statement; the single-query parse entry point unwraps it.
        assertThat(CypherQueryParser.parseStatement("MATCH (a:Account) RETURN a.id").isSingle()).isTrue();
    }

    @Test
    void parseRejectsUnion_directingToStatementApi() {
        // The single-query parse(...) entry point cannot represent a UNION - it fails loud rather than dropping
        // branches.
        assertThatThrownBy(() -> CypherQueryParser.parse(
                "MATCH (a:Account) RETURN a.id AS id UNION MATCH (d:Device) RETURN d.id AS id"))
                .isInstanceOf(SyntaxException.class)
                .hasMessageContaining("UNION");
    }

    @Test
    void withHavingPipe_parses() {
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (p:Person)-[:PARTY_TO]->(c:Crime) WITH p.id AS pid, count(c) AS n WHERE n > 5 "
                + "RETURN pid, n")).doesNotThrowAnyException();
    }

    @Test
    void namespacedFunctionAndPropertyAccess_disambiguate() {
        // A namespaced call (NAME.NAME(...)) and a property access (NAME.NAME) both start NAME DOT NAME; the
        // trailing '(' tells them apart. All three coexist in one query.
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) WHERE a.balance = 1 RETURN a.id, stroom.upperCase(a.name)"))
                .doesNotThrowAnyException();
    }

    @Test
    void functionWithPropertyArgument_parses() {
        // Function arguments are now general expressions, so a scalar function can apply to a matched property.
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) RETURN upperCase(a.name)")).doesNotThrowAnyException();
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) RETURN concat(a.first, ' ', a.last)")).doesNotThrowAnyException();
    }

    @Test
    void optionalMatch_parsesWithOptionalFlag() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (p:Person {id: 'p-1'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) RETURN p.id");

        assertThat(query.readingClauses()).hasSize(2);
        assertThat(((AstMatch) query.readingClauses().get(0)).optional()).isFalse();
        assertThat(((AstMatch) query.readingClauses().get(1)).optional()).isTrue();
    }

    @Test
    void inAndIsNullPredicates_parse() {
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) WHERE a.id IN ['x', 'y'] RETURN a.id")).doesNotThrowAnyException();
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) WHERE a.closed IS NULL RETURN a.id")).doesNotThrowAnyException();
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) WHERE a.closed IS NOT NULL RETURN a.id")).doesNotThrowAnyException();
        assertThatCode(() -> CypherQueryParser.parse(
                "MATCH (a:Account) WHERE a.id IN [] RETURN a.id")).doesNotThrowAnyException();

        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (a:Account) WHERE a.id IN ['x', 'y'] RETURN a.id");
        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        assertThat(match.where().expr()).isInstanceOf(AstInPredicate.class);
    }

    @Test
    void returnDistinctWithAlias_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (a:Account) RETURN DISTINCT a.id AS accountId");

        assertThat(query.returnClause().distinct()).isTrue();
        assertThat(query.returnClause().items().getFirst().alias()).isEqualTo("accountId");
    }

    @Test
    void withOrderBySkipLimit_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (a:Account) WITH a ORDER BY a.id SKIP 5 LIMIT 10 RETURN a.id");

        assertThat(query.readingClauses()).hasSize(2);
        assertThat(query.readingClauses().getFirst()).isInstanceOf(AstMatch.class);
        final AstWith with = (AstWith) query.readingClauses().get(1);
        assertThat(with.orderBy()).isNotNull();
        assertThat(with.skip().value()).isEqualTo(5);
        assertThat(with.limit().value()).isEqualTo(10);
    }

    @Test
    void countStar_parses() {
        final AstCypherQuery query = CypherQueryParser.parse("MATCH (a:Account) RETURN count(*) AS total");

        final AstAggregateExpr aggregate = (AstAggregateExpr) query.returnClause().items().getFirst().expression();
        assertThat(aggregate.function()).isEqualTo(AstAggregateFunction.COUNT);
        assertThat(aggregate.star()).isTrue();
        assertThat(aggregate.argument()).isNull();
    }

    @Test
    void countDistinctOfProperty_parsesWithDistinctFlag() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (o:Officer) RETURN count(DISTINCT o.rank) AS ranks");

        final AstAggregateExpr aggregate = (AstAggregateExpr) query.returnClause().items().getFirst().expression();
        assertThat(aggregate.function()).isEqualTo(AstAggregateFunction.COUNT);
        assertThat(aggregate.distinct()).isTrue();
        assertThat(aggregate.star()).isFalse();
        assertThat(aggregate.argument()).isInstanceOf(AstPropertyAccessExpr.class);
    }

    @Test
    void collectOfProperty_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (o:Officer)-[:INVESTIGATED]->(c:Crime) RETURN o.id, collect(c.type) AS types");

        final AstAggregateExpr aggregate =
                (AstAggregateExpr) query.returnClause().items().get(1).expression();
        assertThat(aggregate.function()).isEqualTo(AstAggregateFunction.COLLECT);
        assertThat(aggregate.distinct()).isFalse();
    }

    @Test
    void sumOfProperty_parses() {
        final AstCypherQuery query = CypherQueryParser.parse("MATCH (a:Account) RETURN sum(a.balance) AS total");

        final AstAggregateExpr aggregate = (AstAggregateExpr) query.returnClause().items().getFirst().expression();
        assertThat(aggregate.function()).isEqualTo(AstAggregateFunction.SUM);
        assertThat(aggregate.star()).isFalse();
        assertThat(aggregate.argument()).isInstanceOf(AstPropertyAccessExpr.class);
    }

    @Test
    void asOfClause_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (a:Account) AS OF datetime('2026-01-01T00:00:00Z') RETURN a.id");

        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        assertThat(match.temporal()).isInstanceOf(AstAsOf.class);
    }

    @Test
    void aroundClause_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                + "AROUND datetime('2026-07-01T09:00:00Z') +/- duration('PT1H') RETURN a.id");

        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        assertThat(match.temporal()).isInstanceOf(AstAround.class);
    }

    @Test
    void betweenClause_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (a:Account) BETWEEN datetime('2026-01-01T00:00:00Z') "
                + "AND datetime('2026-02-01T00:00:00Z') RETURN a.id");

        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        assertThat(match.temporal()).isInstanceOf(AstBetween.class);
    }

    @Test
    void diffClause_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (a:Account) DIFF FROM datetime('2026-07-01T00:00:00Z') "
                + "TO datetime('2026-07-08T00:00:00Z') RETURN changeKind, a.id");

        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        assertThat(match.temporal()).isInstanceOf(AstDiff.class);
        final AstDiff diff = (AstDiff) match.temporal();
        assertThat(diff.baseline()).isNotNull();
        assertThat(diff.comparison()).isNotNull();
    }

    @Test
    void beforeAndAfterAccessors_parse() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (u:User) DIFF FROM datetime('2026-07-01T00:00:00Z') "
                + "TO datetime('2026-07-08T00:00:00Z') "
                + "RETURN before(u.department) AS wasDept, after(u.department) AS nowDept");

        final AstDiffAccessorExpr was =
                (AstDiffAccessorExpr) query.returnClause().items().get(0).expression();
        assertThat(was.side()).isEqualTo(AstDiffSide.BEFORE);
        assertThat(was.target().variable()).isEqualTo("u");
        assertThat(was.target().property()).isEqualTo("department");

        final AstDiffAccessorExpr now =
                (AstDiffAccessorExpr) query.returnClause().items().get(1).expression();
        assertThat(now.side()).isEqualTo(AstDiffSide.AFTER);
    }

    @Test
    void anchorWithNoLabelOrProperties_parses() {
        final AstCypherQuery query = CypherQueryParser.parse("MATCH (n)-->(m) RETURN n");

        final AstNodePattern anchor = ((AstMatch) query.readingClauses().getFirst()).pattern().anchor();
        assertThat(anchor.labels()).isEmpty();
        assertThat(anchor.properties()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------
    // Workstream D:
    // the RETURN GRAPH element-row terminal form, plain and combined with DIFF.
    // ------------------------------------------------------------------------------------------------------

    @Test
    void returnGraph_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (d:Device)-[:CONNECTED_TO]->(a:Account) RETURN GRAPH");

        assertThat(query.returnClause().graph()).isTrue();
        assertThat(query.returnClause().items()).isEmpty();
        assertThat(query.returnClause().distinct()).isFalse();
        assertThat(query.returnClause().orderBy()).isNull();
        assertThat(query.returnClause().skip()).isNull();
        assertThat(query.returnClause().limit()).isNull();
    }

    @Test
    void returnGraphIsCaseInsensitiveLikeOtherKeywords() {
        final AstCypherQuery query = CypherQueryParser.parse("MATCH (a:Account) return graph");

        assertThat(query.returnClause().graph()).isTrue();
    }

    @Test
    void diffWithReturnGraph_parses() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                + "DIFF FROM datetime('2026-07-01T00:00:00Z') TO datetime('2026-07-08T00:00:00Z') "
                + "RETURN GRAPH");

        final AstMatch match = (AstMatch) query.readingClauses().getFirst();
        assertThat(match.temporal()).isInstanceOf(AstDiff.class);
        assertThat(query.returnClause().graph()).isTrue();
    }

    @Test
    void plainReturnItems_stillLeaveTheGraphFlagFalse() {
        final AstCypherQuery query = CypherQueryParser.parse("MATCH (a:Account) RETURN a.id");

        assertThat(query.returnClause().graph()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------
    // Workstream A: the optional leading `from "X"`
    // portability clause.
    // ------------------------------------------------------------------------------------------------------

    @Test
    void leadingFromClause_parsesAndReachesTheAstUnescaped() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "from \"Test Graph\" MATCH (a:Account) RETURN a.id");

        assertThat(query.dataSourceName()).isEqualTo("Test Graph");
    }

    @Test
    void leadingFromClauseWithSingleQuotes_unescapesTheName() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "from 'My \\'Graph\\'' MATCH (a:Account) RETURN a.id");

        assertThat(query.dataSourceName()).isEqualTo("My 'Graph'");
    }

    @Test
    void noFromClause_leavesDataSourceNameNull() {
        final AstCypherQuery query = CypherQueryParser.parse("MATCH (a:Account) RETURN a.id");

        assertThat(query.dataSourceName()).isNull();
    }

    @Test
    void fromClauseIsCaseInsensitiveLikeOtherKeywords() {
        final AstCypherQuery query = CypherQueryParser.parse(
                "FROM \"Test Graph\" MATCH (a:Account) RETURN a.id");

        assertThat(query.dataSourceName()).isEqualTo("Test Graph");
    }

    // ------------------------------------------------------------------------------------------------------
    // MUST error: out-of-subset (rejected simply because the construct has no grammar rule at all)
    // ------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE (n:Account) RETURN n",
            "MATCH (a:Account) SET a.x = 1 RETURN a",
            "MATCH (a) RETURN a UNION MATCH (b) RETURN b",
            "UNWIND [1, 2, 3] AS x RETURN x",
            "MATCH (a:Account)-[:OWNS*]->(b) RETURN b",     // unbounded var-length - no finite max
            "CALL db.labels() RETURN *",
            "MATCH (a:Account) DELETE a",
            "MATCH (a:Account) REMOVE a.x RETURN a",
            "MATCH (a:Account) MERGE (b:Owner) RETURN a, b",
            // RETURN GRAPH carries none of the scalar RETURN's per-item modifiers (see Cypher.g4's returnClause
            // rule comment). It DOES accept an optional LIMIT - covered by returnGraphLimit_parses below.
            "MATCH (a:Account) RETURN GRAPH, a.id",
            "MATCH (a:Account) RETURN DISTINCT GRAPH",
            "MATCH (a:Account) RETURN GRAPH ORDER BY a.id",
            "MATCH (a:Account) RETURN GRAPH SKIP 5"
    })
    void outOfSubsetConstructs_throwSyntaxException(final String cypher) {
        assertThatThrownBy(() -> CypherQueryParser.parse(cypher)).isInstanceOf(SyntaxException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MATCH (n) RETURN GRAPH LIMIT 100",
            "MATCH (a:Account) RETURN GRAPH LIMIT 5"
    })
    void returnGraphLimit_parses(final String cypher) {
        assertThatCode(() -> CypherQueryParser.parse(cypher)).doesNotThrowAnyException();
    }

    @Test
    void malformedQuery_reportsPreciseLineAndColumn() {
        assertThatThrownBy(() -> CypherQueryParser.parse("MATCH (a RETURN a"))
                .isInstanceOfSatisfying(SyntaxException.class, e -> {
                    assertThat(e.getLine()).isEqualTo(1);
                    assertThat(e.getColumn()).isGreaterThan(0);
                });
    }

    // ------------------------------------------------------------------------------------------------------
    // MUST error: numeric literals too large to fit the field they parse into (code-review fix - these used to
    // reach Integer.parseInt/Long.parseLong directly and throw a raw, positionless NumberFormatException instead
    // of the documented SyntaxException contract).
    // ------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "MATCH (a:Account) RETURN a.id SKIP 99999999999999999999",
            "MATCH (a:Account) RETURN a.id LIMIT 99999999999999999999",
            "MATCH (a:Account)-[:OWNS*99999999999999999999..3]->(b) RETURN b",
            "MATCH (a:Account)-[:OWNS*1..99999999999999999999]->(b) RETURN b"
    })
    void oversizedNumericLiteral_throwsSyntaxExceptionSayingTooLarge(final String cypher) {
        assertThatThrownBy(() -> CypherQueryParser.parse(cypher))
                .isInstanceOfSatisfying(SyntaxException.class, e -> {
                    assertThat(e.getLine()).isEqualTo(1);
                    assertThat(e.getMessage()).contains("too large");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MATCH (a:Account) RETURN a.id SKIP 3.5",
            "MATCH (a:Account) RETURN a.id LIMIT 3.5"
    })
    void fractionalNumericLiteral_throwsSyntaxExceptionSayingWholeNumber(final String cypher) {
        // The NUMBER lexer rule matches decimals, so a fractional SKIP/LIMIT must be reported as "must be a whole
        // number", not misattributed to magnitude ("too large") as the first version of this fix did.
        assertThatThrownBy(() -> CypherQueryParser.parse(cypher))
                .isInstanceOfSatisfying(SyntaxException.class,
                        e -> assertThat(e.getMessage()).contains("whole number"));
    }
}
