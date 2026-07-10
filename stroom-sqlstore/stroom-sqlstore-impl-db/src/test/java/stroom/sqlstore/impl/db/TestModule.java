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

package stroom.sqlstore.impl.db;

import stroom.collection.mock.MockCollectionModule;
import stroom.dictionary.mock.MockWordListProviderModule;
import stroom.docrefinfo.mock.MockDocRefInfoModule;
import stroom.test.common.util.db.DbTestModule;

import com.google.inject.AbstractModule;

/**
 * Guice wiring for the real-database {@link UpdatableTemporalStoreDaoImpl} tests.
 *
 * <p>Installs the production {@link SqlStoreDbModule} (which binds the DAO and,
 * via its {@code @Provides} method, runs Flyway against the datasource) on top
 * of {@link DbTestModule}, which swaps in a uniquely-named test database so
 * parallel Gradle forks never collide.</p>
 *
 * <p>The DAO builds an {@code ExpressionMapper} whose {@code TermHandlerFactory}
 * needs {@code WordListProvider}, {@code CollectionService} and
 * {@code DocRefInfoService}; the three {@code Mock*Module}s satisfy those.
 * {@code SqlStoreDbConfig} is just-in-time constructed from its no-arg
 * constructor.</p>
 */
public class TestModule extends AbstractModule {

    @Override
    protected void configure() {
        super.configure();

        // Production module: binds UpdatableTemporalStoreDao -> ...Impl and
        // provides the (Flyway-migrated) SqlStoreDbConnProvider.
        install(new SqlStoreDbModule());

        // Test datasource: unique per-fork/thread DB, cleared after the run.
        install(new DbTestModule());

        // Collaborators required by the ExpressionMapper term handlers.
        install(new MockWordListProviderModule());
        install(new MockCollectionModule());
        install(new MockDocRefInfoModule());
    }
}
