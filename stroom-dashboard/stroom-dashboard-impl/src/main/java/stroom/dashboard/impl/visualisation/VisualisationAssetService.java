package stroom.dashboard.impl.visualisation;

import stroom.docref.DocRef;
import stroom.importexport.api.ByteArrayImportExportAsset;
import stroom.importexport.api.ImportExportAsset;
import stroom.resource.api.ResourceStore;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResourceKey;
import stroom.visualisation.shared.VisualisationAsset;
import stroom.visualisation.shared.VisualisationAssets;
import stroom.visualisation.shared.VisualisationDoc;

import com.google.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * @throws IOException if something goes wrong.
     */
    VisualisationAssets fetchAssets(final String ownerId) throws IOException {
        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.VIEW)) {
            return dao.fetchAssets(ownerId);
        } else {
            // No permission so return empty assets
            LOGGER.info("User does not have permission to see assets");
            return new VisualisationAssets(ownerId);
        }
    }

    /**
     * Called from the UI to put assets into the system.
     * @param ownerDocId   The ID of the document that owns these assets.
     * @param assets       The object wrapping all the assets that we need to update in the system.
     * @throws IOException If something goes wrong. The method will try to do as much as
     *                     possible, throwing an error at the end of the update with a message
     *                     covering all the issues found.
     */
    void updateAssets(final String ownerDocId,
                      final VisualisationAssets assets) throws IOException {

        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, ownerDocId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {

            // Store errors so we can throw one exception at the end
            final Map<String, Path> uploadsThatDoNotExist = new HashMap<>();
            final StringBuilder exceptionBuf = new StringBuilder();
            final Map<String, VisualisationAsset> assetLookup = new HashMap<>();
            for (final VisualisationAsset asset : assets.getAssets()) {
                assetLookup.put(asset.getId(), asset);
            }

            // First store the assets - the paths and metadata
            try {
                dao.storeAssets(ownerDocId, assets);
            } catch (final IOException e) {
                exceptionBuf.append("\nError storing assets: ");
                exceptionBuf.append(e.getMessage());
            }

            // Now store the uploaded files
            final Map<String, ResourceKey> uploadedFiles = assets.getUploadedFiles();
            for (final Map.Entry<String, ResourceKey> uploadedFileEntry : uploadedFiles.entrySet()) {

                final Path uploadedPath = resourceStore.getTempFile(uploadedFileEntry.getValue());
                if (!uploadedPath.toFile().exists()) {
                    // File does not exist - maybe it has been deleted already
                    uploadsThatDoNotExist.put(uploadedFileEntry.getKey(), uploadedPath);
                } else {
                    try {
                        // Read the data in and store it in the DB
                        // Note that file could be deleted between checking it exists and copying it.
                        // TODO Should stream this data into the DB
                        final byte[] data = Files.readAllBytes(uploadedPath);
                        dao.storeData(uploadedFileEntry.getKey(), data);
                    } catch (final IOException e) {
                        exceptionBuf.append("\nError copying '");
                        final VisualisationAsset asset = assetLookup.get(uploadedFileEntry.getKey());
                        final String path = asset == null
                                ? "unknown"
                                : asset.getPath();
                        exceptionBuf.append(path);
                        exceptionBuf.append("': ");
                        exceptionBuf.append(e.getMessage());
                    }
                }
            }

            // Did anything go wrong?
            if (!uploadsThatDoNotExist.isEmpty()) {
                exceptionBuf.append("\nThese files no longer exist; please upload them again:");
                for (final Map.Entry<String, Path> notExistEntry : uploadsThatDoNotExist.entrySet()) {
                    final VisualisationAsset asset = assetLookup.get(notExistEntry.getKey());
                    exceptionBuf.append("\n");
                    final String path = asset == null
                            ? "unknown"
                            : asset.getPath();
                    exceptionBuf.append(path);
                }
            }

            // If anything went wrong then throw an overall exception here
            if (!exceptionBuf.isEmpty()) {
                throw new IOException(exceptionBuf.toString());
            }
        } else {
            LOGGER.info("User does not have permission to update the assets");
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
    byte[] getData(final String documentId, final String assetPath)
            throws IOException, PermissionException {
        LOGGER.info("Returning assets for {}, {}", documentId, assetPath);
        final DocRef docRef = new DocRef(VisualisationDoc.TYPE, documentId);
        if (securityContext.hasDocumentPermission(docRef, DocumentPermission.VIEW)) {
            return dao.getData(documentId, assetPath);
        } else {
            // Catch this higher up and return a 401.
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to view this asset");
        }
    }

    /**
     * Returns the assets in a form suitable for exporting.
     * @param documentId The ID of the owning document
     * @return Assets to export. Never null.
     * @throws IOException If something goes wrong
     * @throws PermissionException If the user doesn't have permission
     */
    Collection<ImportExportAsset> getAssetsForExport(final String documentId)
            throws IOException, PermissionException {

        LOGGER.info("Returning assets for export for {}", documentId);
        final List<ImportExportAsset> importExportAssets = new ArrayList<>();
        final VisualisationAssets assets = this.fetchAssets(documentId);
        for (final VisualisationAsset asset : assets.getAssets()) {
            final byte[] data = this.getData(documentId, asset.getPath());
            importExportAssets.add(new ByteArrayImportExportAsset(asset.getPath(), data));
        }
        return importExportAssets;
    }

}
