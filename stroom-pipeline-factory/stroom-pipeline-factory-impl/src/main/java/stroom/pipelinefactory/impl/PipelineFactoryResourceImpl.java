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
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.pipelinefactory.shared.PipelineFactoryCombination;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryResource;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;
import java.util.Map;

/// The Pipeline Factory document's resource: fetch and save, the save going through the store's
/// checks; and what the factory has met, through [PipelineFactoryService].
@AutoLogged
public class PipelineFactoryResourceImpl implements PipelineFactoryResource {

    private final Provider<PipelineFactoryStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<PipelineFactoryService> serviceProvider;

    @Inject
    PipelineFactoryResourceImpl(final Provider<PipelineFactoryStore> storeProvider,
                                final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                                final Provider<PipelineFactoryService> serviceProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.serviceProvider = serviceProvider;
    }

    @Override
    public PipelineFactoryDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(storeProvider.get(), docRefOf(uuid));
    }

    @Override
    public PipelineFactoryDoc update(final String uuid, final PipelineFactoryDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(storeProvider.get(), doc);
    }

    @Override
    public List<PipelineFactoryCombination> combinations(final String uuid) {
        return serviceProvider.get().combinations(uuid);
    }

    @Override
    public Boolean forget(final String uuid, final Map<String, String> values) {
        return serviceProvider.get().forget(uuid, values);
    }

    private static DocRef docRefOf(final String uuid) {
        return PipelineFactoryDoc.buildDocRef().uuid(uuid).build();
    }
}
