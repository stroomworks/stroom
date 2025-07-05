package stroom.proxy.app;

import stroom.security.api.UserIdentityFactory;
import stroom.util.jersey.JerseyClientFactory;
import stroom.util.jersey.JerseyClientName;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.NullSafe;

import jakarta.inject.Provider;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.StatusType;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public abstract class AbstractDownstreamClient {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AbstractDownstreamClient.class);

    protected final JerseyClientFactory jerseyClientFactory;
    protected final UserIdentityFactory userIdentityFactory;
    protected final Provider<DownstreamHostConfig> downstreamHostConfigProvider;


    public AbstractDownstreamClient(final JerseyClientFactory jerseyClientFactory,
                                    final UserIdentityFactory userIdentityFactory,
                                    final Provider<DownstreamHostConfig> downstreamHostConfigProvider) {
        this.jerseyClientFactory = jerseyClientFactory;
        this.userIdentityFactory = userIdentityFactory;
        this.downstreamHostConfigProvider = downstreamHostConfigProvider;
    }

    /**
     * @return The full URL if it has been explicitly configured.
     */
    protected abstract Optional<String> getConfiguredUrl();

    /**
     * @return The default path part for the resource, e.g. /api/blah/blah
     */
    protected abstract String getDefaultPath();

    public boolean isDownstreamEnabled() {
        return downstreamHostConfigProvider.get().isEnabled();
    }

    public String getFullUrl() {
        // This allows a full url to be explicitly configured, else we just combine the default path
        // with the downstream host config.
        final String url = getConfiguredUrl()
                .filter(NullSafe::isNonBlankString)
                .orElseGet(() ->
                        downstreamHostConfigProvider.get().getUri(getDefaultPath()));
        LOGGER.debug("getFullUrl() - url: {}", url);
        return url;
    }

    protected Response getResponse(final Function<Invocation.Builder, Response> buildStep) {
        final String url = getFullUrl();
        final WebTarget webTarget = jerseyClientFactory.createWebTarget(JerseyClientName.DOWNSTREAM, url);
        final Builder builder = webTarget
                .request(MediaType.APPLICATION_JSON)
                .headers(getAuthHeaders());

        final Response response = Objects.requireNonNull(buildStep).apply(builder);

        LOGGER.debug(() -> LogUtil.message("getResponse() - url: '{}', webTarget: {}, response: {} - {}",
                url,
                webTarget,
                NullSafe.get(response, Response::getStatusInfo, StatusType::getStatusCode),
                NullSafe.get(response, Response::getStatusInfo, StatusType::getReasonPhrase)));
        return response;
    }

    protected MultivaluedMap<String, Object> getAuthHeaders() {
        final Map<String, String> headers;

        final DownstreamHostConfig downstreamHostConfig = downstreamHostConfigProvider.get();
        final String apiKey = NullSafe.trim(downstreamHostConfig.getApiKey());
        if (!apiKey.isEmpty()) {
            // Intended for when stroom is using its internal IDP. Create the API Key in stroom UI
            // and add it to config.
            LOGGER.debug(() -> LogUtil.message("getAuthHeaders() - Using API key from config prop {}",
                    downstreamHostConfig.getFullPathStr(DownstreamHostConfig.PROP_NAME_API_KEY)));
            headers = userIdentityFactory.getAuthHeaders(apiKey);
        } else {
            LOGGER.debug("getAuthHeaders() - Adding service user token to headers");
            // Use a token from the external IDP
            headers = userIdentityFactory.getServiceUserAuthHeaders();
        }
        return new MultivaluedHashMap<>(headers);
    }
}
