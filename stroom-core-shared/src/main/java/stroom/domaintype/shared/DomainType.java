/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.domaintype.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Represents a Domain Type within Stroom.
 * <p>
 * A Domain Type is of the form class.attributeType. Wildcards are permitted, thus
 * *.ipaddress or Host.*.
 * </p>
 */
@JsonInclude(Include.NON_NULL)
public class DomainType {

    @JsonProperty
    private final String classPart;

    @JsonProperty
    private final String attributePart;

    /**
     * Allows a string to be parsed into classPart and attributePart.
     * @param fullType Overall String holding type.
     */
    public DomainType(final String fullType) {
        Objects.requireNonNull(fullType);
        final String[] parts = fullType.split("\\.", 2);
        if (parts.length == 0) {
            classPart = "";
            attributePart = fullType;
        } else if (parts.length == 1) {
            classPart = "";
            attributePart = parts[0];
        } else if (parts.length == 2) {
            classPart = parts[0];
            attributePart = parts[1];
        } else {
            throw new IllegalArgumentException("Cannot parse domain type: " + fullType);
        }
    }

    @JsonCreator
    public DomainType(@JsonProperty("classPart") final String classPart,
                      @JsonProperty("attributePart") final String attributePart) {
        Objects.requireNonNull(classPart);
        Objects.requireNonNull(attributePart);
        this.classPart = classPart;
        this.attributePart = attributePart;
    }

    @Override
    public String toString() {
        return classPart + "." + attributePart;
    }

    public String getClassPart() {
        return classPart;
    }

    /**
     * @return Never returns null.
     */
    public String getAttributePart() {
        return attributePart;
    }

    public boolean canAccept(final DomainType other) {
        return matches(classPart, other.classPart) && matches(attributePart, other.attributePart);
    }

    private boolean matches(final String pattern, final String value) {
        return pattern.equals("*") || pattern.equals(value);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DomainType that = (DomainType) o;
        return Objects.equals(classPart, that.classPart) && Objects.equals(attributePart,
                that.attributePart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classPart, attributePart);
    }

}
