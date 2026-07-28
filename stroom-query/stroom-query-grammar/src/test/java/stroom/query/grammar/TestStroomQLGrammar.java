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

package stroom.query.grammar;

import stroom.query.grammar.antlr.StroomQLLexer;
import stroom.query.grammar.antlr.StroomQLParser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@code StroomQL.g4} (1): a
 * representative spread of real StroomQL constructs parses without error, and garbage input is rejected with a
 * precise position. Semantic/parity tests belong to later tasks (1.2+); this only proves the grammar accepts the
 * language it is supposed to.
 */
class TestStroomQLGrammar {

    /**
     * Collects every syntax error reported while parsing, instead of the default behaviour of printing to
     * stderr and continuing. Never null; empty when parsing succeeds cleanly.
     */
    private static final class CollectingErrorListener extends BaseErrorListener {

        private final List<String> errors = new ArrayList<>();

        @Override
        public void syntaxError(final Recognizer<?, ?> recognizer,
                                final Object offendingSymbol,
                                final int line,
                                final int charPositionInLine,
                                final String msg,
                                final RecognitionException e) {
            errors.add(line + ":" + charPositionInLine + " " + msg);
        }
    }

    private List<String> parse(final String query) {
        final StroomQLLexer lexer = new StroomQLLexer(CharStreams.fromString(query));
        final CollectingErrorListener lexerErrors = new CollectingErrorListener();
        lexer.removeErrorListeners();
        lexer.addErrorListener(lexerErrors);

        final StroomQLParser parser = new StroomQLParser(new CommonTokenStream(lexer));
        final CollectingErrorListener parserErrors = new CollectingErrorListener();
        parser.removeErrorListeners();
        parser.addErrorListener(parserErrors);

        parser.query();

        final List<String> allErrors = new ArrayList<>(lexerErrors.errors);
        allErrors.addAll(parserErrors.errors);
        return allErrors;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "from 'Test Index' select StreamId, EventId",
            "from 'Test Index' where UserId = user5 and Description = e0567 select StreamId",
            "from 'Test Index' where EventTime between 2022-05-05T00:00:00.000Z and 2023-05-05T00:00:00.000Z "
                    + "select StreamId",
            "from 'Test Index' where StreamId in (123, 456) select StreamId",
            "from \"index_view\" where StreamId in dictionary \"my_dictionary\" select StreamId",
            "from 'Test Index' where Status is null select StreamId",
            "from 'Test Index' where Status is not null select StreamId",
            "from 'Test Index' eval x = upperCase(UserId) select x",
            "from 'Test Index' eval bool = and(idx1 >= 0, idx2 >= 0) select bool",
            "from 'Test Index' eval comp = max(toFloat(day()-10d)) select comp",
            "from 'Test Index' where not UserId = user5 and Description = e0567 select StreamId",
            "from 'Test Index' group by StreamId group by EventTime select StreamId, EventTime",
            "from 'Test Index' having ${Stream Id} > -2 select ${Stream Id}",
            "from 'Test Index' sort by StreamId asc, EventTime desc select StreamId, EventTime",
            "from 'Test Index' select * limit 10",
            "from 'Test Index' select StreamId as \"Stream Id\" show as chart",
            "from 'Test Index' window EventTime by 1h advance 10m using max select StreamId",
            "// a leading comment\nfrom 'Test Index' /* inline */ select StreamId",
            "from Events as e left join UserState as u on e.userId = u.userId select e.userId",
    })
    void parsesWithoutError(final String query) {
        assertThat(parse(query)).isEmpty();
    }

    @Test
    void rejectsGarbageWithAPosition() {
        // `limit` is not valid immediately after `select` (legacy rejects this too - see design doc
        // Appendix A: "select ... limit N" is rejected with "Unexpected token LIMIT after SELECT" - here we
        // only assert our grammar reports A precise, non-empty position; Task 1.4 reproduces the exact legacy
        // message semantically, not syntactically).
        final List<String> errors = parse("from X blah blah blah");
        assertThat(errors).isNotEmpty();
        assertThat(errors.getFirst()).matches("\\d+:\\d+ .*");
    }

    @Test
    void rejectsUnclosedBracket() {
        final List<String> errors = parse("from X where (a = 1 select a");
        assertThat(errors).isNotEmpty();
    }

    @Test
    void rejectsMissingFrom() {
        final List<String> errors = parse("select a");
        assertThat(errors).isNotEmpty();
    }
}
