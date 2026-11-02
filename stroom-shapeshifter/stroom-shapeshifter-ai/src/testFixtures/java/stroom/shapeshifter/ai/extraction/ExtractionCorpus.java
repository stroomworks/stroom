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

import stroom.test.common.ProjectPathUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The extraction-stage ground truth of design §2.1: the {@code TestDS3} corpus in {@code stroom-pipeline},
 * read as triples of configuration, input and expected output. The corpus is used as it stands, from its
 * own module, so that the evaluation and the Data Splitter's own tests can never drift apart.
 */
public final class ExtractionCorpus {

    private static final Path DIR = ProjectPathUtil.getRepoRoot()
            .resolve("stroom-pipeline/src/test/resources/TestDS3");
    private static final String CONFIGURATION = ".ds3.xml";
    private static final String INPUT = ".in";
    private static final String EXPECTED_RECORDS = ".out.xml";
    private static final String FAILING = "_FAIL";

    private ExtractionCorpus() {
    }

    /**
     * A case whose configuration is known to be good: run over its input, it produces exactly the expected
     * records without raising an error.
     */
    public record Golden(String stem, String configuration, String input, String expectedRecords) {

        private static final String RECORD = "<record>";

        int expectedRecordCount() {
            int count = 0;
            for (int i = expectedRecords.indexOf(RECORD); i >= 0; i = expectedRecords.indexOf(RECORD, i + 1)) {
                count++;
            }
            return count;
        }

        @Override
        public String toString() {
            return stem;
        }
    }

    /**
     * A case whose configuration is known to be bad, whether it fails to compile or runs and raises errors.
     * The corpus records what it does produce, but that is not a golden and is not kept here.
     */
    public record Failing(String stem, String configuration, String input) {

        @Override
        public String toString() {
            return stem;
        }
    }

    public static List<Golden> goldens() {
        return stems()
                .filter(stem -> !stem.endsWith(FAILING))
                .map(stem -> new Golden(
                        stem,
                        read(stem + CONFIGURATION),
                        read(stem + INPUT),
                        read(stem + EXPECTED_RECORDS)))
                .toList();
    }

    public static List<Failing> failing() {
        return stems()
                .filter(stem -> stem.endsWith(FAILING))
                .map(stem -> new Failing(stem, read(stem + CONFIGURATION), read(stem + INPUT)))
                .toList();
    }

    private static Stream<String> stems() {
        try (final Stream<Path> files = Files.list(DIR)) {
            return files
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(CONFIGURATION))
                    .map(name -> name.substring(0, name.length() - CONFIGURATION.length()))
                    .sorted()
                    .toList()
                    .stream();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(final String fileName) {
        try {
            return Files.readString(DIR.resolve(fileName));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
