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

package stroom.learningflow.extraction;

import stroom.content.ContentPack;
import stroom.content.ContentPacks;
import stroom.docref.DocRef;
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.impl.StoreFactoryImpl;
import stroom.docstore.impl.memory.MemoryPersistence;
import stroom.learningflow.element.XmlSchemaTargetSchemas;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.scoring.SchemaConformanceScorer;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.cache.PoolItem;
import stroom.pipeline.cache.PoolKey;
import stroom.pipeline.cache.SchemaKey;
import stroom.pipeline.cache.SchemaLoaderImpl;
import stroom.pipeline.cache.SchemaPool;
import stroom.pipeline.cache.StoredSchema;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.filter.SchemaFilter;
import stroom.pipeline.state.PipelineContext;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;
import stroom.pipeline.xmlschema.XmlSchemaCache;
import stroom.pipeline.xmlschema.XmlSchemaSerialiser;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.pipeline.xmlschema.XmlSchemaStoreImpl;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.test.common.util.test.ContentPackZipDownloader;
import stroom.test.common.util.test.FileSystemTestUtil;
import stroom.util.io.StreamUtil;
import stroom.xmlschema.shared.XmlSchemaDoc;

import com.google.inject.Guice;
import com.google.inject.Injector;
import jakarta.inject.Provider;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Provides what a [DataSplitterCompiler] and the schema conformance scorer need in order to run outside a
/// Stroom node, for tests.
///
/// The Data Splitter validates each configuration against the `data-splitter:3` schema, which it finds
/// through Stroom's XML schema store. That schema is not in the repository; it ships in the
/// `core-xml-schemas` content pack. So this fixture downloads the pack into the shared test download area
/// and creates an in-memory schema store holding that schema. It does the same for the event-logging 3.0.0
/// schema, which translations are scored against.
public final class NodeFixture {

    /// The path of the Data Splitter schema inside the content pack, using the pack's own file naming.
    private static final String SCHEMA_FILE = "XML_Schemas/data_splitter/"
            + "data_splitter_v3_0.XMLSchema.9e1e2567-ba83-4720-95c0-f882b951bd3e.xsd";
    private static final String EVENTS_SCHEMA_FILE = "XML_Schemas/event_logging/"
            + "event_logging_v3_0_0.XMLSchema.4fe14042-770e-4297-8711-d92e607bc4d5.xsd";
    /// The schema group of the event-logging 3.0.0 schema, as the pack's `.meta` file declares it.
    public static final String EVENTS_SCHEMA_GROUP = "EVENTS";
    private static final String EVENTS_NAMESPACE_URI = "event-logging:3";
    private static final String EVENTS_SYSTEM_ID = "file://event-logging-v3.0.0.xsd";

    private final DataSplitterCompiler compiler;
    private final Provider<SchemaFilter> schemaFilters;
    private final ErrorReceiverProxy errorReceiverProxy;
    private final XmlSchemaStore schemaStore;
    private final SecurityContext securityContext;

    public NodeFixture() {
        securityContext = new MockSecurityContext();
        schemaStore = new XmlSchemaStoreImpl(
                new StoreFactoryImpl(new MemoryPersistence(), null, securityContext, null, null),
                securityContext,
                new XmlSchemaSerialiser(new Serialiser2FactoryImpl()));
        loadDataSplitterSchema(schemaStore);
        loadEventLoggingSchema(schemaStore);

        final XmlSchemaCache schemaCache = new XmlSchemaCache(schemaStore, securityContext);
        final SchemaPool schemaPool = new LoadingSchemaPool(new SchemaLoaderImpl(schemaCache));

        // The parser factory's constructor is package-private and injected, so it is created as a node
        // creates it: through Guice. Each factory gets a new schema filter, because the filter keeps state
        // during a configure, and every filter reports errors through the one proxy the compiler owns.
        final ErrorReceiverProxy errorReceiverProxy = new ErrorReceiverProxy();
        final Injector injector = Guice.createInjector(binder -> binder
                .bind(SchemaFilter.class)
                .toProvider(() -> new SchemaFilter(
                        schemaPool,
                        schemaCache,
                        errorReceiverProxy,
                        new LocationFactoryProxy(),
                        new PipelineContext())));
        this.errorReceiverProxy = errorReceiverProxy;
        this.schemaFilters = injector.getProvider(SchemaFilter.class);
        compiler = new DataSplitterCompiler(injector.getProvider(DS3ParserFactory.class), errorReceiverProxy);
    }

    public DataSplitterCompiler compiler() {
        return compiler;
    }

