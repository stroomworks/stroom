package stroom.data.store.impl.fs.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

class FsDataStoreDbConnProvider extends HikariDataSource {
    FsDataStoreDbConnProvider(final HikariConfig configuration) {
        super(configuration);
    }
}