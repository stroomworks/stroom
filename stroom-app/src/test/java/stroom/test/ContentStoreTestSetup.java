package stroom.test;

import stroom.contentstore.impl.ContentStoreResourceImpl;
import stroom.contentstore.shared.ContentStoreContentPack;
import stroom.contentstore.shared.ContentStoreContentPackWithDynamicState;
import stroom.contentstore.shared.ContentStoreCreateGitRepoRequest;
import stroom.contentstore.shared.ContentStoreResponse;
import stroom.contentstore.shared.ContentStoreResponse.Status;
import stroom.test.common.ProjectPathUtil;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Pulls Content Store items into Stroom for integration tests
 * and development.
 * Called from SetupSampleData.
 */
public class ContentStoreTestSetup {

    /** Provider of the Thing to do the actual import */
    private final Provider<ContentStoreResourceImpl> contentStoreResourceProvider;

    /** Thing to do the actual work */
    private ContentStoreResourceImpl contentStoreResource;

    /** Stuff in the Content Store */
    private List<ContentStoreContentPackWithDynamicState> contentStoreContents;

    /** Every ID in the content store - SetupSampleData installs all of them */
    private final List<String> samplePackIds = new ArrayList<>();

    /**
     * Standard packs, used by integration tests.
     */
    public static final List<String> STANDARD_PACK_IDS = Arrays.asList(
            "core-xml-schemas",
            "event-logging-xml-schema",
            "standard-pipelines",
            "template-pipelines",
            "planb"
    );

    /**
     * Constructor - injected.
     * @param contentStoreResourceProvider Injected service for managing content stores and packs.
     */
    @Inject
    public ContentStoreTestSetup(
            final Provider<ContentStoreResourceImpl> contentStoreResourceProvider) {

        this.contentStoreResourceProvider = contentStoreResourceProvider;
    }

    /**
     * Load the content store, if necessary.
     * Cannot be called from injected constructor.
     */
    private synchronized void cacheContentStore() {
        if (contentStoreContents == null) {

            // Hack to force the content store config to use our content store config file
            contentStoreResource = contentStoreResourceProvider.get();
            contentStoreResource.resetContentStoreUrlsToFile(
                    ProjectPathUtil.getRepoRoot(),
                    "content-store-sample-data.yml");

            // Get all the items in one go
            final PageRequest pageRequest = new PageRequest(0, Integer.MAX_VALUE);
            final ResultPage<ContentStoreContentPackWithDynamicState> page =
                    contentStoreResource.list(pageRequest);

            contentStoreContents = page.stream().toList();

            // List out all the IDs so we can install them if necessary
            for (final ContentStoreContentPackWithDynamicState cpds : contentStoreContents) {
                samplePackIds.add(cpds.getContentPack().getId());
            }
        }
    }

    /**
     * Installs the content pack with the given ID.
     * @param contentPackId The ID of the content pack.
     * @throws RuntimeException If something goes wrong.
     */
    public void install(final String contentPackId) throws RuntimeException {
        this.cacheContentStore();

        // Find the content pack
        ContentStoreContentPack cp = null;
        for (final ContentStoreContentPackWithDynamicState cpds : this.contentStoreContents) {
            if (cpds.getContentPack().getId().equals(contentPackId)) {
                cp = cpds.getContentPack();
                break;
            }
        }

        if (cp == null) {
            throw new RuntimeException("Cannot find content pack with ID '" + contentPackId + "'");
        }

        if (cp.getGitNeedsAuth()) {
            throw new RuntimeException("Cannot import content pack with ID '"
                                  + contentPackId
                                  + "': authentication is required");
        }

        // Install the pack
        final ContentStoreCreateGitRepoRequest request =
                new ContentStoreCreateGitRepoRequest(cp, null, null);
        final ContentStoreResponse response = contentStoreResource.create(request);
        final ContentStoreResponse.Status status = response.getStatus();
        if (!(status.equals(Status.OK) || status.equals(Status.ALREADY_EXISTS))) {
            throw new RuntimeException("Couldn't create content pack with ID '"
                + contentPackId + "': " + response.getMessage());
        }

    }

    /**
     * Installs a number of content packs.
     * @param contentPackIds Collection of content pack IDs.
     * @throws RuntimeException If something goes wrong.
     */
    public void install(final Collection<String> contentPackIds) throws RuntimeException {
        for (final String id : contentPackIds) {
            install(id);
        }
    }

    /**
     * Installs all the content packs for SetupSampleData.
     * @throws RuntimeException if something goes wrong.
     */
    public void installSampleDataPacks() throws RuntimeException {
        install(samplePackIds);
    }

    /**
     * Installs the standard packs, as used in integration tests.
     */
    public void installStandardPacks() throws RuntimeException {
        install(STANDARD_PACK_IDS);
    }

    /**
     * Installs visualisations.
     */
    public void installVisualisations() throws RuntimeException {
        install("stroom-visualisations");
    }
}
