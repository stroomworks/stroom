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

package stroom.pipelinefactory.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.Store;
import stroom.docstore.api.StoreFactory;
import stroom.importexport.api.ImportExportDocument;
import stroom.importexport.shared.ImportSettings;
import stroom.importexport.shared.ImportState;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.security.api.SecurityContext;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.Message;
import stroom.util.shared.Severity;
import stroom.util.shared.UserRef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestPipelineFactoryStoreImpl {

    private static final DocRef DOC_REF = PipelineFactoryDoc.buildDocRef().uuid("factory").name("raw").build();

    @Mock
    private StoreFactory storeFactory;
    @Mock
    private Store<PipelineFactoryDoc> store;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private PipelineFactorySerialiser serialiser;
    @Mock
    private ImportExportDocument importExportDocument;

    private final InMemoryCombinations combinations = new InMemoryCombinations();
    private PipelineFactoryStoreImpl factoryStore;

    @BeforeEach
    void setUp() {
        when(storeFactory.<PipelineFactoryDoc, PipelineFactoryDoc.Builder>createStore(
                any(), any(), any(), any(), any()))
                .thenReturn(store);
        factoryStore = new PipelineFactoryStoreImpl(storeFactory, securityContext, serialiser, () -> combinations);
    }

    @Test
    void aFactoryWithProblemsIsNotSaved() {
        // A119: what the job could not run, or whose key would make a pipeline per stream, goes no further
        // than the save.
        final PipelineFactoryDoc doc = factory().key(List.of("Feed", "Type", "Id")).build();

        assertThatThrownBy(() -> factoryStore.writeDocument(doc))
                .isInstanceOf(EntityServiceException.class)
                .hasMessage("Pipeline Factory 'raw' cannot be saved: "
                            + "'Id' cannot be a key field: a key may name only Feed, Type, Pipeline");
        verify(store, never()).writeDocument(any());
    }

    @Test
    void aFactoryWithoutProblemsIsSaved() {
        final PipelineFactoryDoc doc = factory().build();
        when(store.getType()).thenReturn(PipelineFactoryDoc.TYPE);
        when(securityContext.hasDocumentPermission(any(), any())).thenReturn(true);
        when(store.writeDocument(doc)).thenReturn(doc);

        assertThat(factoryStore.writeDocument(doc)).isSameAs(doc);
    }

    @Test
    void aFactoryWithProblemsIsNotImportedAndTheImportSaysWhy() throws IOException {
        final PipelineFactoryDoc incoming = factory().key(List.of("Feed", "Type", "Create Time")).build();
        when(serialiser.read(importExportDocument)).thenReturn(incoming);
        final ImportState importState = new ImportState(DOC_REF, "raw");

        final DocRef imported = factoryStore.importDocument(
                DOC_REF, importExportDocument, importState, ImportSettings.auto());

        assertThat(imported).isEqualTo(DOC_REF);
        assertThat(importState.getMessageList()).extracting(Message::getSeverity, Message::getMessage)
                .containsExactly(tuple(Severity.ERROR,
                        "Pipeline Factory 'raw' cannot be saved: "
                        + "'Create Time' cannot be a key field: a key may name only Feed, Type, Pipeline"));
        verify(store, never()).importDocument(any(), any(), any(), any());
    }

    @Test
    void aFactoryWithoutProblemsIsImported() throws IOException {
        when(serialiser.read(importExportDocument)).thenReturn(factory().build());
        final ImportState importState = new ImportState(DOC_REF, "raw");
        final ImportSettings settings = ImportSettings.auto();
        when(store.importDocument(DOC_REF, importExportDocument, importState, settings)).thenReturn(DOC_REF);

        assertThat(factoryStore.importDocument(DOC_REF, importExportDocument, importState, settings))
                .isEqualTo(DOC_REF);
        assertThat(importState.getMessageList()).isEmpty();
    }

    @Test
    void aDeletedFactoryTakesItsRecordWithIt() {
        combinations.met(DOC_REF.getUuid(), Combination.ofFeedAndType("SYSLOG", "Raw Events"));
        combinations.lastMetaId(DOC_REF.getUuid(), 40L);
        when(securityContext.hasDocumentPermission(any(), any())).thenReturn(true);

        factoryStore.deleteDocument(DOC_REF);

        verify(store).deleteDocument(DOC_REF);
        assertThat(combinations.list(DOC_REF.getUuid())).isEmpty();
        assertThat(combinations.lastMetaId(DOC_REF.getUuid())).isZero();
    }

    private static PipelineFactoryDoc.Builder factory() {
        return PipelineFactoryDoc.builder()
                .uuid(DOC_REF.getUuid())
                .name(DOC_REF.getName())
                .enabled(true)
                .template(PipelineDoc.buildDocRef().uuid("template").build())
                .runAsUser(UserRef.forUserUuid("run-as"));
    }
}
