package stroom.security.common.impl;

import stroom.security.api.HasJwt;
import stroom.security.api.UserIdentity;
import stroom.util.authentication.HasRefreshable;
import stroom.util.shared.NullSafe;

import org.jose4j.jwt.JwtClaims;

import java.util.Objects;

/**
 * User identity for a service user for this application to authenticate with other
 * applications on the same IDP realm. I.e. Stroom's processing user.
 * This user uses the client credentials flow.
 */
public class ServiceUserIdentity implements UserIdentity, HasJwtClaims, HasJwt, HasRefreshable {

    private final String id;
    private final String displayName;
    private final UpdatableToken updatableToken;

    public ServiceUserIdentity(final String id,
                               final String displayName,
                               final UpdatableToken updatableToken) {
        this.id = Objects.requireNonNull(id);
        // The IDP may or may not provide a nice name for the service user so provide a hard coded fallback.
        // There should be only one service user so this is ok.
        this.displayName = Objects.requireNonNullElse(displayName, "Internal Processing User");
        this.updatableToken = Objects.requireNonNull(updatableToken);
    }

    @Override
    public String subjectId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public UpdatableToken getRefreshable() {
        return updatableToken;
    }

    @Override
    public String getJwt() {
        return NullSafe.get(updatableToken, UpdatableToken::getJwt);
    }

    @Override
    public JwtClaims getJwtClaims() {
        return NullSafe.get(updatableToken, UpdatableToken::getJwtClaims);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ServiceUserIdentity that = (ServiceUserIdentity) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ServiceUserIdentity{" +
               "id='" + id + '\'' +
               ", updatableToken=" + updatableToken +
               '}';
    }

}
