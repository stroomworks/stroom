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
import stroom.shapeshifter.ai.extraction.Compilation.Rejected;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.util.logging.AsciiTable;
import stroom.util.logging.AsciiTable.Column;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibrates the extraction-stage scorers against ground truth (design §9, item 2). Every golden
 * configuration in the corpus must compile, reproduce its expected output exactly, and score at the top;
 * every failing configuration must be rejected or raise errors. A scorer that ranks a shipped,
 * golden-diffing configuration poorly is broken, and this is where that is discovered — before the
 * scorer gates a promotion.
 */
class TestExtractionCalibration {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestExtractionCalibration.class);

    private static final NodeFixture FIXTURE = new NodeFixture();

    @TestFactory
    Stream<DynamicTest> goldenConfigurationReproducesExpectedRecords() {
        return ExtractionCorpus.goldens().stream().map(golden -> DynamicTest.dynamicTest(golden.stem(), () -> {
            final Compiled compiled = compile(golden);
            final ExtractionResult result = DataSplitterRunner.run(compiled, golden.input());

            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.records()).isEqualTo(golden.expectedRecords());
        }));
    }

    @TestFactory
    Stream<DynamicTest> failingConfigurationIsRejectedOrRaisesErrors() {
        return ExtractionCorpus.failing().stream().map(failing -> DynamicTest.dynamicTest(failing.stem(), () -> {
            final Compilation compilation = FIXTURE.compiler().compile(failing.configuration());
            switch (compilation) {
                case Rejected rejected -> assertThat(rejected.diagnostics()).isNotEmpty();
                case Compiled compiled -> {
                    final ExtractionResult result = DataSplitterRunner.run(compiled, failing.input());
                    assertThat(result.count(Severity.ERROR) + result.count(Severity.FATAL_ERROR))
                            .as("%s ran without error", failing)
                            .isPositive();
                }
            }
            LOGGER.info("{}:\n{}", failing, describe(compilation));
        }));
    }

    @Test
    void goldenConfigurationsScoreAtTheTop() {
        final List<Scored> scored = ExtractionCorpus.goldens().stream()
                .map(golden -> {
                    final ExtractionResult result = DataSplitterRunner.run(compile(golden), golden.input());
                    return new Scored(golden, result, result.coverage(golden.input()));
                })
                .toList();

        LOGGER.info("Golden configurations:\n{}", AsciiTable.builder(scored)
                .withColumn(Column.of("Case", (Scored row) -> row.golden().stem()))
                .withColumn(Column.integer("Records", (Scored row) -> row.result().recordCount()))
                .withColumn(Column.of("Chars", (Scored row) ->
                        row.coverage().charsCovered() + "/" + row.coverage().charsTotal()))
                .withColumn(Column.decimal("Char ratio", (Scored row) -> row.coverage().charRatio(), 3))
                .withColumn(Column.of("Lines", (Scored row) ->
                        row.coverage().linesCovered() + "/" + row.coverage().linesTotal()))
                .withColumn(Column.decimal("Line ratio", (Scored row) -> row.coverage().lineRatio(), 3))
                .build());

        for (final Scored row : scored) {
            assertThat(row.result().recordCount())
                    .as("%s yielded no records", row.golden())
                    .isPositive();
        }
    }

    private static Compiled compile(final Golden golden) {
        final Compilation compilation = FIXTURE.compiler().compile(golden.configuration());
        assertThat(compilation)
                .as("%s failed to compile:\n%s", golden, describe(compilation))
                .isInstanceOf(Compiled.class);
        return (Compiled) compilation;
    }

    private static String describe(final Compilation compilation) {
        return compilation.diagnostics().stream()
                .map(Object::toString)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(no diagnostics)");
    }

    private record Scored(Golden golden, ExtractionResult result, InputCoverage coverage) {

    }
}
