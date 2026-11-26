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

package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.shared.TargetSchemaSummary;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.PermissionException;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the summary of a target schema that the resource gives the Learning Flow editor's Setup tab.
///
/// The summary says whether the schema is event-logging, because the step that learns marked `Unknown` events
/// only works for event-logging. It also lists what the root element may hold, because each child of the root
/// is read as one record.
class TestLearningFlowResourceImpl {

    private static final DocRef BATCHES = new DocRef(XmlSchemaDoc.TYPE, "schema-3", "batches v1");
    // A root holding a header element beside its records
    private static final String BATCHES_XSD = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:batches:1"
                       xmlns="urn:batches:1" elementFormDefault="qualified" version="1">
              <xs:element name="Batch">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="Header" type="xs:string"/>
                    <xs:element name="Record" type="xs:string" maxOccurs="unbounded"/>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """;

    @Test
    void aSchemaIsSummarisedFromItsXsd() {
        final TargetSchemaSummary summary = resource(new MockSecurityContext()).targetSchema("schema-3");

        assertThat(summary.getName()).isEqualTo("batches v1");
        assertThat(summary.isEventLogging()).isFalse();
        assertThat(summary.getRootElement()).isEqualTo("Batch");
        assertThat(summary.getRootChildren()).containsExactly("Header", "Record");
    }

    @Test
    void aSchemaThatCannotBeReadSaysNothing() {
        assertThat(resource(new MockSecurityContext()).targetSchema("gone")).isNull();
    }

    @Test
    void aSchemaThePersonMayNotUseIsRefused() {
        final SecurityContext refused = Mockito.mock(SecurityContext.class);
        Mockito.when(refused.hasDocumentPermission(Mockito.any(), Mockito.eq(DocumentPermission.USE)))
                .thenReturn(false);

        assertThatThrownBy(() -> resource(refused).targetSchema("schema-3"))
                .isInstanceOf(PermissionException.class);
    }

    private static LearningFlowResourceImpl resource(final SecurityContext securityContext) {
        final TargetSchemas targetSchemas = new TargetSchemas() {
            @Override
            public Optional<TargetSchema> resolve(final DocRef docRef) {
                return BATCHES.getUuid().equals(docRef.getUuid())
                        ? Optional.of(new TargetSchema(BATCHES, BATCHES.getName(), "urn:batches:1",
                        "file://batches-v1.xsd", "1", "Batch", BATCHES_XSD))
                        : Optional.empty();
            }

            @Override
            public Optional<TargetSchema> latestEventLogging() {
                return Optional.empty();
            }
        };
        return new LearningFlowResourceImpl(() -> null, () -> null, () -> targetSchemas,
                () -> securityContext);
    }
}
