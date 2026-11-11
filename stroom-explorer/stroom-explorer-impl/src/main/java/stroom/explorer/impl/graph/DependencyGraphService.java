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

package stroom.explorer.impl.graph;

import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.DependencyGraphRequest;
import stroom.docref.DocRef;
import stroom.docstore.api.DependencyEdge;
import stroom.docstore.api.DocDependencyService;
import stroom.docstore.api.DocumentStore;
import stroom.docstore.api.DocumentStoreRegistry;
import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerNode;
import stroom.feed.shared.FeedDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.Document;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Fetches a folder's [DependencyGraph] for the Structure view.
///
/// Reads the folder's contents, the recorded dependencies and the pipelines' and feeds' configuration,
/// as the user, and gives them to [DependencyGraphAssembler], which applies the rules. Every document is
/// checked for VIEW permission, and a check that fails for any reason leaves the document out: unlike
/// [DocDependencyService#fetchDependencies], which lets an edge through when its check throws.
public class DependencyGraphService {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DependencyGraphService.class);

    /// How many hops of context beyond the folder are included when the request does not say.
    static final int DEFAULT_CONTEXT_DEPTH = 1;
    /// The most hops of context a request may ask for.
    static final int MAX_CONTEXT_DEPTH = 2;
    /// How many nodes are returned at most when the request does not say.
    static final int DEFAULT_MAX_NODES = 500;
    /// The most nodes a request may ask for.
    static final int NODE_CEILING = 2000;

    private final Provider<DocDependencyService> docDependencyServiceProvider;
    private final Provider<ExplorerNodeService> explorerNodeServiceProvider;
    private final Provider<DocumentStoreRegistry> documentStoreRegistryProvider;
    private final SecurityContext securityContext;

    @Inject
    DependencyGraphService(final Provider<DocDependencyService> docDependencyServiceProvider,
                           final Provider<ExplorerNodeService> explorerNodeServiceProvider,
                           final Provider<DocumentStoreRegistry> documentStoreRegistryProvider,
                           final SecurityContext securityContext) {
        this.docDependencyServiceProvider = docDependencyServiceProvider;
        this.explorerNodeServiceProvider = explorerNodeServiceProvider;
        this.documentStoreRegistryProvider = documentStoreRegistryProvider;
        this.securityContext = securityContext;
    }

    /// Fetches a folder's dependency graph, as far as the user may see it.
    ///
    /// @param request The folder, and how much context and how many nodes to include.
    /// @return the graph.
    /// @throws PermissionException if the user may not view the folder.
    public DependencyGraph fetchGraph(final DependencyGraphRequest request) {
        Objects.requireNonNull(request, "request");
        final DocRef root = Objects.requireNonNull(request.getRoot(), "root");
        if (!ExplorerConstants.SYSTEM_DOC_REF.equals(root) && !canView(root)) {
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to view this folder");
        }
        final int contextDepth = clamp(request.getContextDepth(), DEFAULT_CONTEXT_DEPTH, 0, MAX_CONTEXT_DEPTH);
        final int maxNodes = clamp(request.getMaxNodes(), DEFAULT_MAX_NODES, 1, NODE_CEILING);
        return DependencyGraphAssembler.assemble(root, contextDepth, maxNodes, new Source());
    }

    static int clamp(final Integer value, final int defaultValue, final int min, final int max) {
        final int wanted = value == null
                ? defaultValue
                : value;
        return Math.max(min, Math.min(max, wanted));
    }

    // Fails closed: a check that cannot be made leaves the document out.
    private boolean canView(final DocRef docRef) {
        try {
            return securityContext.hasDocumentPermission(docRef, DocumentPermission.VIEW);
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> LogUtil.message("Unable to check VIEW permission on {}, so leaving it out: {}",
                    docRef, e.getMessage()));
            return false;
        }
    }

    private Document read(final DocRef docRef) {
        try {
            final DocumentStore<?> store = documentStoreRegistryProvider.get().getDocumentStore(docRef.getType());
            return store.readDocument(docRef);
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> LogUtil.message("Unable to read {}: {}", docRef, e.getMessage()));
            return null;
        }
    }

    // One request's source: it reads each document at most once, as a parent pipeline is shared by
    // its children.
    private class Source implements DependencyGraphAssembler.GraphSource {

        private final Map<String, Optional<Document>> documents = new HashMap<>();

        @Override
        public List<DocRef> contents(final DocRef folder) {
            final List<DocRef> contents = new ArrayList<>();
            for (final ExplorerNode node : explorerNodeServiceProvider.get().getDescendants(folder)) {
                if (node.getDocRef() != null) {
                    contents.add(node.getDocRef());
                }
            }
            return contents;
        }

        @Override
        public boolean canView(final DocRef docRef) {
            return DependencyGraphService.this.canView(docRef);
        }

        @Override
        public boolean isDocumentType(final String type) {
            try {
                return documentStoreRegistryProvider.get().getDocumentStore(type) != null;
            } catch (final RuntimeException e) {
                return false;
            }
        }

        private Document cachedRead(final DocRef docRef) {
            return documents.computeIfAbsent(docRef.getUuid(), k -> Optional.ofNullable(read(docRef)))
                    .orElse(null);
        }

        @Override
        public List<DependencyEdge> edgesFrom(final Set<String> uuids) {
            return docDependencyServiceProvider.get().getEdgesFrom(uuids);
        }

        @Override
        public List<DependencyEdge> edgesTo(final Set<String> uuids) {
            return docDependencyServiceProvider.get().getEdgesTo(uuids);
        }

        @Override
        public PipelineFacts pipelineFacts(final DocRef pipeline) {
            if (!(cachedRead(pipeline) instanceof final PipelineDoc doc)) {
                return null;
            }
            final List<PipelineLayer> layers = layers(doc);
            try {
                return PipelineFacts.of(doc.getParentPipeline(), layers);
            } catch (final RuntimeException e) {
                LOGGER.debug(() -> LogUtil.message("Unable to merge {}: {}", pipeline, e.getMessage()));
                return null;
            }
        }

        @Override
        public String streamType(final DocRef feed) {
            return cachedRead(feed) instanceof final FeedDoc doc
                    ? doc.getStreamType()
                    : null;
        }

        // The pipeline's data and its ancestors', oldest first. If an ancestor cannot be viewed or read,
        // only the pipeline's own data: a merge missing a layer would describe a pipeline that does not
        // exist.
        private List<PipelineLayer> layers(final PipelineDoc doc) {
            final List<PipelineLayer> layers = new ArrayList<>();
            layers.add(new PipelineLayer(doc.asDocRef(), doc.getPipelineData()));
            final Set<String> seen = new HashSet<>();
            seen.add(doc.getUuid());
            DocRef parent = doc.getParentPipeline();
            while (parent != null) {
                if (!seen.add(parent.getUuid()) || !canView(parent)
                    || !(cachedRead(parent) instanceof final PipelineDoc parentDoc)) {
                    return List.of(new PipelineLayer(doc.asDocRef(), doc.getPipelineData()));
                }
                layers.addFirst(new PipelineLayer(parentDoc.asDocRef(), parentDoc.getPipelineData()));
                parent = parentDoc.getParentPipeline();
            }
            return layers;
        }
    }
}
