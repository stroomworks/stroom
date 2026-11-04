/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.ai.extraction;

import stroom.shapeshifter.ai.extraction.Compilation.Compiled;
import stroom.shapeshifter.ai.extraction.Compilation.Rejected;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Runs one Data Splitter configuration over one input, outside a Stroom node, and reports what came out.
///
/// It exists so that a candidate configuration, written by a person or a model, can be checked without a
/// running node: does it compile, how many records does it make, how much of the input do they account for,
/// what errors were raised, and (given the expected records) is the output exactly what was wanted.
///
/// It is a thin layer over [DataSplitterCompiler] and [DataSplitterRunner], which do the work, and it needs
/// the same fixture as the other extraction tests ([NodeFixture]). It can be run from the command line, so a
/// pilot script can call it. See [#main(String[])].
public final class DataSplitterCheck {

    private static final String RECORDS_OPTION = "--records-out";

    private final NodeFixture fixture;

    /// Creates a checker with its own node fixture. Building the fixture is the slow part, so one checker
    /// should be reused for several checks.
    public DataSplitterCheck() {
        this.fixture = new NodeFixture();
    }

    /// Compiles the configuration and, if it compiles, runs it over the input.
    ///
    /// @param configuration   The Data Splitter configuration text.
    /// @param input           The raw input to split.
    /// @param expectedRecords The records the run should produce, in the form the stepper shows them, or empty
    ///                        where there is no expected output to compare with.
    /// @return What happened. A configuration that does not compile is reported, not thrown.
    public Report check(final String configuration, final String input, final Optional<String> expectedRecords) {
        final Compilation compilation = fixture.compiler().compile(configuration);
        if (compilation instanceof final Rejected rejected) {
            return new Report(
                    Outcome.REJECTED, 0, 0, 0, countOf(rejected.diagnostics(), Severity.ERROR)
                            + countOf(rejected.diagnostics(), Severity.FATAL_ERROR),
                    countOf(rejected.diagnostics(), Severity.WARNING), "",
                    describe(rejected.diagnostics()), false);
        }
        final ExtractionResult result = DataSplitterRunner.run((Compiled) compilation, input);
        final InputCoverage coverage = result.coverage(input);
        final boolean matches = expectedRecords.map(expected -> expected.equals(result.records())).orElse(false);
        return new Report(
                Outcome.RAN,
                result.recordCount(),
                coverage.charRatio(),
                coverage.lineRatio(),
                result.count(Severity.ERROR) + result.count(Severity.FATAL_ERROR),
                result.count(Severity.WARNING),
                result.records(),
                describe(result.diagnostics()),
                matches);
    }

    /// Checks a configuration file against an input file from the command line.
    ///
    /// Arguments: the configuration file, the input file, and optionally the file holding the expected
    /// records. `--records-out FILE` writes the records the run produced. A summary is printed in a fixed
    /// form so that a script can read it, and the exit status is 0 only if the configuration compiled, raised
    /// no errors and (where expected records were given) matched them.
    ///
    /// @param args The command-line arguments.
    /// @throws IOException If a file cannot be read or written.
    public static void main(final String[] args) throws IOException {
        Optional<Path> recordsOut = Optional.empty();
        final List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (RECORDS_OPTION.equals(args[i]) && i + 1 < args.length) {
                recordsOut = Optional.of(Path.of(args[++i]));
            } else {
                files.add(args[i]);
            }
        }
        if (files.size() < 2 || files.size() > 3) {
            System.err.println("usage: DataSplitterCheck CONFIG INPUT [EXPECTED_RECORDS] [--records-out FILE]");
            System.exit(2);
        }
        final String configuration = Files.readString(Path.of(files.get(0)), StandardCharsets.UTF_8);
        final String input = Files.readString(Path.of(files.get(1)), StandardCharsets.UTF_8);
        final Optional<String> expected = files.size() == 3
                ? Optional.of(Files.readString(Path.of(files.get(2)), StandardCharsets.UTF_8))
                : Optional.empty();

        final Report report = new DataSplitterCheck().check(configuration, input, expected);
        System.out.println(report.summary(expected.isPresent()));
        if (recordsOut.isPresent()) {
            Files.writeString(recordsOut.get(), report.records(), StandardCharsets.UTF_8);
        }
        System.exit(report.passed(expected.isPresent())
                ? 0
                : 1);
    }

    private static long countOf(final List<StoredError> diagnostics, final Severity severity) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.getSeverity() == severity)
                .count();
    }

    private static List<String> describe(final List<StoredError> diagnostics) {
        return diagnostics.stream()
                .map(diagnostic -> diagnostic.getSeverity() + ": " + diagnostic.getMessage())
                .toList();
    }


    // --------------------------------------------------------------------------------


    /// Whether the configuration could be run at all.
    public enum Outcome {
        /// The configuration did not compile, so nothing was run.
        REJECTED,
        /// The configuration compiled and was run over the input.
        RAN
    }


    // --------------------------------------------------------------------------------


    /// What a check found.
    ///
    /// @param outcome       Whether the configuration compiled and ran.
    /// @param recordCount   The number of records produced.
    /// @param charCoverage  The share of the input's characters that fall inside a record, from 0 to 1.
    /// @param lineCoverage  The share of the input's lines that fall inside a record, from 0 to 1.
    /// @param errors        The number of errors and fatal errors raised, compiling or running.
    /// @param warnings      The number of warnings raised.
    /// @param records       The records produced, in the form the stepper shows them. Empty if rejected.
    /// @param diagnostics   Every diagnostic, as "severity: message".
    /// @param matchesExpected Whether the records equal the expected records. False if none were given.
    public record Report(Outcome outcome,
                         int recordCount,
                         double charCoverage,
                         double lineCoverage,
                         long errors,
                         long warnings,
                         String records,
                         List<String> diagnostics,
                         boolean matchesExpected) {

        /// Whether the check passed: the configuration ran, raised no errors, and matched the expected records
        /// if there were any.
        ///
        /// @param expectedGiven Whether expected records were supplied.
        /// @return True if it passed.
        public boolean passed(final boolean expectedGiven) {
            return outcome == Outcome.RAN
                   && errors == 0
                   && (!expectedGiven || matchesExpected);
        }

        /// The report as lines a script can read, one fact per line.
        ///
        /// @param expectedGiven Whether expected records were supplied.
        /// @return The summary text.
        public String summary(final boolean expectedGiven) {
            final StringBuilder text = new StringBuilder();
            text.append("OUTCOME ").append(outcome).append('\n');
            text.append("RECORDS ").append(recordCount).append('\n');
            text.append(String.format("COVERAGE chars=%.3f lines=%.3f%n", charCoverage, lineCoverage));
            text.append("ERRORS ").append(errors).append(" WARNINGS ").append(warnings).append('\n');
            text.append("MATCH ").append(expectedGiven
                    ? matchesExpected
                    ? "yes"
                    : "no"
                    : "n/a").append('\n');
            diagnostics.forEach(diagnostic -> text.append("DIAGNOSTIC ").append(diagnostic).append('\n'));
            return text.toString();
        }
    }
}
