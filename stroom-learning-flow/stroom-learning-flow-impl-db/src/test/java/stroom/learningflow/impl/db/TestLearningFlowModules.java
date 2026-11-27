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

package stroom.learningflow.impl.db;

import stroom.cache.api.CacheManager;
import stroom.cache.impl.CacheManagerImpl;
import stroom.db.util.DataSourceFactory;
import stroom.learningflow.LearningFlowDbConfig;
import stroom.learningflow.cache.CachedLearnerBindings;
import stroom.learningflow.cache.CachedLearnerStates;
import stroom.learningflow.cache.Rows;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearnerStates;
import stroom.util.entityevent.EntityEventBus;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/// Tests that each of the two Guice modules binds only what it should. [LearningFlowDbModule] is installed
/// in the injector that runs the Flyway migrations before the application exists, and that injector holds
/// only data sources and configuration. A binding there that needed a cache manager or the entity event
/// bus would stop the node on startup.
///
/// None of these tests reaches the database: no data source is created, because nothing here asks for one.
class TestLearningFlowModules {

    @Test
    void theDbModuleAsksForNothingTheBootstrapInjectorHasNot() {
        assertThatNoException().isThrownBy(() -> Guice.createInjector(
                new LearningFlowDbModule(),
                bootstrap()));
    }

    @Test
    void theLearnerBindingsAreNotBoundBeforeTheApplicationExists() {
        final Injector injector = Guice.createInjector(new LearningFlowDbModule(), bootstrap());

        assertThat(injector.getExistingBinding(Key.get(LearnerBindings.class))).isNull();
        assertThat(injector.getExistingBinding(Key.get(LearnerStates.class))).isNull();
    }

    @Test
    void theDaoModuleBindsTheRowsAndTheCachesInFrontOfThem() {
        final Injector injector = Guice.createInjector(new LearningFlowDaoModule(), application());

        assertThat(injector.getInstance(Key.get(LearnerBindings.class, Rows.class)))
                .isInstanceOf(LearnerBindingsDao.class);
        assertThat(injector.getInstance(Key.get(LearnerStates.class, Rows.class)))
                .isInstanceOf(LearnerStatesDao.class);
        assertThat(injector.getInstance(LearnerBindings.class)).isInstanceOf(CachedLearnerBindings.class);
        assertThat(injector.getInstance(LearnerStates.class)).isInstanceOf(CachedLearnerStates.class);
    }

    /// Stands in for what the bootstrap injector provides: the module's configuration and the factory it
    /// would create a connection pool from. It provides nothing else, which is the point of the first two
    /// tests.
    private static AbstractModule bootstrap() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(LearningFlowDbConfig.class).toInstance(new LearningFlowDbConfig());
                bind(DataSourceFactory.class).toInstance(Mockito.mock(DataSourceFactory.class));
            }
        };
    }

    /// Stands in for what the application provides to the DAO module: a real cache manager, an event bus
    /// with no other node listening, and a connection provider that these tests never use.
    private static AbstractModule application() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(CacheManager.class).toInstance(new CacheManagerImpl());
                bind(EntityEventBus.class).toInstance(Mockito.mock(EntityEventBus.class));
                bind(LearningFlowDbConnProvider.class)
                        .toInstance(Mockito.mock(LearningFlowDbConnProvider.class));
            }
        };
    }
}
