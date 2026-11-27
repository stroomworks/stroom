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

package stroom.learningflow.impl.db;

import stroom.cache.api.CacheManager;
import stroom.cache.impl.CacheManagerImpl;
import stroom.test.common.util.db.DbTestModule;
import stroom.util.entityevent.EntityEventBus;

import com.google.inject.AbstractModule;

/// The Guice module for the DAO tests: the Learning Flow's tables in a real database, with nothing else of
/// Stroom wired in, since these tests are only about the rows.
public class TestModule extends AbstractModule {

    @Override
    protected void configure() {
        super.configure();
        install(new DbTestModule());
        install(new LearningFlowDbModule());
        install(new LearningFlowDaoModule());
        // The caches that the DAO module binds in front of the DAOs need a cache manager and an event bus.
        // These tests are about the rows, so they get a real cache manager and a bus nothing listens on.
        bind(CacheManager.class).toInstance(new CacheManagerImpl());
        bind(EntityEventBus.class).toInstance(new EntityEventBus() {
            @Override
            public void fire(final stroom.util.entityevent.EntityEvent event) {
                // Nothing else is running, so there is no other node to notify.
            }

            @Override
            public void fire(final stroom.util.entityevent.EntityEventBatch events) {
                // As above.
            }
        });
    }
}
