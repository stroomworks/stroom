package stroom.dashboard.impl.visualisation;

import stroom.docref.DocRef;
import stroom.importexport.api.ImportExportAsset;
import stroom.resource.api.ResourceStore;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResourceKey;
import stroom.visualisation.shared.VisualisationAssets;
import stroom.visualisation.shared.VisualisationDoc;

import com.google.inject.Inject;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Intermediates between VisualisationAssetResource and VisualisationAssetDao.
 * Primarily responsible for checking permissions.
 * Allows easy access to Assets within the database.
 */
public class VisualisationAssetService {

    /** DAO to talk to the DB */
    private final VisualisationAssetDao dao;

    /** Allows access to uploaded files */
    private final ResourceStore resourceStore;

    /** Security checks */
    private final SecurityContext securityContext;

    /** Logger */
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisualisationAssetService.class);

    /**
     * Injected constructor.
     */
    @SuppressWarnings("unused")
    @Inject
    public VisualisationAssetService(final VisualisationAssetDao dao,
                                     final ResourceStore resourceStore,
                                     final SecurityContext securityContext) {
        this.dao = dao;
        this.resourceStore = resourceStore;
        this.securityContext = securityContext;
    }

    /**
     * Used by the UI to get all the asset metadata associated with a document.
     * @param ownerId The ID of the document that owns these assets.
     * @return An object that holds all the metadata about the assets. Note that
     *         getUploadedFiles() will always return an empty map.
     *         This will be the draft assets for the user logged in.
     * @throws IOException if something goes wrong.
     */
    VisualisationAssets fetchDraftAssets(final String ownerId) throws IOException {
        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.VIEW)) {
            return dao.fetchDraftAssets(securityContext.getUserRef().getUuid(), ownerId);
        } else {
            // No permission so return empty assets
            LOGGER.info("User does not have permission to see assets");
            return new VisualisationAssets(ownerId);
        }
    }

    /**
     * Creates a new folder at the given path.
     * @param ownerDocId Document that owns the assets. Must not be null.
     * @param path Path and name of the new file. Must not be null.
     * @throws IOException If something goes wrong.
     */
    void updateNewFolder(final String ownerDocId, final String path) throws IOException {
        Objects.requireNonNull(ownerDocId);
        Objects.requireNonNull(path);

        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            dao.updateNewFolder(
                    securityContext.getUserRef().getUuid(),
                    ownerDocId,
                    path);
        } else {
            LOGGER.info("User does not have permission to create a new folder '{}'", path);
        }

    }

    /**
     * Creates a new file at the given path.
     * @param ownerDocId Document that owns the assets. Must not be null.
     * @param path Path and name of the new file. Must not be null.
     * @param mimetype Mimetype of the file. Can be null in which case the file extension
     *                 will be used to derive mimetype.
     * @throws IOException If something goes wrong.
     */
    void updateNewFile(final String ownerDocId,
                       final String path,
                       final String mimetype) throws IOException {
        Objects.requireNonNull(ownerDocId);
        Objects.requireNonNull(path);

        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            dao.updateNewFile(
                    securityContext.getUserRef().getUuid(),
                    ownerDocId,
                    path,
                    mimetype);
        } else {
            LOGGER.info("User does not have permission to create a new file '{}'", path);
        }
    }

    /**
     * Creates a new file at the given path from a file upload.
     * @param ownerDocId Document that owns the assets. Must not be null.
     * @param path Path and name of the new file. Must not be null.
     * @param resourceKey The resourceKey associated with the upload. Must not be null.
     * @param mimetype The optional mimetype. Can be null.
     * @throws IOException If something goes wrong.
     */
    void updateNewUploadedFile(final String ownerDocId,
                               final String path,
                               final ResourceKey resourceKey,
                               final String mimetype) throws IOException {
        Objects.requireNonNull(ownerDocId);
        Objects.requireNonNull(path);
        Objects.requireNonNull(resourceKey);

        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            final Path uploadPath = resourceStore.getTempFile(resourceKey);
            if (!uploadPath.toFile().exists()) {
                throw new IOException("The uploaded file does not exist");
            }
            // Stream the data into the database from the temp file
            try (final InputStream uploadStream = new BufferedInputStream(new FileInputStream(uploadPath.toFile()))) {
                dao.updateNewUploadedFile(
                        securityContext.getUserRef().getUuid(),
                        ownerDocId,
                        path,
                        mimetype,
                        uploadStream);
                resourceStore.deleteTempFile(resourceKey);
            }
        } else {
            LOGGER.info("User does not have permission to create a new file from an upload: '{}'", path);
        }
    }

    /**
     * Deletes a file or folder at the given path, and everything underneath that path.
     * @param ownerDocId Document that owns the assets. Must not be null.
     * @param path Path and name of the file or folder to delete. Must not be null.
     * @param isFolder Whether the thing to delete is a file or folder.
     */
    void updateDelete(final String ownerDocId,
                      final String path,
                      final boolean isFolder) throws IOException {
        Objects.requireNonNull(ownerDocId);
        Objects.requireNonNull(path);

        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            dao.updateDelete(
                    securityContext.getUserRef().getUuid(),
                    ownerDocId,
                    path,
                    isFolder);
        } else {
            LOGGER.info("User does not have permission to delete an item '{}'", path);
        }
    }

    /**
     * Renames a file or folder at the oldPath.
     * @param ownerDocId Document that owns the assets. Must not be null.
     * @param oldPath Where the thing used to be.
     * @param newPath Where the thing needs to be.
     * @param isFolder true if the thing is a folder, false if it is a file.
     */
    void updateRename(final String ownerDocId,
                      final String oldPath,
                      final String newPath,
                      final boolean isFolder) throws IOException {
        Objects.requireNonNull(ownerDocId);
        Objects.requireNonNull(oldPath);
        Objects.requireNonNull(newPath);

        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            dao.updateRename(
                    securityContext.getUserRef().getUuid(),
                    ownerDocId,
                    oldPath,
                    newPath,
                    isFolder);
        } else {
            LOGGER.info("User does not have permission to rename an item '{}'", oldPath);
        }
    }

    /**
     * Updates the content in a file.
     * @param ownerDocId Document that owns the assets. Must not be null.
     * @param path Location of the document to update the content for.
     *
     */
    void updateContent(final String ownerDocId,
                       final String path,
                       final byte[] content) throws IOException {
        Objects.requireNonNull(ownerDocId);
        Objects.requireNonNull(path);

        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            dao.updateContent(
                    securityContext.getUserRef().getUuid(),
                    ownerDocId,
                    path,
                    content);
        } else {
            LOGGER.info("User does not have permission to update the content of an item '{}'", path);
        }
    }

    /**
     * Copies all draft information into the main storage so it is live.
     * @param ownerDocId The document that owns these assets.
     * @throws IOException If something goes wrong.
     */
    public void saveDraftToLive(final String ownerDocId) throws IOException {
        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            dao.saveDraftToLive(securityContext.getUserRef().getUuid(), ownerDocId);
        }
    }

    /**
     * Empties the draft data so fetchDraftAssets() will return the Live data again.
     * @param ownerDocId The document that owns these assets.
     * @throws IOException If something goes wrong.
     */
    public void revertDraftFromLive(final String ownerDocId) throws IOException {
        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            dao.revertDraftFromLive(securityContext.getUserRef().getUuid(), ownerDocId);
        }
    }

    /**
     * Gets the data for a given asset. Called from the Servlet to get the asset for a given
     * document and path.
     * <br>TODO This data should be streamed rather than held in a byte[] in memory.
     * @param documentId The ID of the document that owns the asset.
     * @param assetPath The path of the visualisation asset we want the data for.
     * @return The data for the asset, or null if the asset is not found.
     * @throws IOException If something goes wrong with the IO, DB etc.
     * @throws PermissionException If the user doesn't have view permissions for these assets.
     */
    byte[] getLiveData(final String documentId, final String assetPath)
            throws IOException, PermissionException {
        LOGGER.info("Returning asset for {}, {}", documentId, assetPath);
        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, documentId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.VIEW)) {
            return dao.getLiveData(documentId, assetPath);
        } else {
            // Catch this higher up and return a 401.
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to view this asset");
        }
    }

    /**
     * Returns the timestamp when the given asset was modified. Called from the Servlet
     * so we know if we need to invalidate the cache.
     * @param documentId The ID of the document that owns the asset.
     *      * @param assetPath The path of the visualisation asset we want the data for.
     *      * @return The data for the asset, or null if the asset is not found.
     *      * @throws IOException If something goes wrong with the IO, DB etc.
     *      * @throws PermissionException If the user doesn't have view permissions for these assets.
     */
    Instant getLiveModifiedTimestamp(final String documentId, final String assetPath)
        throws IOException, PermissionException {
        LOGGER.info("Returning asset timestamp for {}, {}", documentId, assetPath);
        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, documentId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.VIEW)) {
            return dao.getLiveModifiedTimestamp(documentId, assetPath);
        } else {
            // Catch this higher up and return a 401.
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to view this asset");
        }
    }

    /**
     * Returns the assets in a form suitable for exporting.
     * @param docRef The ref of the owning document
     * @return Assets to export. Never null.
     * @throws IOException If something goes wrong
     * @throws PermissionException If the user doesn't have permission
     */
    Collection<ImportExportAsset> getAssetsForExport(final DocRef docRef)
            throws IOException, PermissionException {
        LOGGER.info("Returning assets for export for {}", docRef);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.VIEW)) {
            return dao.getAssetsForExport(docRef.getUuid());
        } else {
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to view this asset");
        }
    }

    /**
     * Sets assets for this visualisation during import.
     * @param docRef The document that owns these assets.
     * @param pathAssets The assets associated with the doc.
     * @throws IOException If something goes wrong.
     * @throws PermissionException If the user doesn't have EDIT permission.
     */
    void setAssetsFromImport(final DocRef docRef,
                             final Collection<ImportExportAsset> pathAssets)
        throws IOException, PermissionException {

        LOGGER.info("Setting assets from import for {}", docRef);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            dao.setAssetsFromImport(docRef.getUuid(), pathAssets);
        } else {
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to import these assets");
        }
    }

}
