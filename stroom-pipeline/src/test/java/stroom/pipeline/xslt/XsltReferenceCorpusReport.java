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

package stroom.pipeline.xslt;

import stroom.dictionary.shared.DictionaryDoc;
import stroom.pipeline.shared.XsltDoc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A diagnostic, not a test: runs the parser over a real corpus of XSLTs and prints what it found.
 * <p>
 * Unit tests can only confirm the parser does what it was told to do. They cannot answer the questions that
 * decide whether reporting findings to users is worth building - how often {@code NOT_FOUND} fires on
 * healthy content, whether {@code UNANALYSED} is rare enough not to become noise, how many stylesheets
 * compute their map names rather than writing them. Those need real stylesheets.
 * <p>
 * Skipped unless it is told where to look, so it costs nothing in CI. Use the environment variable, because
 * Gradle passes {@code -D} to its own JVM rather than to the test JVM:
 * <pre>
 * XSLT_CORPUS_DIR=/path/to/content \
 *   ./gradlew :stroom-pipeline:test --tests "*XsltReferenceCorpusReport" -i
 * </pre>
 * The {@code xslt.corpus.dir} system property is honoured as well, for running from an IDE where setting
 * one is easier.
 * <p>
 * Point it at any directory tree containing {@code .xsl} files - a content export, a Stroom GitRepo
 * checkout, or a filesystem docstore.
 * <p>
 * Names come from the {@code .node} sidecar an export writes beside each document, which carries the true
 * {@code name}, {@code type} and {@code uuid}. Reading them from file names instead does not work: an
 * export replaces every non-alphanumeric character, so the document {@code stroom-json} is written as
 * {@code stroom_json.XSLT.<uuid>.xsl} and an {@code xsl:import href="stroom-json"} then appears to resolve
 * to nothing. Where there are no sidecars - a filesystem docstore, which writes {@code <uuid>.xsl} - the
 * UUID is used and resolution by name is not possible.
 * <p>
 * <b>Read the output with one caveat in mind.</b> There is no document store here, so the only documents
 * that can be resolved are the ones present in the tree. If the export is partial, names defined elsewhere
 * are reported {@code NOT_FOUND} when a running Stroom would resolve them. Map names, endpoints,
 * unanalysable expressions and parse failures do not depend on the store and are trustworthy either way.
 */
class XsltReferenceCorpusReport {

    private static final String CORPUS_DIR_PROPERTY = "xslt.corpus.dir";
    private static final String CORPUS_DIR_ENV = "XSLT_CORPUS_DIR";
    private static final String XSLT_EXTENSION = ".xsl";
    private static final String NODE_EXTENSION = ".node";

    @Test
    @DisplayName("report what the parser finds across a real corpus of XSLTs")
    void report() throws IOException {
        // The environment variable first: Gradle applies a command line -D to its own JVM, not to the
        // forked test JVM, but it does pass the environment through.
        final String dir = Objects.requireNonNullElse(
                System.getenv(CORPUS_DIR_ENV),
                System.getProperty(CORPUS_DIR_PROPERTY, ""));
        Assumptions.assumeTrue(!dir.isBlank(),
                "Set " + CORPUS_DIR_ENV + "=<dir> to run this diagnostic");

        final Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }

        final List<Path> stylesheets = filesWithExtension(root, XSLT_EXTENSION);
        requireNonEmpty(stylesheets, root);
        final XsltReferenceParser parser = new XsltReferenceParserImpl(lookupFor(root));

        final Map<String, Integer> byKind = new TreeMap<>();
        final Map<String, Integer> byReason = new TreeMap<>();
        final List<String> notFound = new ArrayList<>();
        final List<String> ambiguous = new ArrayList<>();
        final List<String> unanalysable = new ArrayList<>();
        final List<String> parseFailures = new ArrayList<>();
        final List<String> mapsRead = new ArrayList<>();
        final List<String> mapsWritten = new ArrayList<>();
        final List<String> endpoints = new ArrayList<>();

        long totalNanos = 0;
        long slowestNanos = 0;
        String slowest = "";

