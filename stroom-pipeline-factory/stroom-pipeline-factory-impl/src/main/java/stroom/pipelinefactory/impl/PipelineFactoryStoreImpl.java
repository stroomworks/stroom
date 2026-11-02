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
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.importexport.api.ImportExportDocument;
import stroom.importexport.shared.ImportSettings;
import stroom.importexport.shared.ImportState;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.security.api.SecurityContext;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/// The store of Pipeline Factory documents. It refuses a factory with problems, whether it is saved or
/// imported (A119): a factory the job cannot run, or one whose key could make a pipeline per stream,
/// must not get as far as the job.
@Singleton
public class PipelineFactoryStoreImpl
        extends AbstractDocumentStore<PipelineFactoryDoc>
        implements PipelineFactoryStore {

    private final PipelineFactorySerialiser serialiser;
    private final Provider<Combinations> combinationsProvider;

    @Inject
    PipelineFactoryStoreImpl(final StoreFactory storeFactory,
                             final SecurityContext securityContext,
                             final PipelineFactorySerialiser serialiser,
                             final Provider<Combinations> combinationsProvider) {
        super(storeFactory,
                securityContext,
                serialiser,
                PipelineFactoryDoc.TYPE,
                PipelineFactoryDoc::builder,
                PipelineFactoryDoc::copy);
        this.serialiser = serialiser;
        this.combinationsProvider = combinationsProvider;
    }

    /// A deleted factory's record of what it met goes with it: nothing will read it again, and a factory
    /// is never undeleted under the same UUID.
    @Override
    public void deleteDocument(final DocRef docRef) {
        super.deleteDocument(docRef);
        combinationsProvider.get().forgetFactory(docRef.getUuid());
    }

    @Override
    public PipelineFactoryDoc writeDocument(final PipelineFactoryDoc document) {
        final List<String> problems = document.problems();
        if (!problems.isEmpty()) {
            throw new EntityServiceException(describe(document.getName(), problems));
        }
        return super.writeDocument(document);
    }

    @Override
    public DocRef importDocument(final DocRef docRef,
                                 final ImportExportDocument importExportDocument,
                                 final ImportState importState,
                                 final ImportSettings importSettings) {
        if (importExportDocument != null) {
            final PipelineFactoryDoc incoming = read(importExportDocument);
            final List<String> problems = incoming.problems();
            if (!problems.isEmpty()) {
                // Said where the import screen shows it, and not imported, in confirmation and in the
                // import alike, so a person sees why before choosing to go on.
                importState.addMessage(Severity.ERROR, describe(incoming.getName(), problems));
                return docRef;
            }
        }
        return super.importDocument(docRef, importExportDocument, importState, importSettings);
    }

    /// The template is the one document a factory depends on: it is listed among the factory's
    /// dependencies, travels with it on export, and is re-pointed when a copy re-points its dependencies.
    @Override
    protected DependencyRemapFunction<PipelineFactoryDoc> getDependencyRemapFunction() {
        return (doc, remapper) -> {
            if (doc.getTemplate() == null) {
                return doc;
            }
            return doc.copy().template(remapper.remap(doc.getTemplate())).build();
        };
    }

    private PipelineFactoryDoc read(final ImportExportDocument importExportDocument) {
        try {
            return serialiser.read(importExportDocument);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String describe(final String name, final List<String> problems) {
        return "Pipeline Factory '" + name + "' cannot be saved: " + String.join("; ", problems);
    }
}
