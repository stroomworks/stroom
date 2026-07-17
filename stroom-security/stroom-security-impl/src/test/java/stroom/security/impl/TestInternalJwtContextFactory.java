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

import stroom.security.openid.api.OpenId;
import stroom.security.openid.api.OpenIdClientFactory;
import stroom.security.openid.api.OpenIdConfiguration;
import stroom.security.openid.api.PublicJsonWebKeyProvider;

import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TestInternalJwtContextFactory {

    private static final String ISSUER = "stroom";
    private static final String USER_ID = "jbloggs";

    @Mock
    private OpenIdClientFactory openIdClientFactory;
    @Mock
    private PublicJsonWebKeyProvider publicJsonWebKeyProvider;
    @Mock
    private OpenIdConfiguration openIdConfiguration;

    private RsaJsonWebKey jwk;
    private InternalJwtContextFactory factory;

    @BeforeEach
    void setUp() throws JoseException {
        jwk = RsaJwkGenerator.generateJwk(2048);
        jwk.setKeyId("test-key");

        final List<PublicJsonWebKey> keys = List.of(jwk);
        lenient().when(publicJsonWebKeyProvider.list()).thenReturn(keys);
        lenient().when(openIdConfiguration.getIssuer()).thenReturn(ISSUER);
        lenient().when(openIdConfiguration.getValidIssuers()).thenReturn(Collections.emptySet());
        // Mirrors the shipped default, which means audience is not validated.
        lenient().when(openIdConfiguration.getAllowedAudiences()).thenReturn(Collections.emptySet());

        factory = new InternalJwtContextFactory(
                openIdClientFactory,
                publicJsonWebKeyProvider,
                () -> openIdConfiguration);
    }

    @Test
    void normalTokenIsAccepted() {
        final Optional<JwtContext> context = factory.getJwtContext(buildToken(null));

        assertThat(context).isPresent();
        assertThat(context.get().getJwtClaims().getClaimValueAsString(OpenId.CLAIM__SUBJECT))
                .isEqualTo(USER_ID);
    }

    @Test
    void passwordResetTokenIsRejected() {
        // A password reset token is signed with the same key and carries the same subject, issuer and
        // audience as a normal token, so only the purpose claim distinguishes it. It must not be usable
        // as a credential to authenticate API requests.
        final Optional<JwtContext> context =
                factory.getJwtContext(buildToken(OpenId.STROOM_PURPOSE__PASSWORD_RESET));

        assertThat(context).isEmpty();
    }

    @Test
    void tokenWithAnyOtherPurposeIsRejected() {
        // Restricted tokens must fail closed, so a purpose this code has never heard of is rejected too.
        final Optional<JwtContext> context = factory.getJwtContext(buildToken("some_future_purpose"));

        assertThat(context).isEmpty();
    }

    @Test
    void tokenWithANonStringPurposeIsRejected() {
        // The purpose check must fail closed. Reading the claim as a String would throw for a value of
        // another type, and swallowing that would let the token through as a credential.
        assertThat(factory.getJwtContext(buildToken(123))).isEmpty();
        assertThat(factory.getJwtContext(buildToken(List.of(OpenId.STROOM_PURPOSE__PASSWORD_RESET))))
                .isEmpty();
        assertThat(factory.getJwtContext(buildToken(""))).isEmpty();
    }

    private String buildToken(final Object purpose) {
        final JwtClaims claims = new JwtClaims();
        claims.setSubject(USER_ID);
        claims.setIssuer(ISSUER);
        claims.setAudience("stroom-client");
        claims.setExpirationTime(NumericDate.fromSeconds(
                Instant.now().plusSeconds(600).getEpochSecond()));
        if (purpose != null) {
            claims.setClaim(OpenId.CLAIM__STROOM_PURPOSE, purpose);
        }

        final JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        jws.setKey(jwk.getPrivateKey());
        jws.setKeyIdHeaderValue(jwk.getKeyId());
        jws.setDoKeyValidation(true);
        try {
            return jws.getCompactSerialization();
        } catch (final JoseException e) {
            throw new RuntimeException(e);
        }
    }
}
