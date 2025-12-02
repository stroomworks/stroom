/*
 * Copyright 2017 Crown Copyright
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

package stroom.dashboard.impl.visualisation;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.ResourceKey;
import stroom.visualisation.shared.VisualisationAsset;
import stroom.visualisation.shared.VisualisationDoc;
import stroom.visualisation.shared.VisualisationResource;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;
import java.util.Map;

@AutoLogged
class VisualisationResourceImpl implements VisualisationResource {

    private final Provider<VisualisationStore> visualisationStoreProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisualisationResourceImpl.class);

    @Inject
    VisualisationResourceImpl(final Provider<VisualisationStore> visualisationStoreProvider,
                              final Provider<DocumentResourceHelper> documentResourceHelperProvider) {
        this.visualisationStoreProvider = visualisationStoreProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
    }

    @Override
    public VisualisationDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(visualisationStoreProvider.get(), getDocRef(uuid));
    }

    @Override
    public VisualisationDoc update(final String uuid, final VisualisationDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        final List<VisualisationAsset> assets = doc.getAssets();
        if (assets != null) {
            for (final VisualisationAsset asset : assets) {
                LOGGER.error("Asset: {}", asset);
            }
        } else {
            LOGGER.error("No assets");
        }

        return documentResourceHelperProvider.get().update(visualisationStoreProvider.get(), doc);
    }

    private DocRef getDocRef(final String uuid) {
        return DocRef.builder()
                .uuid(uuid)
                .type(VisualisationDoc.TYPE)
                .build();
    }

    /**
     * To upload files into Stroom, files are first uploaded to the ImportUtil.getImportFileURL().
     * This puts the file into stroom.resource.api.ResourceStore and returns a ResourceKey.
     * When the client is ready to keep the file somewhere we call ResourceStore.getTempFile()
     * to get the file's path, then copy the file to its final destination.
     * @param uuid The UUID of the document that owns the file.
     * @param uploads The map of ID to ResourceKey so we can get the uploaded files.
     * @return TRUE if everything works, FALSE if not.
     */
    @Override
    public Boolean storeUploads(final String uuid, final Map<String, ResourceKey> uploads) {
        LOGGER.error("Storing uploads for {}", uuid);
        if (uploads != null) {
            for (final Map.Entry<String, ResourceKey> entry : uploads.entrySet()) {
                LOGGER.error("Asset doc UUID {} -> Resource Key {}", entry.getKey(), entry.getValue());
            }
        }
        return Boolean.TRUE;
    }
}
