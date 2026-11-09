/*
 * Copyright 2020 Crown Copyright
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
import stroom.security.api.SecurityContext;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

@AutoLogged
class XsltResourceImpl implements XsltResource {

    private final Provider<XsltStore> xsltStoreProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<XsltReferenceParser> referenceParserProvider;
    private final SecurityContext securityContext;

    @Inject
    XsltResourceImpl(final Provider<XsltStore> xsltStoreProvider,
                     final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                     final Provider<XsltReferenceParser> referenceParserProvider,
                     final SecurityContext securityContext) {
        this.xsltStoreProvider = xsltStoreProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.referenceParserProvider = referenceParserProvider;
        this.securityContext = securityContext;
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

        // Resolved twice. The report itself is resolved with the caller's permissions, so a document they
        // cannot view is never named: it is reported as not found, and a match they cannot see is not
        // listed. Resolving as the processing user as well, as the runtime and the recorded dependencies do,
        // adds one fact and no more: that a name also matches documents the caller cannot view, and so is
        // ambiguous at runtime even though the caller sees one match or none. The report says so without
        // saying how many such documents there are (see XsltReferenceInfoMapper). That much is already
        // disclosed by the runtime's error ("Found 2 XSLTs named ...") and by the explorer, which flags the
        // XSLT; XsltStoreImpl.getDependencyRemapFunction explains why that is acceptable.
        //
        // A document the caller cannot view that a name resolves to unambiguously is still reported as not
        // found. Saying otherwise would reveal that a document by that name exists.

        // The caller's copy wins where there is one, so an author checks what is in front of them rather
        // than what was last saved.
        final String data = request.getData() != null
                ? request.getData()
                : doc.getData();

        final XsltReferenceParser parser = referenceParserProvider.get();
        final XsltReferences asUser = parser.parse(data);
        final XsltReferences asProcessingUser = securityContext.asProcessingUserResult(() -> parser.parse(data));
        return XsltReferenceInfoMapper.toResult(asUser, asProcessingUser);
    }

    private DocRef getDocRef(final String uuid) {
        return DocRef.builder()
                .uuid(uuid)
                .type(XsltDoc.TYPE)
                .build();
    }
}
