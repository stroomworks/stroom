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

import stroom.shapeshifter.ai.extraction.Compilation.Rejected;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compile gate on the kinds of configuration a model is most likely to hand back wrongly. Each must be
 * rejected with a diagnostic that says what to change, since that diagnostic is the feedback the next
 * attempt is built on.
 */
class TestDataSplitterCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestDataSplitterCompiler.class);

    private static final NodeFixture FIXTURE = new NodeFixture();

    @Test
    void rejectsMalformedXmlOnce() {
        final Compilation compilation = FIXTURE.compiler().compile("""
                <?xml version="1.1" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
                  <split delimiter="\\n">
                    <group value="$1">
                  </split>
                </dataSplitter>
                """);

        LOGGER.info("{}", compilation);
        assertThat(compilation).isInstanceOf(Rejected.class);
        assertThat(compilation.diagnostics()).hasSize(1);
        assertThat(compilation.diagnostics().getFirst().getSeverity()).isEqualTo(Severity.FATAL_ERROR);
        assertThat(compilation.diagnostics().getFirst().getMessage()).contains("</group>");
    }

    @Test
    void rejectsAConfigurationWithoutASchemaLocationAndSaysWhichToUse() {
        final Compilation compilation = FIXTURE.compiler().compile("""
                <?xml version="1.1" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <split delimiter="\\n">
                    <group value="$1">
                      <data value="$1"/>
                    </group>
                  </split>
                </dataSplitter>
                """);

        LOGGER.info("{}", compilation);
        assertThat(compilation).isInstanceOf(Rejected.class);
        assertThat(compilation.diagnostics().getFirst().getMessage())
                .contains("file://data-splitter-v3.0.xsd");
    }

    @Test
    void rejectsAConfigurationThatBreaksTheSchema() {
        final Compilation compilation = FIXTURE.compiler().compile("""
                <?xml version="1.1" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
                  <split>
                    <group value="$1">
                      <data value="$1"/>
                    </group>
                  </split>
                </dataSplitter>
                """);

        LOGGER.info("{}", compilation);
        assertThat(compilation).isInstanceOf(Rejected.class);
        assertThat(compilation.diagnostics().getFirst().getMessage()).contains("'delimiter'");
    }
}
