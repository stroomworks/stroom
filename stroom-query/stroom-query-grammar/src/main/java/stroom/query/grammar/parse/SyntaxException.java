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

import java.util.List;
import java.util.Objects;

/**
 * A precise query syntax error: a 1-based line, a 0-based column, a human-readable message, and (where known)
 * the set of token descriptions that would have been valid at that position - see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 1.3. Shared by both ANTLR-driven grammars in this
 * module: StroomQL (via {@code StroomQlParser}) and Cypher (via {@code CypherQueryParser}).
 *
 * <p>Unlike legacy's {@code stroom.query.api.token.TokenException} (which carries a legacy {@code AbstractToken}),
 * this exception is raised by the ANTLR-driven grammar and carries plain position data instead, since ANTLR has
 * no equivalent token type to reference.</p>
 */
public final class SyntaxException extends RuntimeException {

    private final int line;
    private final int column;
    private final List<String> expectedTokens;

    /**
     * @param message        never null; a human-readable description of the problem.
     * @param line           1-based source line, matching {@link stroom.query.grammar.ast.AstPosition}'s
     *                       convention.
     * @param column         0-based column offset within {@code line}.
     * @param expectedTokens never null; possibly empty when the set of valid next tokens could not be
     *                       determined (e.g. a lexer-level error, which has no parser state to inspect).
     */
    public SyntaxException(final String message, final int line, final int column,
                           final List<String> expectedTokens) {
        super(message);
        this.line = line;
        this.column = column;
        this.expectedTokens = List.copyOf(Objects.requireNonNull(expectedTokens, "expectedTokens"));
    }

    /** @return 1-based source line. */
    public int getLine() {
        return line;
    }

    /** @return 0-based column offset within {@link #getLine()}. */
    public int getColumn() {
        return column;
    }

    /** @return never null; possibly empty. Human-readable descriptions of the tokens that would have been
     *          valid at this position (e.g. {@code "'from'"}, {@code "<EOF>"}). */
    public List<String> getExpectedTokens() {
        return expectedTokens;
    }

    @Override
    public String toString() {
        return "SyntaxException{" +
               "line=" + line +
               ", column=" + column +
               ", message=" + getMessage() +
               ", expectedTokens=" + expectedTokens +
               '}';
    }
}
