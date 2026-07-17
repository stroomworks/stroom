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

/**
 * The claims of a verified password reset token.
 *
 * @param userId              The account the token was issued for.
 * @param nonce               Identifies this token. Only the most recently issued token for an account
 *                            is accepted, so requesting another reset email stops this one working.
 * @param passwordLastChangedMs The account's password last changed time at the point the token was
 *                              issued. The token is only valid while this still matches the account, so
 *                              using it, or changing the password by any other means, stops it working.
 *                              Note that this invalidates on password change rather than on use, so
 *                              several tokens issued before any of them is used all stay valid until one
 *                              of them is.
 */
public record PasswordResetToken(String userId, String nonce, Long passwordLastChangedMs) {

}
