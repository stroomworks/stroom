package stroom.dashboard.impl.visualisation;

import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.IsServlet;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Allows access to the
 */
public class VisualisationAssetServlet extends HttpServlet implements IsServlet {

    /** The service that provides the backend to this servlet */
    private final VisualisationAssetService service;

    /** Logger */
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisualisationAssetServlet.class);

    /** The URL path to this servlet */
    public static final String PATH_PART = "/assets";

    /** Set of paths to access this servlet */
    private static final Set<String> PATH_SPECS = Set.of(PATH_PART);

    /**
     * Injected constructor.
     */
    @Inject
    public VisualisationAssetServlet(final VisualisationAssetService service) {
        this.service = service;
    }

    /**
     * Called to return an asset via HTTP.
     */
    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
        throws IOException {

        LOGGER.debug("assets: {}", request.getQueryString());

        // TODO Find the owning document UUID in the parameters
        // TODO Find the path of the file in the parameters
        // TODO Call VisualisationAssetService.get(ownerDocRef, path)
        // TODO Return via streaming

        final String query = request.getQueryString();
        if (NullSafe.isNonBlankString(query)) {
            final String id = URLDecoder.decode(query, StandardCharsets.UTF_8);
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
}
