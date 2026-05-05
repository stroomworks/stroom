package stroom.document.asset.impl.db;

import stroom.db.util.AbstractFlyWayDbModule;
import stroom.db.util.DataSourceProxy;
import stroom.document.asset.impl.DocumentAssetConfig.DocumentAssetDbConfig;

import java.util.List;
import javax.sql.DataSource;

public class DocumentAssetDbModule
        extends AbstractFlyWayDbModule<DocumentAssetDbConfig, DocumentAssetDbConnProvider> {

    /** Name of this module */
    private static final String MODULE = "stroom-document-asset";

    /** Where the Flyway SQL scripts are */
    private static final String FLYWAY_LOCATIONS = "stroom/document/asset/impl/db/migration";

    /** Table with the Flyway history */
    private static final String FLYWAY_TABLE = "visualisation_assets_schema_history";

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
    protected Class<DocumentAssetDbConnProvider> getConnectionProviderType() {
        return DocumentAssetDbConnProvider.class;
    }

    @Override
    protected DocumentAssetDbConnProvider createConnectionProvider(final DataSource dataSource) {
        return new DataSourceImpl(dataSource);
    }

    private static class DataSourceImpl extends DataSourceProxy implements DocumentAssetDbConnProvider {
        private DataSourceImpl(final DataSource dataSource) {
            super(dataSource, MODULE);
        }
    }

}
