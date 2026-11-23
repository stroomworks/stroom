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

package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.learningflow.extraction.NodeFixture;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Task 7, decision D1 option A: the one schema a document's translations must write, read from the
/// `XMLSchema` document it names.
class TestXmlSchemaTargetSchemas {

    private final NodeFixture node = new NodeFixture();

    @Test
    void aSchemaDescribesItselfFromItsXsdRatherThanFromItsName() {
        // The version and the root element come out of the XSD, so a schema installed under another name
        // still says what it is; the namespace and system id are the document's, since that is what
        // xsi:schemaLocation and the validator go by.
        final TargetSchema target = node.targetSchemas().resolve(node.eventLoggingSchemaRef()).orElseThrow();

        assertThat(target.namespaceURI()).isEqualTo(TargetSchema.EVENT_LOGGING);
        assertThat(target.systemId()).isEqualTo("file://event-logging-v3.0.0.xsd");
        assertThat(target.version()).describedAs("the version the XSD declares").isEqualTo("3.0.0");
        assertThat(target.rootElement()).describedAs("event-logging declares one global element")
                .isEqualTo("Events");
        assertThat(target.isEventLogging())
                .describedAs("so that the guidance written by hand for event-logging still applies")
                .isTrue();
        assertThat(target.schemaLocation()).isEqualTo("event-logging:3 file://event-logging-v3.0.0.xsd");
        assertThat(target.text()).contains("<xs:schema");
    }

    @Test
    void theCriteriaPickOutOneVersionRatherThanAWholeGroup() {
        // The point of decision D1: EVENTS holds seven versions of event-logging, and a document writes
        // one of them. A system id is unique in Stroom, so the namespace and it name a single document.
        final TargetSchema target = node.targetSchemas().resolve(node.eventLoggingSchemaRef()).orElseThrow();

        assertThat(target.criteria().getNamespaceURI()).isEqualTo(TargetSchema.EVENT_LOGGING);
        assertThat(target.criteria().getSystemId()).isEqualTo("file://event-logging-v3.0.0.xsd");
        assertThat(target.criteria().getSchemaGroup())
                .describedAs("the group is what the setting replaces, so it is not part of the criteria")
                .isNull();
    }

    @Test
    void aNewDocumentStartsWithTheLatestEventLoggingInstalled() {
        assertThat(node.targetSchemas().latestEventLogging())
                .get()
                .extracting(TargetSchema::version)
                .isEqualTo("3.0.0");
    }

    @Test
    void aTargetThatIsNotSetOrHasGoneIsEmptyRatherThanAFailure() {
        // A document part way through its setup, and a document whose schema somebody deleted. Both are
        // for the readiness check to say in words; neither may throw where a stream is being judged.
        final TargetSchemas targetSchemas = node.targetSchemas();

        assertThat(targetSchemas.resolve(null)).isEmpty();
        assertThat(targetSchemas.resolve(new DocRef(XmlSchemaDoc.TYPE, "not-a-schema", "Gone"))).isEmpty();
        assertThat(targetSchemas.of(LearningFlowDoc.builder().uuid("d").name("n").build()))
                .describedAs("a document that names none")
                .isEmpty();
        assertThat(targetSchemas.of(null)).isEmpty();
    }

    @Test
    void aVersionIsOrderedAsNumbersSoThatFourBeatsThirtyFour() {
        // "4.1.0" after "3.4.2" as numbers, which as text it would not be; and a version that does not
        // read sorts first, so that a schema which does not say what it is never wins the default.
        assertThat(XmlSchemaTargetSchemas.compareVersions("4.1.0", "3.4.2")).isPositive();
        assertThat(XmlSchemaTargetSchemas.compareVersions("3.10.0", "3.9.0")).isPositive();
        assertThat(XmlSchemaTargetSchemas.compareVersions("3.0.0", "3")).isZero();
        assertThat(XmlSchemaTargetSchemas.compareVersions("3.0.1", "3")).isPositive();
        assertThat(XmlSchemaTargetSchemas.compareVersions(null, "1.0.0")).isNegative();
        assertThat(XmlSchemaTargetSchemas.compareVersions("beta", "1.0.0")).isNegative();
    }

    @Test
    void everythingAboutATargetComesFromOnePlace() {
        // The acceptance criterion behind decision D1: the prompts, the target checks and the conformance
        // check are given the same resolved schema, so that changing the setting changes all three.
        final Optional<TargetSchema> resolved = node.targetSchemas().resolve(node.eventLoggingSchemaRef());

        assertThat(resolved).get().satisfies(target -> {
            assertThat(target.docRef()).isEqualTo(node.eventLoggingSchemaRef());
            assertThat(target.name()).isEqualTo("event-logging v3.0.0");
        });
    }
}
