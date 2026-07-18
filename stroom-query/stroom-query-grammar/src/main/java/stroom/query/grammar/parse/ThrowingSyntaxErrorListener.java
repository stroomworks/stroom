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

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces ANTLR's default behaviour (print to stderr and attempt error recovery) with an immediate, precise
 * {@link SyntaxException} - see {@code docs/query-optimiser-implementation-plan.md}, Task 1.3. Install on both
 * the lexer and the parser via {@code removeErrorListeners()} + {@code addErrorListener(INSTANCE)} before
 * parsing (see {@link StroomQlParser}, which does this).
 */
public final class ThrowingSyntaxErrorListener extends BaseErrorListener {

    /** Stateless - a single shared instance is safe to reuse across parses. */
    public static final ThrowingSyntaxErrorListener INSTANCE = new ThrowingSyntaxErrorListener();

    private ThrowingSyntaxErrorListener() {
    }

    /**
     * @param recognizer          never null (per the ANTLR contract); used to resolve
     *                            {@code e.getExpectedTokens()} token types to display names, when available.
     * @param offendingSymbol     nullable; unused (the message/position already describe the problem).
     * @param line                1-based, matching {@link SyntaxException#getLine()}.
     * @param charPositionInLine  0-based, matching {@link SyntaxException#getColumn()}.
     * @param msg                 never null (per the ANTLR contract).
     * @param e                   nullable; absent for lexer-level errors, which have no parser state to
     *                            inspect for an expected-token set.
     * @throws SyntaxException always - this listener never lets ANTLR attempt error recovery.
     */
    @Override
    public void syntaxError(final Recognizer<?, ?> recognizer,
                            final Object offendingSymbol,
                            final int line,
                            final int charPositionInLine,
                            final String msg,
                            final @Nullable RecognitionException e) {
        throw new SyntaxException(msg, line, charPositionInLine, expectedTokenNames(recognizer, e));
    }

    private List<String> expectedTokenNames(final Recognizer<?, ?> recognizer,
                                            final @Nullable RecognitionException e) {
        if (e == null) {
            return List.of();
        }
        final IntervalSet expected = e.getExpectedTokens();
        if (expected == null || expected.isNil()) {
            return List.of();
        }
        final Vocabulary vocabulary = recognizer.getVocabulary();
        final List<String> names = new ArrayList<>(expected.size());
        for (final int tokenType : expected.toList()) {
            names.add(vocabulary.getDisplayName(tokenType));
        }
        return names;
    }
}
