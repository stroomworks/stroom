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

import stroom.query.grammar.antlr.CypherLexer;
import stroom.query.grammar.antlr.CypherParser;
import stroom.query.grammar.ast.cypher.AstCypherBuilder;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.ast.cypher.AstCypherStatement;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.Objects;

/**
 * The single entry point from Cypher text to a decoupled {@link AstCypherQuery}: wires the ANTLR lexer/parser, the
 * precise {@link ThrowingSyntaxErrorListener}, and {@link AstCypherBuilder} together - mirrors
 * {@link StroomQlParser#parse(String)} exactly (
 * Task PoC.1). Named {@code CypherQueryParser}, not {@code CypherParser}, to avoid a collision with the
 * generated {@code stroom.query.grammar.antlr.CypherParser}.
 */
public final class CypherQueryParser {

    private CypherQueryParser() {
        // Static utility - not instantiable.
    }

    /**
     * <b>Preconditions:</b> {@code cypher} is not null (an empty or all-whitespace string is a valid argument
     * here - it is rejected as a {@link SyntaxException} once the parser reaches end-of-input looking for a
     * reading clause, exactly as any other syntax error is).
     * <b>Postconditions:</b> a query outside the locked v1 subset (see {@code Cypher.g4}'s file header) throws
     * {@link SyntaxException} rather than being silently accepted, since unsupported constructs are simply absent
     * from the grammar.
     * <b>Null status:</b> neither the parameter nor the return value is nullable.
     *
     * @param cypher the Cypher text to parse.
     * @return never null.
     * @throws SyntaxException on any lexer or parser error, precise to the offending line/column.
     */
    public static AstCypherQuery parse(final String cypher) {
        final AstCypherStatement statement = parseStatement(cypher);
        if (!statement.isSingle()) {
            throw new SyntaxException(
                    "this query uses UNION; parse it with parseStatement(...) (the single-query parse(...) entry "
                    + "point returns one AstCypherQuery)",
                    statement.position().line(), statement.position().column(), java.util.List.of());
        }
        return statement.branches().getFirst();
    }

    /**
     * The statement entry point: parses Cypher text that may combine several single queries with {@code UNION} /
     * {@code UNION ALL} into an {@link AstCypherStatement} (a plain query is a one-branch statement). Same
     * lexer/parser/error contract as {@link #parse(String)}.
     *
     * @param cypher the Cypher text to parse.
     * @return never null.
     * @throws SyntaxException on any lexer or parser error, precise to the offending line/column.
     */
    public static AstCypherStatement parseStatement(final String cypher) {
        Objects.requireNonNull(cypher, "cypher");

        final CypherLexer lexer = new CypherLexer(CharStreams.fromString(cypher));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingSyntaxErrorListener.INSTANCE);

        final CommonTokenStream tokens = new CommonTokenStream(lexer);
        final CypherParser parser = new CypherParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingSyntaxErrorListener.INSTANCE);

        final CypherParser.QueryContext ctx = parser.query();
        return new AstCypherBuilder().build(ctx);
    }
}
