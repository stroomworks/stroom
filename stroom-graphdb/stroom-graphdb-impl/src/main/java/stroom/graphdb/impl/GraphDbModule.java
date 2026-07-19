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
import stroom.query.api.datasource.DataSourceProvider;
import stroom.query.common.v2.IndexFieldProvider;
import stroom.query.common.v2.SearchProvider;
import stroom.query.planner.port.GraphStoreStats;
import stroom.util.RunnableWrapper;
import stroom.util.entityevent.EntityEvent;
import stroom.util.guice.GuiceUtil;
import stroom.util.shared.Clearable;
import stroom.util.shared.scheduler.CronExpressions;

import com.google.inject.AbstractModule;
import jakarta.inject.Inject;

/**
 * Registers {@link GraphDbDoc}'s document store/cache, {@link GraphSearchProvider} (Task PoC.6), and the
 * {@code GraphFilter} ingest pipeline element (Task P2.2, via {@link GraphElementModule}) - mirroring
 * {@code stroom.planb.impl.PlanBModule} - {@code PlanBModule} itself is not edited (Decision D1). No REST
 * resource / document-management API is bound yet (P5).
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

        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(GraphDbDocCacheImpl.class);

        GuiceUtil.buildMultiBinder(binder(), Clearable.class)
                .addBinding(GraphDbDocCacheImpl.class);

        DocumentStoreBinder.create(binder())
                .bind(GraphDbDoc.TYPE, GraphDbDocStore.class, GraphDbDocStoreImpl.class);

        bind(GraphStoreStats.class).to(GraphStoreStatsAdapter.class);

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
    }

    private static class GraphRetentionRunnable extends RunnableWrapper {

        static final String TASK_NAME = "Graph DB Retention";

        @Inject
        GraphRetentionRunnable(final GraphDbDocStore graphDbDocStore, final GraphStoreManager graphStoreManager) {
            super(() -> {
                for (final DocRef docRef : graphDbDocStore.list()) {
                    final GraphDbDoc doc = graphDbDocStore.readDocument(docRef);
                    if (doc != null) {
                        graphStoreManager.getOrOpen(doc).deleteOldData(doc);
                    }
                }
            });
        }
    }
}
