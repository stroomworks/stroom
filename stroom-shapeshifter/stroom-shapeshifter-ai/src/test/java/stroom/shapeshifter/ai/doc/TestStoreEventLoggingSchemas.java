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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.security.mock.MockSecurityContext;
import stroom.util.shared.ResultPage;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// The event-logging schemas installed, as a Learning Flow's target schema is chosen from them (D1).
@ExtendWith(MockitoExtension.class)
class TestStoreEventLoggingSchemas {

    private static final XmlSchemaDoc V3 = schema("4fe14042", "event-logging v3.0.0", "3.0.0", "3.0.0");
    private static final XmlSchemaDoc V3_10 = schema("aaaa0310", "event-logging v3.10.0", "3.10.0", "3.10.0");
    private static final XmlSchemaDoc V4 = schema("befcc474", "event-logging v4.1.0", "4.1.0", "4.1.0");

    @Mock
    private XmlSchemaStore store;

    /// By the version the schema declares, compared as numbers: 4.1.0 is later than 3.10.0, which a
    /// comparison of the text would get wrong.
    @Test
    void theLatestIsTheHighestVersionDeclared() {
        assertThat(StoreEventLoggingSchemas.latest(List.of(V3_10, V4, V3))).contains(docRef(V4));
        assertThat(StoreEventLoggingSchemas.latest(List.of(V3, V3_10))).contains(docRef(V3_10));
    }

    @Test
    void aSchemaDeclaringNoVersionIsTheEarliest() {
        final XmlSchemaDoc unversioned = V4.copy().uuid("unversioned").name("zzz")
                .data("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>").build();

        assertThat(StoreEventLoggingSchemas.latest(List.of(unversioned, V3))).contains(docRef(V3));
    }

    /// Two copies of one version: the same one is chosen every time, whatever order the store lists them in.
    @Test
    void twoOfOneVersionAreChosenBetweenByName() {
        final XmlSchemaDoc copy = V4.copy().uuid("copy").name("event-logging v4.1.0 (copy)").build();

        assertThat(StoreEventLoggingSchemas.latest(List.of(V4, copy))).contains(docRef(copy));
        assertThat(StoreEventLoggingSchemas.latest(List.of(copy, V4))).contains(docRef(copy));
    }

    @Test
    void noSchemasHaveNoLatest() {
        assertThat(StoreEventLoggingSchemas.latest(List.of())).isEmpty();
    }

    @Test
    void theLatestIsLookedForInTheEventLoggingNamespace() {
        when(store.find(any())).thenReturn(new ResultPage<>(List.of(V3, V4)));

        assertThat(schemas().latest()).contains(docRef(V4));

        final ArgumentCaptor<FindXMLSchemaCriteria> criteria = ArgumentCaptor.forClass(FindXMLSchemaCriteria.class);
        verify(store).find(criteria.capture());
        assertThat(criteria.getValue().getNamespaceURI()).isEqualTo("event-logging:3");
        assertThat(criteria.getValue().getSystemId()).isNull();
    }

    @Test
    void v3IsFoundByItsSystemIdAndRemembered() {
        when(store.find(any())).thenReturn(new ResultPage<>(List.of(V3)));
        final StoreEventLoggingSchemas schemas = schemas();

        assertThat(schemas.v3()).contains(docRef(V3));
        assertThat(schemas.v3()).contains(docRef(V3));

        final ArgumentCaptor<FindXMLSchemaCriteria> criteria = ArgumentCaptor.forClass(FindXMLSchemaCriteria.class);
        verify(store, times(1)).find(criteria.capture());
        assertThat(criteria.getValue().getSystemId()).isEqualTo("file://event-logging-v3.0.0.xsd");
    }

    @Test
    void noV3InstalledIsEmpty() {
        when(store.find(any())).thenReturn(new ResultPage<>(List.of()));

        assertThat(schemas().v3()).isEmpty();
    }

    /// A store that fails is no schema found, which the Learning Flow's readiness says, not a failure to make
    /// or read the Learning Flow.
    @Test
    void aStoreThatFailsFindsNone() {
        when(store.find(any())).thenThrow(new RuntimeException("Database is down"));

        assertThat(schemas().latest()).isEmpty();
    }

    private StoreEventLoggingSchemas schemas() {
        return new StoreEventLoggingSchemas(store, new MockSecurityContext());
    }

    private static XmlSchemaDoc schema(final String uuid,
                                       final String name,
                                       final String fileVersion,
                                       final String declared) {
        return XmlSchemaDoc.builder()
                .uuid(uuid)
                .name(name)
                .schemaGroup("EVENTS")
                .namespaceURI("event-logging:3")
                .systemId("file://event-logging-v" + fileVersion + ".xsd")
                .data("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" version=\"" + declared + "\"/>")
                .build();
    }

    private static DocRef docRef(final XmlSchemaDoc schema) {
        return XmlSchemaDoc.buildDocRef().uuid(schema.getUuid()).name(schema.getName()).build();
    }
}
