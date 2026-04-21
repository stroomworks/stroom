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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDomainType {

    @Test
    void testFullTypeParsing() {
        check("Class.Attribute", "Class", "Attribute");
        check("Class.*", "Class", "*");
        check("*.Attribute", "*", "Attribute");
        check("Attribute", "", "Attribute");
        check(".Attribute", "", "Attribute");
        check("Class.", "Class", "");
        check("Part1.Part2.Part3", "Part1", "Part2.Part3");
        check("", "", "");
    }

    @Test
    void testConstructor() {
        final DomainType domainType = new DomainType("Class", "Attribute");
        assertThat(domainType.getClassPart()).isEqualTo("Class");
        assertThat(domainType.getAttributePart()).isEqualTo("Attribute");
    }

    @Test
    void testNulls() {
        assertThatThrownBy(() -> new DomainType(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DomainType(null, "attr"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DomainType("class", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testToString() {
        assertThat(new DomainType("Class", "Attribute").toString()).isEqualTo("Class.Attribute");
        assertThat(new DomainType("Attribute").toString()).isEqualTo(".Attribute");
        assertThat(new DomainType("Class", "").toString()).isEqualTo("Class.");
        assertThat(new DomainType("One.Two").toString()).isEqualTo("One.Two");
        assertThat(new DomainType("Two").toString()).isEqualTo(".Two");
        assertThat(new DomainType("One.").toString()).isEqualTo("One.");
    }

    @Test
    void testEqualsAndHashCode() {
        final DomainType domainType1 = new DomainType("Class1", "Attr1");
        final DomainType domainType1Duplicate = new DomainType("Class1", "Attr1");
        final DomainType domainType2 = new DomainType("Class2", "Attr2");
        final DomainType domainType1DifferentAttr = new DomainType("Class1", "Attr2");
        final DomainType domainType1DifferentClass = new DomainType("Class2", "Attr1");

        assertThat(domainType1).isEqualTo(domainType1);
        assertThat(domainType1).isEqualTo(domainType1Duplicate);
        assertThat(domainType1.hashCode()).isEqualTo(domainType1Duplicate.hashCode());

        assertThat(domainType1).isNotEqualTo(domainType2);
        assertThat(domainType1).isNotEqualTo(domainType1DifferentAttr);
        assertThat(domainType1).isNotEqualTo(domainType1DifferentClass);
        assertThat(domainType1).isNotEqualTo(null);
        assertThat(domainType1).isNotEqualTo("some string");
    }

    @Test
    void testCanAccept() {
        final DomainType dt = new DomainType("Class", "Attr");

        // Exact match
        assertThat(dt.canAccept(new DomainType("Class", "Attr"))).isTrue();

        // Wildcard class
        assertThat(new DomainType("*", "Attr").canAccept(dt)).isTrue();
        // matches is not symmetric for wildcards
        assertThat(dt.canAccept(new DomainType("*", "Attr"))).isFalse();

        // Wildcard attr
        assertThat(new DomainType("Class", "*").canAccept(dt)).isTrue();
        assertThat(dt.canAccept(new DomainType("Class", "*"))).isFalse();

        // No match
        assertThat(dt.canAccept(new DomainType("Other", "Attr"))).isFalse();
        assertThat(dt.canAccept(new DomainType("Class", "Other"))).isFalse();
        assertThat(new DomainType("*", "Other").canAccept(dt)).isFalse();
        assertThat(new DomainType("Other", "*").canAccept(dt)).isFalse();
    }

    private void check(final String fullType, final String expectedClass, final String expectedAttribute) {
        final DomainType domainType = new DomainType(fullType);
        assertThat(domainType.getClassPart())
                .as("Class part for " + fullType)
                .isEqualTo(expectedClass);
        assertThat(domainType.getAttributePart())
                .as("Attribute part for " + fullType)
                .isEqualTo(expectedAttribute);
    }
}
