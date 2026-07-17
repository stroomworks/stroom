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

import stroom.event.logging.api.StroomEventLoggingService;
import stroom.security.identity.account.AccountDao;
import stroom.security.identity.account.AccountService;
import stroom.security.identity.config.IdentityConfig;
import stroom.security.identity.config.PasswordPolicyConfig;
import stroom.security.identity.shared.Account;
import stroom.security.identity.shared.ChangePasswordResponse;
import stroom.security.identity.shared.ResetPasswordRequest;
import stroom.security.identity.token.PasswordResetToken;
import stroom.security.identity.token.PasswordResetTokenVerifier;
import stroom.task.api.ExecutorProvider;

import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestResetPasswordUsingToken {

    private static final String USER_ID = "jbloggs";
    private static final String TOKEN = "a-token";
    private static final String NONCE = "the-nonce";
    private static final String NEW_PASSWORD = "correct-horse-battery-staple";
    private static final long PASSWORD_LAST_CHANGED_MS = 1_000L;

    @Mock
    private AccountDao accountDao;
    @Mock
    private AccountService accountService;
    @Mock
    private PasswordResetTokenVerifier verifier;
    @Mock
    private ExecutorProvider executorProvider;

    @Mock
    private Provider<StroomEventLoggingService> stroomEventLoggingService;
    @Mock
    private StroomEventLoggingService eventLoggingService;

    @BeforeEach
    void setUp() {
        lenient().when(executorProvider.get(any())).thenReturn(Runnable::run);
        lenient().when(stroomEventLoggingService.get()).thenReturn(eventLoggingService);
        lenient().when(verifier.verify(TOKEN))
                .thenReturn(Optional.of(new PasswordResetToken(USER_ID, NONCE, PASSWORD_LAST_CHANGED_MS)));
        lenient().when(accountDao.getResetTokenNonce(USER_ID)).thenReturn(Optional.of(NONCE));
        lenient().when(accountDao.unlockAndSetPassword(eq(USER_ID), anyString(), eq(NONCE)))
                .thenReturn(true);
        lenient().when(accountDao.getPasswordLastChangedMs(USER_ID))
                .thenReturn(Optional.of(PASSWORD_LAST_CHANGED_MS));
        // By default the new password is not the one already on the account.
        lenient().when(accountDao.validateCredentials(eq(USER_ID), anyString()))
                .thenReturn(new CredentialValidationResult(false, false, false, false, false, false));
    }

    @Test
    void unlockedAccountCanResetItsPassword() {
        givenAccount(account(false, false, true));

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isTrue();
        verify(accountDao).unlockAndSetPassword(USER_ID, NEW_PASSWORD, NONCE);
    }

    @Test
    void resettingAPasswordDoesNotReactivateAnInactiveAccount() {
        // The whole point of unlockAndSetPassword rather than resetPassword. Only a successful
        // authentication may make an inactive account active again.
        givenAccount(account(false, true, true));

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isTrue();
        verify(accountDao).unlockAndSetPassword(USER_ID, NEW_PASSWORD, NONCE);
        verify(accountDao, never()).reactivateAccount(anyString());
        verify(accountDao, never()).resetPassword(anyString(), anyString());
    }

    @Test
    void lockedAccountCannotResetItsPasswordUnlessConfigured() {
        givenAccount(account(true, false, true));

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isFalse();
        // Must not say why. The caller does not necessarily own this account.
        assertThat(response.getMessage()).doesNotContain("locked");
        assertThat(response.getMessage()).contains("contact your administrator");
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void lockedAccountCanResetItsPasswordWhenConfigured() {
        givenAccount(account(true, false, true));

        final ChangePasswordResponse response = resetPassword(config(true));

        assertThat(response.isChangeSucceeded()).isTrue();
        verify(accountDao).unlockAndSetPassword(USER_ID, NEW_PASSWORD, NONCE);
    }

    @Test
    void disabledAccountCanNeverResetItsPassword() {
        givenAccount(account(false, false, false));

        final ChangePasswordResponse response = resetPassword(config(true));

        assertThat(response.isChangeSucceeded()).isFalse();
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void currentPasswordCannotBeReused() {
        // Otherwise a user locked out by mistyping could clear the lock without changing anything.
        givenAccount(account(true, false, true));
        when(accountDao.validateCredentials(USER_ID, NEW_PASSWORD))
                .thenReturn(new CredentialValidationResult(true, false, true, false, false, false));

        final ChangePasswordResponse response = resetPassword(config(true));

        assertThat(response.isChangeSucceeded()).isFalse();
        assertThat(response.getMessage()).contains("reuse");
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void linkConsumedByAConcurrentRequestIsRefused() {
        // Two requests can both get past the nonce read, so the conditional update is what actually
        // decides. The loser must be told the link is spent rather than reporting success.
        givenAccount(account(false, false, true));
        when(accountDao.unlockAndSetPassword(eq(USER_ID), anyString(), eq(NONCE))).thenReturn(false);

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isFalse();
        assertThat(response.getMessage()).contains("invalid or has expired");
    }

    @Test
    void onlyTheMostRecentlyIssuedTokenWorks() {
        // Requesting another reset email records a new nonce, so the older link must stop working.
        givenAccount(account(false, false, true));
        when(accountDao.getResetTokenNonce(USER_ID)).thenReturn(Optional.of("a-newer-nonce"));

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isFalse();
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void usedTokenIsRefusedBecauseTheNonceIsCleared() {
        // unlockAndSetPassword clears the nonce, which is what makes a token single use.
        givenAccount(account(false, false, true));
        when(accountDao.getResetTokenNonce(USER_ID)).thenReturn(Optional.empty());

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isFalse();
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void tokenIsRefusedIfThePasswordChangedByAnotherRoute() {
        // The nonce is only cleared by a reset, so the password last changed binding is what catches a
        // password changed some other way while a reset link was outstanding.
        givenAccount(account(false, false, true));
        when(accountDao.getPasswordLastChangedMs(USER_ID))
                .thenReturn(Optional.of(PASSWORD_LAST_CHANGED_MS + 1));

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isFalse();
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void processingAccountCanNeverResetItsPassword() {
        final Account account = account(false, false, true);
        account.setProcessingAccount(true);
        givenAccount(account);

        final ChangePasswordResponse response = resetPassword(config(true));

        assertThat(response.isChangeSucceeded()).isFalse();
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void mismatchedConfirmationIsReportedRatherThanThrown() {
        // PasswordValidator signals policy violations by throwing. On this unauthenticated endpoint that
        // would surface as a 500 rather than telling the user what they got wrong.
        givenAccount(account(false, false, true));

        final ChangePasswordResponse response = resetPassword(config(false),
                new ResetPasswordRequest(TOKEN, NEW_PASSWORD, "something-else"));

        assertThat(response.isChangeSucceeded()).isFalse();
        assertThat(response.getMessage()).contains("confirmation");
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void passwordThatIsTooShortIsReportedRatherThanThrown() {
        givenAccount(account(false, false, true));

        final ChangePasswordResponse response = resetPassword(config(false),
                new ResetPasswordRequest(TOKEN, "x", "x"));

        assertThat(response.isChangeSucceeded()).isFalse();
        assertThat(response.getMessage()).contains("length");
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void validTokenForAnAccountThatNoLongerExistsIsRefused() {
        when(accountDao.get(USER_ID)).thenReturn(Optional.empty());

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isFalse();
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void unverifiableTokenIsRefused() {
        when(verifier.verify(TOKEN)).thenReturn(Optional.empty());

        final ChangePasswordResponse response = resetPassword(config(false));

        assertThat(response.isChangeSucceeded()).isFalse();
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void resetsAreRefusedWhenPasswordResetsAreDisallowed() {
        final ChangePasswordResponse response = resetPassword(config(true, false));

        assertThat(response.isChangeSucceeded()).isFalse();
        verify(accountDao, never()).unlockAndSetPassword(anyString(), anyString(), anyString());
        // The guard is placed before verification deliberately, so a disabled feature does no work.
        verify(verifier, never()).verify(anyString());
    }

    private void givenAccount(final Account account) {
        when(accountDao.get(USER_ID)).thenReturn(Optional.of(account));
    }

    private Account account(final boolean locked, final boolean inactive, final boolean enabled) {
        final Account account = new Account();
        account.setUserId(USER_ID);
        account.setLocked(locked);
        account.setInactive(inactive);
        account.setEnabled(enabled);
        return account;
    }

    private ChangePasswordResponse resetPassword(final IdentityConfig config) {
        return resetPassword(config, new ResetPasswordRequest(TOKEN, NEW_PASSWORD, NEW_PASSWORD));
    }

    private ChangePasswordResponse resetPassword(final IdentityConfig config,
                                                 final ResetPasswordRequest request) {
        final AuthenticationServiceImpl service = new AuthenticationServiceImpl(
                null,
                config,
                null,
                accountDao,
                accountService,
                null,
                null,
                null,
                null,
                stroomEventLoggingService,
                verifier,
                executorProvider);
        return service.resetPasswordUsingToken(request);
    }

    private IdentityConfig config(final boolean allowLockedAccountPasswordReset) {
        return config(allowLockedAccountPasswordReset, true);
    }

    private IdentityConfig config(final boolean allowLockedAccountPasswordReset,
                                  final boolean allowPasswordResets) {
        return new IdentityConfig(
                null,
                null,
                ".*\\((.*)\\)",
                null,
                3,
                false,
                allowLockedAccountPasswordReset,
                null,
                null,
                null,
                null,
                passwordPolicy(allowPasswordResets),
                null);
    }

    private PasswordPolicyConfig passwordPolicy(final boolean allowPasswordResets) {
        return new PasswordPolicyConfig(
                allowPasswordResets,
                null,
                null,
                null,
                null,
                null,
                0,
                8,
                null);
    }
}
