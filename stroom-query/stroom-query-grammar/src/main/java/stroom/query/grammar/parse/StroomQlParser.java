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

import stroom.query.grammar.antlr.StroomQLLexer;
import stroom.query.grammar.antlr.StroomQLParser;
import stroom.query.grammar.ast.AstBuilder;
import stroom.query.grammar.ast.AstQuery;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.Objects;

/**
 * The single entry point from StroomQL text to a decoupled {@link AstQuery}: wires the ANTLR lexer/parser, the
 * precise {@link ThrowingSyntaxErrorListener}, and {@link AstBuilder} together (see
 * Task 1.3). Used directly by
 * {@code OptimisingQueryCompiler} (Task 1.4).
 */
public final class StroomQlParser {

    private StroomQlParser() {
        // Static utility - not instantiable.
    }

    /**
     * @param query the StroomQL text to parse. Must not be null (an empty or all-whitespace string is a valid
     *              argument here - it is rejected as a {@link SyntaxException} once the parser reaches
     *              end-of-input looking for {@code from}, exactly as any other syntax error is).
     * @return never null.
     * @throws SyntaxException on any lexer or parser error, precise to the offending line/column - see
     *                         {@link ThrowingSyntaxErrorListener}.
     */
    public static AstQuery parse(final String query) {
        Objects.requireNonNull(query, "query");

        final StroomQLLexer lexer = new StroomQLLexer(CharStreams.fromString(query));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingSyntaxErrorListener.INSTANCE);

        final CommonTokenStream tokens = new CommonTokenStream(lexer);
        final StroomQLParser parser = new StroomQLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingSyntaxErrorListener.INSTANCE);

        final StroomQLParser.QueryContext ctx = parser.query();
        return new AstBuilder(tokens).build(ctx);
    }
}
