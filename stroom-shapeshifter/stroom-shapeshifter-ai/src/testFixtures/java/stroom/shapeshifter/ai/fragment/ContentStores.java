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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentStoreRegistry;
import stroom.docstore.api.Serialiser2Factory;
import stroom.docstore.api.StoreFactory;
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.impl.StoreFactoryImpl;
import stroom.docstore.impl.memory.MemoryPersistence;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.PipelineStoreImpl;
import stroom.pipeline.factory.PipelineStackLoader;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorService;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.util.shared.DocPath;

import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Providers;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The three real document stores a fragment is written to — pipeline, text converter and XSLT — over
 * in-memory persistence. The text converter and XSLT store implementations are package-private and
 * injected, and their modules would drag in REST resources and a URI resolver with dependencies of
 * their own, so they are reached by name through Guice, which constructs them as a node would. The
 * pipeline store also wants the processor services, for deleting a pipeline's filters, and a registry of
 * the other stores, for deleting a pipeline's embedded documents; neither path is exercised by writing,
 * so all three are absent.
 */
public final class ContentStores {

    /// The explorer's pattern for a folder's name (`FolderExplorerActionHandler`), which it holds a folder
    /// to as it makes one: a folder no node could make is refused here too.
    private static final Pattern FOLDER_NAME = Pattern.compile("^[a-zA-Z0-9_\\- \\.\\(\\)]{1,}$");

    public final PipelineStore pipelines;
    /**
     * Follows {@code parentPipeline} through the same store, so a fragment that inherits from a template
     * runs merged in Tier 1 as it would in a pipeline.
     */
    public final PipelineStackLoader stackLoader;
    public final TextConverterStore textConverters;
    public final XsltStore xslts;
    /**
     * Creates straight through the stores: there is no explorer here, so the folder is ignored and every
     * document is in the one folder a free name is found in.
     */
    public final ContentCreator creator;

    public ContentStores() {
        final SecurityContext securityContext = new MockSecurityContext();
        final StoreFactory storeFactory =
                new StoreFactoryImpl(new MemoryPersistence(), null, securityContext, null, () -> null);
        final Injector injector = Guice.createInjector(binder -> {
            binder.bind(StoreFactory.class).toInstance(storeFactory);
            binder.bind(SecurityContext.class).toInstance(securityContext);
            binder.bind(Serialiser2Factory.class).to(Serialiser2FactoryImpl.class);
            binder.bind(ProcessorFilterService.class).toProvider(Providers.of(null));
            binder.bind(ProcessorService.class).toProvider(Providers.of(null));
            binder.bind(DocumentStoreRegistry.class).toProvider(Providers.of(null));
            binder.bind(PipelineStore.class).to(PipelineStoreImpl.class);
            bindByName(binder, TextConverterStore.class, "stroom.pipeline.textconverter.TextConverterStoreImpl");
            bindByName(binder, XsltStore.class, "stroom.pipeline.xslt.XsltStoreImpl");
            bindByName(binder, PipelineStackLoader.class, "stroom.pipeline.factory.PipelineStackLoaderImpl");
        });
        pipelines = injector.getInstance(PipelineStore.class);
        stackLoader = injector.getInstance(PipelineStackLoader.class);
        textConverters = injector.getInstance(TextConverterStore.class);
        xslts = injector.getInstance(XsltStore.class);
        creator = new ContentCreator() {

            @Override
            public DocRef create(final DocPath folder, final String type, final String name) {
                return switch (type) {
                    case PipelineDoc.TYPE -> pipelines.createDocument(name);
                    case TextConverterDoc.TYPE -> textConverters.createDocument(name);
                    case XsltDoc.TYPE -> xslts.createDocument(name);
                    default -> throw new IllegalArgumentException(type);
                };
            }

            @Override
            public void ensure(final DocPath folder) {
                folder.forEach((index, part) -> {
                    if (!FOLDER_NAME.matcher(part).matches()) {
                        throw new IllegalArgumentException("Invalid name \"" + part + "\"");
                    }
                });
            }

            @Override
            public Set<String> names(final DocPath folder) {
                return Stream.of(pipelines.list(), textConverters.list(), xslts.list())
                        .flatMap(List::stream)
                        .map(DocRef::getName)
                        .collect(Collectors.toSet());
            }
        };
    }

    /// A fragment as text, read from these stores: what a snapshot is taken from.
    public FragmentCodes codes() {
        return new FragmentCodes(pipelines, textConverters, xslts);
    }

    public FragmentWriter writer() {
        return new FragmentWriter(creator, pipelines, textConverters, xslts);
    }

    private static <S> void bindByName(final Binder binder, final Class<S> store, final String implName) {
        try {
            binder.bind(store).to(Class.forName(implName).asSubclass(store));
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
