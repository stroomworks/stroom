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

import stroom.query.api.token.QuotedStringUtil;

import java.util.Objects;

/**
 * A single name/identifier/value reference used throughout the grammar: a field name, an alias, a dictionary
 * name, a sort/group target, a param reference, etc. Carries the exact raw source text and enough type
 * information ({@link Kind}) to reproduce legacy's per-subtype {@code getUnescapedText()} dispatch (see
 * {@code stroom.query.api.token.AbstractToken}/{@code QuotedStringToken}/{@code ParamToken}) without depending on
 * that token hierarchy at all.
 *
 * @param kind     never null; determines how {@link #unescapedText()} interprets {@link #rawText()}.
 * @param rawText  never null; exactly as it appeared in the source, including surrounding quotes or {@code ${ }}
 *                 for {@link Kind#PARAM}.
 * @param position never null.
 */
public record AstToken(Kind kind, String rawText, AstPosition position) {

    public AstToken {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(position, "position");
    }

    /**
     * The kind of lexical token an {@link AstToken} was built from, determining how {@link #unescapedText()}
     * must interpret {@link #rawText()}.
     */
    public enum Kind {
        /** An unquoted run of characters (a StroomQL bareword) or a bare {@code NUMBER}/{@code DURATION} literal
         * used in a name position (e.g. a limit value) - never unescaped, used verbatim. */
        BAREWORD,
        /** A {@code '...'}-quoted string - unescaped by stripping the outer quotes and unescaping {@code \\}. */
        SINGLE_QUOTED,
        /** A {@code "..."}-quoted string - unescaped the same way as {@link #SINGLE_QUOTED}. */
        DOUBLE_QUOTED,
        /** A {@code ${...}} parameter reference - unescaped by stripping the {@code ${} } wrapper only (no
         * backslash processing - see {@code stroom.query.api.token.ParamToken}). */
        PARAM
    }

    /**
     * Reproduces {@code AbstractToken.getUnescapedText()}'s exact per-subtype behaviour.
     *
     * @return never null. For {@link Kind#BAREWORD}, identical to {@link #rawText()}. For
     *         {@link Kind#SINGLE_QUOTED}/{@link Kind#DOUBLE_QUOTED}, the quoted content with the outer quotes
     *         removed and {@code \\}-escapes resolved (via the shared
     *         {@link QuotedStringUtil#unescape(char[], int, int, char)}). For {@link Kind#PARAM}, the content
     *         between {@code ${} and the closing {@code }}.
     */
    public String unescapedText() {
        return switch (kind) {
            case BAREWORD -> rawText;
            case SINGLE_QUOTED, DOUBLE_QUOTED -> {
                final char[] chars = rawText.toCharArray();
                yield QuotedStringUtil.unescape(chars, 0, chars.length - 1, '\\');
            }
            case PARAM -> {
                final int start = rawText.indexOf("${") == 0 ? 2 : 0;
                final int end = rawText.indexOf('}', start);
                yield rawText.substring(start, end == -1 ? rawText.length() : end);
            }
        };
    }
}
