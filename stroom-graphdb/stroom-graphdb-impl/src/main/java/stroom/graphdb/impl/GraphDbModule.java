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
import stroom.lifecycle.api.LifecycleBinder;
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

        // At startup rather than on first use: the point is to tell an operator their data is stranded before
        // they discover it through empty query results.
        LifecycleBinder.create(binder())
                .bindStartupTaskTo(GraphRootMarkerCheck.class);

        ScheduledJobsBinder.create(binder())
                .bindJobTo(GraphMaintenanceRunnable.class, builder -> builder
                        .name(GraphMaintenanceRunnable.TASK_NAME)
                        .description("Graph DB maintenance: reclaims graphs whose document has been deleted, "
                                     + "applies retention where it is enabled, and condenses redundant versions "
                                     + "for every graph")
                        .cronSchedule(CronExpressions.EVERY_10_MINUTES.getExpression())
                        .advanced(true));

        ScheduledJobsBinder.create(binder())
                .bindJobTo(GraphCompactionRunnable.class, builder -> builder
                        .name(GraphCompactionRunnable.TASK_NAME)
                        .description("Graph DB compaction: rewrites each graph whose store has free pages to "
                                     + "reclaim, returning the space to the filesystem. Excludes queries on a "
                                     + "graph while that graph is being rewritten, and needs room for a second "
                                     + "copy of it")
                        .cronSchedule(GraphCompactionRunnable.CRON_SCHEDULE)
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

    private static class GraphRootMarkerCheck extends RunnableWrapper {

        @Inject
        GraphRootMarkerCheck(final GraphRootMarker graphRootMarker) {
            super(graphRootMarker::check);
        }
    }

    private static class GraphMergeRunnable extends RunnableWrapper {

        @Inject
        GraphMergeRunnable(final GraphMergeProcessor graphMergeProcessor) {
            super(graphMergeProcessor::merge);
        }
    }

    /**
     * Named for what it does rather than for the one thing it used to do. It began as retention only; it now also
     * reclaims graphs whose document has gone and condenses redundant versions - and condensing applies to every
     * graph, including those with retention disabled. Leaving it called "retention" would have meant an operator
     * disabling it believed they were switching off one thing and were in fact switching off three.
     */
    private static class GraphMaintenanceRunnable extends RunnableWrapper {

        private static final Logger LOGGER = LoggerFactory.getLogger(GraphMaintenanceRunnable.class);

        static final String TASK_NAME = "Graph DB Maintenance";

        @Inject
        GraphMaintenanceRunnable(final GraphDbDocStore graphDbDocStore,
                                 final GraphStoreManager graphStoreManager) {
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
                        // Note the store is now opened for any graph, because condensing applies to all of them.
                        // The eager-open concern the comment below records is therefore accepted rather than
                        // avoided - and less sharp than it was, since a store whose document has been deleted is
                        // reclaimed by cleanupOrphanedStores above.
                        // Code-review fix: only open the store when retention is actually enabled. use()
                        // eagerly opens (and permanently caches - there is no eviction) the doc's LMDB
                        // environment, so calling it unconditionally would, every 10 minutes, open an env for
                        // every graph doc even though retention is disabled by default and deleteOldData() would
                        // immediately no-op - leaving every never-queried doc's env held open forever.
                        if (doc != null) {
                            // Condensing is unconditional: it changes no query result and benefits any graph
                            // reloaded on a schedule, whether or not retention is enabled. Retention still is
                            // conditional - and checked first, so condense works on the smaller surviving set.
                            graphStoreManager.use(doc, stores -> {
                                final long aged = retentionEnabled(doc)
                                        ? stores.deleteOldData(doc)
                                        : 0L;
                                return aged + stores.condense();
                            });
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

    /**
     * Returns the space retention and condensing freed to the filesystem.
     *
     * <p><b>A separate job from maintenance, and on a far slower schedule, because the two differ in cost by
     * orders of magnitude.</b> Removing data is proportional to what is removed; compacting rewrites the entire
     * store and holds a lock that excludes every query on that graph while it runs. Running the two together
     * meant a graph reloaded on a schedule - which condenses something on essentially every cycle, that being
     * the workload condensing exists for - was fully rewritten every ten minutes. The gate was "did anything get
     * removed", which on that workload is always true.</p>
     *
     * <p>Daily and off-peak, and adjustable: it is an ordinary scheduled job, so an operator who needs it hourly
     * or weekly can say so without a code change. The per-graph gate is still there and is now durable, so a
     * graph nothing has been removed from is skipped before anything is copied rather than after.</p>
     */
    private static class GraphCompactionRunnable extends RunnableWrapper {

        private static final Logger LOGGER = LoggerFactory.getLogger(GraphCompactionRunnable.class);

        static final String TASK_NAME = "Graph DB Compaction";

        /** Off-peak, and late enough that a nightly reload has finished producing the versions to condense. */
        static final String CRON_SCHEDULE = CronExpressions.EVERY_DAY_AT_3AM.getExpression();

        @Inject
        GraphCompactionRunnable(final GraphDbDocStore graphDbDocStore,
                                final GraphStoreManager graphStoreManager) {
            super(() -> {
                for (final DocRef docRef : graphDbDocStore.list()) {
                    // Per-graph, like the maintenance loop: one graph that cannot be compacted must not stop
                    // every other graph from reclaiming its space until tomorrow.
                    try {
                        final GraphDbDoc doc = graphDbDocStore.readDocument(docRef);
                        if (doc != null) {
                            graphStoreManager.compact(doc);
                        }
                    } catch (final RuntimeException e) {
                        LOGGER.error("Error compacting {}", docRef, e);
                    }
                }
            });
        }
    }
}
