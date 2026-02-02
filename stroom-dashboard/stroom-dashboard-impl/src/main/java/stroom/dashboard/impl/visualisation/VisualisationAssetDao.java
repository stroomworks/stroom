package stroom.dashboard.impl.visualisation;

import stroom.importexport.api.ImportExportAsset;
import stroom.visualisation.shared.VisualisationAssets;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Provides a way to store visualisation assets within the database.
 */
public interface VisualisationAssetDao {

    /**
     * Returns all the assets for a given docRef.
     * @param userUuid The user ID that we want draft info for
     * @param ownerId The document that owns the assets
     * @return Assets to display in UI.
     * @throws IOException If something goes wrong.
     */
    VisualisationAssets fetchDraftAssets(String userUuid,
                                         String ownerId) throws IOException;

    /**
     * Stores a draft version of all assets under the given username.
     * @param userUuid The user ID that we want draft info for
     * @param ownerDocId The document that owns the assets.
     * @param visAssets The assets to store.
     * @throws IOException If something goes wrong.
     */
    void storeDraftAssets(String userUuid,
                          String ownerDocId,
                          VisualisationAssets visAssets)
            throws IOException;

    /**
     * Stores a draft version of the asset.
     * @param userUuid The user ID that we're setting draft data for.
     * @param assetId The UUID of the asset.
     * @param data The data to store.
     * @throws IOException If something goes wrong.
     */
    void storeDraftData(String userUuid,
                        String assetId,
                        byte[] data)
            throws IOException;

    /**
     * Copies all draft information into the main storage so it is live.
     * @param userUuid The user to copy draft information for.
     * @param documentId The document ID that owns the draft information.
     * @throws IOException If something goes wrong.
     */
    void saveDraftToLive(String userUuid, String documentId) throws IOException;

    /**
     * Empties the draft data so fetchDraftAssets() will return the Live data again.
     * @param userUuid Username to revert draft data for.
     * @param documentId The document ID that owns the draft information.
     * @throws IOException If something goes wrong.
     */
    void revertDraftFromLive(String userUuid, String documentId) throws IOException;

    /**
     * Returns live assets for serialising the assets for a document ID.
     * @param ownerId The document that owns the assets
     * @return ImportExportAssets holding the relevant Import/Export data.
     * @throws IOException If something goes wrong.
     */
    List<ImportExportAsset> getExportAssets(String ownerId) throws IOException;

    /**
     * Gets the data for a given asset.
     * @param documentId The ID of the owner document we want the data for.
     * @param assetPath The path of the asset within the tree.
     * @return The data for the asset. Returns null if the asset is not found.
     */
    byte[] getLiveData(String documentId, String assetPath) throws IOException;

    /**
     * Gets the timestamp for the entry for the asset. Returns null if the asset isn't found.
     * @param documentId The ID of the owner document we want the data for.
     * @param assetPath The path of the asset within the tree.
     * @return The timestamp for the asset. Returns null if the asset isn't found.
     * @throws IOException If something goes wrong.
     */
    Instant getLiveModifiedTimestamp(String documentId, String assetPath) throws IOException;

}
