package stroom.dashboard.impl.db;

import stroom.dashboard.impl.visualisation.VisualisationAssetDao;
import stroom.db.util.JooqUtil;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Injected constructor.
     */
    @SuppressWarnings("unused")
    @Inject
    VisualisationAssetDaoImpl(final VisualisationAssetDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public VisualisationAssets fetchDraftAssets(final String userUuid,
                                                final String ownerDocId) throws IOException {
        LOGGER.info("Fetching assets for user {}, document {}", userUuid, ownerDocId);
        try {
            // Do everything in one transaction
            final List<VisualisationAsset> assets = new ArrayList<>();

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
                    result = txnContext
                            .select(Tables.VISUALISATION_ASSETS.ASSET_UUID,
                                    Tables.VISUALISATION_ASSETS.PATH,
                                    Tables.VISUALISATION_ASSETS.IS_FOLDER)
                            .from(Tables.VISUALISATION_ASSETS)
                            .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(ownerDocId))
                            .fetch();
                }

                for (final Record3<String, String, Byte> record : result) {
                    final VisualisationAsset asset = new VisualisationAsset(record.value1(),
                            record.value2(),
                            record.value3() == BYTE_TRUE);
                    assets.add(asset);
                    LOGGER.info("    Fetching asset: {}", asset);
                }
            });

            return new VisualisationAssets(ownerDocId, null, assets);
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
            // Timestamp
            final long timestamp = Instant.now().toEpochMilli();

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
        // Timestamp
        final long timestamp = Instant.now().toEpochMilli();

        // TODO This could probably be more efficient to avoid copying lots of data when it hasn't changed

        try {
            // Do everything in one transaction
            JooqUtil.transaction(connProvider, txnContext -> {
                // Delete all existing live content for the owning document ID
                txnContext
                        .deleteFrom(Tables.VISUALISATION_ASSETS)
                        .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(documentId))
                        .execute();

                // Copy all relevant data from the user draft table into the live table
                txnContext
                        .insertInto(Tables.VISUALISATION_ASSETS,
                                Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID,
                                Tables.VISUALISATION_ASSETS.ASSET_UUID,
                                Tables.VISUALISATION_ASSETS.PATH,
                                Tables.VISUALISATION_ASSETS.PATH_HASH,
                                Tables.VISUALISATION_ASSETS.IS_FOLDER,
                                Tables.VISUALISATION_ASSETS.DATA)
                        .select(txnContext.select(Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID,
                                        Tables.VISUALISATION_ASSETS_DRAFT.ASSET_UUID,
                                        Tables.VISUALISATION_ASSETS_DRAFT.PATH,
                                        Tables.VISUALISATION_ASSETS_DRAFT.PATH_HASH,
                                        Tables.VISUALISATION_ASSETS_DRAFT.IS_FOLDER,
                                        Tables.VISUALISATION_ASSETS_DRAFT.DATA)
                                .where(Tables.VISUALISATION_ASSETS_DRAFT.DRAFT_USER_UUID.eq(userUuid)
                                        .and(Tables.VISUALISATION_ASSETS_DRAFT.OWNER_DOC_UUID.eq(documentId))))
                        .execute();

                // Set the timestamp on the live table
                txnContext.update(Tables.VISUALISATION_ASSETS)
                        .set(Tables.VISUALISATION_ASSETS.MODIFIED, timestamp)
                        .where(Tables.VISUALISATION_ASSETS.OWNER_DOC_UUID.eq(documentId))
                        .execute();
            });
        } catch (final DataAccessException e) {
            LOGGER.error("Error saving draft assets to live for user {}, doc {}: {}",
                    userUuid, documentId, e.getMessage(), e);
            throw new IOException("Error saving draft assets to live: " + e.getMessage(), e);
        }
    }

    @Override
    public void revertDraftFromLive(final String userUuid, final String documentId) throws IOException {
        // TODO
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
