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
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.ast.cypher.AstEdgeDirection;
import stroom.query.grammar.ast.cypher.AstMatch;
import stroom.query.grammar.ast.cypher.AstNodePattern;
import stroom.query.grammar.ast.cypher.AstPropertyAccessExpr;
import stroom.query.grammar.ast.cypher.AstReadingClause;
import stroom.query.grammar.ast.cypher.AstWith;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The golden corpus for the locked v1 Cypher subset (see {@code Cypher.g4}'s file header and
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task 0.2 outcome / Task PoC.1): every listed
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
    void anchorWithNoLabelOrProperties_parses() {
        final AstCypherQuery query = CypherQueryParser.parse("MATCH (n)-->(m) RETURN n");

        final AstNodePattern anchor = ((AstMatch) query.readingClauses().getFirst()).pattern().anchor();
        assertThat(anchor.labels()).isEmpty();
        assertThat(anchor.properties()).isEmpty();
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
            "OPTIONAL MATCH (a:Account) RETURN a",
            "MATCH (a:Account)-[:OWNS*]->(b) RETURN b",     // unbounded var-length - no finite max
            "CALL db.labels() RETURN *",
            "MATCH (a:Account) DELETE a",
            "MATCH (a:Account) REMOVE a.x RETURN a",
            "MATCH (a:Account) MERGE (b:Owner) RETURN a, b"
    })
    void outOfSubsetConstructs_throwSyntaxException(final String cypher) {
        assertThatThrownBy(() -> CypherQueryParser.parse(cypher)).isInstanceOf(SyntaxException.class);
    }

    @Test
    void malformedQuery_reportsPreciseLineAndColumn() {
        assertThatThrownBy(() -> CypherQueryParser.parse("MATCH (a RETURN a"))
                .isInstanceOfSatisfying(SyntaxException.class, e -> {
                    assertThat(e.getLine()).isEqualTo(1);
                    assertThat(e.getColumn()).isGreaterThan(0);
                });
    }
}
