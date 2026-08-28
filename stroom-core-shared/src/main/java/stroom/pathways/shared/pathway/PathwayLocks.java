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

package stroom.pathways.shared.pathway;

import stroom.pathways.shared.PathwaysDoc;
import stroom.util.shared.AbstractBuilder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Stores the state of different lockable properties at a single level in the pathway hierarchy.
 * <p>
 * The same object is attached to a {@link Constraint} (only {@link #getValue()} and
 * {@link #getOptional()} are meaningful there), a {@link PathNode}, a {@link Pathway} and the
 * {@link PathwaysDoc}. It is used both for an element's own explicit locks and, on pathways/docs, for
 * the child-default locks stamped onto newly-discovered nodes and constraints.
 * <p>
 * {@link LockState#INHERIT} is the "unset" state. In memory a property is always a real,
 * non-null {@link LockState}; a {@code null} supplied at construction (e.g. absent from JSON)
 * defaults to {@link LockState#INHERIT}, mirroring how {@link PathwaysDoc} defaults its nullable
 * settings via {@link Objects#requireNonNullElse}. Representing INHERIT compactly (as an
 * absent/sentinel value) is left to the storage layer.
 */
@JsonInclude(Include.NON_NULL)
public class PathwayLocks {

    @JsonProperty
    private final LockState value;
    @JsonProperty
    private final LockState optional;
    @JsonProperty
    private final LockState nodeDiscovery;
    @JsonProperty
    private final LockState constraintDiscovery;

    @JsonCreator
    public PathwayLocks(@JsonProperty("value") final LockState value,
                        @JsonProperty("optional") final LockState optional,
                        @JsonProperty("nodeDiscovery") final LockState nodeDiscovery,
                        @JsonProperty("constraintDiscovery") final LockState constraintDiscovery) {
        // A null (e.g. absent from JSON) defaults to INHERIT.
        this.value = Objects.requireNonNullElse(value, LockState.INHERIT);
        this.optional = Objects.requireNonNullElse(optional, LockState.INHERIT);
        this.nodeDiscovery = Objects.requireNonNullElse(nodeDiscovery, LockState.INHERIT);
        this.constraintDiscovery = Objects.requireNonNullElse(constraintDiscovery, LockState.INHERIT);
    }

    /**
     * @return Whether a constraint's widened value may change.
     */
    public LockState getValue() {
        return value;
    }

    /**
     * @return Whether a constraint's required/optional state may change.
     */
    public LockState getOptional() {
        return optional;
    }

    /**
     * @return Whether a node may gain new child nodes.
     */
    public LockState getNodeDiscovery() {
        return nodeDiscovery;
    }

    /**
     * @return Whether a node may gain new constraints.
     */
    public LockState getConstraintDiscovery() {
        return constraintDiscovery;
    }

    /**
     * Resolves this (more specific) set against a less specific one: for each property this value
     * is kept unless it is {@link LockState#INHERIT}, in which case the fallback's value is used.
     * Used to walk the lock hierarchy from most specific (constraint) to least specific (doc).
     */
    public PathwayLocks withFallback(final PathwayLocks fallback) {
        if (fallback == null || fallback.isAllInherit()) {
            return this;
        }
        return new PathwayLocks(
                resolve(value, fallback.value),
                resolve(optional, fallback.optional),
                resolve(nodeDiscovery, fallback.nodeDiscovery),
                resolve(constraintDiscovery, fallback.constraintDiscovery));
    }

    private static LockState resolve(final LockState specific, final LockState fallback) {
        return specific != LockState.INHERIT
                ? specific
                : fallback;
    }

    /**
     * @return {@code true} if every property is {@link LockState#INHERIT}, i.e. nothing is set.
     */
    @JsonIgnore
    public boolean isAllInherit() {
        return value == LockState.INHERIT
               && optional == LockState.INHERIT
               && nodeDiscovery == LockState.INHERIT
               && constraintDiscovery == LockState.INHERIT;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PathwayLocks that = (PathwayLocks) o;
        return value == that.value &&
               optional == that.optional &&
               nodeDiscovery == that.nodeDiscovery &&
               constraintDiscovery == that.constraintDiscovery;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, optional, nodeDiscovery, constraintDiscovery);
    }

    @Override
    public String toString() {
        return "PathwayLocks{" +
               "value=" + getValue() +
               ", optional=" + getOptional() +
               ", nodeDiscovery=" + getNodeDiscovery() +
               ", constraintDiscovery=" + getConstraintDiscovery() +
               '}';
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<PathwayLocks, Builder> {

        private LockState value;
        private LockState optional;
        private LockState nodeDiscovery;
        private LockState constraintDiscovery;

        private Builder() {
        }

        private Builder(final PathwayLocks pathwayLocks) {
            this.value = pathwayLocks.value;
            this.optional = pathwayLocks.optional;
            this.nodeDiscovery = pathwayLocks.nodeDiscovery;
            this.constraintDiscovery = pathwayLocks.constraintDiscovery;
        }

        public Builder value(final LockState value) {
            this.value = value;
            return self();
        }

        public Builder optional(final LockState optional) {
            this.optional = optional;
            return self();
        }

        public Builder nodeDiscovery(final LockState nodeDiscovery) {
            this.nodeDiscovery = nodeDiscovery;
            return self();
        }

        public Builder constraintDiscovery(final LockState constraintDiscovery) {
            this.constraintDiscovery = constraintDiscovery;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public PathwayLocks build() {
            return new PathwayLocks(
                    value,
                    optional,
                    nodeDiscovery,
                    constraintDiscovery);
        }
    }
}
