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

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.planb.impl.data.FileDescriptor;
import stroom.planb.impl.data.MergeTarget;
import stroom.planb.impl.data.PartMergeProcessor;
import stroom.security.api.SecurityContext;
import stroom.task.api.ExecutorProvider;
import stroom.task.api.TaskContextFactory;
import stroom.util.metrics.Metrics;

import com.codahale.metrics.Counter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Merges received graph fragments into the authoritative store for each {@link GraphDbDoc} on this node.
 *
 * <p>This is what makes Graph DB correct on more than one node. Ingest no longer writes into a live store on
 * whichever node happened to process the stream; it writes a self-contained fragment which arrives here and is
 * merged into one store, so a traversal can cross data that was ingested by different nodes.</p>
 *
 * <p>The lifecycle is {@link PartMergeProcessor}'s, shared with Plan B. Graph DB supplies its own
 * {@link GraphPaths} directories - Plan B's merge loop discards fragments whose document it cannot resolve, and a
 * graph document is not a Plan B document, so sharing directories would let each feature delete the other's
 * data.</p>
 *
 * <p>Unlike Plan B, a failed merge increments a metric as well as logging at ERROR. A silently retained fragment
 * directory is how a cluster ends up permanently missing data with nothing to alert on, so the failure is made
 * countable.</p>
 */
@Singleton
public class GraphMergeProcessor {

    public static final String MERGE_TASK_NAME = "Graph DB Merge Processor";

    private static final String FEATURE_NAME = "Graph DB";
    private static final String MERGE_FAILURES_METRIC = "mergeFailures";

    private final PartMergeProcessor partMergeProcessor;
    private final GraphDbDocStore graphDbDocStore;
    private final GraphStoreManager graphStoreManager;
    private final Counter mergeFailures;

    @Inject
    public GraphMergeProcessor(final GraphPaths graphPaths,
                               final GraphDbDocStore graphDbDocStore,
                               final GraphStoreManager graphStoreManager,
                               final SecurityContext securityContext,
                               final TaskContextFactory taskContextFactory,
                               final ExecutorProvider executorProvider,
                               final Metrics metrics) {
        this.graphDbDocStore = Objects.requireNonNull(graphDbDocStore, "graphDbDocStore must not be null");
        this.graphStoreManager = Objects.requireNonNull(graphStoreManager, "graphStoreManager must not be null");
        this.mergeFailures = Objects.requireNonNull(metrics, "metrics must not be null")
                .registrationBuilder(getClass())
                .addNamePart(MERGE_FAILURES_METRIC)
                .counter()
                .createAndRegister();
        this.partMergeProcessor = new PartMergeProcessor(
                FEATURE_NAME,
                MERGE_TASK_NAME,
                graphPaths.getStagingDir(),
                graphPaths.getMergingDir(),
                graphPaths.getUnzipDir(),
                securityContext,
                taskContextFactory,
                executorProvider.get(),
                this::resolveStore,
                mergeFailures::inc);
    }

    private MergeTarget resolveStore(final String docUuid) {
        final DocRef docRef = DocRef.builder().type(GraphDbDoc.TYPE).uuid(docUuid).build();
        final GraphDbDoc doc = graphDbDocStore.readDocument(docRef);
        if (doc == null) {
            // The graph has been deleted since the fragment was written; the fragment is discarded.
            throw new DocumentNotFoundException(docRef);
        }
        return new MergeTarget() {
            @Override
            public String getDisplayName() {
                return doc.getName();
            }

            @Override
            public void merge(final Path sourceDir) {
                graphStoreManager.getOrOpen(doc).merge(sourceDir);
            }
        };
    }

    /**
     * Takes ownership of a received fragment zip, to be merged.
     *
     * <p><b>Preconditions:</b> neither parameter is null and the file's hash matches the descriptor.
     * <b>Postconditions:</b> the file has been moved into the staging store; if {@code synchroniseMerge} then it
     * has also been merged before returning.
     * <b>Null status:</b> neither parameter is nullable.
     *
     * @param fileDescriptor   identifies the fragment and carries its hash.
     * @param file             the zip to take ownership of.
     * @param synchroniseMerge whether to block until the fragment has been merged.
     * @throws IOException if the file cannot be hashed or moved into the store.
     */
    public void add(final FileDescriptor fileDescriptor,
                    final Path file,
                    final boolean synchroniseMerge) throws IOException {
        partMergeProcessor.add(fileDescriptor, file, synchroniseMerge);
    }

    /**
     * Starts background merging if it is not already running. Bound to a scheduled job so a restart resumes any
     * fragments that were still queued.
     */
    public void merge() {
        partMergeProcessor.merge();
    }

    /**
     * Merges every fragment currently staged, synchronously. Intended for tests and for operators who need a
     * merge to have completed before they query.
     */
    public void mergeCurrent() {
        partMergeProcessor.mergeCurrent();
    }

    /**
     * The number of merges that have failed since startup. A non-zero value means at least one fragment is sitting
     * unmerged on disk and this node's graphs are incomplete.
     *
     * @return the failure count.
     */
    public long getMergeFailureCount() {
        return mergeFailures.getCount();
    }
}
