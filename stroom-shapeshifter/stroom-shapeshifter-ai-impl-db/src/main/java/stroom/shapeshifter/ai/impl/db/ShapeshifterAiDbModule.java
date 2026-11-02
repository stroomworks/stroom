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

package stroom.shapeshifter.ai.impl.db;

import stroom.db.util.AbstractFlyWayDbModule;
import stroom.db.util.DataSourceProxy;
import stroom.shapeshifter.ai.ShapeshifterAiDbConfig;

import java.util.List;
import javax.sql.DataSource;

/// The tables of A26 and the connection to them, and nothing else. This module is installed in the
/// bootstrap injector, which holds only data sources and configuration so that it can run the Flyway
/// migrations before the application exists: anything bound here that asks for an application service —
/// a cache manager, the entity event bus — stops the node before it starts. Those bindings live in
/// [ShapeshifterAiDaoModule], which the application injector installs.
public class ShapeshifterAiDbModule
        extends AbstractFlyWayDbModule<ShapeshifterAiDbConfig, ShapeshifterAiDbConnProvider> {

    private static final String MODULE = "stroom-shapeshifter-ai";
    private static final String FLYWAY_LOCATIONS = "stroom/shapeshifter/ai/impl/db/migration";
    private static final String FLYWAY_TABLE = "shapeshifter_ai_schema_history";

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
    protected Class<ShapeshifterAiDbConnProvider> getConnectionProviderType() {
        return ShapeshifterAiDbConnProvider.class;
    }

    @Override
    protected ShapeshifterAiDbConnProvider createConnectionProvider(final DataSource dataSource) {
        return new DataSourceImpl(dataSource);
    }

    private static class DataSourceImpl extends DataSourceProxy implements ShapeshifterAiDbConnProvider {

        private DataSourceImpl(final DataSource dataSource) {
            super(dataSource, MODULE);
        }
    }
}
