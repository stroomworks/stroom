package stroom.dashboard.impl.visualisation;

import stroom.util.config.annotations.RequiresRestart;
import stroom.util.config.annotations.RequiresRestart.RestartScope;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for Visualisation Asset Management, notably mimetype mapping.
 */
public class VisualisationAssetConfig extends AbstractConfig implements IsStroomConfig {

    /** Map of filename extension to mimetype */
    private final Map<String, String> mimetypes = new HashMap<>();

    /** Mimetype to use if nothing in the map matches */
    private final String defaultMimetype;

    /** Default mimetype map */
    private static final Map<String, String> DEFAULT_MIMETYPES = new HashMap<>();

    /** Mimetype if nothing else matches */
    private static final String DEFAULT_MIMETYPE = "application/octet-stream";

    /** Logger */
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisualisationAssetConfig.class);

    /*
     * Initialise mimetype map.
     * Values from here: https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/MIME_types/Common_types
     */
    static {
        DEFAULT_MIMETYPES.put("apng", "image/apng");
        DEFAULT_MIMETYPES.put("bmp", "image/bmp");
        DEFAULT_MIMETYPES.put("css", "text/css");
        DEFAULT_MIMETYPES.put("gif", "image/jpeg");
        DEFAULT_MIMETYPES.put("html", "text/html");
        DEFAULT_MIMETYPES.put("htm", "text/html");
        DEFAULT_MIMETYPES.put("jpg", "image/jpeg");
        DEFAULT_MIMETYPES.put("jpeg", "image/jpeg");
        DEFAULT_MIMETYPES.put("js", "text/javascript");
        DEFAULT_MIMETYPES.put("png", "image/png");
        DEFAULT_MIMETYPES.put("svg", "image/svg+xml");
        DEFAULT_MIMETYPES.put("tif", "image/tiff");
        DEFAULT_MIMETYPES.put("tiff", "image/tiff");
        DEFAULT_MIMETYPES.put("txt", "text/plain");
        DEFAULT_MIMETYPES.put("webp", "image/webp");
        DEFAULT_MIMETYPES.put("xml", "application/xml");
    }

    /**
     * Default constructor. Configuration created with default values.
     */
    public VisualisationAssetConfig() {
        this.mimetypes.putAll(DEFAULT_MIMETYPES);
        this.defaultMimetype = DEFAULT_MIMETYPE;
    }

    /**
     * Injected constructor.
     */
    @SuppressWarnings("unused")
    @JsonCreator
    public VisualisationAssetConfig(@JsonProperty("mimetypes") final Map<String, String> mimetypes,
                                    @JsonProperty("default") final String defaultMimetype) {
        if (mimetypes == null || mimetypes.isEmpty()) {
            LOGGER.info("No mimetypes supplied in the configuration file; using default values");
        } else {
            this.mimetypes.putAll(mimetypes);
        }

        if (defaultMimetype == null || defaultMimetype.isEmpty()) {
            LOGGER.info("No default mimetype supplied in the configuration file; using default value");
            this.defaultMimetype = DEFAULT_MIMETYPE;
        } else {
            this.defaultMimetype = defaultMimetype;
        }
    }

    @RequiresRestart(RestartScope.SYSTEM)
    @JsonPropertyDescription("The mimetypes map from extension to mimetype for the asset manager")
    @JsonProperty("mimetypes")
    public Map<String, String> getMimetypes() {
        return Collections.unmodifiableMap(mimetypes);
    }

    @RequiresRestart(RestartScope.SYSTEM)
    @JsonPropertyDescription("Mimetype to use if nothing else matches")
    @JsonProperty("default")
    public String getDefaultMimetype() {
        return defaultMimetype;
    }

}
