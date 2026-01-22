package stroom.dashboard.impl.visualisation;

import stroom.util.io.PathCreator;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.IsServlet;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Allows access to the visualisation assets over HTTP, so that the UI can pull in assets as necessary.
 */
@Singleton
public class VisualisationAssetServlet extends HttpServlet implements IsServlet, HasAssetCache {

    /** The service that provides the backend to this servlet */
    private final VisualisationAssetService service;

    /** File extension to mimetype */
    private final Map<String, String> mimetypes;

    /** Default mimetype if nothing else matches */
    private final String defaultMimetype;

    /** Where we're caching assets */
    private final Path assetCacheDir;

    /** Synchronisation locks */
    private final Map<String, Lock> docLocks = new ConcurrentHashMap<>();

    /** Logger */
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisualisationAssetServlet.class);

    /** The URL path to this servlet */
    public static final String PATH_PART = "/assets/*";

    /** Set of paths to access this servlet */
    private static final Set<String> PATH_SPECS = Set.of(PATH_PART);

    /** Index of DocID in returned value */
    private static final int DOCID_INDEX = 0;

    /** Index of Path in returned value */
    private static final int PATH_INDEX = 1;

    /** Prefix for temporary files, so we can delete them if necessary */
    private static final String ASSET_CACHE_TEMP_PREFIX = "asset-temp-";

    /** Suffix for temporary files */
    private static final String ASSET_CACHE_TEMP_SUFFIX = ".tmp";

    /**
     * Injected constructor.
     */
    @Inject
    public VisualisationAssetServlet(final VisualisationAssetService service,
                                     final Provider<VisualisationAssetConfig> configProvider,
                                     final PathCreator pathCreator) {
        LOGGER.error("Constructing VisualisationAssetServlet");
        this.service = service;
        final VisualisationAssetConfig config = configProvider.get();
        this.mimetypes = config.getMimetypes();
        this.defaultMimetype = config.getDefaultMimetype();
        this.assetCacheDir = pathCreator.toAppPath(config.getAssetCacheDir());

        try {
            Files.createDirectory(this.assetCacheDir);
        } catch (final IOException e) {
            LOGGER.error("Error creating asset cache directory: {}", e.getMessage(), e);
        }

        // Get rid of any temporary files that may be left over
        deleteTempFiles();
    }

    /**
     * Returns the asset cache path for the given document ID.
     * @param docId Document ID of the document that owns the assets. Must not be null.
     * @return The path to the root of the cache for that document. Probably doesn't exist on disk.
     */
    private Path getCachePathForDoc(final String docId) {

        return assetCacheDir.resolve(makePathSafe(docId));
    }

    /**
     * Returns the lock for the given docId.
     * @param docId The document that we're locking against.
     * @return A lock. Never returns null.
     */
    private Lock getLock(final String docId) {
        final String safeDocId = makePathSafe(docId);
        LOGGER.info("Getting lock for '{}'", safeDocId);
        docLocks.computeIfAbsent(safeDocId, k -> new ReentrantLock());
        return docLocks.get(safeDocId);
    }

    /**
     * Gets the path to the asset within the cache.
     * @param docId ID of the document
     * @param assetPath Path to the asset within the document
     * @return Path to the asset. May not exist on disk.
     */
    private Path getCachePathForAsset(final String docId,
                                      final String assetPath) {
        return getCachePathForDoc(docId).resolve(makePathSafe(assetPath));
    }

    /**
     * Returns an input stream reading from a cached copy of the asset.
     * Uses a temporary file atomically moved to the cache file to ensure
     * that bad cache files are not created in the event of errors.
     * @param docId The document ID that owns the asset
     * @param assetPath The path of the asset within the owning document
     * @return InputStream (buffered) that reads the file. Must be closed.
     * @throws IOException If something goes wrong.
     * @throws PermissionException If something goes wrong.
     */
    private InputStream getInputStreamForAsset(final String docId,
                                               final String assetPath)
    throws IOException, PermissionException {

        final Lock docLock = getLock(docId);
        docLock.lock();
        try {
            final Path cachedAssetPath = getCachePathForAsset(docId, assetPath);
            if (!cachedAssetPath.toFile().exists()) {
                final Path cachedAssetDir = cachedAssetPath.getParent();
                Files.createDirectories(cachedAssetDir);
                final Path tempCachedAssetPath = Files.createTempFile(cachedAssetDir,
                        ASSET_CACHE_TEMP_PREFIX,
                        ASSET_CACHE_TEMP_SUFFIX);
                final byte[] data = service.getData(docId, assetPath);
                if (data == null) {
                    throw new FileNotFoundException("Asset '" + assetPath + "' does not exist");
                }
                LOGGER.info("Writing cache file '{}'", tempCachedAssetPath);
                Files.write(tempCachedAssetPath, data, StandardOpenOption.TRUNCATE_EXISTING);
                LOGGER.info("Moving cache file to '{}'", cachedAssetPath);
                Files.move(tempCachedAssetPath,
                        cachedAssetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }

            // File must exist now, so return an InputStream attached to it
            // UNIX won't delete the file underneath us, so it is OK to release the lock now
            return new BufferedInputStream(new FileInputStream(cachedAssetPath.toFile()));
        } finally {
            docLock.unlock();
        }
    }

    /**
     * Called to return an asset via HTTP.
     */
    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) {

        final List<String> arguments = splitIntoDocIdAndPath(request.getPathInfo());
        final String docId = arguments.get(DOCID_INDEX);
        final String path = arguments.get(PATH_INDEX);

        try (final InputStream istr = getInputStreamForAsset(docId, path)) {
            response.setContentType(getMimetype(path));
            response.setStatus(HttpServletResponse.SC_OK);
            try (final ServletOutputStream ostr = response.getOutputStream()) {
                istr.transferTo(ostr);
            }
        } catch (final FileNotFoundException e) {
            LOGGER.error("Asset {}/{} does not exist", docId, path);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } catch (final IOException e) {
            LOGGER.error("Error retrieving asset for docId {}, path '{}': {}", docId, path, e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        } catch (final PermissionException e) {
            LOGGER.info("User does not have permission to view assets");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    /**
     * Takes the pathInfo and splits it into the docId and the path information.
     * @param pathInfo Request.getPathInfo(). Can be null.
     * @return List of docId, path. Two elements always present. Neither will be null.
     */
    private List<String> splitIntoDocIdAndPath(String pathInfo) {
        String docId = "";
        String path = "";
        if (pathInfo != null) {
            if (pathInfo.startsWith("/")) {
                pathInfo = pathInfo.substring(1);
            }
            final int firstSlash = pathInfo.indexOf('/');
            if (firstSlash != -1) {
                docId = pathInfo.substring(0, firstSlash);
                path = pathInfo.substring(firstSlash);
            }
        }
        return List.of(docId, path);
    }

    /**
     * Given a path to a file, including the filename, uses the extension to
     * find a suitable mimetype.
     * @param path The path to the file, including the filename and extension.
     *             Must not be null.
     * @return The mimetype. Never returns null.
     */
    private String getMimetype(final String path) {
        String mimetype = defaultMimetype;
        final int dotIndex = path.lastIndexOf('.');
        if (dotIndex != -1) {
            // Got an extension - look it up
            final String extension = path.substring(dotIndex + 1);
            if (mimetypes.containsKey(extension)) {
                mimetype = mimetypes.get(extension);
            }
        }

        return mimetype;
    }

    @Override
    public void invalidateCacheForDoc(final String docId) {
        final Lock docLock = getLock(docId);
        docLock.lock();
        LOGGER.info("Invalidating cache for document '{}'", docId);
        try {
            Files.walkFileTree(getCachePathForDoc(docId),
                    new SimpleFileVisitor<>() {
                        @Override
                        public @NonNull FileVisitResult visitFile(final @NonNull Path file,
                                                                  final @NonNull BasicFileAttributes attrs)
                                throws IOException {
                            LOGGER.info("Invalidating cache file '{}'", file);
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NonNull FileVisitResult postVisitDirectory(final @NonNull Path dir,
                                                                           final IOException e)
                                throws IOException {

                            if (e == null) {
                                LOGGER.info("Invalidating cache directory '{}'", dir);
                                Files.delete(dir);
                                return FileVisitResult.CONTINUE;
                            } else {
                                throw e;
                            }
                        }
                    });
        } catch (final IOException e) {
            LOGGER.error("Failed to invalidate visualisation asset cache for document '{}': {}",
                    docId,
                    e.getMessage(),
                    e);
        }
        finally {
            docLock.unlock();
        }
    }

    /**
     * Deletes all the temporary files. These should only exist if something went wrong.
     */
    private void deleteTempFiles() {
        try {
            Files.walkFileTree(assetCacheDir,
                    new SimpleFileVisitor<>() {
                        @Override
                        public @NonNull FileVisitResult visitFile(final @NonNull Path file,
                                                                  final @NonNull BasicFileAttributes attrs)
                                throws IOException {

                            if (Files.isRegularFile(file)) {
                                final String filename = file.getFileName().toString();
                                if (filename.startsWith(ASSET_CACHE_TEMP_PREFIX)
                                    && filename.endsWith(ASSET_CACHE_TEMP_SUFFIX)) {
                                    LOGGER.warn("Deleting visualisation asset cache temporary file '{}'", file);
                                    Files.delete(file);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (final IOException e) {
            LOGGER.error("Error deleting temporary files from visualisation asset cache: {}", e.getMessage(), e);
        }
    }

    @Override
    public void init() throws ServletException {
        LOGGER.debug("Creating VisualisationAssetServlet");
        super.init();
    }

    @Override
    public void destroy() {
        LOGGER.debug("Destroying VisualisationAssetServlet");
        super.destroy();
    }

    @Override
    public Set<String> getPathSpecs() {
        LOGGER.debug("PathSpecs: {}", PATH_SPECS);
        return PATH_SPECS;
    }

    /**
     * Returns a path without a leading slash. Removes multiple leading slashes if necessary.
     * @param in The path to remove the leading slash from.
     * @return Safe version of the filename.
     */
    private String makePathSafe(final String in) {

        // Remove leading slash
        final String retval = recurseRemoveLeadingSlash(in);

        // Remove any security sensitive strings
        return retval.replace("..", "__");
    }

    /**
     * Recurse down the path, removing any leading slashes. Must not be null.
     * @param in The string to sanitise.
     * @return The input string without any leading slashes.
     */
    private String recurseRemoveLeadingSlash(final String in) {
        if (in.startsWith("/")) {
            return recurseRemoveLeadingSlash(in.substring(1));
        } else {
            return in;
        }
    }

}
