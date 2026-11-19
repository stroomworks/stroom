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

package stroom.shapeshifter.ai.scoring;

import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The target schema of D1: what the schema filter is constrained to, and the root the questions ask for.
class TestTargetSchema {

    private static final TargetSchema EVENTS_V4 = new TargetSchema("EVENTS", "event-logging:3",
            "file://event-logging-v4.1.0.xsd", "4.1.0");

    /// The default target's root is exactly what the questions asked for before D1, so that a document saved
    /// then, which reads as v3.0.0, is asked the same question it always was.
    @Test
    void theDefaultRootIsTheOneTheQuestionsAlwaysAskedFor() {
        assertThat(TargetSchema.DEFAULT.eventsOpen()).isEqualTo(
                "<Events xmlns=\"event-logging:3\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:schemaLocation=\"event-logging:3 file://event-logging-v3.0.0.xsd\" "
                + "Version=\"3.0.0\">");
    }

    @Test
    void aNamedTargetsRootNamesItsLocationAndVersion() {
        assertThat(EVENTS_V4.eventsOpen())
                .contains("xsi:schemaLocation=\"event-logging:3 file://event-logging-v4.1.0.xsd\"")
                .contains("Version=\"4.1.0\"")
                .doesNotContain("v3.0.0");
    }

    @Test
    void aSchemaDeclaringNoVersionGivesNoVersionAttribute() {
        final TargetSchema unversioned = new TargetSchema("EVENTS", "event-logging:3",
                "file://event-logging-v4.1.0.xsd", null);

        assertThat(unversioned.eventsOpen()).doesNotContain("Version=");
    }

    /// The constraint is one schema, not the group: the EVENTS group holds every event-logging version, all
    /// in one namespace, so only the system id tells them apart.
    @Test
    void theCriteriaNameOneVersion() {
        final FindXMLSchemaCriteria criteria = EVENTS_V4.criteria();

        assertThat(criteria.getSchemaGroup()).isEqualTo("EVENTS");
        assertThat(criteria.getNamespaceURI()).isEqualTo("event-logging:3");
        assertThat(criteria.getSystemId()).isEqualTo("file://event-logging-v4.1.0.xsd");
    }

    @Test
    void aSchemasVersionIsReadFromItsRoot() {
        final String xsd = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="event-logging:3"
                           version=" 4.1.0 " id="event-logging-v4.1.0">
                  <xs:element name="Events"/>
                </xs:schema>""";

        assertThat(TargetSchema.versionOf(xsd)).contains("4.1.0");
    }

    @Test
    void aSchemaWithNoVersionOrNoTextHasNone() {
        assertThat(TargetSchema.versionOf("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>")).isEmpty();
        assertThat(TargetSchema.versionOf("<schema version=\"1.0\"/>"))
                .describedAs("a root that is not an XML Schema's")
                .isEmpty();
        assertThat(TargetSchema.versionOf("not xml")).isEmpty();
        assertThat(TargetSchema.versionOf(null)).isEmpty();
        assertThat(TargetSchema.versionOf(" ")).isEmpty();
    }

    @Test
    void versionsAreOrderedByTheirNumbers() {
        assertThat(TargetSchema.compareVersions("4.1.0", "3.10.0")).isPositive();
        assertThat(TargetSchema.compareVersions("3.10.0", "3.4.2")).isPositive();
        assertThat(TargetSchema.compareVersions("3.0.0", "4.0.0")).isNegative();
        assertThat(TargetSchema.compareVersions("4.1", "4.1.0")).isZero();
        assertThat(TargetSchema.compareVersions(" 4.1.0 ", "4.1.0")).isZero();
    }

    @Test
    void aVersionThatIsNotNumbersIsComparedAsText() {
        assertThat(TargetSchema.compareVersions("4.1.0-beta", "4.1.0-alpha")).isPositive();
        assertThat(TargetSchema.compareVersions("4.x", "4.1"))
                .describedAs("text against a number, compared as text, the same either way round")
                .isEqualTo(-TargetSchema.compareVersions("4.1", "4.x"));
    }

    @Test
    void noVersionIsTheEarliest() {
        assertThat(TargetSchema.compareVersions(null, "1.0")).isNegative();
        assertThat(TargetSchema.compareVersions("1.0", null)).isPositive();
        assertThat(TargetSchema.compareVersions(null, null)).isZero();
    }
}
