package stroom.security.shared;

import stroom.util.shared.NullSafe;
import stroom.util.shared.UserRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

@JsonInclude(Include.NON_NULL)
public class AppUserPermissions implements HasUserRef {

    @JsonProperty
    private final UserRef userRef;
    @JsonProperty
    private final Set<AppPermission> permissions;
    @JsonProperty
    private final Set<AppPermission> inherited;

    public AppUserPermissions(final UserRef userRef,
                              final Set<AppPermission> permissions) {
        this(userRef, permissions, Collections.emptySet());
    }

    @JsonCreator
    public AppUserPermissions(@JsonProperty("userRef") final UserRef userRef,
                              @JsonProperty("permissions") final Set<AppPermission> permissions,
                              @JsonProperty("inherited") final Set<AppPermission> inherited) {
        this.userRef = userRef;
        // Hold them as EnumSet for better perf and mem use, who knows what GWT does with them
        this.permissions = NullSafe.unmodifialbeEnumSet(AppPermission.class, permissions);
        this.inherited = NullSafe.unmodifialbeEnumSet(AppPermission.class, inherited);
    }

    public UserRef getUserRef() {
        return userRef;
    }

    public Set<AppPermission> getPermissions() {
        return permissions;
    }

    public Set<AppPermission> getInherited() {
        return inherited;
    }

//    public void setInherited(final Set<AppPermission> inherited) {
//        this.inherited = inherited;
//    }

    @JsonIgnore
    public boolean isUserEnabled() {
        return NullSafe.isTrue(userRef, UserRef::isEnabled);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final AppUserPermissions that = (AppUserPermissions) o;
        return Objects.equals(userRef, that.userRef);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userRef);
    }
}
