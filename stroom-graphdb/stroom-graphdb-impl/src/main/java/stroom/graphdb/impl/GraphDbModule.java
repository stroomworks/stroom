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
import stroom.docstore.api.DocumentStoreBinder;
import stroom.graphdb.impl.pipeline.GraphElementModule;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.job.api.ScheduledJobsBinder;
import stroom.planb.shared.RetentionSettings;
import stroom.query.api.QueryNodeResolver;
import stroom.query.api.datasource.DataSourceProvider;
import stroom.query.common.v2.IndexFieldProvider;
import stroom.query.common.v2.SearchProvider;
import stroom.query.language.AlternativeQueryCompiler;
import stroom.query.planner.port.GraphStoreStats;
import stroom.util.RunnableWrapper;
import stroom.util.entityevent.EntityEvent;
import stroom.util.guice.GuiceUtil;
import stroom.util.guice.RestResourcesBinder;
import stroom.util.shared.Clearable;
import stroom.util.shared.scheduler.CronExpressions;

import com.google.inject.AbstractModule;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers {@link GraphDbDoc}'s document store/cache, {@link GraphSearchProvider} (Task PoC.6), its REST
 * resource (Task P5.2), and the {@code GraphFilter} ingest pipeline element (Task P2.2, via
 * {@link GraphElementModule}) - mirroring {@code stroom.planb.impl.PlanBModule} - {@code PlanBModule} itself is
 * not edited (Decision D1).
 *
 * <p>Also binds a single retention scheduled job (Task P1.4, Decision D9) - deliberately not a growth of Plan
 * B's {@code ShardManager} machinery, which solves distributed-shard snapshot/placement problems a single
 * per-doc {@link GraphStores} environment doesn't have.</p>
 */
public class GraphDbModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new GraphElementModule());

        bind(GraphDbDocCache.class).to(GraphDbDocCacheImpl.class);
        bind(GraphStoreManager.class).to(GraphStoreManagerImpl.class);
        bind(GraphFileTransferClient.class).to(GraphFileTransferClientImpl.class);

        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(GraphDbDocCacheImpl.class);

        GuiceUtil.buildMultiBinder(binder(), Clearable.class)
                .addBinding(GraphDbDocCacheImpl.class);

        DocumentStoreBinder.create(binder())
                .bind(GraphDbDoc.TYPE, GraphDbDocStore.class, GraphDbDocStoreImpl.class);

        RestResourcesBinder.create(binder())
                .bind(GraphDbResourceImpl.class)
                .bind(GraphFileTransferResourceImpl.class);

        // Read-side cluster correctness: pins a graph query to a node that actually holds graph data.
        GuiceUtil.buildMultiBinder(binder(), QueryNodeResolver.class)
                .addBinding(GraphQueryNodeResolverImpl.class);

        bind(GraphStoreStats.class).to(GraphStoreStatsAdapter.class);

        // Task P6.1: gives CypherCompiler a real caller - QueryServiceImpl resolves this from the
        // Set<AlternativeQueryCompiler> multibinder when a search's owning doc-ref is a GraphDbDoc.
        GuiceUtil.buildMultiBinder(binder(), AlternativeQueryCompiler.class)
                .addBinding(GraphCypherQueryCompiler.class);

        GuiceUtil.buildMultiBinder(binder(), DataSourceProvider.class)
                .addBinding(GraphSearchProvider.class);
        GuiceUtil.buildMultiBinder(binder(), SearchProvider.class)
                .addBinding(GraphSearchProvider.class);
        GuiceUtil.buildMultiBinder(binder(), IndexFieldProvider.class)
                .addBinding(GraphSearchProvider.class);

        ScheduledJobsBinder.create(binder())
                .bindJobTo(GraphRetentionRunnable.class, builder -> builder
                        .name(GraphRetentionRunnable.TASK_NAME)
                        .description("Graph DB retention")
                        .cronSchedule(CronExpressions.EVERY_10_MINUTES.getExpression())
                        .advanced(true));

        // Starts the merge loops if they are not already running, so a restart resumes any queued fragments. The
        // loops then run continuously; the schedule exists to (re)start them, not to pace them.
        ScheduledJobsBinder.create(binder())
                .bindJobTo(GraphMergeRunnable.class, builder -> builder
                        .name(GraphMergeProcessor.MERGE_TASK_NAME)
                        .description("Graph DB fragment merge")
                        .cronSchedule(CronExpressions.EVERY_MINUTE.getExpression())
                        .advanced(true));
    }

    private static class GraphMergeRunnable extends RunnableWrapper {

        @Inject
        GraphMergeRunnable(final GraphMergeProcessor graphMergeProcessor) {
            super(graphMergeProcessor::merge);
        }
    }

    private static class GraphRetentionRunnable extends RunnableWrapper {

        private static final Logger LOGGER = LoggerFactory.getLogger(GraphRetentionRunnable.class);

        static final String TASK_NAME = "Graph DB Retention";

        @Inject
        GraphRetentionRunnable(final GraphDbDocStore graphDbDocStore, final GraphStoreManager graphStoreManager) {
            super(() -> {
                // Reclaim graphs whose document is gone before sweeping the survivors. A document deleted while
                // this node was down never produced an entity event here, so nothing else would ever notice.
                try {
                    graphStoreManager.cleanupOrphanedStores();
                } catch (final RuntimeException e) {
                    LOGGER.error("Error reclaiming orphaned graph stores", e);
                }
                for (final DocRef docRef : graphDbDocStore.list()) {
                    // Code-review fix: previously an exception retaining one doc (a corrupt store, a disk error,
                    // ...) aborted this whole loop, silently skipping retention for every other doc due that
                    // cycle until the next scheduled run 10 minutes later - mirrors the per-shard try/catch
                    // stroom.planb.impl.data.ShardManager's own maintenance loops use for the identical reason.
                    try {
                        final GraphDbDoc doc = graphDbDocStore.readDocument(docRef);
                        // Code-review fix: only open the store when retention is actually enabled. getOrOpen()
                        // eagerly opens (and permanently caches - there is no eviction) the doc's LMDB
                        // environment, so calling it unconditionally would, every 10 minutes, open an env for
                        // every graph doc even though retention is disabled by default and deleteOldData() would
                        // immediately no-op - leaving every never-queried doc's env held open forever.
                        if (doc != null && retentionEnabled(doc)) {
                            graphStoreManager.getOrOpen(doc).deleteOldData(doc);
                        }
                    } catch (final RuntimeException e) {
                        LOGGER.error("Error running retention for {}", docRef, e);
                    }
                }
            });
        }

        private static boolean retentionEnabled(final GraphDbDoc doc) {
            final RetentionSettings retention = doc.getRetention();
            return retention != null && retention.isEnabled();
        }
    }
}
