/*
 * Copyright 2016-2025 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.security.identity.token;

import stroom.security.openid.api.OpenId;
import stroom.security.openid.api.OpenIdClientFactory;
import stroom.security.openid.api.OpenIdConfiguration;
import stroom.security.openid.api.PublicJsonWebKeyProvider;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.JwksVerificationKeyResolver;
import org.jose4j.keys.resolvers.VerificationKeyResolver;

import java.util.List;
import java.util.Optional;

/**
 * Verifies the short lived token that {@link TokenBuilderFactory} mints and that is emailed to a user so
 * that they can reset a forgotten password.
 * <p>
 * This deliberately does not use {@code InternalJwtContextFactory} from stroom-security-impl. That class
 * is package private, is not on this module's compile classpath, and is bound via a delegating factory
 * that resolves to the external IDP when one is configured, which would be wrong for a token that this
 * module minted itself. Verification therefore mirrors it here, using the same providers that
 * {@link TokenBuilderFactory} uses to sign.
 * </p>
 */
@Singleton
public class PasswordResetTokenVerifier {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PasswordResetTokenVerifier.class);

    private static final int ALLOWED_CLOCK_SKEW_SECONDS = 30;

    private final PublicJsonWebKeyProvider publicJsonWebKeyProvider;
    private final Provider<OpenIdConfiguration> openIdConfigurationProvider;
    private final OpenIdClientFactory openIdClientDetailsFactory;

    @Inject
    public PasswordResetTokenVerifier(final PublicJsonWebKeyProvider publicJsonWebKeyProvider,
                                      final Provider<OpenIdConfiguration> openIdConfigurationProvider,
                                      final OpenIdClientFactory openIdClientDetailsFactory) {
        this.publicJsonWebKeyProvider = publicJsonWebKeyProvider;
        this.openIdConfigurationProvider = openIdConfigurationProvider;
        this.openIdClientDetailsFactory = openIdClientDetailsFactory;
    }

    /**
     * Verify the signature, expiry, issuer, audience and purpose of a password reset token.
     *
     * @return The token's claims, or empty if it is not a valid password reset token. The caller must
     * still check {@link PasswordResetToken#nonce()} and
     * {@link PasswordResetToken#passwordLastChangedMs()} against the account, which is what stops a
     * token being used twice or after a newer one has been issued.
     */
    public Optional<PasswordResetToken> verify(final String token) {
        if (NullSafe.isBlankString(token)) {
            return Optional.empty();
        }

        try {
            final JwtClaims claims = newJwtConsumer().processToClaims(token);

            final String purpose = claims.getClaimValueAsString(OpenId.CLAIM__STROOM_PURPOSE);
            if (!OpenId.STROOM_PURPOSE__PASSWORD_RESET.equals(purpose)) {
                // Refuse to reset a password using anything other than a token minted for that purpose,
                // e.g. a token that authenticates API requests must not also be a password reset token.
                LOGGER.warn(() -> "Rejecting a token with purpose '" + purpose
                                  + "' that was presented to reset a password");
                return Optional.empty();
            }

            final Long passwordLastChangedMs = getPasswordLastChangedMs(claims);
            if (passwordLastChangedMs == null) {
                LOGGER.warn(() -> "Rejecting a password reset token with no "
                                  + OpenId.CLAIM__STROOM_PASSWORD_LAST_CHANGED_MS + " claim");
                return Optional.empty();
            }

            final String nonce = claims.getClaimValueAsString(OpenId.CLAIM__STROOM_RESET_TOKEN_NONCE);
            if (NullSafe.isBlankString(nonce)) {
                LOGGER.warn(() -> "Rejecting a password reset token with no "
                                  + OpenId.CLAIM__STROOM_RESET_TOKEN_NONCE + " claim");
                return Optional.empty();
            }

            return Optional.of(new PasswordResetToken(claims.getSubject(), nonce, passwordLastChangedMs));

        } catch (final Exception e) {
            LOGGER.debug(() -> "Unable to verify password reset token: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Long getPasswordLastChangedMs(final JwtClaims claims) {
        final Object value = claims.getClaimValue(OpenId.CLAIM__STROOM_PASSWORD_LAST_CHANGED_MS);
        return value instanceof final Number number
                ? number.longValue()
                : null;
    }

    private JwtConsumer newJwtConsumer() {
        final List<PublicJsonWebKey> publicJsonWebKeys = publicJsonWebKeyProvider.list();
        final JsonWebKeySet publicJsonWebKeySet = new JsonWebKeySet(publicJsonWebKeys);
        final VerificationKeyResolver verificationKeyResolver = new JwksVerificationKeyResolver(
                publicJsonWebKeySet.getJsonWebKeys());

        return new JwtConsumerBuilder()
                .setAllowedClockSkewInSeconds(ALLOWED_CLOCK_SKEW_SECONDS)
                .setRequireSubject()
                .setRequireExpirationTime()
                .setVerificationKeyResolver(verificationKeyResolver)
                .setRelaxVerificationKeyValidation()
                .setJwsAlgorithmConstraints(new AlgorithmConstraints(
                        ConstraintType.PERMIT,
                        AlgorithmIdentifiers.RSA_USING_SHA256))
                .setExpectedIssuer(openIdConfigurationProvider.get().getIssuer())
                // The audience is always the internal client id that the token was minted with, so we can
                // require it rather than relying on the allowedAudiences property, which is empty by
                // default and would mean the audience was not checked at all.
                .setExpectedAudience(openIdClientDetailsFactory.getClient().getClientId())
                .build();
    }
}
