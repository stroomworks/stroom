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

package stroom.learningflow.fragment;

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

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// The three real document stores that a learned fragment is written to (pipeline, text converter and
/// XSLT), backed by in-memory persistence, for tests that write and run fragments without a Stroom node.
///
/// The text converter and XSLT store implementations are package-private and injected, and their Guice
/// modules would pull in REST resources and a URI resolver with dependencies of their own. So they are
/// bound by class name and constructed by Guice, as a node would construct them.
///
/// The pipeline store also needs the processor services, to delete a pipeline's processor filters, and a
/// registry of the other stores, to delete a pipeline's embedded documents. Deleting a draft fragment calls
/// the processor services, so they are stubbed to do nothing. A learned fragment embeds no documents, so
/// the registry is left out.
public final class ContentStores {

    /// The pattern the explorer checks a new folder's name against (see `FolderExplorerActionHandler`),
    /// so that a folder name a node would refuse is refused here too.
    private static final Pattern FOLDER_NAME = Pattern.compile("^[a-zA-Z0-9_\\- \\.\\(\\)]{1,}$");

    public final PipelineStore pipelines;
    /// Follows `parentPipeline` through the same store, so that in the in-memory scenario tests a
    /// fragment that inherits from a template runs merged with it, as it would in a real pipeline.
    public final PipelineStackLoader stackLoader;
    public final TextConverterStore textConverters;
    public final XsltStore xslts;
    /// Creates documents directly in the stores. There is no explorer here, so the folder is ignored and
    /// every document is in one flat namespace, in which free names are found.
    public final ContentCreator creator;

    public ContentStores() {
        final SecurityContext securityContext = new MockSecurityContext();
        final StoreFactory storeFactory =
                new StoreFactoryImpl(new MemoryPersistence(), null, securityContext, null, () -> null);
        final Injector injector = Guice.createInjector(binder -> {
            binder.bind(StoreFactory.class).toInstance(storeFactory);
            binder.bind(SecurityContext.class).toInstance(securityContext);
            binder.bind(Serialiser2Factory.class).to(Serialiser2FactoryImpl.class);
            binder.bind(ProcessorFilterService.class).toInstance(nothing(ProcessorFilterService.class));
            binder.bind(ProcessorService.class).toInstance(nothing(ProcessorService.class));
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
            public void delete(final DocRef docRef) {
                switch (docRef.getType()) {
                    case PipelineDoc.TYPE -> pipelines.deleteDocument(docRef);
                    case TextConverterDoc.TYPE -> textConverters.deleteDocument(docRef);
                    case XsltDoc.TYPE -> xslts.deleteDocument(docRef);
                    default -> throw new IllegalArgumentException(docRef.getType());
                }
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

    /// Returns a reader of a fragment's configuration text from these stores, which is what a snapshot is
    /// taken from.
    public FragmentCodes codes() {
        return new FragmentCodes(pipelines, textConverters, xslts);
    }

    public FragmentWriter writer() {
        return new FragmentWriter(creator, pipelines, textConverters, xslts);
    }

    /// Creates a stand-in that returns false, zero or null from every call. The pipeline store calls the
    /// processor services when a pipeline is deleted, and there are no processors here.
    ///
    /// @param type The interface to stand in for.
    /// @param <T>  The interface.
    /// @return An instance of it that does nothing.
    @SuppressWarnings("unchecked")
    private static <T> T nothing(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    final Class<?> returns = method.getReturnType();
                    if (boolean.class.equals(returns)) {
                        return false;
                    }
                    if (returns.isPrimitive() && !void.class.equals(returns)) {
                        return 0;
                    }
                    return null;
                });
    }

    private static <S> void bindByName(final Binder binder, final Class<S> store, final String implName) {
        try {
            binder.bind(store).to(Class.forName(implName).asSubclass(store));
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
