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

package stroom.learningflow.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/// What the Setup tab needs to know about a Learning Flow document's target schema, read from its XSD on the
/// server because the client cannot read one. It says whether the schema is event-logging, which matters
/// because only event-logging has the marked Unknown events the fallback step writes, and which elements the
/// root may hold, since every child of the root is read as one record.
@JsonInclude(Include.NON_NULL)
public class TargetSchemaSummary {

    @JsonProperty
    private final String name;
    @JsonProperty
    private final boolean eventLogging;
    @JsonProperty
    private final String rootElement;
    @JsonProperty
    private final List<String> rootChildren;

    /// @param name         The schema's name.
    /// @param eventLogging Whether it is event-logging.
    /// @param rootElement  The element a document of it starts with, or null where the XSD declares none.
    /// @param rootChildren The elements that root may hold, in the order the XSD gives them.
    @JsonCreator
    public TargetSchemaSummary(@JsonProperty("name") final String name,
                               @JsonProperty("eventLogging") final Boolean eventLogging,
                               @JsonProperty("rootElement") final String rootElement,
                               @JsonProperty("rootChildren") final List<String> rootChildren) {
        this.name = name;
        this.eventLogging = Objects.requireNonNullElse(eventLogging, false);
        this.rootElement = rootElement;
        this.rootChildren = rootChildren == null
                ? List.of()
                : List.copyOf(rootChildren);
    }

    /// @return The schema's name.
    public String getName() {
        return name;
    }

    /// @return Whether it is event-logging.
    public boolean isEventLogging() {
        return eventLogging;
    }

    /// @return The element a document of it starts with, or null where the XSD declares none.
    public String getRootElement() {
        return rootElement;
    }

    /// @return The elements its root may hold, in the order the XSD gives them.
    public List<String> getRootChildren() {
        return rootChildren;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TargetSchemaSummary that = (TargetSchemaSummary) o;
        return eventLogging == that.eventLogging
               && Objects.equals(name, that.name)
               && Objects.equals(rootElement, that.rootElement)
               && Objects.equals(rootChildren, that.rootChildren);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, eventLogging, rootElement, rootChildren);
    }

    @Override
    public String toString() {
        return "TargetSchemaSummary{name=" + name + ", eventLogging=" + eventLogging + ", rootElement="
               + rootElement + ", rootChildren=" + rootChildren + '}';
    }
}
