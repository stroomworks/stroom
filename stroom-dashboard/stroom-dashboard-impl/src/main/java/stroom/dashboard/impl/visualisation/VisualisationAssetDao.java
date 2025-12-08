package stroom.dashboard.impl.visualisation;

import stroom.visualisation.shared.VisualisationAssets;

import java.io.IOException;

/**
 * Provides a way to store visualisation assets within the database.
 */
public interface VisualisationAssetDao {

    /**
     * Returns all the assets for a given docRef.
     * @param ownerId The owner of the assets
     * @return Assets to display in UI.
     */
    VisualisationAssets fetchAssets(String ownerId) throws IOException;

    /**
     * Stores all assets for a given DocRef.
     * @param ownerDocId The document which owns the assets.
     * @param visAssets  The assets that need to be stored.
     */
    void storeAssets(String ownerDocId, VisualisationAssets visAssets) throws IOException;

    /**
     * Stores the given data in the database.
     * @param assetId The ID of the visualisation asset we're storing data for.
     * @param data The data to store.
     */
    void storeData(String assetId,  byte[] data) throws IOException;

    /**
     * Gets the data for a given asset.
     * @param documentId The ID of the owner document we want the data for.
     * @param assetPath The path of the asset within the tree.
     * @return The data for the asset. May return null if the asset is not found.
     */
    byte[] getData(String documentId, String assetPath) throws IOException;

}
