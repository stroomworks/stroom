/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.pathways.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.pathways.shared.TracesDoc;
import stroom.pathways.shared.TracesDocResource;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.FetchWithUuid;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

@AutoLogged
class TracesDocResourceImpl implements TracesDocResource, FetchWithUuid<TracesDoc> {

    private final Provider<TracesDocStore> tracesDocStoreProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;

    @Inject
    TracesDocResourceImpl(final Provider<TracesDocStore> tracesDocStoreProvider,
                          final Provider<DocumentResourceHelper> documentResourceHelperProvider) {
        this.tracesDocStoreProvider = tracesDocStoreProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
    }

    @Override
    public TracesDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(tracesDocStoreProvider.get(), getDocRef(uuid));
    }

    @Override
    public TracesDoc update(final String uuid, final TracesDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(tracesDocStoreProvider.get(), doc);
    }

    private DocRef getDocRef(final String uuid) {
        return DocRef.builder()
                .uuid(uuid)
                .type(TracesDoc.TYPE)
                .build();
    }
}
