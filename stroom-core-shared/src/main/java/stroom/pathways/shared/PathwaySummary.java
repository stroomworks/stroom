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

package stroom.pathways.shared;

import stroom.pathways.shared.otel.trace.NanoTime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * One row of the pathway list: what a learnt pathway is called and when it was last touched, without
 * the model it holds.
 *
 * <p>A pathway keeps every path it has ever seen an operation take, so one can run to megabytes. The
 * list shows four fields, and reading the tree to render them is what made the screen unopenable —
 * so the read stops before it, and {@link #getSizeBytes()} says how much was not read.
 */
@JsonInclude(Include.NON_NULL)
public class PathwaySummary {

    @JsonProperty
    private final String name;
    @JsonProperty
    private final NanoTime createTime;
    @JsonProperty
    private final NanoTime updateTime;
    @JsonProperty
    private final NanoTime lastUsedTime;
    @JsonProperty
    private final long sizeBytes;

    @JsonCreator
    public PathwaySummary(@JsonProperty("name") final String name,
                          @JsonProperty("createTime") final NanoTime createTime,
                          @JsonProperty("updateTime") final NanoTime updateTime,
                          @JsonProperty("lastUsedTime") final NanoTime lastUsedTime,
                          @JsonProperty("sizeBytes") final long sizeBytes) {
        this.name = name;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.lastUsedTime = lastUsedTime;
        this.sizeBytes = sizeBytes;
    }

    public String getName() {
        return name;
    }

    public NanoTime getCreateTime() {
        return createTime;
    }

    public NanoTime getUpdateTime() {
        return updateTime;
    }

    public NanoTime getLastUsedTime() {
        return lastUsedTime;
    }

    /**
     * How large this pathway is where it is stored, which is also roughly what fetching it costs.
     */
    public long getSizeBytes() {
        return sizeBytes;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PathwaySummary that = (PathwaySummary) o;
        return sizeBytes == that.sizeBytes
               && Objects.equals(name, that.name)
               && Objects.equals(createTime, that.createTime)
               && Objects.equals(updateTime, that.updateTime)
               && Objects.equals(lastUsedTime, that.lastUsedTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, createTime, updateTime, lastUsedTime, sizeBytes);
    }

    @Override
    public String toString() {
        return "PathwaySummary{name='" + name + "', sizeBytes=" + sizeBytes + '}';
    }
}