        for (final Path path : stylesheets) {
            final String label = documentNameOf(path).orElseGet(() -> root.relativize(path).toString());
            final String data = Files.readString(path);

            final long start = System.nanoTime();
            final XsltReferences references = parser.parse(data);
            final long elapsed = System.nanoTime() - start;
            totalNanos += elapsed;
            if (elapsed > slowestNanos) {
                slowestNanos = elapsed;
                slowest = label;
            }

            if (references.hasParseFailure()) {
                parseFailures.add(label + " - " + references.parseFailure());
            }
            for (final XsltReference reference : references.references()) {
                byKind.merge(reference.kind().name(), 1, Integer::sum);
                if (reference.reason() != null) {
                    byReason.merge(reference.reason().name(), 1, Integer::sum);
                }
                final String where = label + ":" + reference.lineNumber();
                switch (reference.reason()) {
                    case NOT_FOUND -> notFound.add(where + " " + reference.kind() + " '"
                                                   + reference.rawValue() + "'");
                    case AMBIGUOUS -> ambiguous.add(where + " " + reference.kind() + " '"
                                                    + reference.rawValue() + "' matched "
                                                    + reference.candidates().size());
                    case UNPARSEABLE -> unanalysable.add(where + " " + reference.rawValue());
                    case null -> collectResolved(reference, mapsRead, mapsWritten, endpoints);
                    default -> {
                        // Counted above; the remaining reasons are expected and not individually listed.
                    }
                }
            }
        }

