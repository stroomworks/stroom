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

package stroom.security.identity.db;

import stroom.db.util.ExpressionMapper;
import stroom.db.util.ExpressionMapperFactory;
import stroom.db.util.GenericDao;
import stroom.db.util.JooqUtil;
import stroom.security.identity.account.AccountDao;
import stroom.security.identity.authenticate.CredentialValidationResult;
import stroom.security.identity.config.IdentityConfig;
import stroom.security.identity.db.jooq.tables.records.AccountRecord;
import stroom.security.identity.exceptions.NoSuchUserException;
import stroom.security.identity.shared.Account;
import stroom.security.identity.shared.AccountFields;
import stroom.security.identity.shared.AccountResultPage;
import stroom.security.identity.shared.FindAccountRequest;
import stroom.util.ResultPageFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;
import stroom.util.shared.ResultPage;

import com.google.common.base.Strings;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.TableField;
import org.jooq.impl.DSL;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static stroom.security.identity.db.jooq.tables.Account.ACCOUNT;

@Singleton
class AccountDaoImpl implements AccountDao {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AccountDaoImpl.class);

    /**
     * Create a derived field that represents the account state.
     */
    private static final Field<String> ACCOUNT_STATUS = DSL
            .when(ACCOUNT.LOCKED.eq(true), "Locked")
            .when(ACCOUNT.INACTIVE.eq(true), "Inactive")
            .when(ACCOUNT.ENABLED.eq(true), "Enabled")
            .otherwise("Disabled")
            .as(DSL.field("status", String.class));

    private static final Function<Record, Account> RECORD_TO_ACCOUNT_MAPPER = record -> {
        final Account account = new Account();
        account.setId(record.get(ACCOUNT.ID));
        account.setVersion(record.get(ACCOUNT.VERSION));
        account.setCreateTimeMs(record.get(ACCOUNT.CREATE_TIME_MS));
        account.setUpdateTimeMs(record.get(ACCOUNT.UPDATE_TIME_MS));
        account.setCreateUser(record.get(ACCOUNT.CREATE_USER));
        account.setUpdateUser(record.get(ACCOUNT.UPDATE_USER));
        account.setUserId(record.get(ACCOUNT.USER_ID));
        account.setEmail(record.get(ACCOUNT.EMAIL));
        account.setFirstName(record.get(ACCOUNT.FIRST_NAME));
        account.setLastName(record.get(ACCOUNT.LAST_NAME));
        account.setComments(record.get(ACCOUNT.COMMENTS));
        account.setLoginCount(record.get(ACCOUNT.LOGIN_COUNT));
        account.setLoginFailures(record.get(ACCOUNT.LOGIN_FAILURES));
        account.setLastLoginMs(record.get(ACCOUNT.LAST_LOGIN_MS));
        account.setReactivatedMs(record.get(ACCOUNT.REACTIVATED_MS));
        account.setForcePasswordChange(
                record.get(ACCOUNT.FORCE_PASSWORD_CHANGE));
        account.setNeverExpires(record.get(ACCOUNT.NEVER_EXPIRES));
        account.setEnabled(record.get(ACCOUNT.ENABLED));
        account.setInactive(record.get(ACCOUNT.INACTIVE));
        account.setLocked(record.get(ACCOUNT.LOCKED));
        account.setProcessingAccount(
                record.get(ACCOUNT.PROCESSING_ACCOUNT));
        return account;
    };

    private static final BiFunction<Account, AccountRecord, AccountRecord> ACCOUNT_TO_RECORD_MAPPER =
            (account, record) -> {
                record.setId(account.getId());
                record.setVersion(account.getVersion());
                record.setCreateTimeMs(account.getCreateTimeMs());
                record.setUpdateTimeMs(account.getUpdateTimeMs());
                record.setCreateUser(account.getCreateUser());
                record.setUpdateUser(account.getUpdateUser());
                record.setUserId(account.getUserId());
                record.setEmail(account.getEmail());
                record.setFirstName(account.getFirstName());
                record.setLastName(account.getLastName());
                record.setComments(account.getComments());
                record.setLoginCount(account.getLoginCount());
                record.setLoginFailures(account.getLoginFailures());
                record.setLastLoginMs(account.getLastLoginMs());
                record.setReactivatedMs(account.getReactivatedMs());
                record.setForcePasswordChange(account.isForcePasswordChange());
                record.setNeverExpires(account.isNeverExpires());
                record.setEnabled(account.isEnabled());
                record.setInactive(account.isInactive());
                record.setLocked(account.isLocked());
                record.setProcessingAccount(account.isProcessingAccount());

                return record;
            };

    private static final Map<String, Field<?>> FIELD_MAP = Map.ofEntries(
            Map.entry("id", ACCOUNT.ID),
            Map.entry("version", ACCOUNT.VERSION),
            Map.entry("createTimeMs", ACCOUNT.CREATE_TIME_MS),
            Map.entry("updateTimeMs", ACCOUNT.UPDATE_TIME_MS),
            Map.entry("createUser", ACCOUNT.CREATE_USER),
            Map.entry("updateUser", ACCOUNT.UPDATE_USER),
            Map.entry(AccountFields.FIELD_NAME_USER_ID, ACCOUNT.USER_ID),
            Map.entry(AccountFields.FIELD_NAME_EMAIL, ACCOUNT.EMAIL),
            Map.entry(AccountFields.FIELD_NAME_FIRST_NAME, ACCOUNT.FIRST_NAME),
            Map.entry(AccountFields.FIELD_NAME_LAST_NAME, ACCOUNT.LAST_NAME),
            Map.entry(AccountFields.FIELD_NAME_COMMENTS, ACCOUNT.COMMENTS),
            Map.entry("loginCount", ACCOUNT.LOGIN_COUNT),
            Map.entry(AccountFields.FIELD_NAME_LOGIN_FAILURES, ACCOUNT.LOGIN_FAILURES),
            Map.entry(AccountFields.FIELD_NAME_LAST_LOGIN_MS, ACCOUNT.LAST_LOGIN_MS),
            Map.entry("reactivatedMs", ACCOUNT.REACTIVATED_MS),
            Map.entry("forcePasswordChange", ACCOUNT.FORCE_PASSWORD_CHANGE),
            Map.entry("neverExpires", ACCOUNT.NEVER_EXPIRES),
            Map.entry("enabled", ACCOUNT.ENABLED),
            Map.entry("inactive", ACCOUNT.INACTIVE),
            Map.entry("locked", ACCOUNT.LOCKED),
            Map.entry("processingAccount", ACCOUNT.PROCESSING_ACCOUNT),
            Map.entry(AccountFields.FIELD_NAME_STATUS, ACCOUNT_STATUS));

    private final Provider<IdentityConfig> identityConfigProvider;
    private final IdentityDbConnProvider identityDbConnProvider;
    private final GenericDao<AccountRecord, Account, Integer> genericDao;
    private final ExpressionMapper expressionMapper;

    @Inject
    public AccountDaoImpl(final Provider<IdentityConfig> identityConfigProvider,
                          final IdentityDbConnProvider identityDbConnProvider,
                          final ExpressionMapperFactory expressionMapperFactory) {
        this.identityConfigProvider = identityConfigProvider;
        this.identityDbConnProvider = identityDbConnProvider;
        genericDao = new GenericDao<>(
                identityDbConnProvider,
                ACCOUNT,
                ACCOUNT.ID,
                ACCOUNT_TO_RECORD_MAPPER,
                RECORD_TO_ACCOUNT_MAPPER);

        expressionMapper = expressionMapperFactory.create()
                .map(AccountFields.FIELD_USER_ID, ACCOUNT.USER_ID, String::valueOf)
                .map(AccountFields.FIELD_FIRST_NAME, ACCOUNT.FIRST_NAME, String::valueOf)
                .map(AccountFields.FIELD_LAST_NAME, ACCOUNT.LAST_NAME, String::valueOf)
                .map(AccountFields.FIELD_EMAIL, ACCOUNT.EMAIL, String::valueOf)
                .map(AccountFields.FIELD_STATUS, ACCOUNT_STATUS, String::valueOf)
                .map(AccountFields.FIELD_COMMENTS, ACCOUNT.COMMENTS, String::valueOf);
    }

    @Override
    public AccountResultPage list() {
        final TableField<AccountRecord, String> orderByUserIdField =
                ACCOUNT.USER_ID;
        final List<Account> list = JooqUtil.contextResult(identityDbConnProvider, context -> context
                        .selectFrom(ACCOUNT)
                        .where(ACCOUNT.PROCESSING_ACCOUNT.isFalse())
                        .orderBy(orderByUserIdField)
                        .fetch())
                .map(RECORD_TO_ACCOUNT_MAPPER::apply);
        return ResultPageFactory.createUnboundedList(list, AccountResultPage::new);
    }

    @Override
    public ResultPage<Account> search(final FindAccountRequest request) {
        final Condition notProcessingUser = ACCOUNT.PROCESSING_ACCOUNT.isFalse();
        final Condition filterConditions = NullSafe.getOrElseGet(
                expressionMapper,
                mapper -> mapper.apply(request.getExpression()),
                DSL::trueCondition);
        // Sort on user_id if no sort supplied
        final Collection<OrderField<?>> orderFields = JooqUtil.getOrderFields(FIELD_MAP, request, ACCOUNT.USER_ID);
        final int limit = JooqUtil.getLimit(request.getPageRequest(), true);
        final int offset = JooqUtil.getOffset(request.getPageRequest());

        return JooqUtil.contextResult(identityDbConnProvider, context -> {
            final List<Account> accounts = context
                    .select(ACCOUNT.asterisk(),
                            ACCOUNT_STATUS)
                    .from(ACCOUNT)
                    .where(notProcessingUser)
                    .and(filterConditions)
                    .orderBy(orderFields)
                    .offset(offset)
                    .limit(limit)
                    .fetch()
                    .map(RECORD_TO_ACCOUNT_MAPPER::apply);
            return ResultPage.createCriterialBasedList(accounts, request);
        });
    }

    @Override
    public Account create(final Account account, final String password) {
        final String passwordHash = hashPassword(password);
        final AccountRecord record = ACCOUNT_TO_RECORD_MAPPER.apply(
                account, ACCOUNT.newRecord());
        record.setPasswordHash(passwordHash);
        try {
            final AccountRecord accountRecord = JooqUtil.create(identityDbConnProvider, record);
            return RECORD_TO_ACCOUNT_MAPPER.apply(accountRecord);
        } catch (final RuntimeException e) {
            throw describeIfDuplicate(account, e);
        }
    }

    @Override
    public void recordSuccessfulLogin(final String userId) {
        // We reset the failed login count if we have a successful login
        JooqUtil.context(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.LOGIN_FAILURES, 0)
                .set(ACCOUNT.REACTIVATED_MS, (Long) null)
                .set(ACCOUNT.LOGIN_COUNT,
                        ACCOUNT.LOGIN_COUNT.plus(1))
                .set(ACCOUNT.LAST_LOGIN_MS, System.currentTimeMillis())
                .where(ACCOUNT.USER_ID.eq(userId))
                .execute());
    }

    @Override
    public void reactivateAccount(final String userId) {
        // Only the inactive flag is cleared. We deliberately leave LOCKED, ENABLED and the password
        // alone as reactivation is not a substitute for unlocking or re-enabling an account.
        // REACTIVATED_MS is set for the same reason that AccountServiceImpl.update sets it when an
        // administrator makes an account active, i.e. to stop the Account Maintenance job immediately
        // deactivating the account again in the window before the caller records a successful login
        // and LAST_LOGIN_MS takes over that job.
        final int count = JooqUtil.contextResult(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.INACTIVE, false)
                .set(ACCOUNT.REACTIVATED_MS, System.currentTimeMillis())
                .where(ACCOUNT.USER_ID.eq(userId))
                .execute());

        if (count == 0) {
            throw new NoSuchUserException("Cannot reactivate this account because this user does not exist!");
        }
    }

    @Override
    public CredentialValidationResult validateCredentials(final String userId, final String password) {
        if (Strings.isNullOrEmpty(userId)
            || Strings.isNullOrEmpty(password)) {
            return new CredentialValidationResult(
                    false, true, false, false, false, false);
        }

        // Is this is a login by the default local 'admin' account, then that should have already been created
        // by AdminAccountBootstrap
        final Optional<AccountRecord> optRecord = JooqUtil.contextResult(identityDbConnProvider, context -> context
                .selectFrom(ACCOUNT)
                .where(ACCOUNT.USER_ID.eq(userId))
                .fetchOptional());

        if (optRecord.isEmpty()) {
            LOGGER.debug("Request to log in with invalid user id: {}", userId);
            return new CredentialValidationResult(
                    false,
                    true,
                    false,
                    false,
                    false,
                    false);
        }

        final AccountRecord record = optRecord.get();
        final boolean isPasswordCorrect = PasswordHashUtil.checkPassword(password, record.getPasswordHash());
        final boolean isDisabled = !record.getEnabled();
        final boolean isInactive = record.getInactive();
        final boolean isLocked = record.getLocked();
        final boolean isProcessingAccount = record.getProcessingAccount();

        return new CredentialValidationResult(
                isPasswordCorrect, false, isLocked, isDisabled, isInactive, isProcessingAccount);
    }

    @Override
    public boolean incrementLoginFailures(final String userId) {
        boolean locked = false;

        final IdentityConfig identityConfig = identityConfigProvider.get();
        if (identityConfig.getFailedLoginLockThreshold() != null) {
            JooqUtil.context(identityDbConnProvider, context -> context
                    .update(ACCOUNT)
//                    .set(DSL.row(ACCOUNT.LOGIN_FAILURES, ACCOUNT.LOGIN_FAILURES),
//                            DSL.row(DSL.select(ACCOUNT.LOGIN_FAILURES.plus(1),
//                                    DSL.field(ACCOUNT.LOGIN_FAILURES.plus(2)
//                                    .ge(config.getFailedLoginLockThreshold())).fr).plus(1))
//                    .set(ACCOUNT.LOCKED,
//                            context.select(DSL.count())
//                                    .from(ACCOUNT)
//                                    .where(ACCOUNT.EMAIL.eq(email)
//                                    .and(ACCOUNT.LOGIN_FAILURES.plus(1).ge(config.getFailedLoginLockThreshold()))
//                          ))

                    .set(ACCOUNT.LOGIN_FAILURES,
                            ACCOUNT.LOGIN_FAILURES.plus(1))
                    .set(ACCOUNT.LOCKED,
                            DSL.field(ACCOUNT.LOGIN_FAILURES
                                    .ge(identityConfig.getFailedLoginLockThreshold())))
                    .where(ACCOUNT.USER_ID.eq(userId))
                    .execute());

            // Query the field back to find out if we locked.
            locked = JooqUtil.contextResult(identityDbConnProvider, context -> context
                            .select(ACCOUNT.LOCKED)
                            .from(ACCOUNT)
                            .where(ACCOUNT.USER_ID.eq(userId))
                            .fetchOptional())
                    .map(Record1::value1)
                    .orElse(false);
        } else {
            JooqUtil.context(identityDbConnProvider, context -> context
                    .update(ACCOUNT)
                    .set(ACCOUNT.LOGIN_FAILURES,
                            ACCOUNT.LOGIN_FAILURES.plus(1))
                    .where(ACCOUNT.USER_ID.eq(userId))
                    .execute());
        }

        if (locked) {
            LOGGER.debug("Account {} has had too many failed access attempts and is locked", userId);
        }

        return locked;
    }

    @Override
    public Optional<Account> get(final String userId) {
        return JooqUtil.contextResult(identityDbConnProvider, context -> context
                        .selectFrom(ACCOUNT)
                        .where(ACCOUNT.USER_ID.eq(userId))
                        .fetchOptional())
                .map(RECORD_TO_ACCOUNT_MAPPER);
    }

    @Override
    public Optional<Account> getByEmail(final String email) {
        if (Strings.isNullOrEmpty(email)) {
            return Optional.empty();
        }

        final List<Account> accounts = JooqUtil.contextResult(identityDbConnProvider, context -> context
                        .selectFrom(ACCOUNT)
                        .where(ACCOUNT.EMAIL.eq(email))
                        .fetch())
                .map(RECORD_TO_ACCOUNT_MAPPER::apply);

        if (accounts.size() > 1) {
            // A unique index makes this impossible, but refuse to guess rather than reset an arbitrary
            // one of them if that index is ever missing.
            LOGGER.error("Found {} accounts with the email address {}. Email addresses must be unique.",
                    accounts.size(), email);
            return Optional.empty();
        }

        return accounts.stream().findFirst();
    }


    @Override
    public void update(final Account account) {
        try {
            genericDao.update(account);
        } catch (final RuntimeException e) {
            throw describeIfDuplicate(account, e);
        }

//        AccountRecord usersRecord = JooqUtil.contextResult(authDbConnProvider, context -> context
//                .selectFrom(ACCOUNT)
//                .where(ACCOUNT.EMAIL.eq(account.getEmail())).fetchSingle());
//
//        UserMapper.mapToRecord(account, usersRecord);
//
//        JooqUtil.contextResult(authDbConnProvider, context -> context
//                .update(ACCOUNT)
//                .set(usersRecord)
//                .where(ACCOUNT.ID.eq(account.getId())}.execute());
    }

    @Override
    public void delete(final int id) {
        genericDao.delete(id);

//        JooqUtil.context(authDbConnProvider, context -> context
//                .deleteFrom(ACCOUNT)
//                .where(ACCOUNT.ID.eq(id)).execute());
    }

    @Override
    public Optional<Account> get(final int id) {
        return genericDao.fetch(id);

//        Optional<AccountRecord> userQuery = JooqUtil.contextResult(authDbConnProvider, context -> context
//                .selectFrom(ACCOUNT)
//                .where(ACCOUNT.ID.eq(id)).fetchOptional());
//
//        return userQuery.map(record -> {
//            final Account account = new Account();
//            UserMapper.mapFromRecord(record, account);
//            return account;
//        });
    }


    @Override
    public void changePassword(final String userId, final String newPassword) {
        final String newPasswordHash = PasswordHashUtil.hash(newPassword);

        final int count = JooqUtil.contextResult(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.PASSWORD_HASH, newPasswordHash)
                .set(ACCOUNT.PASSWORD_LAST_CHANGED_MS,
                        System.currentTimeMillis())
                .set(ACCOUNT.FORCE_PASSWORD_CHANGE, false)
                .where(ACCOUNT.USER_ID.eq(userId))
                .execute());

        if (count == 0) {
            throw new NoSuchUserException("Cannot change this password because this user does not exist!");
        }
    }

    @Override
    public void resetPassword(final String userId, final String newPassword) {
        final String newPasswordHash = PasswordHashUtil.hash(newPassword);

        final int count = JooqUtil.contextResult(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.PASSWORD_HASH, newPasswordHash)
                .set(ACCOUNT.PASSWORD_LAST_CHANGED_MS,
                        System.currentTimeMillis())
                .set(ACCOUNT.FORCE_PASSWORD_CHANGE, false)
                .set(ACCOUNT.LOCKED, false)
                .set(ACCOUNT.INACTIVE, false)
                .set(ACCOUNT.ENABLED, true)
                .set(ACCOUNT.LOGIN_FAILURES, 0)
                .where(ACCOUNT.USER_ID.eq(userId))
                .execute());

        if (count == 0) {
            throw new NoSuchUserException("Cannot reset this password because this user does not exist!");
        }
    }

    @Override
    public boolean unlockAndSetPassword(final String userId,
                                        final String newPassword,
                                        final String expectedResetTokenNonce) {
        if (Strings.isNullOrEmpty(expectedResetTokenNonce)) {
            return false;
        }

        final String newPasswordHash = PasswordHashUtil.hash(newPassword);

        // INACTIVE is deliberately not cleared here, unlike resetPassword. Proving control of the
        // account's email address is enough to clear a lock caused by failed logins, but an inactive
        // account may only be made active again by an actual successful authentication.
        //
        // Matching on the nonce as well as the user makes this the point at which a reset link is
        // consumed. Two requests using the same link cannot both succeed because the first clears the
        // nonce, so the second matches no rows.
        final int count = JooqUtil.contextResult(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.PASSWORD_HASH, newPasswordHash)
                .set(ACCOUNT.PASSWORD_LAST_CHANGED_MS,
                        System.currentTimeMillis())
                .set(ACCOUNT.FORCE_PASSWORD_CHANGE, false)
                .set(ACCOUNT.LOCKED, false)
                .set(ACCOUNT.LOGIN_FAILURES, 0)
                .set(ACCOUNT.RESET_TOKEN_NONCE, (String) null)
                .where(ACCOUNT.USER_ID.eq(userId))
                .and(ACCOUNT.RESET_TOKEN_NONCE.eq(expectedResetTokenNonce))
                .execute());

        return count > 0;
    }

    @Override
    public Optional<Long> getPasswordLastChangedMs(final String userId) {
        return JooqUtil.contextResult(identityDbConnProvider, context -> context
                        .select(ACCOUNT.PASSWORD_LAST_CHANGED_MS, ACCOUNT.CREATE_TIME_MS)
                        .from(ACCOUNT)
                        .where(ACCOUNT.USER_ID.eq(userId))
                        .fetchOptional())
                // A password that has never been changed has no changed time, so fall back to the
                // account create time in the same way that needsPasswordChange does.
                .map(record -> Objects.requireNonNullElse(
                        record.get(ACCOUNT.PASSWORD_LAST_CHANGED_MS),
                        record.get(ACCOUNT.CREATE_TIME_MS)));
    }

    @Override
    public boolean tryRecordResetEmailRequest(final String userId,
                                              final long requestTimeMs,
                                              final long earliestPreviousRequestMs) {
        // One conditional update rather than a read then a write, so that concurrent requests, including
        // ones on other nodes, cannot all decide they are allowed.
        final int count = JooqUtil.contextResult(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.RESET_EMAIL_REQUESTED_MS, requestTimeMs)
                .where(ACCOUNT.USER_ID.eq(userId))
                .and(ACCOUNT.RESET_EMAIL_REQUESTED_MS.isNull()
                        .or(ACCOUNT.RESET_EMAIL_REQUESTED_MS.le(earliestPreviousRequestMs)))
                .execute());

        return count > 0;
    }

    @Override
    public void setResetTokenNonce(final String userId, final String nonce) {
        final int count = JooqUtil.contextResult(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.RESET_TOKEN_NONCE, nonce)
                .where(ACCOUNT.USER_ID.eq(userId))
                .execute());

        if (count == 0) {
            throw new NoSuchUserException("Cannot issue a reset token because this user does not exist!");
        }
    }

    @Override
    public Optional<String> getResetTokenNonce(final String userId) {
        return JooqUtil.contextResult(identityDbConnProvider, context -> context
                .select(ACCOUNT.RESET_TOKEN_NONCE)
                .from(ACCOUNT)
                .where(ACCOUNT.USER_ID.eq(userId))
                .fetchOptional(ACCOUNT.RESET_TOKEN_NONCE));
    }

    @Override
    public Boolean needsPasswordChange(final String userId,
                                       final Duration mandatoryPasswordChangeDuration,
                                       final boolean forcePasswordChangeOnFirstLogin) {
        Objects.requireNonNull(userId, "userId must not be null");

        final AccountRecord user = JooqUtil.contextResult(identityDbConnProvider, context -> context
                .selectFrom(ACCOUNT)
                .where(ACCOUNT.USER_ID.eq(userId))
                .fetchOne());

        if (user == null) {
            throw new NoSuchUserException(
                    "Cannot check if this user needs a password change because this user does not exist!");
        }

        final long passwordLastChangedMs = user.getPasswordLastChangedMs() == null
                ? user.getCreateTimeMs()
                : user.getPasswordLastChangedMs();

        final Duration durationSinceLastPasswordChange = Duration.between(
                Instant.ofEpochMilli(passwordLastChangedMs),
                Instant.now());

        final boolean thresholdBreached = durationSinceLastPasswordChange
                                                  .compareTo(mandatoryPasswordChangeDuration) > 0;

        final boolean isFirstLogin = user.getPasswordLastChangedMs() == null;

        if (thresholdBreached
            || (forcePasswordChangeOnFirstLogin && isFirstLogin)
            || user.getForcePasswordChange()) {
            LOGGER.debug("User {} needs a password change.", userId);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public int deactivateNewInactiveUsers(final Duration neverUsedAccountDeactivationThreshold) {
        final long activityThreshold = Instant.now()
                .minus(neverUsedAccountDeactivationThreshold)
                .toEpochMilli();

        return JooqUtil.contextResult(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.INACTIVE, true)
                .where(ACCOUNT.CREATE_TIME_MS
                        .lessOrEqual(activityThreshold))
                // We are only going to deactivate active accounts
                .and(ACCOUNT.INACTIVE.isFalse())
                // A 'new' user is one who has never logged in.
                .and(ACCOUNT.LAST_LOGIN_MS.isNull())
                // We don't want to disable all accounts
                .and(ACCOUNT.NEVER_EXPIRES.isFalse())
                // We don't want to disable accounts that have been recently reactivated.
                .and(ACCOUNT.REACTIVATED_MS.isNull()
                        .or(ACCOUNT.REACTIVATED_MS
                                .lessThan(activityThreshold)))
                .execute());
    }

    @Override
    public int deactivateInactiveUsers(final Duration unusedAccountDeactivationThreshold) {
        final long activityThreshold = Instant.now()
                .minus(unusedAccountDeactivationThreshold)
                .toEpochMilli();

        return JooqUtil.contextResult(identityDbConnProvider, context -> context
                .update(ACCOUNT)
                .set(ACCOUNT.INACTIVE, true)
                .where(ACCOUNT.CREATE_TIME_MS
                        .lessOrEqual(activityThreshold))
                // We are only going to deactivate active accounts
                .and(ACCOUNT.INACTIVE.isFalse())
                // Choose users that have logged in but not for a while.
                .and(ACCOUNT.LAST_LOGIN_MS.isNotNull())
                .and(ACCOUNT.LAST_LOGIN_MS
                        .lessOrEqual(activityThreshold))
                // We don't want to disable all accounts
                .and(ACCOUNT.NEVER_EXPIRES.isFalse())
                // We don't want to disable accounts that have been recently reactivated.
                .and(ACCOUNT.REACTIVATED_MS.isNull()
                        .or(ACCOUNT.REACTIVATED_MS
                                .lessThan(activityThreshold)))
                .execute());
    }

    /**
     * Callers are expected to check for a clash before writing, but two of them can pass that check at
     * the same time and only one will get the row. The unique indexes are what actually decide, so turn
     * what they throw into the same message the caller would have given, rather than letting a raw
     * 'Duplicate entry ... for key ...' reach a user.
     * <p>
     * Which index was hit is worked out by looking, rather than by reading the database's message, which
     * is not ours to depend on.
     * </p>
     *
     * @return The exception to throw, which is the original if it was not a duplicate key.
     */
    private RuntimeException describeIfDuplicate(final Account account, final RuntimeException e) {
        if (!JooqUtil.isDuplicateKeyException(e)) {
            return e;
        }

        // An account being updated is allowed to keep its own user id and email address.
        if (!Strings.isNullOrEmpty(account.getEmail())
            && isUsedByAnotherAccount(getByEmail(account.getEmail()), account)) {
            return new RuntimeException(
                    "The email address '" + account.getEmail() + "' is already used by another account", e);
        }
        if (isUsedByAnotherAccount(get(account.getUserId()), account)) {
            return new RuntimeException(
                    "The user id '" + account.getUserId() + "' is already used by another account", e);
        }

        return e;
    }

    private boolean isUsedByAnotherAccount(final Optional<Account> optExisting, final Account account) {
        return optExisting
                .filter(existing -> !Objects.equals(existing.getId(), account.getId()))
                .isPresent();
    }

    private String hashPassword(final String password) {
        if (password == null) {
            return null;
        }
        return PasswordHashUtil.hash(password);
    }
}
