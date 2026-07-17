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

package stroom.security.impl;

import stroom.security.identity.config.IdentityConfig;
import stroom.security.identity.token.PasswordResetTokenVerifier;
import stroom.security.identity.token.TokenBuilderFactory;
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
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * The crux of the password reset token design, and the only place it can be proved because
 * stroom-security-impl and stroom-security-identity do not depend on each other.
 * <p>
 * A password reset token is minted with the same key, subject, issuer and audience as an ordinary
 * token, so on the wire only the purpose claim tells them apart. This asserts that one and the same
 * token, built by the real {@link TokenBuilderFactory} exactly as
 * {@code AuthenticationServiceImpl.createResetEmailToken} builds it, is:
 * </p>
 * <ul>
 *     <li>accepted by {@link PasswordResetTokenVerifier}, so the reset flow works, and</li>
 *     <li>rejected by {@link InternalJwtContextFactory}, so it cannot be presented as
 *     {@code Authorization: Bearer ...} to authenticate API requests as its subject.</li>
 * </ul>
 * <p>
 * Testing each half against its own hand rolled token would not prove the two agree.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class TestResetTokenIsNotACredential {

    private static final String ISSUER = "stroom";
    private static final String CLIENT_ID = "stroom-client";
    private static final String USER_ID = "jbloggs";

    @Mock
    private PublicJsonWebKeyProvider publicJsonWebKeyProvider;
    @Mock
    private OpenIdConfiguration openIdConfiguration;
    @Mock
    private OpenIdClientFactory openIdClientFactory;

    private TokenBuilderFactory tokenBuilderFactory;
    private PasswordResetTokenVerifier verifier;
    private InternalJwtContextFactory internalJwtContextFactory;

    @BeforeEach
    void setUp() throws JoseException {
        final RsaJsonWebKey jwk = RsaJwkGenerator.generateJwk(2048);
        jwk.setKeyId("test-key");

        final List<PublicJsonWebKey> keys = List.of(jwk);
        lenient().when(publicJsonWebKeyProvider.list()).thenReturn(keys);
        lenient().when(publicJsonWebKeyProvider.getFirst()).thenReturn(jwk);
        lenient().when(openIdConfiguration.getIssuer()).thenReturn(ISSUER);
        lenient().when(openIdConfiguration.getValidIssuers()).thenReturn(Collections.emptySet());
        lenient().when(openIdConfiguration.getAllowedAudiences()).thenReturn(Collections.emptySet());
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
        internalJwtContextFactory = new InternalJwtContextFactory(
                openIdClientFactory,
                publicJsonWebKeyProvider,
                () -> openIdConfiguration);
    }

    @Test
    void realResetTokenResetsPasswordsButCannotAuthenticate() {
        final String resetToken = realResetToken();

        assertThat(verifier.verify(resetToken))
                .as("the reset flow must accept the token it emails")
                .isPresent();
        assertThat(internalJwtContextFactory.getJwtContext(resetToken))
                .as("the same token must not authenticate an API request")
                .isEmpty();
    }

    @Test
    void ordinaryTokenAuthenticatesButCannotResetPasswords() {
        // The mirror image, which also proves the assertions above are not passing for some unrelated
        // reason such as a broken key or issuer.
        final String ordinaryToken = tokenBuilderFactory.builder()
                .expirationTime(Instant.now().plusSeconds(600))
                .clientId(CLIENT_ID)
                .subject(USER_ID)
                .build();

        assertThat(internalJwtContextFactory.getJwtContext(ordinaryToken))
                .as("an ordinary token must still authenticate")
                .isPresent();
        assertThat(verifier.verify(ordinaryToken))
                .as("an ordinary token must not be usable to reset a password")
                .isEmpty();
    }

    /**
     * Built exactly as {@code AuthenticationServiceImpl.createResetEmailToken} builds it.
     */
    private String realResetToken() {
        return tokenBuilderFactory.builder()
                .expirationTime(Instant.now().plus(new IdentityConfig()
                        .getTokenConfig()
                        .getEmailResetTokenExpiration()))
                .clientId(CLIENT_ID)
                .purpose(OpenId.STROOM_PURPOSE__PASSWORD_RESET)
                .passwordLastChangedMs(1234L)
                .resetTokenNonce("the-nonce")
                .subject(USER_ID)
                .build();
    }
}
