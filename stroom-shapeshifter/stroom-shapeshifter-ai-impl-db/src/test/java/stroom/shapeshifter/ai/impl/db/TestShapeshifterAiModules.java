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

package stroom.shapeshifter.ai.impl.db;

import stroom.cache.api.CacheManager;
import stroom.cache.impl.CacheManagerImpl;
import stroom.db.util.DataSourceFactory;
import stroom.shapeshifter.ai.ShapeshifterAiDbConfig;
import stroom.shapeshifter.ai.cache.CachedLearnerBindings;
import stroom.shapeshifter.ai.cache.CachedLearnerStates;
import stroom.shapeshifter.ai.cache.Rows;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.LearnerStates;
import stroom.util.entityevent.EntityEventBus;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/// Which of the two modules holds what, which is not a matter of taste. [ShapeshifterAiDbModule] is
/// installed in the injector that runs the Flyway migrations before the application exists, and that
/// injector holds only data sources and configuration: a binding there that asks for a cache manager or
/// the entity event bus stops the node on startup rather than at the first stream.
///
/// Neither test reaches the database — no data source is created, because nothing here asks for one.
class TestShapeshifterAiModules {

    @Test
    void theDbModuleAsksForNothingTheBootstrapInjectorHasNot() {
        assertThatNoException().isThrownBy(() -> Guice.createInjector(
                new ShapeshifterAiDbModule(),
                bootstrap()));
    }

    @Test
    void theLearnerBindingsAreNotBoundBeforeTheApplicationExists() {
        final Injector injector = Guice.createInjector(new ShapeshifterAiDbModule(), bootstrap());

        assertThat(injector.getExistingBinding(Key.get(LearnerBindings.class))).isNull();
        assertThat(injector.getExistingBinding(Key.get(LearnerStates.class))).isNull();
    }

    @Test
    void theDaoModuleBindsTheRowsAndTheCachesInFrontOfThem() {
        final Injector injector = Guice.createInjector(new ShapeshifterAiDaoModule(), application());

        assertThat(injector.getInstance(Key.get(LearnerBindings.class, Rows.class)))
                .isInstanceOf(LearnerBindingsDao.class);
        assertThat(injector.getInstance(Key.get(LearnerStates.class, Rows.class)))
                .isInstanceOf(LearnerStatesDao.class);
        assertThat(injector.getInstance(LearnerBindings.class)).isInstanceOf(CachedLearnerBindings.class);
        assertThat(injector.getInstance(LearnerStates.class)).isInstanceOf(CachedLearnerStates.class);
    }

    /// What the bootstrap injector brings: the module's configuration, and the factory it would make a
    /// pool from. Nothing else, which is the point of the first two tests.
    private static AbstractModule bootstrap() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(ShapeshifterAiDbConfig.class).toInstance(new ShapeshifterAiDbConfig());
                bind(DataSourceFactory.class).toInstance(Mockito.mock(DataSourceFactory.class));
            }
        };
    }

    /// What the application brings the DAO module, standing in for a node: a real cache manager, a bus
    /// with no other node listening on it, and a connection nothing in these tests uses.
    private static AbstractModule application() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(CacheManager.class).toInstance(new CacheManagerImpl());
                bind(EntityEventBus.class).toInstance(Mockito.mock(EntityEventBus.class));
                bind(ShapeshifterAiDbConnProvider.class)
                        .toInstance(Mockito.mock(ShapeshifterAiDbConnProvider.class));
            }
        };
    }
}
