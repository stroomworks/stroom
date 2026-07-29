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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the rejection messages the documentation quotes are still the messages the code throws, and that
 * the counts it states are still correct.
 *
 * <p><b>Why this exists.</b> A rejection message is the whole of the user's experience of an unsupported
 * construct: they write a query, they read a sentence, and that sentence has to tell them what to do instead.
 * The language reference reproduces a dozen of them verbatim so an author can search for the one they hit. A
 * quoted message that has since been reworded sends them looking for a string that no longer exists, and the
 * documentation is the last place anyone thinks to check when a message changes.</p>
 *
 * <p>Landing this found one defect immediately: {@code 13-developer-guide.md} stated the compiler held
 * <b>64</b> explicit rejections where it holds 65, while {@code 06-language-reference.md} had the same number
 * right. Two documents disagreeing about a countable fact is exactly what a build should not tolerate, and the
 * count assertion below is cheaper than either of them being re-counted by hand.</p>
 *
 * <h2>What counts as a quoted message</h2>
 *
 * <p>Text that begins with one of the three prefixes the documentation itself defines - a compiler message
 * begins {@code not in PoC subset:} or {@code not supported in this version:}, an engine one begins
 * {@code not yet supported:} - found in one of three places, each a form the documentation already uses:</p>
 *
 * <ul>
 *   <li><b>A table cell</b>, which is how the language reference's rejection table and the limits table quote
 *       them.</li>
 *   <li><b>An untagged fenced block</b>, used where a message is shown on its own.</li>
 *   <li><b>A run of blockquote lines</b>, joined, used where a long message is quoted inside a callout.</li>
 * </ul>
 *
 * <p>Keying off the prefixes is what keeps this free of a hand-kept list, in the spirit
 * {@link TestDocumentationReferences} sets out. It also means a message with a <em>new</em> prefix silently
 * escapes the check - so if a fourth family of rejections is introduced, add it to {@link #PREFIXES}.</p>
 *
 * <h2>Elision, and what is not covered</h2>
 *
 * <p>The documentation abbreviates long messages with {@code …}, at the end and in the middle. A documented
 * message is therefore split on {@code …} and its segments matched <b>in order</b> within one string literal -
 * the same treatment {@code TestEventLoggingXslt} gives elided XSLT, and for the same reason.</p>
 *
 * <p><b>Not covered:</b> the ingest-side message tables in {@code 03-ingest.md} and {@code 07-functions.md}
 * that quote {@code GraphFilter}'s per-record errors. Those are assembled from runtime values rather than being
 * fixed text, so matching them needs a different technique than searching for a literal. That is a known gap,
 * recorded here rather than left for the next reader to deduce from the absence of a check.</p>
 */
class TestDocumentationMessages {

    /** The classes that throw the messages the documentation quotes. */
    private static final String COMPILER = "CypherToLogicalPlan";
    private static final String ENGINE = "GraphTraversalEngine";

    /**
     * The message prefixes the documentation defines, mapped to the class that must contain the message.
     *
     * <p>Ordered longest-first is unnecessary here because none is a prefix of another, but the mapping is the
     * point: it is what makes "the compiler said this" a checkable claim rather than a stylistic one.</p>
     */
    private static final Map<String, String> PREFIXES = new LinkedHashMap<>(Map.of(
            "not in PoC subset:", COMPILER,
            "not supported in this version:", COMPILER,
            "not yet supported:", ENGINE));

    /** A floor, so a changed table heading or fence style cannot turn this into a no-op. */
    private static final int MINIMUM_MESSAGES = 10;

    /** A markdown table row's cells. */
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\|(.+)\\|\\s*$");

    /** An untagged fenced block's body. */
    private static final Pattern PLAIN_BLOCK = Pattern.compile("```\\n(.*?)```", Pattern.DOTALL);

    /** One or more consecutive blockquote lines. */
    private static final Pattern BLOCKQUOTE = Pattern.compile("(?m)(^>.*(?:\\n>.*)*)");

    /** A Java string literal, escapes included. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * The seam between two adjacent literals of one concatenated message: {@code " + "}. Removing it first is
     * what lets a message wrapped across four source lines be found as one string.
     */
    private static final Pattern LITERAL_JOIN = Pattern.compile("\"\\s*\\+\\s*\"");

    /** {@code CypherToLogicalPlan} holds <b>N</b> explicit rejections, in the developer guide. */
    private static final Pattern GUIDE_COUNT =
            Pattern.compile("holds \\*{0,2}(\\d+)\\*{0,2} explicit rejections");

    /** The compiler contains <b>N</b> ... and the engine a further <b>M</b>, in the language reference. */
    private static final Pattern REFERENCE_COUNTS = Pattern.compile(
            "compiler contains \\*{0,2}(\\w+)\\*{0,2} such rejection messages and the engine a further "
            + "\\*{0,2}(\\w+)\\*{0,2}");

    /** Number words the documentation spells out. Prose counts small numbers in words; the check must too. */
    private static final Map<String, Integer> NUMBER_WORDS = Map.of(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
            "six", 6, "seven", 7, "eight", 8, "nine", 9, "ten", 10);

    @Test
    void everyDocumentedRejectionMessageIsStillThrown() {
        final Map<String, List<String>> messagesByClass = Map.of(
                COMPILER, messagesOf(COMPILER, COMPILER),
                ENGINE, messagesOf(ENGINE, ENGINE));

        final List<String> failures = new ArrayList<>();
        int checked = 0;

        for (final Path file : DocumentationSources.documentationFiles()) {
            for (final String quoted : quotedMessagesIn(file)) {
                final String owner = PREFIXES.get(prefixOf(quoted));
                checked++;
                if (messagesByClass.get(owner).stream().noneMatch(message -> containsSegments(message, quoted))) {
                    failures.add(file.getFileName() + ": " + owner + " no longer contains\n    " + quoted);
                }
            }
        }

        assertThat(failures)
                .describedAs("documented rejection messages that the code no longer throws - reword the "
                            + "documentation to match the code, not the other way round, unless the message "
                            + "itself is what needs improving")
                .isEmpty();
        assertThat(checked)
                .describedAs("quoted messages found - a floor, so this cannot silently become a no-op")
                .isGreaterThanOrEqualTo(MINIMUM_MESSAGES);
    }

    /**
     * The documentation states how many rejections each layer holds. Those numbers rot in silence, and two
     * documents state the compiler's independently.
     */
    @Test
    void theDocumentedRejectionCountsAreCorrect() {
        final int compilerRejections = messagesOf(COMPILER, COMPILER).size();
        final int engineRejections = messagesOf(ENGINE, ENGINE).size();

        final String guide = DocumentationSources.read(
                DocumentationSources.documentationFile("13-developer-guide.md"));
        final Matcher guideCount = GUIDE_COUNT.matcher(guide);
        assertThat(guideCount.find())
                .describedAs("13-developer-guide.md should state how many explicit rejections %s holds. If that "
                            + "sentence was deliberately removed, remove this assertion with it", COMPILER)
                .isTrue();
        assertThat(Integer.parseInt(guideCount.group(1)))
                .describedAs("the rejection count in 13-developer-guide.md, against %s's message literals",
                        COMPILER)
                .isEqualTo(compilerRejections);

        final String reference = DocumentationSources.read(
                DocumentationSources.documentationFile("06-language-reference.md"));
        final Matcher referenceCounts = REFERENCE_COUNTS.matcher(reference);
        assertThat(referenceCounts.find())
                .describedAs("06-language-reference.md should state the compiler's and engine's rejection counts")
                .isTrue();
        assertThat(parseCount(referenceCounts.group(1)))
                .describedAs("the compiler rejection count in 06-language-reference.md")
                .isEqualTo(compilerRejections);
        assertThat(parseCount(referenceCounts.group(2)))
                .describedAs("the engine rejection count in 06-language-reference.md")
                .isEqualTo(engineRejections);
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Every message quoted in {@code file}, normalised, in document order.
     *
     * @param file never null.
     * @return never null (may be empty); every element begins with one of {@link #PREFIXES}.
     */
    private static List<String> quotedMessagesIn(final Path file) {
        final String content = DocumentationSources.read(file);
        final List<String> messages = new ArrayList<>();

        for (final String line : content.lines().toList()) {
            final Matcher row = TABLE_ROW.matcher(line);
            if (row.matches()) {
                for (final String cell : row.group(1).split("\\|", -1)) {
                    addIfMessage(cell, messages);
                }
            }
        }
        collectGroup(PLAIN_BLOCK.matcher(content), messages);
        collectGroup(BLOCKQUOTE.matcher(content), messages);

        return messages;
    }

    private static void collectGroup(final Matcher matcher, final List<String> messages) {
        while (matcher.find()) {
            addIfMessage(matcher.group(1), messages);
        }
    }

    /** Adds {@code text} to {@code messages} if, once normalised, it is a quoted rejection message. */
    private static void addIfMessage(final String text, final List<String> messages) {
        final String normalised = normalise(text);
        if (prefixOf(normalised) != null && !messages.contains(normalised)) {
            messages.add(normalised);
        }
    }

    /**
     * Strips the markdown a quoted message is dressed in, so what remains is comparable with a string literal:
     * blockquote markers, bold and italic markers, backticks, the {@code (runtime)} annotation the language
     * reference uses to mark an engine message, and any collapsible whitespace.
     */
    private static String normalise(final String text) {
        return text.lines()
                .map(line -> line.replaceFirst("^>\\s?", ""))
                .reduce("", (a, b) -> a + " " + b)
                .replace("**", "")
                .replace("`", "")
                .replace("*(runtime)*", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** The {@link #PREFIXES} key {@code message} begins with, or null if it begins with none of them. */
    private static @Nullable String prefixOf(final String message) {
        return PREFIXES.keySet().stream().filter(message::startsWith).findFirst().orElse(null);
    }

    /**
     * Whether {@code literal} contains every {@code …}-separated segment of {@code documented}, in order.
     *
     * <p>Segment matching rather than a single {@code contains} because the documentation elides both at the
     * end of a message and in the middle of one - {@code "not supported in this version: … in a DIFF WHERE
     * clause"} quotes a message whose subject is interpolated. Requiring the segments in order keeps that a
     * real check rather than reducing it to "the prefix exists".</p>
     */
    private static boolean containsSegments(final String literal, final String documented) {
        int position = 0;
        for (final String segment : documented.split("…")) {
            final String trimmed = segment.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int found = literal.indexOf(trimmed, position);
            if (found < 0) {
                return false;
            }
            position = found + trimmed.length();
        }
        return true;
    }

    /**
     * Every rejection message a class throws, reconstructed from its source as one string each.
     *
     * <p>Two source shapes have to be put back together, and both are common:</p>
     *
     * <ul>
     *   <li><b>Wrapping.</b> A message is usually four source lines of {@code "..." + "..."}, and the documented
     *       text spans the joins - so searching the source for the documented string directly would fail on
     *       every message long enough to need wrapping, which is most of them. The seams are removed first.</li>
     *   <li><b>Interpolation.</b> Some messages name the offending construct by splicing a value in:
     *       {@code "not supported in this version: " + side + "(...) in a DIFF WHERE clause …"}. The fragments
     *       either side are separate literals with an expression between them, and the documentation quotes such
     *       a message with {@code …} where the value goes. So a message is gathered per <b>throw site</b> - from
     *       its prefix literal to the end of that statement - and its fragments joined with {@code …}, which
     *       lines the reconstruction up with how the documentation writes it.</li>
     * </ul>
     *
     * @param simpleName the class's simple name; never null.
     * @param owner      which {@link #PREFIXES} owner's messages to gather; never null.
     * @return one entry per throw site, whitespace collapsed; never null.
     */
    private static List<String> messagesOf(final String simpleName, final String owner) {
        final Path source = DocumentationSources.javaFilesBySimpleName().get(simpleName).getFirst();
        final String joined = LITERAL_JOIN.matcher(DocumentationSources.read(source)).replaceAll("");
        final List<String> owned = PREFIXES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(owner))
                .map(Map.Entry::getKey)
                .toList();

        final List<String> messages = new ArrayList<>();
        final Matcher literal = STRING_LITERAL.matcher(joined);
        while (literal.find()) {
            final String text = literal.group(1).replaceAll("\\s+", " ");
            if (owned.stream().anyMatch(text::startsWith)) {
                messages.add(statementFrom(joined, literal.start()));
            }
        }
        return messages;
    }

    /**
     * The whole message of the statement beginning at {@code start}: every string literal from there to the end
     * of the statement, joined with {@code …} to stand for the values spliced between them.
     *
     * <p>The statement end is the next {@code ;} <b>outside a string literal</b>. The distinction is not
     * pedantry: two of these messages contain a semicolon in their own text - "…is a later phase); it is
     * supported in RETURN" - and a scan that stopped at the first raw {@code ;} truncated them mid-sentence, so
     * the documented text was reported as missing when it was present. Stopping at the statement rather than
     * parsing Java is still the right trade: being wrong the other way reconstructs a message with too much
     * text on the end, which cannot cause a false failure, because the documented segments are searched for
     * rather than compared.</p>
     */
    private static String statementFrom(final String source, final int start) {
        final String statement = source.substring(start, statementEnd(source, start));

        final List<String> fragments = new ArrayList<>();
        final Matcher literal = STRING_LITERAL.matcher(statement);
        while (literal.find()) {
            fragments.add(literal.group(1).replaceAll("\\s+", " "));
        }
        return String.join("…", fragments);
    }

    /**
     * The index of the first {@code ;} at or after {@code start} that is not inside a string literal, or the
     * source's length if there is none.
     */
    private static int statementEnd(final String source, final int start) {
        boolean inString = false;
        for (int i = start; i < source.length(); i++) {
            final char character = source.charAt(i);
            if (character == '\\' && inString) {
                i++;
            } else if (character == '"') {
                inString = !inString;
            } else if (character == ';' && !inString) {
                return i;
            }
        }
        return source.length();
    }

    /** A count the documentation states either as digits or as a word. */
    private static int parseCount(final String stated) {
        final Integer word = NUMBER_WORDS.get(stated.toLowerCase());
        return word != null ? word : Integer.parseInt(stated);
    }
}
