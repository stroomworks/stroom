/*
 * Copyright 2026 Crown Copyright
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

package stroom.pathways.impl;

import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@JsonPropertyOrder(alphabetic = true)
public class PathwaysConfig extends AbstractConfig implements IsStroomConfig {

    private final List<String> ignoredAttributes;
    private final List<String> ignoredSpanNames;

    public PathwaysConfig() {
        this(Collections.emptyList(), Collections.emptyList());
    }

    @JsonCreator
    public PathwaysConfig(@JsonProperty("ignoredAttributes") final List<String> ignoredAttributes,
                          @JsonProperty("ignoredSpanNames") final List<String> ignoredSpanNames) {
        this.ignoredAttributes = ignoredAttributes == null
                ? Collections.emptyList()
                : ignoredAttributes;
        this.ignoredSpanNames = ignoredSpanNames == null
                ? Collections.emptyList()
                : ignoredSpanNames;
    }

    @JsonProperty("ignoredAttributes")
    @JsonPropertyDescription("Span attributes the model should not learn a value for, as a list of " +
                             "names where '*' stands for any run of characters, e.g. 'thread.*'. A " +
                             "matching attribute is recorded once as accepting anything and is never " +
                             "changed again, so it stays visible against the node without the model " +
                             "growing every time its value differs. Matching is case sensitive and " +
                             "covers the whole name.")
    public List<String> getIgnoredAttributes() {
        return ignoredAttributes;
    }

    @JsonProperty("ignoredSpanNames")
    @JsonPropertyDescription("Spans that are not part of the route, as a list of names where '*' " +
                             "stands for any run of characters, e.g. 'Ping'. For work the runtime " +
                             "does when it feels like it rather than when the code says to, such as " +
                             "a connection pool checking a connection it has not used for a while: " +
                             "whether it happens is decided by how long something sat idle, so " +
                             "recording it doubles the routes for every place it can appear. A " +
                             "matching span is still a node of the model and a trace holding one " +
                             "still matches the pathway; only the route leaves it out, along with " +
                             "whatever it ran. Matching is case sensitive and covers the whole name.")
    public List<String> getIgnoredSpanNames() {
        return ignoredSpanNames;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PathwaysConfig that = (PathwaysConfig) o;
        return Objects.equals(ignoredAttributes, that.ignoredAttributes)
               && Objects.equals(ignoredSpanNames, that.ignoredSpanNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ignoredAttributes, ignoredSpanNames);
    }

    @Override
    public String toString() {
        return "PathwaysConfig{ignoredAttributes=" + ignoredAttributes
               + ", ignoredSpanNames=" + ignoredSpanNames + "}";
    }
}
