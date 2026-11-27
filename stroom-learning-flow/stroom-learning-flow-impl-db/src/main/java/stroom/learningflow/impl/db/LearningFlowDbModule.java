/*
 * Copyright 2024 Crown Copyright
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

import stroom.db.util.AbstractFlyWayDbModule;
import stroom.db.util.DataSourceProxy;
import stroom.learningflow.LearningFlowDbConfig;

import java.util.List;
import javax.sql.DataSource;

/// The Guice module that provides the Learning Flow's database connection and runs its Flyway migrations.
///
/// It binds nothing else, because it is installed in the bootstrap injector, which holds only data sources
/// and configuration so that it can run the migrations before the application exists. Anything bound here
/// that needed an application service, such as a cache manager or the entity event bus, would stop the
/// node starting. Those bindings are in [LearningFlowDaoModule], which the application injector installs.
public class LearningFlowDbModule
        extends AbstractFlyWayDbModule<LearningFlowDbConfig, LearningFlowDbConnProvider> {

    private static final String MODULE = "stroom-learning-flow";
    private static final String FLYWAY_LOCATIONS = "stroom/learningflow/impl/db/migration";
    private static final String FLYWAY_TABLE = "learning_flow_schema_history";

    @Override
    protected String getFlyWayTableName() {
        return FLYWAY_TABLE;
    }

    @Override
    protected String getModuleName() {
        return MODULE;
    }

    @Override
    protected List<String> getFlyWayLocations() {
        return List.of(FLYWAY_LOCATIONS);
    }

    @Override
    protected Class<LearningFlowDbConnProvider> getConnectionProviderType() {
        return LearningFlowDbConnProvider.class;
    }

    @Override
    protected LearningFlowDbConnProvider createConnectionProvider(final DataSource dataSource) {
        return new DataSourceImpl(dataSource);
    }

    private static class DataSourceImpl extends DataSourceProxy implements LearningFlowDbConnProvider {

        private DataSourceImpl(final DataSource dataSource) {
            super(dataSource, MODULE);
        }
    }
}
