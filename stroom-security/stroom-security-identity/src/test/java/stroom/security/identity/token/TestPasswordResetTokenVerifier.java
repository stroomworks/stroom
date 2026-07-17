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

import stroom.security.identity.config.IdentityConfig;
import stroom.security.openid.api.OpenId;
import stroom.security.openid.api.OpenIdClient;
import stroom.security.openid.api.OpenIdClientFactory;
import stroom.security.openid.api.OpenIdConfiguration;
import stroom.security.openid.api.PublicJsonWebKeyProvider;

import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link PasswordResetTokenVerifier} against tokens minted by the real
 * {@link TokenBuilderFactory}, so that the mint and verify halves are proved to agree rather than
 * each being tested against this test's own idea of what a token looks like.
 */
@ExtendWith(MockitoExtension.class)
class TestPasswordResetTokenVerifier {

    private static final String ISSUER = "stroom";
    private static final String CLIENT_ID = "stroom-client";
    private static final String USER_ID = "jbloggs";
    private static final long PASSWORD_LAST_CHANGED_MS = 1234L;
    private static final String NONCE = "the-nonce";

    @Mock
    private PublicJsonWebKeyProvider publicJsonWebKeyProvider;
    @Mock
    private OpenIdConfiguration openIdConfiguration;
    @Mock
    private OpenIdClientFactory openIdClientFactory;

    private RsaJsonWebKey jwk;
    private TokenBuilderFactory tokenBuilderFactory;
    private PasswordResetTokenVerifier verifier;

    @BeforeEach
    void setUp() throws JoseException {
        jwk = RsaJwkGenerator.generateJwk(2048);
        jwk.setKeyId("test-key");

        final List<PublicJsonWebKey> keys = List.of(jwk);
        lenient().when(publicJsonWebKeyProvider.list()).thenReturn(keys);
        lenient().when(publicJsonWebKeyProvider.getFirst()).thenReturn(jwk);
        lenient().when(openIdConfiguration.getIssuer()).thenReturn(ISSUER);
        lenient().when(openIdClientFactory.getClient())
                .thenReturn(new OpenIdClient("stroom", CLIENT_ID, "secret", ".*"));

        tokenBuilderFactory = new TokenBuilderFactory(
                IdentityConfig::new,
                publicJsonWebKeyProvider,
                () -> openIdConfiguration);

        verifier = new PasswordResetTokenVerifier(
                publicJsonWebKeyProvider,
                () -> openIdConfiguration,
                openIdClientFactory);
    }

    @Test
    void realResetTokenIsVerified() {
        final Optional<PasswordResetToken> result = verifier.verify(resetToken().build());

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(USER_ID);
        assertThat(result.get().nonce()).isEqualTo(NONCE);
        assertThat(result.get().passwordLastChangedMs()).isEqualTo(PASSWORD_LAST_CHANGED_MS);
    }

    @Test
    void ordinaryTokenIsNotAcceptedAsAResetToken() {
        // A token that authenticates API requests must not double as a password reset token.
        final String token = tokenBuilderFactory.builder()
                .expirationTime(Instant.now().plusSeconds(600))
                .clientId(CLIENT_ID)
                .subject(USER_ID)
                .build();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void tokenWithSomeOtherPurposeIsRejected() {
        final String token = resetToken()
                .purpose("some_other_purpose")
                .build();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        // Well outside the 30s clock skew allowance.
        final String token = resetToken()
                .expirationTime(Instant.now().minusSeconds(600))
                .build();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        when(openIdConfiguration.getIssuer()).thenReturn("someone-else");
        final String token = resetToken().issuer("someone-else").build();

        // Put the expected issuer back so the verifier expects ours, not theirs.
        when(openIdConfiguration.getIssuer()).thenReturn(ISSUER);

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void tokenForAnotherAudienceIsRejected() {
        final String token = resetToken().clientId("some-other-client").build();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws JoseException {
        final RsaJsonWebKey otherKey = RsaJwkGenerator.generateJwk(2048);
        otherKey.setKeyId("test-key");
        final String forged = new TokenBuilder()
                .issuer(ISSUER)
                .privateVerificationKey(otherKey)
                .expirationTime(Instant.now().plusSeconds(600))
                .clientId(CLIENT_ID)
                .subject(USER_ID)
                .purpose(OpenId.STROOM_PURPOSE__PASSWORD_RESET)
                .passwordLastChangedMs(PASSWORD_LAST_CHANGED_MS)
                .resetTokenNonce(NONCE)
                .build();

        assertThat(verifier.verify(forged)).isEmpty();
    }

    @Test
    void resetTokenWithNoPasswordBindingIsRejected() {
        // Without the binding claim the token could not be made single use, so it must not be accepted.
        final String token = tokenBuilderFactory.builder()
                .expirationTime(Instant.now().plusSeconds(600))
                .clientId(CLIENT_ID)
                .subject(USER_ID)
                .purpose(OpenId.STROOM_PURPOSE__PASSWORD_RESET)
                .build();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void resetTokenWithNoNonceIsRejected() {
        // Without the nonce there would be no way to tell a superseded token from the current one.
        final String token = tokenBuilderFactory.builder()
                .expirationTime(Instant.now().plusSeconds(600))
                .clientId(CLIENT_ID)
                .subject(USER_ID)
                .purpose(OpenId.STROOM_PURPOSE__PASSWORD_RESET)
                .passwordLastChangedMs(PASSWORD_LAST_CHANGED_MS)
                .build();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void tokenWithNoSubjectIsRejected() {
        final String token = resetToken().subject(null).build();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(verifier.verify("not-a-jwt")).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify(null)).isEmpty();
    }

    private TokenBuilder resetToken() {
        return tokenBuilderFactory.builder()
                .expirationTime(Instant.now().plusSeconds(600))
                .clientId(CLIENT_ID)
                .subject(USER_ID)
                .purpose(OpenId.STROOM_PURPOSE__PASSWORD_RESET)
                .passwordLastChangedMs(PASSWORD_LAST_CHANGED_MS)
                .resetTokenNonce(NONCE);
    }
}