        print(stylesheets, byKind, byReason, notFound, ambiguous, unanalysable, parseFailures,
                mapsRead, mapsWritten, endpoints, totalNanos, slowestNanos, slowest);
    }

    private static void collectResolved(final XsltReference reference,
                                        final List<String> mapsRead,
                                        final List<String> mapsWritten,
                                        final List<String> endpoints) {
        switch (reference.kind()) {
            case REF_MAP_READ -> mapsRead.add(reference.rawValue());
            case REF_MAP_WRITE -> mapsWritten.add(reference.rawValue());
            case HTTP -> endpoints.add(reference.direction() + " " + reference.rawValue());
            default -> {
                // Documents are counted by kind; their names are not interesting in bulk.
            }
        }
    }

    /**
     * Build a lookup from the corpus itself, so that references between documents in the tree resolve as
     * they would in a running Stroom.
     * <p>
     * Driven by the {@code .node} sidecars rather than by file names, so every document type is discovered
     * with its true name - including the dictionaries, which are identified by their declared type rather
     * than by having a {@code .txt} extension.
     */
    private static XsltReferenceLookup lookupFor(final Path root) throws IOException {
        final FakeXsltReferenceLookup lookup = new FakeXsltReferenceLookup();
        for (final Path node : filesWithExtension(root, NODE_EXTENSION)) {
            final Properties properties = readProperties(node);
            final String type = properties.getProperty("type");
            final String name = properties.getProperty("name");
            final String uuid = properties.getProperty("uuid");
            if (name != null && uuid != null
                && (XsltDoc.TYPE.equals(type) || DictionaryDoc.TYPE.equals(type))) {
                lookup.with(type, name, uuid);
            }
        }
        // A filesystem docstore has no sidecars, so fall back to the UUID in the file name. Resolution by
        // name is then impossible, which the report's NOT_FOUND section will make obvious.
        if (lookup.isEmpty()) {
            for (final Path path : filesWithExtension(root, XSLT_EXTENSION)) {
                final String fileName = path.getFileName().toString();
                final String uuid = fileName.substring(0, fileName.length() - XSLT_EXTENSION.length());
                lookup.with(XsltDoc.TYPE, uuid, uuid);
            }
        }
        return lookup;
    }

    /**
     * @return the document's real name, from the {@code .node} sidecar beside it, or empty if there is
     * none.
     */
    private static Optional<String> documentNameOf(final Path stylesheet) {
        final String fileName = stylesheet.getFileName().toString();
        final Path node = stylesheet.resolveSibling(
                fileName.substring(0, fileName.length() - XSLT_EXTENSION.length()) + NODE_EXTENSION);
        if (!Files.isRegularFile(node)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(readProperties(node).getProperty("name"));
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    private static Properties readProperties(final Path path) throws IOException {
        final Properties properties = new Properties();
        try (final Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
    }

    private static List<Path> filesWithExtension(final Path root, final String extension)
            throws IOException {
        try (final Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .sorted()
                    .toList();
        } catch (final UncheckedIOException e) {
            throw new IOException("Failed to walk " + root, e);
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber") // A report, assembled in one place on purpose.
    private static void print(final List<Path> stylesheets,
                              final Map<String, Integer> byKind,
                              final Map<String, Integer> byReason,
                              final List<String> notFound,
                              final List<String> ambiguous,
                              final List<String> unanalysable,
                              final List<String> parseFailures,
                              final List<String> mapsRead,
                              final List<String> mapsWritten,
                              final List<String> endpoints,
                              final long totalNanos,
                              final long slowestNanos,
                              final String slowest) {
        final StringBuilder sb = new StringBuilder("\n");
        sb.append("XSLT reference parser - corpus report\n");
        sb.append("=====================================\n\n");
        sb.append(stylesheets.size()).append(" stylesheets parsed in ")
                .append(totalNanos / 1_000_000).append(" ms (mean ")
                .append(stylesheets.isEmpty() ? 0 : totalNanos / stylesheets.size() / 1_000_000)
                .append(" ms, slowest ").append(slowestNanos / 1_000_000).append(" ms: ")
                .append(slowest).append(")\n\n");

        appendCounts(sb, "Findings by kind", byKind);
        appendCounts(sb, "Unresolved by reason", byReason);

        appendDistinct(sb, "Map names read", mapsRead);
        appendDistinct(sb, "Map names written", mapsWritten);
        appendDistinct(sb, "External endpoints", endpoints);

        appendList(sb, "NOT_FOUND - a name resolving to no document (see the caveat in the class javadoc)",
                notFound);
        appendList(sb, "AMBIGUOUS - a name matching several documents", ambiguous);
        appendList(sb, "UNANALYSED - expressions the parser could not compile", unanalysable);
        appendList(sb, "Parse failures - stylesheets not readable in full", parseFailures);

        System.out.println(sb);
    }

    private static void appendCounts(final StringBuilder sb,
                                     final String heading,
                                     final Map<String, Integer> counts) {
        sb.append(heading).append('\n');
        if (counts.isEmpty()) {
            sb.append("  (none)\n");
        }
        counts.forEach((key, count) -> sb.append("  ").append(key).append(": ").append(count).append('\n'));
        sb.append('\n');
    }

    private static void appendDistinct(final StringBuilder sb,
                                       final String heading,
                                       final List<String> values) {
        final Map<String, Long> counts = new TreeMap<>();
        values.forEach(value -> counts.merge(value, 1L, Long::sum));
        sb.append(heading).append(" (").append(counts.size()).append(" distinct, ")
                .append(values.size()).append(" uses)\n");
        if (counts.isEmpty()) {
            sb.append("  (none)\n");
        }
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sb.append("  ").append(entry.getKey())
                        .append(" x").append(entry.getValue()).append('\n'));
        sb.append('\n');
    }

    private static void appendList(final StringBuilder sb, final String heading, final List<String> lines) {
        sb.append(heading).append(" (").append(lines.size()).append(")\n");
        if (lines.isEmpty()) {
            sb.append("  (none)\n");
        }
        lines.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(line -> sb.append("  ").append(line).append('\n'));
        sb.append('\n');
    }

    /**
     * Guards against the diagnostic silently doing nothing if the corpus has no stylesheets in it.
     */
    private static void requireNonEmpty(final List<Path> stylesheets, final Path root) {
        Objects.requireNonNull(stylesheets);
        if (stylesheets.isEmpty()) {
            throw new IllegalStateException("No " + XSLT_EXTENSION + " files found under " + root);
        }
    }
}
