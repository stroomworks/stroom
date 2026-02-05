package stroom.dashboard.impl.visualisation;

import stroom.importexport.api.ImportExportAsset;
import stroom.visualisation.shared.VisualisationAssets;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collection;
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
     * Creates a new folder.
     * The updates are stored in the Draft table and made live by calling saveDraftToLive().
     * @param userUuid The user ID that is doing the update
     * @param ownerDocId The document that owns the assets
     * @param path The path to the folder, including the folder name.
     * @throws IOException If something goes wrong.
     */
    void updateNewFolder(String userUuid,
                         String ownerDocId,
                         String path)
        throws IOException;

    /**
     * Creates a new file.
     * The updates are stored in the Draft table and made live by calling saveDraftToLive().
     * @param userUuid The user ID that is doing the update
     * @param ownerDocId The document that owns the assets
     * @param path The path to the file, including the file name and extension.
     * @param mimetype The mimetype of the file. Can be null in which case the extension
     *                 will be used to derive the mimetype in the Servlet.
     * @throws IOException If something goes wrong.
     */
    void updateNewFile(String userUuid,
                       String ownerDocId,
                       String path,
                       String mimetype)
        throws IOException;

    /**
     * Creates a new file from a file upload.
     * The updates are stored in the Draft table and made live by calling saveDraftToLive().
     * @param userUuid The user ID that is doing the update
     * @param ownerDocId The document that owns the assets
     * @param path The path to the file, including the file name and extension.
     * @param mimetype The mimetype of the file. Can be null in which case the extension
     *                 will be used to derive the mimetype in the Servlet.
     * @param uploadStream The stream to read the file contents from.
     *                     Must not be null.
     * @throws IOException If something goes wrong.
     */
    void updateNewUploadedFile(String userUuid,
                               String ownerDocId,
                               String path,
                               String mimetype,
                               InputStream uploadStream)
        throws IOException;

    /**
     * Deletes a folder or file.
     * The updates are stored in the Draft table and made live by calling saveDraftToLive().
     * @param userUuid The user ID that is doing the update
     * @param ownerDocId The document that owns the assets
     * @param path The path to the folder or file to be deleted, including the item name.
     * @param isFolder Whether the thing being deleted is a file or folder.
     * @throws IOException If something goes wrong.
     */
    void updateDelete(String userUuid,
                      String ownerDocId,
                      String path,
                      boolean isFolder)
        throws IOException;

    /**
     * Renames a file or folder.
     * The updates are stored in the Draft table and made live by calling saveDraftToLive().
     * @param userUuid The user ID that is doing the update
     * @param ownerDocId The document that owns the assets
     * @param oldPath The existing path to the folder or file, including the item name.
     * @param newPath What the path needs to be changed to.
     * @param isFolder true if the thing being renamed is a folder, false if it is a file.
     * @throws IOException If something goes wrong.
     */
    void updateRename(String userUuid,
                      String ownerDocId,
                      String oldPath,
                      String newPath,
                      boolean isFolder)
        throws IOException;

    /**
     * Updates the content in a file.
     * The updates are stored in the Draft table and made live by calling saveDraftToLive().
     * @param userUuid The user ID that is doing the update
     * @param ownerDocId The document that owns the assets
     * @param path The path to the file, including the file name and extension.
     * @param content The new content for the file.
     * @throws IOException If something goes wrong.
     */
    void updateContent(String userUuid,
                       String ownerDocId,
                       String path,
                       byte[] content)
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
    List<ImportExportAsset> getAssetsForExport(String ownerId) throws IOException;

    /**
     * Imports live assets during import. Called from VisualisationStoreImpl.
     * @param ownerId The ID of the document that owns these assets.
     * @param pathAssets The assets that are stored under paths during import/export.
     * @throws IOException If something goes wrong.
     */
    void setAssetsFromImport(String ownerId, Collection<ImportExportAsset> pathAssets) throws IOException;

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
