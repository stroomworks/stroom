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

package stroom.pathways.shared.pathway;

import stroom.pathways.shared.otel.trace.NanoTime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One route through the model, with how many traces took it.
 *
 * <p>The visits are gathered by node, nodes in the order the walk first reached them and each node's
 * steps in ascending order. A node reached fifteen times taking the same steps each time appears
 * once, because how much work there was to do is the workload rather than the path through the code,
 * and a node that ran two ways appears twice however its runs were timed. Two traces that visited the
 * same nodes taking the same steps took the same route and share a row.
 *
 * <p>The id of the trace that created the row is kept so the screen can open one that took the
 * route. It stays put as the count rises rather than moving to the newest, so the row points at a
 * trace someone can have looked at — at the cost of being the one most likely to have aged out of
 * the trace store.
 */
@JsonInclude(Include.NON_NULL)
public class RouteUse {

    @JsonProperty
    private final List<RouteVisit> visits;
    @JsonProperty
    private final long timesUsed;
    @JsonProperty
    private final NanoTime firstUsedTime;
    @JsonProperty
    private final NanoTime lastUsedTime;
    @JsonProperty
    private final String createdByTraceId;

    @JsonCreator
    public RouteUse(@JsonProperty("visits") final List<RouteVisit> visits,
                    @JsonProperty("timesUsed") final long timesUsed,
                    @JsonProperty("firstUsedTime") final NanoTime firstUsedTime,
                    @JsonProperty("lastUsedTime") final NanoTime lastUsedTime,
                    @JsonProperty("createdByTraceId") final String createdByTraceId) {
        this.visits = visits == null
                ? Collections.emptyList()
                : new ArrayList<>(visits);
        this.timesUsed = timesUsed;
        this.firstUsedTime = firstUsedTime;
        this.lastUsedTime = lastUsedTime;
        this.createdByTraceId = createdByTraceId;
    }

    public List<RouteVisit> getVisits() {
        return visits;
    }

    public long getTimesUsed() {
        return timesUsed;
    }

    public NanoTime getFirstUsedTime() {
        return firstUsedTime;
    }

    public NanoTime getLastUsedTime() {
        return lastUsedTime;
    }

    public String getCreatedByTraceId() {
        return createdByTraceId;
    }

    /**
     * The same route taken again. The creating trace id and first used time keep the values they were
     * given, so the row still points at a trace that has been checked rather than the newest one.
     */
    public RouteUse used(final NanoTime time) {
        return new RouteUse(visits, timesUsed + 1, firstUsedTime, time, createdByTraceId);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RouteUse that = (RouteUse) o;
        return timesUsed == that.timesUsed
               && Objects.equals(visits, that.visits)
               && Objects.equals(firstUsedTime, that.firstUsedTime)
               && Objects.equals(lastUsedTime, that.lastUsedTime)
               && Objects.equals(createdByTraceId, that.createdByTraceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(visits, timesUsed, firstUsedTime, lastUsedTime, createdByTraceId);
    }

    @Override
    public String toString() {
        return "RouteUse{" + visits.size() + " visits x" + timesUsed + '}';
    }
}
