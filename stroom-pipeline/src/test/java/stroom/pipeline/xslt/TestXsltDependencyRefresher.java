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

package stroom.pipeline.xslt;

import stroom.dictionary.shared.DictionaryDoc;
import stroom.docref.DocRef;
import stroom.docstore.api.DocDependencies;
import stroom.docstore.api.DocDependencyService;
import stroom.docstore.api.DocFinder;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.pipeline.shared.XsltDoc;
import stroom.security.mock.MockSecurityContext;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestXsltDependencyRefresher {

    private static final DocRef READER = new DocRef(XsltDoc.TYPE, "uuid-reader", "Reader");
    private static final DocRef IMPORTER = new DocRef(XsltDoc.TYPE, "uuid-importer", "Importer");
    private static final DocRef WORDS = new DocRef(DictionaryDoc.TYPE, "uuid-words", "Words");
    private static final DocRef COMMON = new DocRef(XsltDoc.TYPE, "uuid-common", "Common");

    @Mock
    private DocDependencyService docDependencyService;
    @Mock
    private XsltStore xsltStore;
    @Mock
    private DocFinder docFinder;

    private XsltDependencyRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new XsltDependencyRefresher(
                () -> docDependencyService,
                () -> xsltStore,
                () -> docFinder,
                new MockSecurityContext());
    }

    @Test
    void createdDictionary_reRecordsTheXsltsThatNameIt() {
        final DocDependencies readerDependencies = DocDependencies.of(Set.of(WORDS));
        when(docDependencyService.findSourcesByTargetName(XsltDoc.TYPE, DictionaryDoc.TYPE, Set.of("Words")))
                .thenReturn(Set.of(READER));
        when(xsltStore.getDocDependencies(READER)).thenReturn(readerDependencies);

        refresher.onChange(new EntityEvent(WORDS, EntityAction.CREATE));

        // The name cache is told first, so the re-recording cannot read the answer from before the change
        final InOrder inOrder = inOrder(docFinder, docDependencyService);
        inOrder.verify(docFinder).invalidateNames(DictionaryDoc.TYPE, Set.of("Words"));
        inOrder.verify(docDependencyService).setDependencies(READER, readerDependencies);
    }

    @Test
    void renamedXslt_looksUpBothNames() {
        final DocRef renamed = new DocRef(XsltDoc.TYPE, COMMON.getUuid(), "Common v2");
        when(docDependencyService.findSourcesByTargetName(XsltDoc.TYPE, XsltDoc.TYPE, Set.of("Common", "Common v2")))
                .thenReturn(Set.of(IMPORTER));
        when(xsltStore.getDocDependencies(IMPORTER)).thenReturn(DocDependencies.empty());

        refresher.onChange(new EntityEvent(renamed, COMMON, EntityAction.UPDATE));

        verify(docFinder).invalidateNames(XsltDoc.TYPE, Set.of("Common", "Common v2"));
        verify(docDependencyService).setDependencies(IMPORTER, DocDependencies.empty());
    }

    @Test
    void deletedDictionary_reRecordsTheXsltsThatNameIt() {
        when(docDependencyService.findSourcesByTargetName(XsltDoc.TYPE, DictionaryDoc.TYPE, Set.of("Words")))
                .thenReturn(Set.of(READER));
        when(xsltStore.getDocDependencies(READER)).thenReturn(DocDependencies.empty());

        refresher.onChange(new EntityEvent(WORDS, EntityAction.DELETE));

        verify(docDependencyService).setDependencies(READER, DocDependencies.empty());
    }

    @Test
    void contentUpdate_changesNoNameSoDoesNothing() {
        refresher.onChange(new EntityEvent(WORDS, EntityAction.UPDATE));
        // An old docRef with the same name, as for a move, is not a rename either
        refresher.onChange(new EntityEvent(WORDS, WORDS, EntityAction.UPDATE));

        verifyNoInteractions(docDependencyService, xsltStore, docFinder);
    }

    @Test
    void noXsltsNameIt_writesNothing() {
        when(docDependencyService.findSourcesByTargetName(anyString(), anyString(), anySet()))
                .thenReturn(Set.of());

        refresher.onChange(new EntityEvent(WORDS, EntityAction.CREATE));

        verify(docDependencyService, never()).setDependencies(any(), any(DocDependencies.class));
    }

    @Test
    void failureForOneXslt_doesNotStopTheOthers() {
        when(docDependencyService.findSourcesByTargetName(XsltDoc.TYPE, DictionaryDoc.TYPE, Set.of("Words")))
                .thenReturn(Set.of(READER, IMPORTER));
        when(xsltStore.getDocDependencies(READER)).thenThrow(new DocumentNotFoundException(READER));
        when(xsltStore.getDocDependencies(IMPORTER)).thenReturn(DocDependencies.empty());

        refresher.onChange(new EntityEvent(WORDS, EntityAction.CREATE));

        verify(docDependencyService).setDependencies(IMPORTER, DocDependencies.empty());
        verify(docDependencyService, never()).setDependencies(READER, DocDependencies.empty());
    }

    @Test
    void affectedNames() {
        assertThat(XsltDependencyRefresher.affectedNames(new EntityEvent(WORDS, EntityAction.CREATE)))
                .containsExactly("Words");
        assertThat(XsltDependencyRefresher.affectedNames(new EntityEvent(WORDS, EntityAction.DELETE)))
                .containsExactly("Words");
        assertThat(XsltDependencyRefresher.affectedNames(
                new EntityEvent(new DocRef(DictionaryDoc.TYPE, WORDS.getUuid(), "Words v2"),
                        WORDS,
                        EntityAction.UPDATE)))
                .containsExactly("Words v2", "Words");
        assertThat(XsltDependencyRefresher.affectedNames(new EntityEvent(WORDS, EntityAction.UPDATE)))
                .isEmpty();
        // A document event with no name gives nothing to look up
        assertThat(XsltDependencyRefresher.affectedNames(
                new EntityEvent(new DocRef(DictionaryDoc.TYPE, "uuid"), EntityAction.CREATE)))
                .isEmpty();
    }
}
