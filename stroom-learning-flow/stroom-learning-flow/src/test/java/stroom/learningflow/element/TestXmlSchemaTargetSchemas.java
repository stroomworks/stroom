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

/// Tests how the target schema, the one schema a document's translations must write, is read from the
/// `XMLSchema` document the Learning Flow document names.
class TestXmlSchemaTargetSchemas {

    private final NodeFixture node = new NodeFixture();

    @Test
    void aSchemaDescribesItselfFromItsXsdRatherThanFromItsName() {
        // The version and the root element are read from the XSD, so a schema installed under another name
        // is still described correctly. The namespace and system id come from the XMLSchema document, because
        // those are what xsi:schemaLocation and the validator use.
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
        // The EVENTS schema group holds several versions of event-logging, but a document writes exactly one.
        // A system id is unique in Stroom, so the namespace and system id together identify a single schema.
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
        // A document part way through its set-up, or one whose schema has been deleted. The readiness check
        // reports both in words, so neither may throw while a stream is being judged.
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
        // Versions are compared part by part as numbers, so "3.10.0" sorts after "3.9.0", which it would not as
        // text. A version that cannot be parsed sorts first, so a schema that does not declare a usable version
        // is never chosen as the default.
        assertThat(XmlSchemaTargetSchemas.compareVersions("4.1.0", "3.4.2")).isPositive();
        assertThat(XmlSchemaTargetSchemas.compareVersions("3.10.0", "3.9.0")).isPositive();
        assertThat(XmlSchemaTargetSchemas.compareVersions("3.0.0", "3")).isZero();
        assertThat(XmlSchemaTargetSchemas.compareVersions("3.0.1", "3")).isPositive();
        assertThat(XmlSchemaTargetSchemas.compareVersions(null, "1.0.0")).isNegative();
        assertThat(XmlSchemaTargetSchemas.compareVersions("beta", "1.0.0")).isNegative();
    }

    @Test
    void everythingAboutATargetComesFromOnePlace() {
        // The prompts, the target checks and the conformance check are all meant to use this one resolved
        // schema, so that changing the setting changes all three. This checks that the resolved schema carries
        // the XMLSchema document's reference and name.
        final Optional<TargetSchema> resolved = node.targetSchemas().resolve(node.eventLoggingSchemaRef());

        assertThat(resolved).get().satisfies(target -> {
            assertThat(target.docRef()).isEqualTo(node.eventLoggingSchemaRef());
            assertThat(target.name()).isEqualTo("event-logging v3.0.0");
        });
    }
}
