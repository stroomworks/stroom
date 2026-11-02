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

package stroom.pipelinefactory.impl.db;

import stroom.db.util.AbstractFlyWayDbModule;
import stroom.db.util.DataSourceProxy;
import stroom.pipelinefactory.impl.PipelineFactoryDbConfig;

import java.util.List;
import javax.sql.DataSource;

/// The Pipeline Factory's tables and the connection to them, and nothing else: this module is installed
/// in the bootstrap injector, which runs the migrations before the application exists. What reads and
/// writes the tables is bound by [PipelineFactoryDaoModule], which the application installs.
public class PipelineFactoryDbModule
        extends AbstractFlyWayDbModule<PipelineFactoryDbConfig, PipelineFactoryDbConnProvider> {

    private static final String MODULE = "stroom-pipeline-factory";
    private static final String FLYWAY_LOCATIONS = "stroom/pipelinefactory/impl/db/migration";
    private static final String FLYWAY_TABLE = "pipeline_factory_schema_history";

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
    protected Class<PipelineFactoryDbConnProvider> getConnectionProviderType() {
        return PipelineFactoryDbConnProvider.class;
    }

    @Override
    protected PipelineFactoryDbConnProvider createConnectionProvider(final DataSource dataSource) {
        return new DataSourceImpl(dataSource);
    }


    // --------------------------------------------------------------------------------


    private static class DataSourceImpl extends DataSourceProxy implements PipelineFactoryDbConnProvider {

        private DataSourceImpl(final DataSource dataSource) {
            super(dataSource, MODULE);
        }
    }
}
