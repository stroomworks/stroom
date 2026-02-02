package stroom.dashboard.impl.db;

import stroom.dashboard.impl.visualisation.VisualisationAssetDao;
import stroom.db.util.JooqUtil;
import stroom.importexport.api.ByteArrayImportExportAsset;
import stroom.importexport.api.ImportExportAsset;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.visualisation.shared.VisualisationAsset;
import stroom.visualisation.shared.VisualisationAssets;
import stroom.dashboard.impl.db.jooq.Tables;

import com.google.common.hash.Hashing;
import com.google.inject.Inject;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Result;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DB implementation of the DAO.
 */
public class VisualisationAssetDaoImpl implements VisualisationAssetDao {

    /** Boostraps connection */
    private final VisualisationAssetDbConnProvider connProvider;

    /** Logger */
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisualisationAssetDaoImpl.class);

    /** Byte value for true */
    private static final byte BYTE_TRUE = 1;

    /** Byte value for false */
    private static final byte BYTE_FALSE = 0;

    @SuppressWarnings("unused")
    @Inject
    VisualisationAssetDaoImpl(final VisualisationAssetDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    /**
     * Utility method to convert a Result set into a list of assets.
     * @param result The result from Jooq
     * @return A list of assets;
     */
    private List<VisualisationAsset> resultToAssets(final Result<Record3<String, String, Byte>> result) {
        final List<VisualisationAsset> assets = new ArrayList<>(result.size());
        for (final Record3<String, String, Byte> record : result) {
            final VisualisationAsset asset = new VisualisationAsset(record.value1(),
                    record.value2(),
                    record.value3() == BYTE_TRUE);
            assets.add(asset);
            LOGGER.info("    Fetching asset: {}", asset);
        }

        return assets;
    }

    /**
     * Utility method to convert a Result into a list of ImportExportAssets.
     */
    private List<ImportExportAsset> resultToImportExportAssets(final Result<Record2<String, byte[]>> result) {
        final List<ImportExportAsset> assets = new ArrayList<>(result.size());
        for (final Record2<String, byte[]> record: result) {
            final ImportExportAsset asset = new ByteArrayImportExportAsset(record.value1(), record.value2());
            LOGGER.info("result -> assets: {}", asset);
            assets.add(asset);
        }
        return assets;
    }

    @Override
    public VisualisationAssets fetchDraftAssets(final String userUuid,
                                                final String ownerDocId) throws IOException {
        LOGGER.info("Fetching assets for user {}, document {}", userUuid, ownerDocId);
        try {
            // Do everything in one transaction
            final List<VisualisationAsset> assets = new ArrayList<>();
            final boolean[] dirty = new boolean[1];
            dirty[0] = true;

            JooqUtil.transaction(connProvider, txnContext -> {
                Result<Record3<String, String, Byte>> result = txnContext
                        .select(Tables.VISUALISATION_ASSETS_DRAFT.ASSET_UUID,
                                Tables.VISUALISATION_ASSETS_DRAFT.PATH,
                                Tables.VISUALISATION_ASSETS_DRAFT.IS_FOLDER)
                        .from(Tables.VISUALISATION_ASSETS_DRAFT)
                        .where(Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID.eq(ownerDocId)
                                .and(Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID.eq(userUuid)))
                        .fetch();

                if (result.isEmpty()) {
                    // No results so try looking in the live table instead
                    LOGGER.info("No draft assets - getting from live table");
                    dirty[0] = false;
                    result = txnContext
                            .select(Tables.VISUALISATION_ASSETS.ASSET_UUID,
                                    Tables.VISUALISATION_ASSETS.PATH,
                                    Tables.VISUALISATION_ASSETS.IS_FOLDER)
                            .from(Tables.VISUALISATION_ASSETS)
                            .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(ownerDocId))
                            .fetch();
                }

                assets.addAll(resultToAssets(result));
            });
            LOGGER.info("Dirty is {}", dirty[0]);
            return new VisualisationAssets(ownerDocId, dirty[0], null, assets);
        } catch (final DataAccessException e) {
            LOGGER.error("Error fetching draft visualisation assets for user {}, document {}: {}",
                    userUuid, ownerDocId, e.getMessage(), e);
            throw new IOException("Error fetching visualisation assets: " + e.getMessage(), e);
        }

    }

    @Override
    public void storeDraftAssets(final String userUuid,
                                 final String ownerDocId,
                                 final VisualisationAssets visAssets)
            throws IOException {
        LOGGER.info("storeDraftAssets(): {}", visAssets);
        try {
            // Do everything in one transaction
            JooqUtil.transaction(connProvider, txnContext -> {

                // Get a list of the existing assets
                final Set<String> existingAssetIds = new HashSet<>();
                try {
                    final Result<Record2<String, String>> result = txnContext
                            .select(Tables.VISUALISATION_ASSETS_DRAFT.ASSET_UUID,
                                    Tables.VISUALISATION_ASSETS_DRAFT.PATH)
                            .from(Tables.VISUALISATION_ASSETS_DRAFT)
                            .where(Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID.eq(ownerDocId)
                                    .and(Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID.eq(userUuid)))
                            .fetch();
                    for (final Record2<String, String> record : result) {
                        existingAssetIds.add(record.value1());
                        LOGGER.info("Found existing asset {}:{}", record.value1(), record.value2());
                    }
                } catch (final DataAccessException e) {
                    LOGGER.error("Error getting list of existing assets: {}", e.getMessage(), e);
                    throw new DataAccessException("Error getting list of existing assets: " +e.getMessage(), e);
                }

                // Then store the updated assets
                final List<VisualisationAsset> assets = visAssets.getAssets();
                for (final VisualisationAsset asset : assets) {
                    LOGGER.info("Storing asset: {}", asset);
                    final byte[] assetPathHash =
                            Hashing.sha256().hashString(asset.getPath(), StandardCharsets.UTF_8).asBytes();

                    try {
                        txnContext
                                .insertInto(Tables.VISUALISATION_ASSETS_DRAFT,
                                        Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID,
                                        Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID,
                                        Tables.VISUALISATION_ASSETS_DRAFT.ASSET_UUID,
                                        Tables.VISUALISATION_ASSETS_DRAFT.PATH,
                                        Tables.VISUALISATION_ASSETS_DRAFT.PATH_HASH,
                                        Tables.VISUALISATION_ASSETS_DRAFT.IS_FOLDER)
                                .values(userUuid,
                                        ownerDocId,
                                        asset.getId(),
                                        asset.getPath(),
                                        assetPathHash,
                                        asset.isFolder() ? BYTE_TRUE : BYTE_FALSE)
                                .onDuplicateKeyUpdate()
                                .set(Tables.VISUALISATION_ASSETS_DRAFT.PATH, asset.getPath())
                                .set(Tables.VISUALISATION_ASSETS_DRAFT.PATH_HASH, assetPathHash)
                                .set(Tables.VISUALISATION_ASSETS_DRAFT.IS_FOLDER, asset.isFolder() ? BYTE_TRUE : BYTE_FALSE)
                                .where(Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID.eq(userUuid)
                                        .and(Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID.eq(ownerDocId))
                                        .and(Tables.VISUALISATION_ASSETS_DRAFT.ASSET_UUID.eq(asset.getId())))
                                .execute();
                        existingAssetIds.remove(asset.getId());
                    } catch (final DataAccessException e) {
                        LOGGER.error("Error storing asset '{}': {}", asset, e.getMessage(), e);
                        throw new DataAccessException("Error storing asset: " + e.getMessage(), e);
                    }
                }

                // Finally delete any excess assets
                for (final String assetId : existingAssetIds) {
                    try {
                        LOGGER.info("Removing extra asset {}", assetId);
                        txnContext
                                .delete(Tables.VISUALISATION_ASSETS)
                                .where(Tables.VISUALISATION_ASSETS.ASSET_UUID.eq(assetId))
                                .execute();
                    } catch (final DataAccessException e) {
                        LOGGER.error("Error deleting excess assets: {}", e.getMessage(), e);
                        throw new DataAccessException("Error deleting asset: " + e.getMessage(), e);
                    }
                }

            });
        } catch (final DataAccessException e) {
            // Message already modified by the time the exception gets here
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public void storeDraftData(final String userUuid,
                               final String assetId,
                               final byte[] data) throws IOException {
        LOGGER.info("Storing data for asset {}", assetId);
        try {
            // Always called after storeAssets(), so the row will already exist
            JooqUtil.context(connProvider, context -> context
                    .update(Tables.VISUALISATION_ASSETS_DRAFT)
                    .set(Tables.VISUALISATION_ASSETS_DRAFT.DATA, data)
                    .where(Tables.VISUALISATION_ASSETS_DRAFT.ASSET_UUID.eq(assetId)
                            .and(Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID.eq(userUuid)))
                    .execute());
        } catch (final DataAccessException e) {
            LOGGER.error("Error storing visualisation asset data: {}", e.getMessage(), e);
            throw new IOException("Error storing data: " + e.getMessage(), e);
        }
    }

    @Override
    public void saveDraftToLive(final String userUuid, final String documentId) throws IOException {
        LOGGER.info("saveDraftToLive({}, {})", userUuid, documentId);

        // Timestamp
        final long timestamp = Instant.now().toEpochMilli();

        // This could probably be more efficient to avoid copying lots of data when it hasn't changed
        // However this version works, and it all happens inside the DB, so shouldn't be too bad.

        try {
            // Do everything in one transaction
            JooqUtil.transaction(connProvider, txnContext -> {
                    LOGGER.info("Deleting existing live assets");
                    // Delete all existing live content for the owning document ID
                    int recordCount = txnContext
                            .deleteFrom(Tables.VISUALISATION_ASSETS)
                            .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(documentId))
                            .execute();
                    LOGGER.info("{} records deleted in live", recordCount);

                    LOGGER.info("Copying data into Live");
                    // Copy all relevant data from the user draft table into the live table
                    recordCount = txnContext
                            .insertInto(Tables.VISUALISATION_ASSETS,
                                    Tables.VISUALISATION_ASSETS.MODIFIED,
                                    Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID,
                                    Tables.VISUALISATION_ASSETS.ASSET_UUID,
                                    Tables.VISUALISATION_ASSETS.PATH,
                                    Tables.VISUALISATION_ASSETS.PATH_HASH,
                                    Tables.VISUALISATION_ASSETS.IS_FOLDER,
                                    Tables.VISUALISATION_ASSETS.DATA)
                            .select(txnContext.select(
                                            DSL.val(timestamp),
                                            Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID,
                                            Tables.VISUALISATION_ASSETS_DRAFT.ASSET_UUID,
                                            Tables.VISUALISATION_ASSETS_DRAFT.PATH,
                                            Tables.VISUALISATION_ASSETS_DRAFT.PATH_HASH,
                                            Tables.VISUALISATION_ASSETS_DRAFT.IS_FOLDER,
                                            Tables.VISUALISATION_ASSETS_DRAFT.DATA)
                                    .from(Tables.VISUALISATION_ASSETS_DRAFT)
                                    .where(Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID.eq(userUuid)
                                            .and(Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID.eq(documentId))))
                            .execute();
                    LOGGER.info("Copied {} records from draft into Live", recordCount);

                    // Delete everything in the draft table so next time we'll get clean live data
                    recordCount = txnContext
                            .deleteFrom(Tables.VISUALISATION_ASSETS_DRAFT)
                            .where(Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID.eq(userUuid)
                                    .and(Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID.eq(documentId)))
                            .execute();
                LOGGER.info("Deleted {} records in the draft table", recordCount);
            });
        } catch (final DataAccessException e) {
            LOGGER.error("Error saving draft assets to live for user {}, doc {}: {}",
                    userUuid, documentId, e.getMessage(), e);
            throw new IOException("Error saving draft assets to live: " + e.getMessage(), e);
        }
    }

    @Override
    public void revertDraftFromLive(final String userUuid, final String documentId) throws IOException {
        LOGGER.info("revertDraftFromLive()");
        try {
            JooqUtil.context(connProvider, context -> context
                    .deleteFrom(Tables.VISUALISATION_ASSETS_DRAFT)
                    .where(Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID.eq(userUuid)
                            .and(Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID.eq(documentId)))
                    .execute());
        } catch (final DataAccessException e) {
            LOGGER.error("Error reverting draft from live: {}", e.getMessage(), e);
            throw new IOException("Error reverting draft: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ImportExportAsset> getAssetsForExport(final String documentId) throws IOException {
        LOGGER.info("Getting export assets for document {}", documentId);
        try {
            final Result<Record2<String, byte[]>> result =
                    JooqUtil.contextResult(connProvider, context -> context
                    .select(Tables.VISUALISATION_ASSETS.PATH,
                            Tables.VISUALISATION_ASSETS.DATA)
                    .from(Tables.VISUALISATION_ASSETS)
                    .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(documentId))
                    .fetch());
            return resultToImportExportAssets(result);
        } catch (final DataAccessException e) {
            LOGGER.error("Error getting export assets for document '{}': {}",
                    documentId, e.getMessage(), e);
            throw new IOException("Error getting export assets for document: " + e.getMessage(), e);
        }
    }

    @Override
    public void setAssetsFromImport(final String documentId, final Collection<ImportExportAsset> pathAssets)
            throws IOException {
        LOGGER.info("Setting import assets for document {}", documentId);

        final long timestamp = Instant.now().toEpochMilli();

        try {
            JooqUtil.transaction(connProvider, txnContext -> {
                try {
                    // Delete all existing live content for the owning document ID
                    final int recordCount = txnContext
                            .deleteFrom(Tables.VISUALISATION_ASSETS)
                            .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(documentId))
                            .execute();
                    LOGGER.info("{} records deleted in live before import", recordCount);

                    for (final ImportExportAsset asset : pathAssets) {
                        final String assetUuid = UUID.randomUUID().toString();
                        final String path = asset.getKey();
                        final byte[] pathHash = Hashing.sha256().hashString(path, StandardCharsets.UTF_8).asBytes();
                        final byte[] data = asset.getInputData();
                        final boolean isFolder = data == null;

                        txnContext
                                .insertInto(Tables.VISUALISATION_ASSETS,
                                        Tables.VISUALISATION_ASSETS.MODIFIED,
                                        Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID,
                                        Tables.VISUALISATION_ASSETS.ASSET_UUID,
                                        Tables.VISUALISATION_ASSETS.PATH,
                                        Tables.VISUALISATION_ASSETS.PATH_HASH,
                                        Tables.VISUALISATION_ASSETS.DATA,
                                        Tables.VISUALISATION_ASSETS.IS_FOLDER)
                                .values(timestamp,
                                        documentId,
                                        assetUuid,
                                        path,
                                        pathHash,
                                        data,
                                        isFolder
                                                ? BYTE_TRUE
                                                : BYTE_FALSE)
                                .execute();
                    }
                } catch (final IOException e) {
                    throw new DataAccessException("Error reading data from asset: " + e.getMessage(), e);
                }
            });
        } catch (final DataAccessException e) {
            LOGGER.error("Error setting import assets for document '{}': {}",
                    documentId, e.getMessage(), e);
            throw new IOException("Error setting import assets for document: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] getLiveData(final String documentId, final String assetPath) throws IOException {
        LOGGER.info("Getting data for document {}, path {}", documentId, assetPath);
        try {
            final byte[] assetPathHash = Hashing.sha256().hashString(assetPath, StandardCharsets.UTF_8).asBytes();
            final Result<Record1<byte[]>> result = JooqUtil.contextResult(connProvider, context -> context
                    .select(Tables.VISUALISATION_ASSETS.DATA)
                    .from(Tables.VISUALISATION_ASSETS)
                    .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(documentId)
                            .and(Tables.VISUALISATION_ASSETS.PATH_HASH.eq(assetPathHash))
                            .and(Tables.VISUALISATION_ASSETS.PATH.eq(assetPath)))
                    .fetch());
            if (result.isEmpty()) {
                // Return null to indicate not found
                return null;
            } else {
                return result.getFirst().value1();
            }
        } catch (final DataAccessException e) {
            LOGGER.error("Error getting data for document '{}', path '{}': {}",
                    documentId, assetPath, e.getMessage(), e);
            throw new IOException("Error getting data for document: " + e.getMessage(), e);
        }
    }

    @Override
    public Instant getLiveModifiedTimestamp(final String documentId, final String assetPath) throws IOException {
        LOGGER.info("getLiveModifiedTimestamp");
        try {
            final byte[] assetPathHash = Hashing.sha256().hashString(assetPath, StandardCharsets.UTF_8).asBytes();
            final Result<Record1<Long>> result = JooqUtil.contextResult(connProvider, context -> context
                    .select(Tables.VISUALISATION_ASSETS.MODIFIED)
                    .from(Tables.VISUALISATION_ASSETS)
                    .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(documentId)
                            .and(Tables.VISUALISATION_ASSETS.PATH_HASH.eq(assetPathHash))
                            .and(Tables.VISUALISATION_ASSETS.PATH.eq(assetPath)))
                    .fetch());
            if (result.isEmpty()) {
                // Return null to indicate not found
                return null;
            } else {
                final Long timestamp = result.getFirst().value1();
                return Instant.ofEpochMilli(timestamp);
            }
        } catch (final DataAccessException e) {
            LOGGER.error("Error getting timestamp for document '{}', path '{}': {}",
                    documentId, assetPath, e.getMessage(), e);
            throw new IOException("Error getting timestamp for document: " + e.getMessage(), e);
        }
    }

}
