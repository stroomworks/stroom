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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the class names, constants and settings the documentation cites still exist - and that the
 * <b>values</b> printed next to them are still the values in the code.
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
 *       {@code Class.CONSTANT} that exists, and the value the row prints must match that constant's
 *       initialiser. These are the values an operator looks up when sizing a deployment, so a stale one either
 *       sends them to a constant that has moved or - worse, because it looks fine - tells them a number the
 *       code stopped using.</li>
 *   <li><b>The developer guide's class tables.</b> Every {@code Class} in the first cell of a row under a
 *       "layer" heading must exist. This is the code map a new contributor navigates by.</li>
 *   <li><b>Every {@code graphdb.*} setting</b> mentioned anywhere must be a real configuration property, and
 *       every documented default must match what {@link GraphDbConfig}'s no-arg constructor applies.</li>
 * </ul>
 *
 * <p>What it does not cover is prose - a class named mid-sentence, or a described behaviour. That is still
 * verified by hand. Documented rejection messages are covered separately, by
 * {@link TestDocumentationMessages}.</p>
 *
 * <h2>Why an unparseable value is not a failure</h2>
 *
 * <p>The two value checks compare only what they can interpret: a documented cell that yields no number, or an
 * initialiser shape {@link #evaluate} does not cover, is counted as <em>unchecked</em> and reported in the
 * assertion description rather than failing the build. A new row with an unusual value must not break CI for
 * whoever wrote it. The protection against that quietly hollowing the check out is the minimum-count floor on
 * each test, not an exception thrown per row.</p>
 */
class TestDocumentationReferences {

    /** Floors, so a pattern slip or a moved file cannot turn any of the checks into a no-op. */
    private static final int MINIMUM_SOURCE_CONSTANTS = 12;
    private static final int MINIMUM_CLASS_MAP_ENTRIES = 20;
    private static final int MINIMUM_SETTINGS = 6;
    private static final int MINIMUM_CONSTANT_VALUES = 14;
    private static final int MINIMUM_SETTING_DEFAULTS = 6;

    /** {@code `Class.MEMBER`} inside a cell. */
    private static final Pattern QUALIFIED = Pattern.compile("`([A-Z][A-Za-z0-9]*)\\.([A-Za-z_][A-Za-z0-9_]*)`");

    /** {@code `Class`} or {@code `Class` / `…Suffix`} at the start of a code-map row. */
    private static final Pattern LEADING_CLASS = Pattern.compile("^\\s*`([A-Z][A-Za-z0-9]*)`");

    /** A {@code graphdb.<property>} reference. */
    private static final Pattern SETTING = Pattern.compile("`graphdb\\.([a-zA-Z][A-Za-z0-9]*)`");

    /** A product of integer literals, e.g. {@code 10L * 1024 * 1024 * 1024}. */
    private static final Pattern LITERAL_PRODUCT = Pattern.compile("\\d+[LlFfDd]?(\\s*\\*\\s*\\d+[LlFfDd]?)*");

    /** {@code Class.CONSTANT} as a whole initialiser - one level of indirection, resolved once. */
    private static final Pattern CONSTANT_REFERENCE =
            Pattern.compile("([A-Z][A-Za-z0-9]*)\\.([A-Z][A-Z0-9_]*)");

    /** The leading number of a documented value cell, with its markdown and thousands separators removed. */
    private static final Pattern DOCUMENTED_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([A-Za-z]*)");

    /** A {@code (6 bytes)} annotation immediately following a constant reference. */
    private static final Pattern ANNOTATED_VALUE = Pattern.compile("\\s*\\((\\d+\\s*[A-Za-z]*)\\)");

    /** The headings a documented value may sit under, in the order they are looked for. */
    private static final List<String> VALUE_COLUMNS = List.of("Default", "Value");

    @Test
    void everySourceConstantExists() {
        final List<String> failures = new ArrayList<>();
        int checked = 0;

        for (final Path file : DocumentationSources.documentationFiles()) {
            for (final String[] row : DocumentationSources.rowsOfTablesWithColumn(file, "Source constant")) {
                final Matcher qualified = QUALIFIED.matcher(row[row.length - 1]);
                while (qualified.find()) {
                    checked++;
                    final String problem =
                            DocumentationSources.resolveMember(qualified.group(1), qualified.group(2));
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
        final Path guide = DocumentationSources.documentationFile("13-developer-guide.md");
        final List<String> failures = new ArrayList<>();
        int checked = 0;

        for (final String[] row : DocumentationSources.rowsOfTablesWithColumn(guide, "Role")) {
            final Matcher leading = LEADING_CLASS.matcher(row[0]);
            if (leading.find()) {
                checked++;
                if (!DocumentationSources.javaFilesBySimpleName().containsKey(leading.group(1))) {
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
        for (final Path file : DocumentationSources.documentationFiles()) {
            final Matcher setting = SETTING.matcher(DocumentationSources.read(file));
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

    /**
     * Every value printed beside a {@code Source constant} must be that constant's value.
     *
     * <p>{@link #everySourceConstantExists} proves the constant is still there; this proves the number next to
     * it is still true, which is what an operator actually reads.</p>
     */
    @Test
    void everyDocumentedConstantValueMatchesItsSource() {
        final List<String> failures = new ArrayList<>();
        final List<String> unchecked = new ArrayList<>();
        int checked = 0;

        for (final Path file : DocumentationSources.documentationFiles()) {
            final int valueColumn = valueColumnIndex(file);
            if (valueColumn < 0) {
                continue;
            }
            for (final String[] row : DocumentationSources.rowsOfTablesWithColumn(file, "Source constant")) {
                if (row.length <= valueColumn) {
                    continue;
                }
                final String constantCell = row[row.length - 1];
                final Long documented = documentedValue(row[valueColumn]);
                final Matcher qualified = QUALIFIED.matcher(constantCell);
                while (qualified.find()) {
                    final String reference = qualified.group(1) + "." + qualified.group(2);
                    final Long actual = constantValue(qualified.group(1), qualified.group(2));
                    // An approximate value cell (~2.8 × 10¹⁴) states a derived figure, but its row annotates
                    // the constant with the real one - "GraphStores.NODE_UID_WIDTH (6 bytes)". Compare that.
                    final Long expected = documented != null
                            ? documented
                            : annotatedValue(constantCell, qualified.end());
                    if (expected == null || actual == null) {
                        unchecked.add(file.getFileName() + ": `" + reference + "` ("
                                      + row[valueColumn].strip() + ")");
                    } else {
                        checked++;
                        if (!expected.equals(actual)) {
                            failures.add(file.getFileName() + ": `" + reference + "` is " + actual
                                         + " but the documentation says " + expected + " ("
                                         + row[valueColumn].strip() + ")");
                        }
                    }
                }
            }
        }

        assertThat(failures)
                .describedAs("documented values that disagree with their source constant")
                .isEmpty();
        assertThat(checked)
                .describedAs("constant values compared - a floor, so this cannot silently become a no-op. "
                            + "Rows whose value or initialiser could not be interpreted, and so were not "
                            + "compared: " + unchecked)
                .isGreaterThanOrEqualTo(MINIMUM_CONSTANT_VALUES);
    }

    /**
     * Every documented default of a {@code graphdb.*} setting must be the default {@link GraphDbConfig} applies.
     *
     * <p>Read from a real {@code new GraphDbConfig()} rather than from source text - unlike the constants, the
     * config object is on the test classpath, so there is nothing to gain from parsing it and a getter cannot
     * misread an initialiser.</p>
     */
    @Test
    void everyDocumentedSettingDefaultMatchesTheConfig() {
        final GraphDbConfig defaults = new GraphDbConfig();
        final List<String> failures = new ArrayList<>();
        final List<String> unchecked = new ArrayList<>();
        int checked = 0;

        for (final Path file : DocumentationSources.documentationFiles()) {
            final int valueColumn = valueColumnIndex(file);
            if (valueColumn < 0) {
                continue;
            }
            for (final String[] row : DocumentationSources.rowsOfTablesWithColumn(file, "Setting")) {
                if (row.length <= valueColumn) {
                    continue;
                }
                final Matcher setting = SETTING.matcher(row[row.length - 1]);
                if (!setting.find()) {
                    continue;
                }
                final String name = setting.group(1);
                final Long documented = documentedValue(row[valueColumn]);
                final Long actual = settingDefault(defaults, name);
                if (documented == null || actual == null) {
                    unchecked.add(file.getFileName() + ": graphdb." + name + " ("
                                  + row[valueColumn].strip() + ")");
                } else {
                    checked++;
                    if (!documented.equals(actual)) {
                        failures.add(file.getFileName() + ": graphdb." + name + " defaults to " + actual
                                     + " but the documentation says " + row[valueColumn].strip());
                    }
                }
            }
        }

        assertThat(failures)
                .describedAs("documented setting defaults that disagree with GraphDbConfig")
                .isEmpty();
        assertThat(checked)
                .describedAs("setting defaults compared - a floor, so this cannot silently become a no-op. "
                            + "Rows not compared: " + unchecked)
                .isGreaterThanOrEqualTo(MINIMUM_SETTING_DEFAULTS);
    }

    // ------------------------------------------------------------------------------------------------------

    /** The index of whichever value heading {@code file}'s tables use, or {@code -1} if neither. */
    private static int valueColumnIndex(final Path file) {
        for (final String column : VALUE_COLUMNS) {
            final int index = DocumentationSources.columnIndex(file, column);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The number a documented value cell states, in the unit the code stores it in.
     *
     * <p>Three conventions, each forced by a row that exists:</p>
     *
     * <ul>
     *   <li><b>Markdown and thousands separators are noise</b> - {@code **1,000,000**} is 1000000. A
     *       {@code LIMIT } prefix is dropped the same way, for the Discover starter-query row.</li>
     *   <li><b>A trailing unit is applied</b>, so {@code 10 GiB} compares against a byte count,
     *       {@code 30 seconds} against a duration in millis and {@code 7 days} against
     *       {@code DEFAULT_WINDOW_MILLIS}.</li>
     *   <li><b>A cell beginning {@code ~} is approximate and is not compared.</b> {@code ~2.8 × 10¹⁴} is
     *       derived from a byte width rather than stored anywhere, and the width itself is checked on the same
     *       row. The {@code ~} was already the documentation's marker for "computed, not a constant", so
     *       reusing it keeps this decidable without an exceptions list.</li>
     * </ul>
     *
     * @param cell never null.
     * @return the value, or {@code null} when the cell states nothing comparable.
     */
    private static @Nullable Long documentedValue(final String cell) {
        final String cleaned = cell
                .replace("**", "")
                .replace("`", "")
                .replace(",", "")
                .replaceFirst("^\\s*LIMIT\\s+", "")
                .strip();
        if (cleaned.startsWith("~")) {
            return null;
        }
        final Matcher number = DOCUMENTED_NUMBER.matcher(cleaned);
        if (!number.find()) {
            return null;
        }
        final double value = Double.parseDouble(number.group(1));
        final long multiplier = unitMultiplier(number.group(2));
        if (multiplier == 0) {
            return null;
        }
        return (long) (value * multiplier);
    }

    /**
     * The value a row annotates its constant reference with, e.g. the {@code 6} in
     * {@code `GraphStores.NODE_UID_WIDTH` (6 bytes)}.
     *
     * <p>This is what makes the two approximate rows checkable. Their {@code Value} cell states a derived
     * figure ({@code ~2.8 × 10¹⁴} distinct nodes) that is stored nowhere, but the same row also prints the byte
     * width it was derived from - and that <em>is</em> the constant. So the row is checked on the number it
     * shares with the code, and the arithmetic on top of it is left to the prose.</p>
     *
     * @param cell   the source-constant cell; never null.
     * @param offset the index just past the constant reference within {@code cell}.
     * @return the annotated value, or {@code null} if the reference carries no parenthesised number.
     */
    private static @Nullable Long annotatedValue(final String cell, final int offset) {
        final Matcher annotation = ANNOTATED_VALUE.matcher(cell);
        return annotation.find(offset) && annotation.start() == offset
                ? documentedValue(annotation.group(1))
                : null;
    }

    /**
     * The factor that converts a documented unit into the unit the code stores.
     *
     * @return the multiplier, or {@code 0} for a unit word this does not recognise - which makes the row
     *         unchecked rather than wrongly compared.
     */
    private static long unitMultiplier(final String unit) {
        return switch (unit.toLowerCase()) {
            case "", "bytes", "byte", "each" -> 1L;
            case "kib" -> 1024L;
            case "mib" -> 1024L * 1024L;
            case "gib" -> 1024L * 1024L * 1024L;
            case "seconds", "second" -> 1000L;
            case "minutes", "minute" -> 60L * 1000L;
            case "hours", "hour" -> 60L * 60L * 1000L;
            case "days", "day" -> 24L * 60L * 60L * 1000L;
            default -> 0L;
        };
    }

    /**
     * A constant's value, read from its declaration in source.
     *
     * @return the value, or {@code null} if the constant has no source declaration or an initialiser
     *         {@link #evaluate} cannot interpret.
     */
    private static @Nullable Long constantValue(final String type, final String member) {
        final String initialiser = DocumentationSources.constantInitialiser(type, member);
        return initialiser == null ? null : evaluate(initialiser, true);
    }

    /**
     * Interprets a constant's initialiser source.
     *
     * <p>Covers the two shapes the documented constants actually use: a product of integer literals
     * ({@code 7L * 24L * 60L * 60L * 1000L}, {@code 10L * 1024 * 1024 * 1024}) and a single reference to
     * another constant ({@code Db.MAX_KEY_LENGTH}). Anything else returns {@code null} and leaves the row
     * unchecked - guessing at an arbitrary expression would be a worse failure than not checking it.</p>
     *
     * @param initialiser      the initialiser source; never null.
     * @param followReference  whether a {@code Class.CONSTANT} initialiser may be resolved. False on the
     *                         recursive call, so a cycle cannot loop and only one hop is ever taken.
     */
    private static @Nullable Long evaluate(final String initialiser, final boolean followReference) {
        final String text = initialiser.strip();

        if (LITERAL_PRODUCT.matcher(text).matches()) {
            long product = 1L;
            for (final String factor : text.split("\\*")) {
                product *= Long.parseLong(factor.strip().replaceAll("[LlFfDd]$", ""));
            }
            return product;
        }

        final Matcher reference = CONSTANT_REFERENCE.matcher(text);
        if (followReference && reference.matches()) {
            final String referenced =
                    DocumentationSources.constantInitialiser(reference.group(1), reference.group(2));
            return referenced == null ? null : evaluate(referenced, false);
        }

        return null;
    }

    /**
     * One setting's default, as a comparable number in the same unit {@link #documentedValue} produces.
     *
     * @return the default, or {@code null} for a setting whose value is not numeric (e.g. {@code path},
     *         {@code nodeList}) or which {@link GraphDbConfig} does not expose.
     */
    private static @Nullable Long settingDefault(final GraphDbConfig defaults, final String name) {
        return switch (name) {
            case "maxStoreSize" -> defaults.getMaxStoreSize();
            case "maxVarLengthHops" -> (long) defaults.getMaxVarLengthHops();
            case "maxVarLengthPathStates" -> defaults.getMaxVarLengthPathStates();
            case "maxTraversalDuration" -> defaults.getMaxTraversalDuration().toMillis();
            case "maxAccumulatedRows" -> defaults.getMaxAccumulatedRows();
            case "wholeGraphNodeCap" -> (long) defaults.getWholeGraphNodeCap();
            default -> null;
        };
    }

    /** The property names {@code GraphDbConfig} exposes, derived from its getters. */
    private static Set<String> configPropertyNames() {
        final Path config = DocumentationSources.javaFilesBySimpleName().get("GraphDbConfig").getFirst();
        final Matcher getter = Pattern
                .compile("public [\\w<>,\\[\\] ]+ get([A-Z][A-Za-z0-9]*)\\(\\)")
                .matcher(DocumentationSources.read(config));
        final Set<String> names = new HashSet<>();
        while (getter.find()) {
            final String name = getter.group(1);
            names.add(Character.toLowerCase(name.charAt(0)) + name.substring(1));
        }
        return names;
    }
}
