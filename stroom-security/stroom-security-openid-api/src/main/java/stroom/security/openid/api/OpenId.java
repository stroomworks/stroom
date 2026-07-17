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

package stroom.security.openid.api;

import java.util.List;

public class OpenId {

    public static final String AUTH_USER = "authuser";
    public static final String CLIENT_ID = "client_id";
    public static final String CLIENT_SECRET = "client_secret";
    public static final String CODE = "code";
    public static final String KEY_ID = "kid";
    public static final String GRANT_TYPE = "grant_type";
    public static final String NONCE = "nonce";
    public static final String LOGIN_PROMPT = "login";

    public static final String POST_LOGOUT_REDIRECT_URI = "post_logout_redirect_uri";
    public static final String PROMPT = "prompt";
    public static final String REDIRECT_URI = "redirect_uri";
    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String RESPONSE_TYPE = "response_type";
    public static final String SCOPE = "scope";
    public static final String STATE = "state";

    public static final String GRANT_TYPE__AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_TYPE__CLIENT_CREDENTIALS = "client_credentials";
    public static final String GRANT_TYPE__REFRESH_TOKEN = "refresh_token";
    public static final String RESPONSE_TYPE__CODE = "code";
    public static final String SCOPE__OPENID = "openid";
    public static final String SCOPE__EMAIL = "email";

    public static final List<String> DEFAULT_REQUEST_SCOPES = List.of(
            OpenId.SCOPE__OPENID,
            OpenId.SCOPE__EMAIL);

    public static final List<String> DEFAULT_CLIENT_CREDENTIALS_SCOPES = List.of(
            OpenId.SCOPE__OPENID);

    /**
     * A claim holding a set of audience values.
     */
    public static final String CLAIM__AUDIENCE = "aud";
    /**
     * Subject - Identifier for the End-User at the Issuer.
     * <p>
     * NOTE: Not all IDPs use this, e.g. Azure AD use 'oid', so refer to {@link OpenIdConfiguration} for
     * which claims to use.
     * </p>
     */
    public static final String CLAIM__SUBJECT = "sub";
    /**
     * End-User's preferred e-mail address.
     */
    public static final String CLAIM__EMAIL = "email";
    /**
     * Shorthand name by which the End-User wishes to be referred to at the RP, such as janedoe or j.doe.
     * This value MAY be any valid JSON string including special characters such as @, /,
     * or whitespace. The RP MUST NOT rely upon this value being unique
     * <p>
     * NOTE: Not all IDPs use this, e.g. Azure AD use 'upn', so refer to {@link OpenIdConfiguration} for
     * which claims to use.
     * </p>
     */
    public static final String CLAIM__PREFERRED_USERNAME = "preferred_username";

    /**
     * End-User's full name in displayable form including all name parts, possibly including titles and suffixes,
     * ordered according to the End-User's locale and preferences.
     */
    public static final String CLAIM__NAME = "name";

    /**
     * A stroom specific claim recording what a token was issued to be used for. A token with no such
     * claim is a normal token that may be used to authenticate API requests. A token with this claim
     * may only be used for the stated purpose and must be rejected everywhere else.
     * <p>
     * The name is deliberately prefixed to reduce the chance of colliding with a claim of the same name
     * issued by someone else. Tokens from an external IDP are verified by StandardJwtContextFactory
     * rather than InternalJwtContextFactory, so they are not subject to the check that reads this.
     * </p>
     */
    public static final String CLAIM__STROOM_PURPOSE = "stroom_purpose";

    /**
     * The value of {@link OpenId#CLAIM__STROOM_PURPOSE} on the short lived token that is emailed to a
     * user so that they can reset a forgotten password. Such a token grants nothing except the ability
     * to set a new password for its subject.
     */
    public static final String STROOM_PURPOSE__PASSWORD_RESET = "password_reset";

    /**
     * A stroom specific claim binding a password reset token to the password it was issued against, by
     * recording the account's password last changed time. Once the password changes the claim no longer
     * matches the account, which makes the token single use.
     */
    public static final String CLAIM__STROOM_PASSWORD_LAST_CHANGED_MS = "stroom_password_last_changed_ms";

    /**
     * A stroom specific claim identifying which password reset token this is. The account records the
     * nonce of the most recently issued one, so issuing a new reset token stops any earlier one working.
     */
    public static final String CLAIM__STROOM_RESET_TOKEN_NONCE = "stroom_reset_token_nonce";

    public static final String ID_TOKEN = "id_token";
}
