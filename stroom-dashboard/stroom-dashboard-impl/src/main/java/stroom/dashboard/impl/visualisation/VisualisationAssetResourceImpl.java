package stroom.dashboard.impl.visualisation;

import stroom.event.logging.rs.api.AutoLogged;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.visualisation.shared.VisualisationAssetResource;
import stroom.visualisation.shared.VisualisationAssets;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.IOException;

import static stroom.event.logging.rs.api.AutoLogged.OperationType.UNLOGGED;

/**
 * Serverside REST handling for Visualisation Assets.
 * <br>TODO Perform audit logging
 */
@AutoLogged(UNLOGGED)
public class VisualisationAssetResourceImpl implements VisualisationAssetResource {

    /** Service that backs the resource */
    private final Provider<VisualisationAssetService> serviceProvider;

    /** Logger */
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisualisationAssetResourceImpl.class);

    /**
     * Injected constructor.
     */
    @SuppressWarnings("unused")
    @Inject
    VisualisationAssetResourceImpl(final Provider<VisualisationAssetService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public VisualisationAssets fetchDraftAssets(final String ownerId) throws RuntimeException {
        LOGGER.info("ResourceImpl: fetchAssets with ownerId {}", ownerId);
        try {
            return serviceProvider.get().fetchDraftAssets(ownerId);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * To upload files into Stroom, files are first uploaded to the ImportUtil.getImportFileURL().
     * This puts the file into stroom.resource.api.ResourceStore and returns a ResourceKey.
     * When the client is ready to keep the file somewhere we call ResourceStore.getTempFile()
     * to get the file's path, then copy the file to its final destination.
     * @param assets The assets sent from the client.
     * @return TRUE if everything works, FALSE if not.
     */
    @Override
    public Boolean updateDraftAssets(final String ownerId,
                                     final VisualisationAssets assets)
            throws RuntimeException {
        try {
            serviceProvider.get().updateDraftAssets(ownerId, assets);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        return Boolean.TRUE;
    }

    @Override
    public Boolean saveDraftToLive(final String ownerDocId) throws RuntimeException {
        try {
            serviceProvider.get().saveDraftToLive(ownerDocId);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        return Boolean.TRUE;
    }

    @Override
    public Boolean revertDraftFromLive(final String ownerDocId) throws RuntimeException {
        try {
            serviceProvider.get().revertDraftFromLive(ownerDocId);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        return Boolean.TRUE;
    }
}
