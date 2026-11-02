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

import java.util.Objects;

/**
 * One business rule (§8.4): an XPath that must evaluate true for a record, named so that a failure
 * reads as a sentence in the feedback to the model.
 */
@JsonPropertyOrder({"name", "xpath"})
@JsonInclude(Include.NON_NULL)
public class XPathAssertion {

    @JsonProperty
    private final String name;
    @JsonProperty
    private final String xpath;

    @JsonCreator
    public XPathAssertion(@JsonProperty("name") final String name,
                          @JsonProperty("xpath") final String xpath) {
        this.name = name;
        this.xpath = xpath;
    }

    public String getName() {
        return name;
    }

    public String getXpath() {
        return xpath;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final XPathAssertion that = (XPathAssertion) o;
        return Objects.equals(name, that.name) && Objects.equals(xpath, that.xpath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, xpath);
    }

    @Override
    public String toString() {
        return name + ": " + xpath;
    }
}
