package stroom.proxy.app.security;

import stroom.proxy.app.AbstractDownstreamClient;
import stroom.proxy.app.DownstreamHostConfig;
import stroom.security.api.UserIdentityFactory;
import stroom.security.shared.AppPermission;
import stroom.security.shared.AppPermissionSet;
import stroom.security.shared.VerifyApiKeyRequest;
import stroom.util.HasHealthCheck;
import stroom.util.HealthCheckUtils;
import stroom.util.jersey.JerseyClientFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.UserDesc;

import com.codahale.metrics.health.HealthCheck.Result;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.StatusType;

import java.util.Optional;

public class ProxyApiKeyCheckClient extends AbstractDownstreamClient implements HasHealthCheck {

    // This api key will never exist as it is malformed, but we can make sure the resource
    // is working with it.
    public static final VerifyApiKeyRequest HEALTH_CHECK_REQUEST = new VerifyApiKeyRequest(
            "DUMMY_API_KEY_FOR_HEALTH_CHECK",
            AppPermissionSet.of(AppPermission.STROOM_PROXY));

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ProxyApiKeyCheckClient.class);

    private final Provider<DownstreamHostConfig> downstreamHostConfigProvider;

    @Inject
    public ProxyApiKeyCheckClient(
            final JerseyClientFactory jerseyClientFactory,
            final UserIdentityFactory userIdentityFactory,
            final Provider<DownstreamHostConfig> downstreamHostConfigProvider) {
        super(jerseyClientFactory,
                userIdentityFactory,
                downstreamHostConfigProvider);
        this.downstreamHostConfigProvider = downstreamHostConfigProvider;
    }

    @Override
    protected Optional<String> getConfiguredUrl() {
        return NullSafe.nonBlank(downstreamHostConfigProvider.get().getApiKeyVerificationUrl());
    }

    @Override
    protected String getDefaultPath() {
        return DownstreamHostConfig.DEFAULT_API_KEY_VERIFICATION_URL_PATH;
    }

    Optional<UserDesc> fetchApiKeyValidity(final VerifyApiKeyRequest request) {
        final String url = getFullUrl();
        Optional<UserDesc> optUserDesc = Optional.empty();
        if (NullSafe.isNonBlankString(url)) {
            try (final Response response = getResponse(builder -> builder.post(Entity.json(request)))) {
                final StatusType statusInfo = response.getStatusInfo();
                if (statusInfo.getStatusCode() == Status.OK.getStatusCode()) {
                    if (response.hasEntity()) {
                        optUserDesc = Optional.ofNullable(response.readEntity(UserDesc.class));
                        LOGGER.debug("fetchApiKeyValidity() - optUserDesc: {}, request: {}", optUserDesc, request);
                    } else {
                        LOGGER.debug("fetchApiKeyValidity() - No response entity from {}", url);
                    }
                } else if (statusInfo.getStatusCode() == Status.NOT_FOUND.getStatusCode()) {
                    LOGGER.debug("fetchApiKeyValidity() - Not Found exception from {}", url);
                    optUserDesc = Optional.empty();
                } else {
                    LOGGER.error("Error fetching API Key validity using url '{}', " +
                                 "got response {} - {}, request: {}",
                            url, statusInfo.getStatusCode(), statusInfo.getReasonPhrase(), request);
                }
            } catch (final NotFoundException e) {
                LOGGER.debug("fetchApiKeyValidity() - Not found exception");
            }
        } else {
            LOGGER.warn("No url configured for API key verification.");
        }
        return optUserDesc;
    }

    @Override
    public Result getHealth() {
        LOGGER.debug("getHealth() called");
        if (!isDownstreamEnabled()) {
            return HealthCheckUtils.healthy("Downstream host disabled");
        } else {
            try {
                try (final Response response = getResponse(builder ->
                        builder.post(Entity.json(HEALTH_CHECK_REQUEST)))) {

                    return HealthCheckUtils.fromResponse(response, Status.NO_CONTENT)
                            .build();
                }
            } catch (final Throwable e) {
                LOGGER.error("API Key check unhealthy: {}", LogUtil.exceptionMessage(e));
                return Result.unhealthy(e);
            }
        }
    }
}
