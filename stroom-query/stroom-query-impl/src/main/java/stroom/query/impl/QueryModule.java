/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.query.impl;

import stroom.docstore.api.DocumentStoreBinder;
import stroom.event.logging.api.ObjectInfoProviderBinder;
import stroom.query.api.datasource.QueryFieldProvider;
import stroom.query.language.DispatchingQueryCompiler;
import stroom.query.language.FieldInfoSourceAdapter;
import stroom.query.language.MetaStatsAdapter;
import stroom.query.language.NoOpIndexShardStats;
import stroom.query.language.NoOpStateStoreStats;
import stroom.query.language.QueryCompiler;
import stroom.query.planner.port.FieldInfoSource;
import stroom.query.planner.port.IndexShardStats;
import stroom.query.planner.port.MetaStats;
import stroom.query.planner.port.StateStoreStats;
import stroom.query.shared.QueryDoc;
import stroom.util.guice.RestResourcesBinder;

import com.google.inject.AbstractModule;

public class QueryModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(QueryService.class).to(QueryServiceImpl.class);
        bind(QueryFieldProvider.class).to(QueryServiceImpl.class);
        bind(QueryCompiler.class).to(DispatchingQueryCompiler.class);
        bind(FieldInfoSource.class).to(FieldInfoSourceAdapter.class);
        bind(MetaStats.class).to(MetaStatsAdapter.class);
        // NoOp placeholders - Task 3.1 deferred the real adapters (a dependency-cycle finding: they must live
        // inside stroom-index-impl/stroom-planb-impl, not here). Replace these bindings when those land.
        bind(IndexShardStats.class).to(NoOpIndexShardStats.class);
        bind(StateStoreStats.class).to(NoOpStateStoreStats.class);

        DocumentStoreBinder.create(binder())
                .bind(QueryDoc.TYPE, QueryStore.class, QueryStoreImpl.class);

        // Provide object info to the logging service.
        ObjectInfoProviderBinder.create(binder())
                .bind(QueryDoc.class, QueryDocObjectInfoProvider.class);

        RestResourcesBinder.create(binder())
                .bind(QueryResourceImpl.class)
                .bind(ExpressionResourceImpl.class)
                .bind(ResultStoreResourceImpl.class);
    }
}
