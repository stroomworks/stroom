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

package stroom.dashboard.impl.visualisation;

import stroom.docstore.api.DocumentSerialiser2;
import stroom.docstore.api.Serialiser2;
import stroom.docstore.api.Serialiser2Factory;
import stroom.importexport.api.ByteArrayImportExportAsset;
import stroom.importexport.api.ImportExportAsset;
import stroom.importexport.api.ImportExportDocument;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.string.EncodingUtil;
import stroom.visualisation.shared.VisualisationDoc;

import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Collection;

public class VisualisationSerialiser implements DocumentSerialiser2<VisualisationDoc> {

    private static final String JSON = "json";

    private final Serialiser2<VisualisationDoc> delegate;

    private final VisualisationAssetService visualisationAssetService;

    private static final LambdaLogger LOGGER =
                LambdaLoggerFactory.getLogger(VisualisationSerialiser.class);

    @Inject
    public VisualisationSerialiser(final Serialiser2Factory serialiser2Factory,
                                   final VisualisationAssetService visualisationAssetService) {
        this.delegate = serialiser2Factory.createSerialiser(VisualisationDoc.class);
        this.visualisationAssetService = visualisationAssetService;
    }

    @Override
    public VisualisationDoc read(final ImportExportDocument importExportDocument) throws IOException {
        final VisualisationDoc document = delegate.read(importExportDocument);

        final String json = EncodingUtil.asString(importExportDocument.getExtAssetData(JSON));
        if (json != null) {
            document.setSettings(json);
        }
        return document;
    }

    @Override
    public ImportExportDocument write(final VisualisationDoc document) throws IOException {
        final String settings = document.getSettings();
        document.setSettings(null);

        final ImportExportDocument importExportDocument = delegate.write(document);

        if (settings != null) {
            importExportDocument.addExtAsset(new ByteArrayImportExportAsset(JSON, EncodingUtil.asBytes(settings)));
            document.setSettings(settings);
        }

        // Get all the assets to be exported to sub-paths
        final Collection<ImportExportAsset> assets = visualisationAssetService.getAssetsForExport(document.getUuid());
        for (final ImportExportAsset asset : assets) {
            LOGGER.info("Exporting asset {}", asset);
            importExportDocument.addPathAsset(asset);
        }
        return importExportDocument;
    }
}
