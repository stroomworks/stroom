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

import stroom.shapeshifter.ai.extraction.DataSplitterCheck.Outcome;
import stroom.shapeshifter.ai.extraction.DataSplitterCheck.Report;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Failing;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// The command-line checker reports what a Data Splitter configuration does, using the known-good and
/// known-bad cases of the extraction corpus.
class TestDataSplitterCheck {

    private static final DataSplitterCheck CHECK = new DataSplitterCheck();

    @Test
    void aKnownGoodConfigurationRunsAndMatchesItsExpectedRecords() {
        final Golden golden = ExtractionCorpus.goldens().getFirst();

        final Report report = CHECK.check(
                golden.configuration(), golden.input(), Optional.of(golden.expectedRecords()));

        assertThat(report.outcome()).isEqualTo(Outcome.RAN);
        assertThat(report.errors()).isZero();
        assertThat(report.recordCount()).isPositive();
        assertThat(report.matchesExpected()).isTrue();
        assertThat(report.passed(true)).isTrue();
        assertThat(report.summary(true)).contains("OUTCOME RAN").contains("MATCH yes");
    }

    @Test
    void reportsADifferenceWhenTheOutputIsNotWhatWasExpected() {
        final List<Golden> goldens = ExtractionCorpus.goldens();
        final Golden golden = goldens.getFirst();
        final Golden other = goldens.stream()
                .filter(candidate -> !candidate.expectedRecords().equals(golden.expectedRecords()))
                .findFirst()
                .orElseThrow();

        final Report report = CHECK.check(
                golden.configuration(), golden.input(), Optional.of(other.expectedRecords()));

        assertThat(report.outcome()).isEqualTo(Outcome.RAN);
        assertThat(report.matchesExpected()).isFalse();
        assertThat(report.passed(true)).isFalse();
        assertThat(report.passed(false)).isTrue();
        assertThat(report.summary(true)).contains("MATCH no");
    }

    @Test
    void withNoExpectedRecordsTheMatchIsNotApplicable() {
        final Golden golden = ExtractionCorpus.goldens().getFirst();

        final Report report = CHECK.check(golden.configuration(), golden.input(), Optional.empty());

        assertThat(report.matchesExpected()).isFalse();
        assertThat(report.summary(false)).contains("MATCH n/a");
    }

    @Test
    void aMalformedConfigurationIsRejectedAndReportedNotThrown() {
        final Report report = CHECK.check("""
                <?xml version="1.1" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
                  <split delimiter="\\n">
                    <group value="$1">
                  </split>
                </dataSplitter>
                """, "a,b\n", Optional.empty());

        assertThat(report.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(report.recordCount()).isZero();
        assertThat(report.errors()).isPositive();
        assertThat(report.diagnostics()).isNotEmpty();
        assertThat(report.passed(false)).isFalse();
        assertThat(report.summary(false)).contains("OUTCOME REJECTED").contains("DIAGNOSTIC");
    }

    @Test
    void aKnownBadConfigurationDoesNotPass() {
        final Failing failing = ExtractionCorpus.failing().getFirst();

        final Report report = CHECK.check(failing.configuration(), failing.input(), Optional.empty());

        assertThat(report.passed(false)).isFalse();
    }

    @Test
    void coverageIsBetweenZeroAndOne() {
        final Golden golden = ExtractionCorpus.goldens().getFirst();

        final Report report = CHECK.check(golden.configuration(), golden.input(), Optional.empty());

        assertThat(report.charCoverage()).isBetween(0.0, 1.0);
        assertThat(report.lineCoverage()).isBetween(0.0, 1.0);
    }
}
