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

package stroom.docstore.impl.db;


import stroom.docref.DocRef;
import stroom.docstore.impl.Persistence;
import stroom.importexport.api.ByteArrayImportExportAsset;
import stroom.importexport.api.ImportExportDocument;
import stroom.test.AbstractCoreIntegrationTest;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestDBPersistence extends AbstractCoreIntegrationTest {

    private static final Charset CHARSET = StandardCharsets.UTF_8;

    @Inject
    private Persistence persistence;

    @Test
    void test() throws IOException {
        final String uuid1 = UUID.randomUUID().toString();
        final String uuid2 = UUID.randomUUID().toString();
        final DocRef docRef = new DocRef("test-type", "test-uuid", "test-name");

        // Ensure the doc doesn't exist.
        if (persistence.exists(docRef)) {
            persistence.delete(docRef);
        }

        // Create
        ImportExportDocument importExportDocument = new ImportExportDocument();
        importExportDocument.addExtAsset(new ByteArrayImportExportAsset("meta", uuid1.getBytes(CHARSET)));
        persistence.write(docRef, false, importExportDocument);

        // Exists
        assertThat(persistence.exists(docRef)).isTrue();

        // Read
        importExportDocument = persistence.read(docRef);
        assertThat(new String(importExportDocument.getExtAssetData("meta"), CHARSET)).isEqualTo(uuid1);

        // Update
        importExportDocument = new ImportExportDocument();
        importExportDocument.addExtAsset(new ByteArrayImportExportAsset("meta", uuid2.getBytes(CHARSET)));
        persistence.write(docRef, true, importExportDocument);

        // Read
        importExportDocument = persistence.read(docRef);
        assertThat(new String(importExportDocument.getExtAssetData("meta"), CHARSET)).isEqualTo(uuid2);

        // List
        final List<DocRef> refs = persistence.list(docRef.getType());
        assertThat(refs.size()).isEqualTo(1);
        assertThat(refs.get(0)).isEqualTo(docRef);

        // Delete
        persistence.delete(docRef);
    }
}
