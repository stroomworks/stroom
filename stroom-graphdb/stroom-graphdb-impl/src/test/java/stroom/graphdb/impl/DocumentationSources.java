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

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The shared reading half of the tests that check the Graph DB documentation against the code
 * ({@link TestDocumentationQueries}, {@link TestDocumentationReferences}, {@link TestDocumentationMessages} and
 * {@code TestEventLoggingXslt}): locating the documentation, reading it, walking its markdown tables, and
 * indexing the repository's Java sources.
 *
 * <p>Extracted because four checks want the same machinery, and three of them had already grown private copies
 * of it. It holds no assertions of its own - each test owns what it concludes; this owns only how the inputs are
 * found.</p>
 *
 * <h2>Why sources are read as text rather than reflectively</h2>
 *
 * <p>{@link #constantInitialiser} greps a declaration out of a {@code .java} file, which looks like the weaker
 * choice next to {@code Field.get}. It is the deliberate one. The constants the documentation cites are spread
 * across visibility and module boundaries a test cannot reach: {@code Db.MAX_KEY_LENGTH} and
 * {@code PlanBEnv.CONCURRENT_READERS} are declared in another module, several are {@code private}, and seven
 * more live in {@code stroom-core-client}, which is GWT-compiled and is not on this module's test classpath at
 * all. Reading the source treats every one of them identically, needs no {@code setAccessible}, and cannot be
 * broken by a future move to the module path. Widening a production constant's visibility to suit a test would
 * be the wrong trade.</p>
 */
final class DocumentationSources {

    /** A markdown table row's cells, without the outer pipes. */
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\|(.+)\\|\\s*$");

    /**
     * A constant declaration's initialiser: everything between {@code =} and the {@code ;}. {@code %s} is the
     * constant's name.
     *
     * <p>Anchored to the start of a line, with <b>optional</b> modifiers, because the documented constants are
     * not all declared the same way: {@code Db.MAX_KEY_LENGTH} is a field of an <em>interface</em>, where
     * {@code public static final} is implicit and absent from the source. Requiring {@code static final} left
     * it - and the constant that initialises itself from it - silently unresolvable. Line-anchoring is what
     * keeps the relaxed modifiers safe: it matches a field declaration, not a name appearing mid-expression.</p>
     */
    private static final String DECLARATION_TEMPLATE =
            "(?m)^\\s*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*"
            + "[\\w<>,.\\[\\]]+\\s+%s\\s*=\\s*([^;]+);";

    private DocumentationSources() {
    }

    /**
     * The repository root, found by walking up from the working directory for a {@code docs/graphdb} directory
     * rather than being hard-coded, because Gradle runs a test from its own module.
     *
     * <p><b>If the documentation moves to another repository these tests fail</b>, deliberately, with a message
     * saying so. Skipping quietly would leave green tests that check nothing - the same failure mode every
     * caller's minimum-count floor exists to prevent.</p>
     *
     * @return never null.
     * @throws IllegalStateException if no {@code docs/graphdb} directory is found at or above the working
     *                               directory.
     */
    static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs/graphdb"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not find a 'docs/graphdb' directory above " + Path.of("").toAbsolutePath()
                + ". If the Graph DB documentation has moved to another repository, either repoint these "
                + "tests at its new location or delete them - do not let them skip, because documentation that "
                + "nothing checks is read as a promise.");
    }

    /**
     * Every markdown file in {@code docs/graphdb}, in name order so a failure list is stable between runs.
     *
     * @return never null; never empty in a well-formed repository.
     */
    static List<Path> documentationFiles() {
        try (Stream<Path> files = Files.list(repositoryRoot().resolve("docs/graphdb"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One named documentation file.
     *
     * @param name the file name within {@code docs/graphdb}, e.g. {@code "10-limits.md"}; never null.
     * @return never null - the path is returned whether or not it exists, so a caller's read reports the
     *         missing file rather than this returning something ambiguous.
     */
    static Path documentationFile(final String name) {
        Objects.requireNonNull(name, "name");
        return repositoryRoot().resolve("docs/graphdb").resolve(name);
    }

    /**
     * One file's whole content.
     *
     * @param file never null.
     * @return never null.
     * @throws UncheckedIOException if the file cannot be read - deliberately not swallowed, since a test that
     *                              cannot read its input has not passed.
     */
    static String read(final Path file) {
        Objects.requireNonNull(file, "file");
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read " + file, e);
        }
    }

    /**
     * Every {@code .java} file in the repository, keyed by simple class name, excluding build output.
     *
     * <p>A name maps to a <em>list</em> because simple names are not unique across modules - the callers that
     * resolve a member search every candidate rather than assuming the first.</p>
     *
     * @return never null; the lists are never null or empty.
     */
    static Map<String, List<Path>> javaFilesBySimpleName() {
        return JavaIndex.BY_SIMPLE_NAME;
    }

    /**
     * Every row of every table in {@code file} whose header row contains a cell exactly equal to {@code column}.
     *
     * <p>Keying off a column heading is what keeps the callers free of a hand-kept exceptions list: a table
     * opts in to being checked by naming the column, so there is nothing to remember and nothing to rot.</p>
     *
     * @param file   never null.
     * @param column the exact heading text a table must carry to be read; never null.
     * @return never null (may be empty); each element is one row's cells, in column order, header and separator
     *         rows excluded.
     */
    static List<String[]> rowsOfTablesWithColumn(final Path file, final String column) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(column, "column");

        final List<String[]> rows = new ArrayList<>();
        boolean inMatchingTable = false;
        for (final String line : read(file).lines().toList()) {
            final Matcher row = TABLE_ROW.matcher(line);
            if (!row.matches()) {
                inMatchingTable = false;
                continue;
            }
            final String[] cells = row.group(1).split("\\|", -1);
            if (cells.length > 0 && cells[0].contains("---")) {
                continue;
            }
            final boolean isHeader = Arrays.stream(cells).anyMatch(c -> c.strip().equals(column));
            if (isHeader) {
                inMatchingTable = true;
            } else if (inMatchingTable) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /**
     * The index of the header row's cell equal to {@code column}, for a table read by
     * {@link #rowsOfTablesWithColumn}.
     *
     * <p>Needed because a documented value and the constant naming it are in different columns, and the columns
     * are not in the same order in every table.</p>
     *
     * @param file   never null.
     * @param column never null.
     * @return the zero-based column index, or {@code -1} if no table in the file carries that heading.
     */
    static int columnIndex(final Path file, final String column) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(column, "column");

        for (final String line : read(file).lines().toList()) {
            final Matcher row = TABLE_ROW.matcher(line);
            if (row.matches()) {
                final String[] cells = row.group(1).split("\\|", -1);
                for (int i = 0; i < cells.length; i++) {
                    if (cells[i].strip().equals(column)) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Whether {@code type} has a source file and some file of that name mentions {@code member}.
     *
     * <p>Deliberately a mention rather than a parsed declaration: the callers need to know that a documented
     * reference still resolves to something, and a member named anywhere in its own class's source is enough
     * for that. {@link #constantInitialiser} is the stricter check, for when the value matters.</p>
     *
     * @param type   the simple class name; never null.
     * @param member the field or method name; never null.
     * @return null when the reference resolves, or a human-readable reason why it does not.
     */
    static @Nullable String resolveMember(final String type, final String member) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(member, "member");

        final List<Path> files = javaFilesBySimpleName().get(type);
        if (files == null) {
            return "has no source file";
        }
        final Pattern declaration = Pattern.compile("\\b" + Pattern.quote(member) + "\\b");
        for (final Path file : files) {
            if (declaration.matcher(read(file)).find()) {
                return null;
            }
        }
        return "is not declared in " + type;
    }

    /**
     * The source text of a {@code static final} constant's initialiser.
     *
     * <p>Returns the text rather than a value because initialisers are not all literals - the current set
     * includes a product ({@code 7L * 24L * 60L * 60L * 1000L}) and a reference to another class's constant
     * ({@code Db.MAX_KEY_LENGTH}). Interpreting that is the caller's business; finding it is this method's.</p>
     *
     * @param type   the simple class name declaring the constant; never null.
     * @param member the constant's name; never null.
     * @return the initialiser source with newlines and runs of whitespace collapsed to single spaces, or
     *         {@code null} if the class has no source file or no such {@code static final} declaration.
     */
    static @Nullable String constantInitialiser(final String type, final String member) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(member, "member");

        final List<Path> files = javaFilesBySimpleName().get(type);
        if (files == null) {
            return null;
        }
        final Pattern declaration = Pattern.compile(
                String.format(DECLARATION_TEMPLATE, Pattern.quote(member)), Pattern.DOTALL);
        for (final Path file : files) {
            final Matcher matcher = declaration.matcher(read(file));
            if (matcher.find()) {
                return matcher.group(1).replaceAll("\\s+", " ").strip();
            }
        }
        return null;
    }

    /**
     * Holder, so the repository-wide walk happens once per JVM and only if something asks for it.
     */
    private static final class JavaIndex {

        private static final Map<String, List<Path>> BY_SIMPLE_NAME = index();

        private static Map<String, List<Path>> index() {
            final Map<String, List<Path>> index = new HashMap<>();
            try (Stream<Path> files = Files.walk(repositoryRoot())) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().contains("/build/"))
                        .forEach(p -> {
                            final String name = p.getFileName().toString();
                            index.computeIfAbsent(name.substring(0, name.length() - ".java".length()),
                                    k -> new ArrayList<>()).add(p);
                        });
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return index;
        }
    }
}
