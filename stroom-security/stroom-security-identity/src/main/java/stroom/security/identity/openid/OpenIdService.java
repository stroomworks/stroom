package stroom.security.identity.openid;

import stroom.config.common.UriFactory;
import stroom.security.identity.authenticate.api.AuthenticationService;
import stroom.security.identity.authenticate.api.AuthenticationService.AuthState;
import stroom.security.identity.exceptions.BadRequestException;
import stroom.security.identity.token.TokenBuilder;
import stroom.security.identity.token.TokenBuilderFactory;
import stroom.security.identity.token.TokenType;
import stroom.security.openid.api.OpenId;
import stroom.security.openid.api.OpenIdClient;
import stroom.security.openid.api.OpenIdClientFactory;
import stroom.security.openid.api.TokenRequest;
import stroom.security.openid.api.TokenResponse;

import com.google.common.base.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;


class OpenIdService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenIdService.class);

    private final UriFactory uriFactory;
    private final AccessCodeCache accessCodeCache;
    private final TokenBuilderFactory tokenBuilderFactory;
    private final AuthenticationService authenticationService;
    private final OpenIdClientFactory openIdClientDetailsFactory;

    @Inject
    OpenIdService(final UriFactory uriFactory,
                  final AccessCodeCache accessCodeCache,
                  final TokenBuilderFactory tokenBuilderFactory,
                  final AuthenticationService authenticationService,
                  final OpenIdClientFactory openIdClientDetailsFactory) {
        this.uriFactory = uriFactory;
        this.accessCodeCache = accessCodeCache;
        this.tokenBuilderFactory = tokenBuilderFactory;
        this.authenticationService = authenticationService;
        this.openIdClientDetailsFactory = openIdClientDetailsFactory;
    }

    public URI auth(final HttpServletRequest request,
                    final String scope,
                    final String responseType,
                    final String clientId,
                    final String redirectUri,
                    @Nullable final String nonce,
                    @Nullable final String state,
                    @Nullable final String prompt) {
        URI result;
        try {
            OpenIdClient oAuth2Client = openIdClientDetailsFactory.getClient(clientId);

            final Pattern pattern = Pattern.compile(oAuth2Client.getUriPattern());
            if (!pattern.matcher(redirectUri).matches()) {
                throw new BadRequestException("Redirect URI is not allowed");
            }

            // If the prompt is 'login' then we always want to prompt the user to login in with username and password.
            final boolean requireLoginPrompt = prompt != null && prompt.equalsIgnoreCase("login");
            if (requireLoginPrompt) {
                LOGGER.info("Relying party requested a user login page by using 'prompt=login'");
            }

            if (requireLoginPrompt) {
                LOGGER.debug("Login has been requested by the RP");
                result = authenticationService.createSignInUri(redirectUri);

            } else {
                // We need to make sure our understanding of the session is correct
                final Optional<AuthState> optionalAuthState = authenticationService.currentAuthState(request);

                // If we have an authenticated session then the user is logged in
                if (optionalAuthState.isPresent()) {
                    final AuthState authState = optionalAuthState.get();

                    // If the users password still needs tp be changed then send them back to the login page.
                    if (authState.isRequirePasswordChange()) {
                        result = authenticationService.createSignInUri(redirectUri);

                    } else {
                        LOGGER.debug("User has a session, sending them back to the RP");

                        // We need to make sure we record this access code request.
                        final String accessCode = createAccessCode();
                        final String token = createIdToken(clientId, authState.getSubject(), nonce, state);
                        final AccessCodeRequest accessCodeRequest = new AccessCodeRequest(
                                scope,
                                responseType,
                                clientId,
                                redirectUri,
                                nonce,
                                state,
                                prompt,
                                token);
                        accessCodeCache.put(accessCode, accessCodeRequest);

                        result = buildRedirectionUrl(redirectUri, accessCode, state);
                    }

                } else {
                    LOGGER.debug("User has no session and no certificate - sending them to login.");
                    result = authenticationService.createSignInUri(redirectUri);
                }
            }

        } catch (final RuntimeException e) {
            LOGGER.error("Error authenticating request {}", request.getRequestURI(), e);
            result = UriBuilder.fromUri(uriFactory.uiUri(AuthenticationService.UNAUTHORISED_URL_PATH)).build();
        }

        return result;
    }

    private static String createAccessCode() {
        final SecureRandom secureRandom = new SecureRandom();
        final byte[] bytes = new byte[20];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().encodeToString(bytes);
    }

    public TokenResponse token(final TokenRequest tokenRequest) {
        final String grantType = tokenRequest.getGrantType();
        final String clientId = tokenRequest.getClientId();
        final String clientSecret = tokenRequest.getClientSecret();
        final String redirectUri = tokenRequest.getRedirectUri();
        final String code = tokenRequest.getCode();

        final Optional<AccessCodeRequest> optionalAccessCodeRequest = accessCodeCache.getAndRemove(code);
        if (optionalAccessCodeRequest.isEmpty()) {
            throw new BadRequestException("No access code request found");
        }

        final AccessCodeRequest accessCodeRequest = optionalAccessCodeRequest.get();
        if (!Objects.equal(clientId, accessCodeRequest.getClientId())) {
            throw new BadRequestException("Unexpected client id");
        }

        if (!Objects.equal(redirectUri, accessCodeRequest.getRedirectUri())) {
            throw new BadRequestException("Unexpected redirect URI");
        }

        final OpenIdClient oAuth2Client = openIdClientDetailsFactory.getClient(clientId);

        if (!Objects.equal(clientSecret, oAuth2Client.getClientSecret())) {
            throw new BadRequestException("Incorrect secret");
        }

        final Pattern pattern = Pattern.compile(oAuth2Client.getUriPattern());
        if (!pattern.matcher(redirectUri).matches()) {
            throw new BadRequestException("Redirect URI is not allowed");
        }

        return new TokenResponse.Builder()
                .idToken(accessCodeRequest.getToken())
                .build();
    }

    private URI buildRedirectionUrl(String redirectUri, String code, String state) {
        return UriBuilder
                .fromUri(redirectUri)
                .replaceQueryParam(OpenId.CODE, code)
                .replaceQueryParam(OpenId.STATE, state)
                .build();
    }

    private String createIdToken(final String clientId,
                                 final String subject,
                                 final String nonce,
                                 final String state) {
        final TokenBuilder tokenBuilder = tokenBuilderFactory
                .newBuilder(TokenType.USER)
                .clientId(clientId)
                .subject(subject)
                .nonce(nonce)
                .state(state);
//                .authSessionId(authSessionId);
//        Instant expiresOn = tokenBuilder.getExpiryDate();
        return tokenBuilder.build();
    }
}