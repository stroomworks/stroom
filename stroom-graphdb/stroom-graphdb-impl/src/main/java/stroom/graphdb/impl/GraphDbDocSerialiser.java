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

package stroom.graphdb.impl;

import stroom.docstore.api.DocumentSerialiser2;
import stroom.docstore.api.Serialiser2;
import stroom.docstore.api.Serialiser2Factory;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.importexport.api.ImportExportDocument;

import jakarta.inject.Inject;

import java.io.IOException;

/**
 * JSON (de)serialisation of {@link GraphDbDoc} for import/export and the document store, delegating to the
 * generic {@link Serialiser2} machinery every doc type uses.
 *
 * <p><b>Null status:</b> neither method accepts or returns null.
 */
public class GraphDbDocSerialiser implements DocumentSerialiser2<GraphDbDoc> {

    private final Serialiser2<GraphDbDoc> delegate;

    /**
     * <b>Preconditions:</b> {@code serialiser2Factory} is not null.
     */
    @Inject
    GraphDbDocSerialiser(final Serialiser2Factory serialiser2Factory) {
        this.delegate = serialiser2Factory.createSerialiser(GraphDbDoc.class);
    }

    @Override
    public GraphDbDoc read(final ImportExportDocument importExportDocument) throws IOException {
        return delegate.read(importExportDocument);
    }

    @Override
    public ImportExportDocument write(final GraphDbDoc document) throws IOException {
        return delegate.write(document);
    }
}
