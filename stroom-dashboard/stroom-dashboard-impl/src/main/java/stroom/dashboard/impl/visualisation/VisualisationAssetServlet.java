package stroom.dashboard.impl.visualisation;

import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.IsServlet;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allows access to the
 */
public class VisualisationAssetServlet extends HttpServlet implements IsServlet {

    /** The service that provides the backend to this servlet */
    private final VisualisationAssetService service;

    /** File extension to mimetype */
    private final Map<String, String> mimetypes;

    /** Default mimetype if nothing else matches */
    private final String defaultMimetype;

    /** Logger */
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisualisationAssetServlet.class);

    /** The URL path to this servlet */
    public static final String PATH_PART = "/assets/*";

    /** Set of paths to access this servlet */
    private static final Set<String> PATH_SPECS = Set.of(PATH_PART);

    /**
     * Injected constructor.
     */
    @Inject
    public VisualisationAssetServlet(final VisualisationAssetService service,
                                     final Provider<VisualisationAssetConfig> configProvider) {
        this.service = service;
        final VisualisationAssetConfig config = configProvider.get();
        this.mimetypes = config.getMimetypes();
        this.defaultMimetype = config.getDefaultMimetype();
    }

    /**
     * Called to return an asset via HTTP.
     */
    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) {

        final List<String> arguments = splitIntoDocIdAndPath(request.getPathInfo());
        final String docId = arguments.get(0);
        final String path = arguments.get(1);

        try {
            final byte[] data = service.getData(docId, path);
            if (data == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            } else {
                response.setContentType(getMimetype(path));
                response.setStatus(HttpServletResponse.SC_OK);
                final ServletOutputStream ostr = response.getOutputStream();
                ostr.write(data);
            }
        } catch (final IOException e) {
            LOGGER.error("Error retrieving asset for docId {}, path '{}'", docId, path);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }
        // TODO Handle PermissionException?

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
}
