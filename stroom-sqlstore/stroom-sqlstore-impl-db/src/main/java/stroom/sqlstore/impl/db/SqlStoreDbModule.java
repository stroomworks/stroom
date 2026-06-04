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

package stroom.sqlstore.impl.db;

import stroom.db.util.DataSourceFactory;
import stroom.db.util.FlywayUtil;
import stroom.sqlstore.impl.SqlStoreConfig.SqlStoreDbConfig;
import stroom.sqlstore.impl.UpdatableTemporalStoreDao;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import jakarta.inject.Singleton;

import java.util.List;
import javax.sql.DataSource;

public class SqlStoreDbModule extends AbstractModule {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(SqlStoreDbModule.class);
    private static final String MODULE = "stroom-sqlstore";
    private static final String FLYWAY_LOCATIONS = "stroom/sqlstore/impl/db/migration";
    private static final String FLYWAY_TABLE = "sqlstore_schema_history";

    @Override
    protected void configure() {
        bind(UpdatableTemporalStoreDao.class).to(UpdatableTemporalStoreDaoImpl.class);
    }

    @Provides
    @Singleton
    @SuppressWarnings("unused")
    public SqlStoreDbConnProvider getConnectionProvider(final SqlStoreDbConfig config,
                                                        final DataSourceFactory dataSourceFactory) {
        LOGGER.debug(() -> "Creating SqlStoreDbConnProvider");
        final DataSource dataSource = dataSourceFactory.create(config, "sqlstore", false);
        FlywayUtil.migrate(dataSource, List.of(FLYWAY_LOCATIONS), FLYWAY_TABLE, MODULE);
        return new SqlStoreDbConnProvider(dataSource);
    }
}
