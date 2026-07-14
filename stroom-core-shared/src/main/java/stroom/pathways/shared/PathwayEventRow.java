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

package stroom.pathways.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * A GWT-safe, flattened transport view of a single pathway event for recall over REST.
 * The server-side {@code stroom.pathways.impl.events.PathwayEvent} hierarchy is not GWT-safe,
 * so this row carries only the display-ready fields.
 */
@JsonInclude(Include.NON_NULL)
public class PathwayEventRow {

    @JsonProperty
    private final String pathwayName;
    @JsonProperty
    private final String category;
    @JsonProperty
    private final String eventType;
    @JsonProperty
    private final String nodeName;
    @JsonProperty
    private final Long timeMs;
    @JsonProperty
    private final String traceId;
    @JsonProperty
    private final String description;

    @JsonCreator
    public PathwayEventRow(@JsonProperty("pathwayName") final String pathwayName,
                           @JsonProperty("category") final String category,
                           @JsonProperty("eventType") final String eventType,
                           @JsonProperty("nodeName") final String nodeName,
                           @JsonProperty("timeMs") final Long timeMs,
                           @JsonProperty("traceId") final String traceId,
                           @JsonProperty("description") final String description) {
        this.pathwayName = pathwayName;
        this.category = category;
        this.eventType = eventType;
        this.nodeName = nodeName;
        this.timeMs = timeMs;
        this.traceId = traceId;
        this.description = description;
    }

    public String getPathwayName() {
        return pathwayName;
    }

    public String getCategory() {
        return category;
    }

    public String getEventType() {
        return eventType;
    }

    public String getNodeName() {
        return nodeName;
    }

    public Long getTimeMs() {
        return timeMs;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PathwayEventRow that = (PathwayEventRow) o;
        return Objects.equals(pathwayName, that.pathwayName) &&
               Objects.equals(category, that.category) &&
               Objects.equals(eventType, that.eventType) &&
               Objects.equals(nodeName, that.nodeName) &&
               Objects.equals(timeMs, that.timeMs) &&
               Objects.equals(traceId, that.traceId) &&
               Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathwayName, category, eventType, nodeName, timeMs, traceId, description);
    }

    @Override
    public String toString() {
        return "PathwayEventRow{" +
               "pathwayName='" + pathwayName + '\'' +
               ", category='" + category + '\'' +
               ", eventType='" + eventType + '\'' +
               ", nodeName='" + nodeName + '\'' +
               ", timeMs=" + timeMs +
               ", traceId='" + traceId + '\'' +
               ", description='" + description + '\'' +
               '}';
    }
}
