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
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.lang.JoseException;

import java.time.Instant;

public class TokenBuilder {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TokenBuilder.class);

    private Instant expirationTime;
    private String issuer;
    private String algorithm = AlgorithmIdentifiers.RSA_USING_SHA256;

    private String subject;
    private String nonce;
    private String state;
    private PublicJsonWebKey publicJsonWebKey;
    private String clientId;
    private String purpose;
    private Long passwordLastChangedMs;
    private String resetTokenNonce;

    public TokenBuilder subject(final String subject) {
        this.subject = subject;
        return this;
    }

    /**
     * Restrict what the token may be used for, see {@link OpenId#CLAIM__STROOM_PURPOSE}. Leave unset for
     * a normal token that can be used to authenticate API requests.
     */
    public TokenBuilder purpose(final String purpose) {
        this.purpose = purpose;
        return this;
    }

    /**
     * Bind the token to the account's current password, see
     * {@link OpenId#CLAIM__STROOM_PASSWORD_LAST_CHANGED_MS}.
     */
    public TokenBuilder passwordLastChangedMs(final Long passwordLastChangedMs) {
        this.passwordLastChangedMs = passwordLastChangedMs;
        return this;
    }

    /**
     * Identify which password reset token this is, see {@link OpenId#CLAIM__STROOM_RESET_TOKEN_NONCE}.
     */
    public TokenBuilder resetTokenNonce(final String resetTokenNonce) {
        this.resetTokenNonce = resetTokenNonce;
        return this;
    }

    public TokenBuilder clientId(final String clientId) {
        this.clientId = clientId;
        return this;
    }

    public TokenBuilder issuer(final String issuer) {
        this.issuer = issuer;
        return this;
    }

    public TokenBuilder privateVerificationKey(final PublicJsonWebKey publicJsonWebKey) {
        this.publicJsonWebKey = publicJsonWebKey;
        return this;
    }

    public TokenBuilder nonce(final String nonce) {
        this.nonce = nonce;
        return this;
    }

    public TokenBuilder state(final String state) {
        this.state = state;
        return this;
    }

    public TokenBuilder algorithm(final String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    public TokenBuilder expirationTime(final Instant expirationTime) {
        this.expirationTime = expirationTime;
        return this;
    }

    public Instant getExpirationTime() {
        return this.expirationTime;
    }

    public String build() {
        final JwtClaims claims = new JwtClaims();
        if (expirationTime != null) {
            claims.setExpirationTime(NumericDate.fromSeconds(expirationTime.getEpochSecond()));
        }
        claims.setSubject(subject);
        claims.setIssuer(issuer);
        claims.setAudience(clientId);
        if (nonce != null) {
            claims.setClaim(OpenId.NONCE, nonce);
        }
        if (state != null) {
            claims.setClaim(OpenId.STATE, state);
        }
        if (purpose != null) {
            claims.setClaim(OpenId.CLAIM__STROOM_PURPOSE, purpose);
        }
        if (passwordLastChangedMs != null) {
            claims.setClaim(OpenId.CLAIM__STROOM_PASSWORD_LAST_CHANGED_MS, passwordLastChangedMs);
        }
        if (resetTokenNonce != null) {
            claims.setClaim(OpenId.CLAIM__STROOM_RESET_TOKEN_NONCE, resetTokenNonce);
        }

        final JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setAlgorithmHeaderValue(this.algorithm);
        jws.setKey(this.publicJsonWebKey.getPrivateKey());
        jws.setDoKeyValidation(true);

        // TODO need to pass this in as it may not be the default one
        if (publicJsonWebKey.getKeyId() != null && !publicJsonWebKey.getKeyId().isEmpty()) {
            LOGGER.debug(() -> "Setting KeyIdHeaderValue to " + publicJsonWebKey.getKeyId());
            jws.setKeyIdHeaderValue(publicJsonWebKey.getKeyId());
        }

        try {
            return jws.getCompactSerialization();
        } catch (final JoseException e) {
            throw new RuntimeException(e);
        }
    }

}
