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

package stroom.graphdb.impl;

import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.planner.cypher.CypherToLogicalPlan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles every Cypher query printed in the Graph DB documentation.
 *
 * <p><b>Why this exists.</b> The documentation is the only place the language is described to the people who
 * use it, and a printed query that does not compile is worse than no example: it is read as a promise. Three
 * were found by hand, and all three had been wrong for weeks - one of them the headline example in the
 * function reference, showing an "activity per hour" aggregation the language cannot express at all. None of
 * them could have been caught by reading, because each fails for a reason the surrounding prose asserts is
 * fine. Compiling them is the only check that works.</p>
 *
 * <p>It is a <b>compile</b> check, not an execution check: it proves the language still accepts what the docs
 * print, not that a query returns the rows they show. Verifying the results needs a populated store and lives
 * in the manual acceptance protocol.</p>
 *
 * <h2>What counts as a checkable query</h2>
 *
 * <p>A fenced {@code cypher} block, split on blank lines, with comments removed. A resulting statement is
 * checked only if it begins with {@code MATCH} - so clause fragments ({@code RETURN c.type AS t …}) and
 * pattern-syntax illustrations ({@code (p:Person)}) are skipped, because neither is a complete query and
 * neither could compile alone.</p>
 *
 * <p>The documentation's own annotations are made executable, and they distinguish the two ways a query is
 * refused - a boundary this test turned out to pin usefully:</p>
 *
 * <ul>
 *   <li><b>{@code -- rejected}</b> asserts the statement does <b>not</b> compile. An example labelled as
 *       rejected which quietly starts compiling is as much a defect as one labelled as working which does
 *       not.</li>
 *   <li><b>{@code -- rejected at runtime}</b> asserts it <b>does</b> compile, because a handful of shapes the
 *       grammar accepts are refused by the engine instead. If one of those ever moves to compile time this
 *       fails, which is the signal that the compile-versus-runtime table in the language reference needs
 *       updating.</li>
 * </ul>
 */
class TestDocumentationQueries {

    /**
     * A floor on how many statements must be found.
     *
     * <p>The load-bearing assertion in this class, and not a style point. A test that walks a directory and
     * compiles what it finds passes perfectly when it finds nothing - a moved file, a changed fence label or a
     * slip in the pattern below all turn it silently into a no-op. The count only ever grows as examples are
     * added, so a floor set below the current total costs nothing and removes that failure mode.</p>
     */
    private static final int MINIMUM_EXPECTED_STATEMENTS = 45;

    /** Matches a fenced Cypher block's body. */
    private static final Pattern CYPHER_BLOCK = Pattern.compile("```cypher\\n(.*?)```", Pattern.DOTALL);

    /** A whole-line or trailing {@code --} comment. */
    private static final Pattern COMMENT = Pattern.compile("--.*$", Pattern.MULTILINE);

    /** Blank line: the separator between independent statements inside one block. */
    private static final Pattern STATEMENT_BREAK = Pattern.compile("(?m)^[ \\t]*$");

    @Test
    void everyDocumentedQueryCompiles() {
        final List<String> failures = new ArrayList<>();
        int checked = 0;

        for (final Path file : DocumentationSources.documentationFiles()) {
            for (final Statement statement : statementsIn(file)) {
                checked++;
                final String outcome = compile(statement.query());
                if (statement.rejectedAtCompileTime() && outcome == null) {
                    failures.add(statement.describe(
                            "is annotated as rejected but now compiles - the annotation or the language has "
                            + "changed"));
                } else if (!statement.rejectedAtCompileTime() && outcome != null) {
                    failures.add(statement.describe(statement.rejectedAtRuntime()
                            ? "is annotated as rejected at runtime, but is now refused at compile time - the "
                              + "compile-versus-runtime table in 06-language-reference.md needs updating"
                            : "does not compile: " + outcome));
                }
            }
        }

        assertThat(failures)
                .describedAs("documented queries that do not behave as the documentation says")
                .isEmpty();
        assertThat(checked)
                .describedAs("statements found - a floor, so this test cannot silently become a no-op")
                .isGreaterThanOrEqualTo(MINIMUM_EXPECTED_STATEMENTS);
    }

    /**
     * @return null when {@code query} compiles, or the compiler's message when it does not.
     */
    private static String compile(final String query) {
        try {
            new CypherToLogicalPlan().compileStatement(CypherQueryParser.parseStatement(query));
            return null;
        } catch (final RuntimeException e) {
            return e.getMessage();
        }
    }

    private static List<Statement> statementsIn(final Path file) {
        final List<Statement> statements = new ArrayList<>();
        final Matcher block = CYPHER_BLOCK.matcher(DocumentationSources.read(file));
        while (block.find()) {
            for (final String raw : STATEMENT_BREAK.split(block.group(1))) {
                // Strip the blockquote prefix first: several examples sit inside a callout.
                final String unquoted = raw.lines()
                        .map(line -> line.replaceFirst("^>\\s?", ""))
                        .reduce("", (a, b) -> a + "\n" + b);
                final String query = COMMENT.matcher(unquoted).replaceAll("").strip();
                if (query.regionMatches(true, 0, "MATCH", 0, "MATCH".length())) {
                    final String annotations = unquoted.toLowerCase();
                    final boolean runtime = annotations.contains("rejected at runtime");
                    statements.add(new Statement(
                            file.getFileName().toString(),
                            query,
                            annotations.contains("rejected") && !runtime,
                            runtime));
                }
            }
        }
        return statements;
    }

    /**
     * One statement lifted from a document.
     *
     * @param file                   the document it came from, for the failure message.
     * @param query                  the statement with comments and blockquote markers removed.
     * @param rejectedAtCompileTime  annotated {@code -- rejected}: must not compile.
     * @param rejectedAtRuntime      annotated {@code -- rejected at runtime}: must compile, and is refused
     *                               later by the engine.
     */
    private record Statement(String file,
                             String query,
                             boolean rejectedAtCompileTime,
                             boolean rejectedAtRuntime) {

        private String describe(final String problem) {
            return file + ": " + query.replace("\n", " ") + "\n    -> " + problem;
        }
    }
}
