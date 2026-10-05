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

import java.util.Objects;

/**
 * One path through the model, with how many traces took it.
 *
 * <p>What the trace did is one shape, {@code root}, holding the whole walk: every node reached, the
 * children run at each, and the order they ran in, with work that repeated said once. How many times
 * a repeat ran is not part of it, because how much work there was to do is the workload rather than
 * the path through the code. Two traces whose shapes are the same took the same path and share a
 * row.
 *
 * <p>The id of the trace that created the row is kept so the screen can open one that took the
 * path. It stays put as the count rises rather than moving to the newest, so the row points at a
 * trace someone can have looked at — at the cost of being the one most likely to have aged out of
 * the trace store.
 */
@JsonInclude(Include.NON_NULL)
public class PathUse {

    @JsonProperty
    private final int root;
    @JsonProperty
    private final long timesUsed;
    @JsonProperty
    private final NanoTime firstUsedTime;
    @JsonProperty
    private final NanoTime lastUsedTime;
    @JsonProperty
    private final String createdByTraceId;

    @JsonCreator
    public PathUse(@JsonProperty("root") final int root,
                    @JsonProperty("timesUsed") final long timesUsed,
                    @JsonProperty("firstUsedTime") final NanoTime firstUsedTime,
                    @JsonProperty("lastUsedTime") final NanoTime lastUsedTime,
                    @JsonProperty("createdByTraceId") final String createdByTraceId) {
        this.root = root;
        this.timesUsed = timesUsed;
        this.firstUsedTime = firstUsedTime;
        this.lastUsedTime = lastUsedTime;
        this.createdByTraceId = createdByTraceId;
    }

    /**
     * Where the walk this path took begins, as a position in the shape list on {@link Paths}.
     */
    public int getRoot() {
        return root;
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
     * The same path taken again. The creating trace id and first used time keep the values they were
     * given, so the row still points at a trace that has been checked rather than the newest one.
     */
    public PathUse used(final NanoTime time) {
        return new PathUse(root, timesUsed + 1, firstUsedTime, time, createdByTraceId);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PathUse that = (PathUse) o;
        return timesUsed == that.timesUsed
               && root == that.root
               && Objects.equals(firstUsedTime, that.firstUsedTime)
               && Objects.equals(lastUsedTime, that.lastUsedTime)
               && Objects.equals(createdByTraceId, that.createdByTraceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, timesUsed, firstUsedTime, lastUsedTime, createdByTraceId);
    }

    @Override
    public String toString() {
        return "PathUse{shape " + root + " x" + timesUsed + '}';
    }
}
