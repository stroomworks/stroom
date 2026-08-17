/*
 * Copyright 2016-2025 Crown Copyright
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

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.pipeline.shared.CheckXsltReferencesRequest;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.XsltReferenceCheckResult;
import stroom.pipeline.shared.XsltResource;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

@AutoLogged
class XsltResourceImpl implements XsltResource {

    private final Provider<XsltStore> xsltStoreProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<XsltReferenceParser> referenceParserProvider;

    @Inject
    XsltResourceImpl(final Provider<XsltStore> xsltStoreProvider,
                     final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                     final Provider<XsltReferenceParser> referenceParserProvider) {
        this.xsltStoreProvider = xsltStoreProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.referenceParserProvider = referenceParserProvider;
    }

    @Override
    public XsltDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(xsltStoreProvider.get(), getDocRef(uuid));
    }

    @Override
    public XsltDoc update(final String uuid, final XsltDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(xsltStoreProvider.get(), doc);
    }

    @Override
    public XsltDoc create(final String name) {
        final XsltStore xsltStore = xsltStoreProvider.get();
        final DocRef docRef = xsltStore.createDocument(name);
        return xsltStore.readDocument(docRef);
    }

    @Override
    public XsltReferenceCheckResult checkReferences(final CheckXsltReferencesRequest request) {
        if (request == null || request.getDocRef() == null) {
            throw new EntityServiceException("A document must be supplied");
        }

        // Read the document even when the caller supplied the body, so the read permission is checked
        // against a real document rather than taken on trust from the request.
        final XsltDoc doc = documentResourceHelperProvider.get()
                .read(xsltStoreProvider.get(), request.getDocRef());
        if (doc == null) {
            throw new EntityServiceException("Document not found: " + request.getDocRef());
        }

        // Deliberately NOT run as the processing user, unlike the save path. Resolution here happens with
        // the caller's permissions, so a document they cannot view is reported as not found and an
        // ambiguity they cannot see is not reported at all. Wrapping this to make the answers more
        // complete would turn the panel into a way of discovering documents, and their number, by name.
        // The cost of getting this right is that the report can differ from what the runtime does: where
        // two documents share a name and only one is visible, this says "found", while the runtime sees
        // both and picks the lowest UUID. That asymmetry is accepted; disclosure is not.

        // The caller's copy wins where there is one, so an author checks what is in front of them rather
        // than what was last saved.
        final String data = request.getData() != null
                ? request.getData()
                : doc.getData();

        return XsltReferenceInfoMapper.toResult(referenceParserProvider.get().parse(data));
    }

    private DocRef getDocRef(final String uuid) {
        return DocRef.builder()
                .uuid(uuid)
                .type(XsltDoc.TYPE)
                .build();
    }
}
