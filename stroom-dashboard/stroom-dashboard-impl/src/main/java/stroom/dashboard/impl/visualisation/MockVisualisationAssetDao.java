package stroom.dashboard.impl.visualisation;

import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.visualisation.shared.VisualisationAsset;
import stroom.visualisation.shared.VisualisationAssets;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory implementation of the Visualisation Asset DAO. Used initially for development, then for testing.
 */
public class MockVisualisationAssetDao implements VisualisationAssetDao {

    /**
     * ownerUUID -> visUUID -> VisualisationAsset (asset metadata)
     */
    private final static Map<String, Map<String, VisualisationAsset>> MOCK_DB = new HashMap<>();

    /**
     * visUUID -> file contents
     */
    private final static Map<String, byte[]> MOCK_DB_DATA = new HashMap<>();

    /** Logger */
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(MockVisualisationAssetDao.class);

    @Override
    public VisualisationAssets fetchAssets(final String ownerId) {
        final VisualisationAssets visAssets = new VisualisationAssets(ownerId);
        Map<String, VisualisationAsset> assetMap = MOCK_DB.get(ownerId);
        if (assetMap == null) {
            LOGGER.info("No assets found for document owner ID {}", ownerId);
            assetMap = new HashMap<>();
            MOCK_DB.put(ownerId, assetMap);
        }
        LOGGER.info("MockVisualisationAssetDao.fetchAssets({}): {}", ownerId, assetMap.values());
        visAssets.addAllAssets(assetMap.values());
        return visAssets;
    }

    @Override
    public void storeAssets(final String ownerDocId,
                            final VisualisationAssets visAssets) {

        final Map<String, VisualisationAsset> assetMap = new HashMap<>();
        for (final VisualisationAsset asset : visAssets.getAssets()) {
            assetMap.put(asset.getId(), asset);
            LOGGER.info("MockVisualisationAssetDao.storeAssets({}): {}", ownerDocId, asset);
        }

        MOCK_DB.put(ownerDocId, assetMap);
        LOGGER.info("MockDB: {}", MOCK_DB);
    }

    @Override
    public void storeData(final String assetId, final byte[] data) {
        LOGGER.info("Storing data for asset {}", assetId);
        MOCK_DB_DATA.put(assetId, data);
    }

    @Override
    public byte[] getData(final String documentId, final String assetPath) throws IOException {
        LOGGER.info("MockDB: {}", MOCK_DB);
        final Map<String, VisualisationAsset> assetsMap = MOCK_DB.get(documentId);
        String assetId = null;
        if (assetsMap == null) {
            // Error - documentId not found
            throw new IOException("Document ID '" + documentId + "' does not have any assets");
        } else {
            // No index here so search for the path
            for (final Map.Entry<String, VisualisationAsset> assetEntry : assetsMap.entrySet()) {
                if (assetEntry.getValue().getPath().equals(assetPath)) {
                    assetId = assetEntry.getValue().getId();
                    break;
                }
            }
            if (assetId != null) {
                // Didn't find the path
                return MOCK_DB_DATA.get(assetId);
            } else {
                throw new IOException("Could not find an asset matching path '" + assetPath + "'");
            }
        }
    }
}