    /// Returns the text of the event-logging 3.0.0 schema, for code that reads a schema rather than
    /// validating against one.
    public static String eventLoggingSchema() {
        final ContentPack pack = ContentPacks.EVENT_LOGGING_XML_SCHEMA_PACK;
        final Path packDir = ContentPackZipDownloader.downloadContentPack(
                pack,
                FileSystemTestUtil.getExplodedContentPacksDir());
        return StreamUtil.fileToString(packDir.resolve(pack.getPath()).resolve(EVENTS_SCHEMA_FILE));
    }

    /// Returns a schema conformance scorer that uses a new schema filter for each run and reports errors
    /// through the fixture's proxy, as a node's scorer does.
    public SchemaConformanceScorer schemaConformanceScorer() {
        return new SchemaConformanceScorer(schemaFilters, errorReceiverProxy, schemaStore, securityContext);
    }

    /// Returns target schemas backed by the fixture's own store, which holds event-logging 3.0.0, so that
    /// a scenario reads a real `XMLSchema` document rather than a made-up one.
    ///
    /// @return The target schemas.
    public TargetSchemas targetSchemas() {
        return new XmlSchemaTargetSchemas(schemaStore, securityContext);
    }

    /// Returns the event-logging schema the fixture loaded, for a scenario that sets a document's target
    /// schema.
    ///
    /// @return A reference to the schema document.
    public DocRef eventLoggingSchemaRef() {
        return schemaRef(EVENTS_NAMESPACE_URI);
    }

    /// Finds a schema the fixture loaded, by its namespace.
    ///
    /// @param namespaceURI The schema's namespace, e.g. `data-splitter:3`.
    /// @return A reference to the schema document.
    public DocRef schemaRef(final String namespaceURI) {
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setNamespaceURI(namespaceURI);
        return securityContext.asProcessingUserResult(() -> schemaStore.find(criteria).getValues().stream()
                .findFirst()
                .map(XmlSchemaDoc::asDocRef)
                .orElseThrow(() -> new IllegalStateException(
                        "The fixture loaded no schema in " + namespaceURI)));
    }

    private static void loadDataSplitterSchema(final XmlSchemaStore schemaStore) {
        loadSchema(schemaStore, ContentPacks.CORE_XML_SCHEMAS_PACK, SCHEMA_FILE, DS3ParserFactory.SCHEMA_NAME,
                DS3ParserFactory.SCHEMA_GROUP, DS3ParserFactory.NAMESPACE_URI, DS3ParserFactory.SYSTEM_ID);
    }

    private static void loadEventLoggingSchema(final XmlSchemaStore schemaStore) {
        loadSchema(schemaStore, ContentPacks.EVENT_LOGGING_XML_SCHEMA_PACK, EVENTS_SCHEMA_FILE,
                "event-logging v3.0.0", EVENTS_SCHEMA_GROUP, EVENTS_NAMESPACE_URI, EVENTS_SYSTEM_ID);
    }

    private static void loadSchema(final XmlSchemaStore schemaStore,
                                   final ContentPack pack,
                                   final String file,
                                   final String name,
                                   final String group,
                                   final String namespaceUri,
                                   final String systemId) {
        final Path packDir = ContentPackZipDownloader.downloadContentPack(
                pack,
                FileSystemTestUtil.getExplodedContentPacksDir());
        final Path schemaFile = packDir.resolve(pack.getPath()).resolve(file);
        final DocRef docRef = schemaStore.createDocument(name);
        schemaStore.writeDocument(schemaStore.readDocument(docRef)
                .copy()
                .schemaGroup(group)
                .namespaceURI(namespaceUri)
                .systemId(systemId)
                .data(StreamUtil.fileToString(schemaFile))
                .build());
    }

    /// Compiles each distinct schema once and returns it on every borrow. There are few schemas and no
    /// contention in tests, so the node's pooled cache would add complexity for no benefit.
    private static final class LoadingSchemaPool implements SchemaPool {

        private final SchemaLoaderImpl loader;
        private final Map<SchemaKey, StoredSchema> schemas = new ConcurrentHashMap<>();

        private LoadingSchemaPool(final SchemaLoaderImpl loader) {
            this.loader = loader;
        }

        @Override
        public PoolItem<StoredSchema> borrowObject(final SchemaKey key, final boolean usePool) {
            final StoredSchema schema = schemas.computeIfAbsent(key, k ->
                    loader.load(k.getSchemaLanguage(), k.getData(), k.getFindXMLSchemaCriteria()));
            return new PoolItem<>(new PoolKey<>(key), schema);
        }

        @Override
        public void returnObject(final PoolItem<StoredSchema> poolItem, final boolean usePool) {
        }
    }
}
