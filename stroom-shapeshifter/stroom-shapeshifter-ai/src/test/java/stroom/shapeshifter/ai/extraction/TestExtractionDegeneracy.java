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

package stroom.shapeshifter.ai.extraction;

import stroom.shapeshifter.ai.extraction.Compilation.Compiled;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.InputCoverageScorer;
import stroom.shapeshifter.ai.scoring.Score;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The degeneracy probe for the extraction stage (design §9, item 3, applied to ruling A11). The dominant
 * failure of a generated splitter is a configuration that produces a believable number of records by
 * quietly discarding everything it could not match. This hand-writes that configuration against a corpus
 * case and confirms the coverage scorer sees what yield alone cannot. If the scorer cannot catch a
 * deliberately degenerate splitter, it will not catch an accidentally degenerate one.
 */
class TestExtractionDegeneracy {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestExtractionDegeneracy.class);

    private static final NodeFixture FIXTURE = new NodeFixture();

    /**
     * Six quoted CSV lines, no header; the golden yields six records at full coverage.
     */
    private static final String CASE = "002_csv_without_header";

    /**
     * Matches only the lines that mention the office and, with {@code ignoreErrors}, throws the rest away
     * without a word. Two tidy records come out.
     */
    private static final String DISCARDS_UNMATCHED_LINES = """
            <?xml version="1.1" encoding="UTF-8"?>
            <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
              <split delimiter="\\n">
                <group value="$1" ignoreErrors="true">
                  <regex pattern="^&#34;([^&#34;]*)&#34;,&#34;([^&#34;]*)&#34;,&#34;office&#34;,&#34;([^&#34;]*)&#34;$">
                    <data name="time" value="$1"/>
                    <data name="user" value="$2"/>
                    <data name="action" value="$3"/>
                  </regex>
                </group>
              </split>
            </dataSplitter>
            """;

    /**
     * Takes every line as a record but keeps only its first field. Six records come out, each missing three
     * quarters of what was there. Whether anyone hears about it is decided by {@code ignoreErrors}.
     */
    private static String discardsFieldsWithinRecords(final boolean ignoreErrors) {
        return """
                <?xml version="1.1" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
                  <split delimiter="\\n">
                    <group value="$1" ignoreErrors="%s">
                      <regex pattern="^&#34;([^&#34;]*)&#34;">
                        <data name="time" value="$1"/>
                      </regex>
                    </group>
                  </split>
                </dataSplitter>
                """.formatted(ignoreErrors);
    }

    @Test
    void coverageExposesDiscardedLinesThatYieldDoesNotSee() {
        final Golden golden = golden();
        final Probe good = probe(golden.configuration(), golden);
        final Probe degenerate = probe(DISCARDS_UNMATCHED_LINES, golden);

        // Yield alone says the degenerate configuration is merely less productive.
        assertThat(degenerate.result().recordCount()).isEqualTo(2);
        assertThat(degenerate.result().diagnostics()).isEmpty();

        // Coverage says it threw away two thirds of the input.
        assertThat(good.coverage().charRatio()).isEqualTo(1.0);
        assertThat(degenerate.coverage().charRatio()).isLessThan(0.5);
        assertThat(degenerate.coverage().lineRatio()).isLessThan(0.5);
    }

    @Test
    void theCoverageScoreIsTheCharacterShareAndTheDiagnosticNamesTheLines() {
        // A36: the header case's golden reads the header into a variable — one line in seven, a sixteenth of
        // the characters. The score is the sixteenth; the line is still named.
        final Golden header = ExtractionCorpus.goldens().stream()
                .filter(golden -> golden.stem().equals("001_csv_with_header"))
                .findFirst()
                .orElseThrow();
        final Probe probe = probe(header.configuration(), header);
        final Optional<Score> score = new InputCoverageScorer().score(null, new Attempted("DSParser", true,
                header.input(), new StepResult(probe.result().records(), List.of(), probe.result().recordRanges())));

        assertThat(probe.coverage().lineRatio()).isLessThan(0.9);
        assertThat(probe.coverage().charRatio()).isGreaterThan(0.9);
        assertThat(score).isPresent();
        assertThat(score.get().value()).isEqualTo(probe.coverage().charRatio());
        assertThat(score.get().diagnostics()).hasSize(1);
        assertThat(score.get().diagnostics().get(0).getMessage()).contains("Lines not consumed: 1");
    }

    @Test
    void errorLoadExposesFieldsDroppedWithinARecord() {
        final Golden golden = golden();
        final Probe degenerate = probe(discardsFieldsWithinRecords(false), golden);

        // Every line became a record, so yield and coverage are both complete: a record's span is the
        // whole of what it was split from, however little of it the configuration kept. The Data Splitter
        // itself is what notices, raising an error per record for the content its expressions left behind.
        assertThat(degenerate.result().recordCount()).isEqualTo(6);
        assertThat(degenerate.coverage().charRatio()).isEqualTo(1.0);
        assertThat(degenerate.result().count(Severity.ERROR)).isEqualTo(6);
        assertThat(degenerate.result().records()).doesNotContain("warehouse");
    }

    @Test
    void ignoreErrorsSilencesTheOnlySignalOfFieldsDroppedWithinARecord() {
        final Golden golden = golden();
        final Probe degenerate = probe(discardsFieldsWithinRecords(true), golden);

        // With errors ignored, nothing in the extraction stage sees the loss. Catching it needs a signal
        // over the output — field count against the incumbent, or the downstream schema stage — which is
        // why a generated configuration must not be allowed to ignore errors.
        assertThat(degenerate.result().recordCount()).isEqualTo(6);
        assertThat(degenerate.coverage().charRatio()).isEqualTo(1.0);
        assertThat(degenerate.result().diagnostics()).isEmpty();
        assertThat(degenerate.result().records()).doesNotContain("warehouse");
    }

    private static Golden golden() {
        return ExtractionCorpus.goldens().stream()
                .filter(golden -> golden.stem().equals(CASE))
                .findFirst()
                .orElseThrow();
    }

    private static Probe probe(final String configuration, final Golden golden) {
        final Compilation compilation = FIXTURE.compiler().compile(configuration);
        assertThat(compilation)
                .as("probe configuration failed to compile: %s", compilation.diagnostics())
                .isInstanceOf(Compiled.class);
        final ExtractionResult result = DataSplitterRunner.run((Compiled) compilation, golden.input());
        final InputCoverage coverage = result.coverage(golden.input());
        LOGGER.info("records={} coverage={}", result.recordCount(), coverage);
        return new Probe(result, coverage);
    }

    private record Probe(ExtractionResult result, InputCoverage coverage) {

    }
}
