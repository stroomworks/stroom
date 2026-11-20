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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.security.mock.MockSecurityContext;
import stroom.shapeshifter.ai.scoring.TargetSchema;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckState;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// A Learning Flow's target schema read from the schema store (D1).
@ExtendWith(MockitoExtension.class)
class TestStoreTargetSchemas {

    private static final DocRef EVENTS_V4_REF = XmlSchemaDoc.buildDocRef()
            .uuid("befcc474-36e4-4db4-a610-bd1fe6825cb1")
            .name("event-logging v4.1.0")
            .build();
    private static final String EVENTS_V4_XSD = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="event-logging:3"
                       version="4.1.0"/>""";

    @Mock
    private XmlSchemaStore store;

    /// A document with no target (a new one where no event-logging schema was installed) is refused before a
    /// model is asked, and said to be, rather than judged against a version nobody chose.
    @Test
    void aDocumentNamingNoTargetIsRefusedWithoutReadingTheStore() {
        assertThatThrownBy(() -> targets().of(doc(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Learning flow has no target schema")
                .hasMessageContaining("AI model and instructions");
        verify(store, times(0)).readDocument(any());
    }

    @Test
    void aNamedTargetIsTheSchemasGroupNamespaceSystemIdAndVersion() {
        when(store.readDocument(EVENTS_V4_REF)).thenReturn(schema("event-logging:3", "1"));

        final TargetSchema target = targets().of(doc(EVENTS_V4_REF));

        assertThat(target).isEqualTo(new TargetSchema("EVENTS", "event-logging:3",
                "file://event-logging-v4.1.0.xsd", "4.1.0"));
    }

    /// The schema's text is read for its version once for each version of the schema document, not for
    /// every stream judged.
    @Test
    void aSchemasVersionIsReadOncePerVersionOfIt() {
        final XmlSchemaDoc schema = spy(schema("event-logging:3", "1"));
        when(store.readDocument(EVENTS_V4_REF)).thenReturn(schema);
        final StoreTargetSchemas targets = targets();
        final ShapeshifterAiDoc doc = doc(EVENTS_V4_REF);

        targets.of(doc);
        targets.of(doc);

        verify(schema, times(1)).getData();
    }

    @Test
    void aTargetThatCannotBeReadIsRefused() {
        when(store.readDocument(EVENTS_V4_REF)).thenReturn(null);

        assertThatThrownBy(() -> targets().of(doc(EVENTS_V4_REF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Learning flow")
                .hasMessageContaining("event-logging v4.1.0")
                .hasMessageContaining("cannot be read");
    }

    @Test
    void aTargetTheStoreFailsToReadIsRefused() {
        when(store.readDocument(EVENTS_V4_REF)).thenThrow(new RuntimeException("Permission denied"));

        assertThatThrownBy(() -> targets().of(doc(EVENTS_V4_REF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be read");
    }

    /// Until the questions are written from the schema (D2), they describe event-logging, so a schema in
    /// another namespace is refused before a model is asked rather than every answer failing it.
    @Test
    void aTargetOutsideEventLoggingIsRefused() {
        when(store.readDocument(EVENTS_V4_REF)).thenReturn(schema("records:2", "1"));

        assertThatThrownBy(() -> targets().of(doc(EVENTS_V4_REF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("records:2")
                .hasMessageContaining("Only event-logging schemas");
    }

    @Test
    void aTargetWithNoSystemIdIsRefused() {
        final XmlSchemaDoc schema = schema("event-logging:3", "1").copy().systemId(null).build();
        when(store.readDocument(EVENTS_V4_REF)).thenReturn(schema);

        assertThatThrownBy(() -> targets().of(doc(EVENTS_V4_REF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no system id");
    }

    private StoreTargetSchemas targets() {
        return new StoreTargetSchemas(store, new MockSecurityContext());
    }

    private static XmlSchemaDoc schema(final String namespace, final String version) {
        return XmlSchemaDoc.builder()
                .uuid(EVENTS_V4_REF.getUuid())
                .name(EVENTS_V4_REF.getName())
                .version(version)
                .schemaGroup("EVENTS")
                .namespaceURI(namespace)
                .systemId("file://event-logging-v4.1.0.xsd")
                .data(EVENTS_V4_XSD)
                .build();
    }

    private static ShapeshifterAiDoc doc(final DocRef target) {
        return ShapeshifterAiDoc.builder()
                .uuid("doc")
                .name("Learning flow")
                .targetSchema(target)
                .checks(List.of(new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0,
                        new SchemaConformanceParameters())))
                .build();
    }
}
