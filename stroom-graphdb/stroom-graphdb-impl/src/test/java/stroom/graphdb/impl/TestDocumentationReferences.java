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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the class names, constants and settings the documentation cites still exist.
 *
 * <p><b>Deliberately narrow.</b> A sweep of every backticked identifier in the documentation was tried and
 * rejected: it flagged sixty-seven benign matches - Cypher keywords, edge labels, dataset ids, JDK types,
 * enum constants, file names - for three real defects. Suppressing that noise needs a hand-kept stop list,
 * and a stop list that quietly grows is the same kind of rot the check is meant to catch.</p>
 *
 * <p>So this checks only the places where a reference follows a <b>convention</b>, which makes it decidable
 * with no stop list at all:</p>
 *
 * <ul>
 *   <li><b>A <em>Source constant</em> column.</b> Every row of a table with that heading must name a
 *       {@code Class.CONSTANT} that exists. These are the values an operator looks up when sizing a
 *       deployment, so a stale one sends them to a constant that has moved or gone.</li>
 *   <li><b>The developer guide's class tables.</b> Every {@code Class} in the first cell of a row under a
 *       "layer" heading must exist. This is the code map a new contributor navigates by.</li>
 *   <li><b>Every {@code graphdb.*} setting</b> mentioned anywhere must be a real configuration property.</li>
 * </ul>
 *
 * <p>What it does not cover is prose - a class named mid-sentence, or a described behaviour. That is still
 * verified by hand.</p>
 */
class TestDocumentationReferences {

    /** Floors, so a pattern slip or a moved file cannot turn any of the three checks into a no-op. */
    private static final int MINIMUM_SOURCE_CONSTANTS = 12;
    private static final int MINIMUM_CLASS_MAP_ENTRIES = 20;
    private static final int MINIMUM_SETTINGS = 6;

    /** A table row's cells. */
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\|(.+)\\|\\s*$");

    /** {@code `Class.MEMBER`} inside a cell. */
    private static final Pattern QUALIFIED = Pattern.compile("`([A-Z][A-Za-z0-9]*)\\.([A-Za-z_][A-Za-z0-9_]*)`");

    /** {@code `Class`} or {@code `Class` / `…Suffix`} at the start of a code-map row. */
    private static final Pattern LEADING_CLASS = Pattern.compile("^\\s*`([A-Z][A-Za-z0-9]*)`");

    /** A {@code graphdb.<property>} reference. */
    private static final Pattern SETTING = Pattern.compile("`graphdb\\.([a-zA-Z][A-Za-z0-9]*)`");

    private static final Map<String, List<Path>> JAVA_BY_SIMPLE_NAME = indexJavaFiles();

    @Test
    void everySourceConstantExists() {
        final List<String> failures = new ArrayList<>();
        int checked = 0;

        for (final Path file : documentationFiles()) {
            for (final String[] row : rowsOfTablesWithColumn(file, "Source constant")) {
                final Matcher qualified = QUALIFIED.matcher(row[row.length - 1]);
                while (qualified.find()) {
                    checked++;
                    final String problem = resolve(qualified.group(1), qualified.group(2));
                    if (problem != null) {
                        failures.add(file.getFileName() + ": `" + qualified.group(1) + "."
                                     + qualified.group(2) + "` " + problem);
                    }
                }
            }
        }

        assertThat(failures).describedAs("documented source constants that no longer exist").isEmpty();
        assertThat(checked).describedAs("source constants found").isGreaterThanOrEqualTo(MINIMUM_SOURCE_CONSTANTS);
    }

    @Test
    void everyClassInTheCodeMapExists() {
        final Path guide = documentationFile("13-developer-guide.md");
        final List<String> failures = new ArrayList<>();
        int checked = 0;

        for (final String[] row : rowsOfTablesWithColumn(guide, "Role")) {
            final Matcher leading = LEADING_CLASS.matcher(row[0]);
            if (leading.find()) {
                checked++;
                if (!JAVA_BY_SIMPLE_NAME.containsKey(leading.group(1))) {
                    failures.add("13-developer-guide.md: `" + leading.group(1) + "` has no source file");
                }
            }
        }

        assertThat(failures).describedAs("classes in the code map that no longer exist").isEmpty();
        assertThat(checked).describedAs("code-map rows found").isGreaterThanOrEqualTo(MINIMUM_CLASS_MAP_ENTRIES);
    }

    @Test
    void everyDocumentedSettingExists() {
        final Set<String> declared = configPropertyNames();
        final Set<String> referenced = new HashSet<>();
        for (final Path file : documentationFiles()) {
            final Matcher setting = SETTING.matcher(read(file));
            while (setting.find()) {
                referenced.add(setting.group(1));
            }
        }

        assertThat(referenced)
                .describedAs("graphdb.* settings referenced in the documentation, against GraphDbConfig")
                .isSubsetOf(declared);
        assertThat(referenced)
                .describedAs("settings found")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_SETTINGS);
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * @return null when {@code Class.member} resolves, or why it does not.
     */
    private static String resolve(final String type, final String member) {
        final List<Path> files = JAVA_BY_SIMPLE_NAME.get(type);
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

    /** Every row of every table in {@code file} whose header contains {@code column}. */
    private static List<String[]> rowsOfTablesWithColumn(final Path file, final String column) {
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
            final boolean isHeader = java.util.Arrays.stream(cells).anyMatch(c -> c.strip().equals(column));
            if (isHeader) {
                inMatchingTable = true;
            } else if (inMatchingTable) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /** The property names {@code GraphDbConfig} exposes, derived from its getters. */
    private static Set<String> configPropertyNames() {
        final Path config = JAVA_BY_SIMPLE_NAME.get("GraphDbConfig").getFirst();
        final Matcher getter = Pattern
                .compile("public [\\w<>,\\[\\] ]+ get([A-Z][A-Za-z0-9]*)\\(\\)")
                .matcher(read(config));
        final Set<String> names = new HashSet<>();
        while (getter.find()) {
            final String name = getter.group(1);
            names.add(Character.toLowerCase(name.charAt(0)) + name.substring(1));
        }
        return names;
    }

    private static Map<String, List<Path>> indexJavaFiles() {
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

    private static Path documentationFile(final String name) {
        return repositoryRoot().resolve("docs/graphdb").resolve(name);
    }

    private static List<Path> documentationFiles() {
        try (Stream<Path> files = Files.list(repositoryRoot().resolve("docs/graphdb"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The repository root, found by walking up for {@code docs/graphdb}.
     *
     * <p><b>If the documentation moves to another repository these tests fail</b>, deliberately, with a message
     * saying so. Skipping quietly would leave green tests that check nothing.</p>
     */
    private static Path repositoryRoot() {
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
                + "tests at its new location or delete them - do not let them skip.");
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read " + file, e);
        }
    }
}
