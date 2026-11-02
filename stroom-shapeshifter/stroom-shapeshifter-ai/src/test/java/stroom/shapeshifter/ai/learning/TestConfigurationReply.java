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

package stroom.shapeshifter.ai.learning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestConfigurationReply {

    private static final String DOCUMENT = "<?xml version=\"1.1\"?>\n<dataSplitter version=\"3.0\"/>";

    @Test
    void takesTheOneFencedBlock() {
        final String reply = "Here is the configuration:\n\n```xml\n" + DOCUMENT + "\n```\n\nIt splits on newlines.";

        assertThat(ConfigurationReply.configuration(reply)).contains(DOCUMENT);
    }

    @Test
    void takesAnUntaggedFencedBlock() {
        assertThat(ConfigurationReply.configuration("```\n" + DOCUMENT + "\n```")).contains(DOCUMENT);
    }

    @Test
    void takesABareDocument() {
        assertThat(ConfigurationReply.configuration("\n" + DOCUMENT + "\n")).contains(DOCUMENT);
    }

    @Test
    void refusesTwoBlocks() {
        final String reply = "```xml\n" + DOCUMENT + "\n```\nor alternatively\n```xml\n" + DOCUMENT + "\n```";

        assertThat(ConfigurationReply.configuration(reply)).isEmpty();
    }

    @Test
    void refusesAnUnclosedFence() {
        assertThat(ConfigurationReply.configuration("```xml\n" + DOCUMENT)).isEmpty();
    }

    @Test
    void refusesProse() {
        assertThat(ConfigurationReply.configuration("I cannot determine the format from this sample.")).isEmpty();
        assertThat(ConfigurationReply.configuration("")).isEmpty();
        assertThat(ConfigurationReply.configuration(null)).isEmpty();
    }
}
