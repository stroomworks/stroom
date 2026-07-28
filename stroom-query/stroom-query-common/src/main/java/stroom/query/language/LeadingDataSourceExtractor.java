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

package stroom.query.language;

import stroom.query.api.token.AbstractToken;
import stroom.query.api.token.KeywordGroup;
import stroom.query.api.token.Token;
import stroom.query.api.token.TokenException;
import stroom.query.api.token.TokenGroup;
import stroom.query.api.token.TokenType;
import stroom.query.language.token.StructureBuilder;
import stroom.query.language.token.Tokeniser;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Workstream A: reads the leading {@code from "X"}
 * data source name out of any query text - StroomQL or Cypher - grammar-agnostically, i.e. without a full parse
 * by either grammar. This lets {@code QueryServiceImpl.mapRequest} decide which grammar to hand a query to
 * <i>before</i> it commits to one, by resolving the name (via {@link DataSourceResolver}) and dispatching on the
 * resolved {@code DocRef}'s type.
 *
 * <p>Built directly on {@link Tokeniser} + {@link StructureBuilder} - the same legacy tokeniser
 * {@link SearchRequestFactory#extractDataSourceOnly} already uses - so this extractor agrees with both existing
 * {@code from} readers (that legacy tokeniser, and the ANTLR-based StroomQL/Cypher grammars' own {@code FROM}
 * handling) on what counts as "the leading data source name". Any text after the name (further StroomQL keywords,
 * a Cypher {@code MATCH}/{@code RETURN} body, a {@code join}) is deliberately not inspected: choosing a grammar
 * only ever needs the left/first source.</p>
 */
public final class LeadingDataSourceExtractor {

    private LeadingDataSourceExtractor() {
        // Static utility - not instantiable.
    }

    /**
     * <b>Preconditions:</b> {@code queryText} is not null.
     * <b>Postconditions:</b> returns empty if {@code queryText} does not begin with a {@code from "X"} (or
     * {@code from 'x'}/bareword-style) clause - including when it is empty/blank, fails to tokenise at all, or the
     * leading keyword is anything other than {@code from} - rather than throwing; a malformed or grammar-specific
     * query body is exactly what this method is meant to tolerate, since the real grammar (StroomQL or Cypher)
     * will parse and validate the query properly once one has been chosen.
     * <b>Null status:</b> the parameter is not nullable; the return value is never null (though it may be empty).
     *
     * @param queryText never null; the raw query text, in either StroomQL or Cypher syntax.
     * @return the unescaped leading data source name, or empty if there is none.
     */
    public static Optional<String> extractLeadingDataSourceName(final String queryText) {
        Objects.requireNonNull(queryText, "queryText");

        try {
            final List<Token> tokens = Tokeniser.parse(queryText);
            if (tokens.isEmpty()) {
                return Optional.empty();
            }

            final TokenGroup tokenGroup = StructureBuilder.create(tokens);
            final List<AbstractToken> children = tokenGroup.getChildren();
            if (children.isEmpty()) {
                return Optional.empty();
            }

            final AbstractToken first = children.getFirst();
            if (!TokenType.FROM.equals(first.getTokenType()) || !(first instanceof final KeywordGroup fromGroup)) {
                return Optional.empty();
            }
            if (fromGroup.getChildren().isEmpty()) {
                return Optional.empty();
            }

            final AbstractToken nameToken = fromGroup.getChildren().getFirst();
            if (!TokenType.isString(nameToken)) {
                return Optional.empty();
            }

            return Optional.of(nameToken.getUnescapedText());
        } catch (final TokenException e) {
            // Grammar-agnostic and best-effort: a tokenising failure just means "no leading from could be read",
            // not a hard error - the eventual grammar (StroomQL or Cypher) is what reports real syntax errors.
            return Optional.empty();
        }
    }
}
