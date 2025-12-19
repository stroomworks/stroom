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

package stroom.importexport.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents data that is being imported or exported from a Stroom document.
 * An asset can be exported in two ways:
 * <ul>
 *     <li>Within a file with an extension such as .meta. This is an Ext
 *         asset.</li>
 *     <li>Within a file where the key forms a path to the asset,
 *         under the document itself. This is a Path asset.</li>
 * </ul>
 * Use the appropriate methods to add and get such assets from this object.
 */
public class ImportExportDocument {

    private final List<ImportExportAsset> extAssets = new ArrayList<>();

    private final List<ImportExportAsset> pathAssets = new ArrayList<>();

    public void addExtAsset(final ImportExportAsset asset) {
        extAssets.add(asset);
    }

    /**
     * @return The assets that should be keyed by extension, as an unmodifiable collection.
     */
    public Collection<ImportExportAsset> getExtAssets() {
        return Collections.unmodifiableList(extAssets);
    }

    public void addPathAsset(final ImportExportAsset asset) {
        pathAssets.add(asset);
    }

    /**
     * @return The assets that should be keyed by path, as an unmodifiable collection.
     */
    public Collection<ImportExportAsset> getPathAssets() {
        return Collections.unmodifiableList(pathAssets);
    }

    /**
     * Temporary method to convert this to old data format during conversion.
     * Uncertain whether this deletes assets so delete this method ASAP.
     * TODO DELETE METHOD
     */
    public Map<String, byte[]> toDataMap() throws IOException {
        final Map<String, byte[]> dataMap = new HashMap<>();
        for (final ImportExportAsset asset : extAssets) {

            final ByteArrayOutputStream bostr = new ByteArrayOutputStream();
            try (final InputStream istr = asset.getInputStream()) {
                if (istr != null) {
                    istr.transferTo(bostr);
                }
            }

            dataMap.put(asset.getKey(), bostr.toByteArray());
        }

        return dataMap;
    }

}
