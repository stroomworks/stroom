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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Business rules (§8.4): the assertions every record must satisfy, and whether the messages a transform
 * raises itself with {@code xsl:message} count against it too.
 */
@JsonPropertyOrder({"assertions", "includeTransformMessages"})
@JsonInclude(Include.NON_NULL)
public final class BusinessRulesParameters extends ScorerParameters {

    @JsonProperty
    private final List<XPathAssertion> assertions;
    /**
     * A transform that raises {@code xsl:message} at warning or above about a record is reporting a
     * rule of its own; by default that counts as a failed assertion for the record.
     */
    @JsonProperty
    private final boolean includeTransformMessages;

    @JsonCreator
    public BusinessRulesParameters(
            @JsonProperty("assertions") final List<XPathAssertion> assertions,
            @JsonProperty("includeTransformMessages") final Boolean includeTransformMessages) {
        this.assertions = assertions == null
                ? Collections.emptyList()
                : List.copyOf(assertions);
        this.includeTransformMessages = Objects.requireNonNullElse(includeTransformMessages, true);
    }

    @Override
    public ScorerType scorerType() {
        return ScorerType.BUSINESS_RULES;
    }

    public List<XPathAssertion> getAssertions() {
        return assertions;
    }

    public boolean isIncludeTransformMessages() {
        return includeTransformMessages;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BusinessRulesParameters that = (BusinessRulesParameters) o;
        return includeTransformMessages == that.includeTransformMessages
               && Objects.equals(assertions, that.assertions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assertions, includeTransformMessages);
    }

    @Override
    public String toString() {
        return assertions.size() + " assertion(s)" + (includeTransformMessages
                ? " + transform messages"
                : "");
    }
}
