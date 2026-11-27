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

package stroom.learningflow.scoring;

import stroom.docref.DocRef;
import stroom.learningflow.extraction.NodeFixture;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.TargetSchema;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the conformance check validates against the one schema version the document names as its target,
/// not against every version in a schema group.
///
/// A document saved before the target schema setting existed names no target, so it is still validated against
/// the schema group its check was configured with, and behaves exactly as it did.
class TestConformanceAgainstTheTargetSchema {

    private static final String EVENTS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Events xmlns="event-logging:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
              <Event>
                <EventTime><TimeCreated>2020-06-17T08:00:00.000Z</TimeCreated></EventTime>
                <EventSource><System><Name>Door</Name><Environment>Test</Environment></System>
                  <Generator>CSV</Generator><Device><Name>d</Name></Device><User><Id>jim</Id></User></EventSource>
                <EventDetail><TypeId>logon</TypeId><Authenticate><Action>Logon</Action><User><Id>jim</Id></User>
                  </Authenticate></EventDetail>
              </Event>
            </Events>
            """;

    private final NodeFixture node = new NodeFixture();

    @Test
    void aDocumentThatNamesItsTargetIsJudgedAgainstThatVersionAlone() {
        final TargetSchema target = node.targetSchemas().resolve(node.eventLoggingSchemaRef()).orElseThrow();

        final Score score = node.schemaConformanceScorer()
                .score(null, transform(), target)
                .orElseThrow();

        assertThat(score.value()).isEqualTo(1.0);
    }

    @Test
    void aDocumentThatNamesNoTargetIsJudgedAgainstItsGroup() {
        // A document saved before the setting existed has no target, so it is judged against the schema group
        // its check carries.
        final Score score = node.schemaConformanceScorer()
                .score(null, transform(), null)
                .orElseThrow();

        assertThat(score.value()).isEqualTo(1.0);
    }

    @Test
    void theTargetIsWhatIsUsedRatherThanTheGroupTheCheckCarries() {
        // This shows the target really takes precedence: a target naming a version this node does not have
        // finds no schema to validate against, even though the group would have found one.
        final TargetSchema missing = new TargetSchema(
                new DocRef(XmlSchemaDoc.TYPE, "schema-9", "event-logging v9.9.9"),
                "event-logging v9.9.9",
                TargetSchema.EVENT_LOGGING,
                "file://event-logging-v9.9.9.xsd",
                "9.9.9",
                "Events",
                "<xs:schema/>");

        final Score score = node.schemaConformanceScorer()
                .score(null, transform(), missing)
                .orElseThrow();

        assertThat(score.value())
                .describedAs("judged against a schema that is not installed, so nothing validates")
                .isEqualTo(0.0);
    }

    private static Attempted transform() {
        return new Attempted("XSLTFilter", false, "<a/>", new StepResult(EVENTS, List.of()));
    }
}
