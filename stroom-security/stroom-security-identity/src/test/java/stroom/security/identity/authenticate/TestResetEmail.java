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

package stroom.security.identity.authenticate;

import stroom.security.identity.account.AccountDao;
import stroom.security.identity.account.AccountService;
import stroom.security.identity.config.IdentityConfig;
import stroom.security.identity.shared.Account;
import stroom.security.identity.token.PasswordResetTokenVerifier;
import stroom.security.identity.token.TokenBuilderFactory;
import stroom.security.openid.api.OpenId;
import stroom.security.openid.api.OpenIdClient;
import stroom.security.openid.api.OpenIdClientFactory;
import stroom.security.openid.api.OpenIdConfiguration;
import stroom.security.openid.api.PublicJsonWebKeyProvider;
import stroom.task.api.ExecutorProvider;

import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestResetEmail {

    private static final String EMAIL = "jbloggs@example.com";
    private static final String USER_ID = "jbloggs";
    private static final long PASSWORD_LAST_CHANGED_MS = 4321L;

    @Mock
    private AccountDao accountDao;
    @Mock
    private AccountService accountService;
    @Mock
    private EmailSender emailSender;
    @Mock
    private PasswordResetTokenVerifier verifier;
    @Mock
    private ExecutorProvider executorProvider;
    @Mock
    private PublicJsonWebKeyProvider publicJsonWebKeyProvider;
    @Mock
    private OpenIdConfiguration openIdConfiguration;
    @Mock
    private OpenIdClientFactory openIdClientFactory;

    @BeforeEach
    void setUp() {
        // Run the emailing straight away rather than on another thread, so the test can assert on it.
        lenient().when(executorProvider.get(any())).thenReturn(Runnable::run);
        lenient().when(openIdClientFactory.getClient())
                .thenReturn(new OpenIdClient("stroom", "stroom-client", "secret", ".*"));
    }

    @Test
    void unknownEmailAddressStillReportsSuccessAndSendsNothing() {
        // The endpoint is unauthenticated, so reporting anything other than success would let anyone work
        // out which email addresses have accounts.
        when(accountDao.getByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThat(service(null).resetEmail(EMAIL)).isTrue();

        verify(emailSender, never()).send(anyString(), any(), any(), anyString());
        // Nothing beyond the lookup happens, so a caller cannot tell a known address from an unknown one
        // by how long we take to answer.
        verify(accountDao, never()).tryRecordResetEmailRequest(anyString(), anyLong(), anyLong());
    }

    @Test
    void requestTooSoonAfterTheLastOneSendsNothing() {
        when(accountDao.getByEmail(EMAIL)).thenReturn(Optional.of(account()));
        when(accountDao.tryRecordResetEmailRequest(eq(USER_ID), anyLong(), anyLong())).thenReturn(false);

        assertThat(service(null).resetEmail(EMAIL)).isTrue();

        verify(emailSender, never()).send(anyString(), any(), any(), anyString());
        // A throttled request must not burn the outstanding link either.
        verify(accountDao, never()).setResetTokenNonce(anyString(), anyString());
    }

    @Test
    void theEmailedTokenIsMarkedAsAResetTokenAndBoundToTheCurrentPassword() throws Exception {
        // Nothing else proves that the token we actually email carries the claims that stop it being
        // used as an API credential and stop it being used twice. Without this, dropping any of them
        // from createResetEmailToken would leave every other test passing.
        when(accountDao.getByEmail(EMAIL)).thenReturn(Optional.of(account()));
        when(accountDao.tryRecordResetEmailRequest(eq(USER_ID), anyLong(), anyLong())).thenReturn(true);
        when(accountDao.getPasswordLastChangedMs(USER_ID)).thenReturn(Optional.of(PASSWORD_LAST_CHANGED_MS));

        final RsaJsonWebKey jwk = RsaJwkGenerator.generateJwk(2048);
        jwk.setKeyId("test-key");
        when(publicJsonWebKeyProvider.getFirst()).thenReturn(jwk);
        when(openIdConfiguration.getIssuer()).thenReturn("stroom");

        assertThat(service(new TokenBuilderFactory(
                IdentityConfig::new,
                publicJsonWebKeyProvider,
                () -> openIdConfiguration)).resetEmail(EMAIL)).isTrue();

        // The mail goes to the address held against the account, not to whatever the caller typed.
        final ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(EMAIL), any(), any(), tokenCaptor.capture());

        // Only the claims matter here; PasswordResetTokenVerifier covers signing and validation.
        final JwtClaims claims = new JwtConsumerBuilder()
                .setSkipAllValidators()
                .setSkipSignatureVerification()
                .build()
                .processToClaims(tokenCaptor.getValue());

        assertThat(claims.getClaimValueAsString(OpenId.CLAIM__STROOM_PURPOSE))
                .isEqualTo(OpenId.STROOM_PURPOSE__PASSWORD_RESET);
        assertThat(((Number) claims.getClaimValue(OpenId.CLAIM__STROOM_PASSWORD_LAST_CHANGED_MS)).longValue())
                .isEqualTo(PASSWORD_LAST_CHANGED_MS);
        assertThat(claims.getSubject()).isEqualTo(USER_ID);

        // The nonce in the token must be the one recorded against the account, or the link would never
        // be accepted.
        final ArgumentCaptor<String> nonceCaptor = ArgumentCaptor.forClass(String.class);
        verify(accountDao).setResetTokenNonce(eq(USER_ID), nonceCaptor.capture());
        assertThat(claims.getClaimValueAsString(OpenId.CLAIM__STROOM_RESET_TOKEN_NONCE))
                .isEqualTo(nonceCaptor.getValue());

        // The account is found by email address, not by treating the address as a user id, which is what
        // used to happen and meant 'forgot password' only worked when the two were the same string.
        verify(accountDao).getByEmail(EMAIL);
        verify(accountService, never()).read(anyString());
    }

    private AuthenticationServiceImpl service(final TokenBuilderFactory tokenBuilderFactory) {
        return new AuthenticationServiceImpl(
                null,
                new IdentityConfig(),
                emailSender,
                accountDao,
                accountService,
                openIdClientFactory,
                tokenBuilderFactory,
                new IdentityConfig().getTokenConfig(),
                null,
                null,
                verifier,
                executorProvider);
    }

    private Account account() {
        final Account account = new Account();
        account.setUserId(USER_ID);
        account.setEmail(EMAIL);
        return account;
    }
}
