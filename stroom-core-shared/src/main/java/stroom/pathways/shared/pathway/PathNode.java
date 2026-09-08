/*
 * Copyright 2025 Crown Copyright
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

import stroom.util.shared.AbstractBuilder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(Include.NON_NULL)
public class PathNode {

    @JsonProperty
    private final String uuid;
    @JsonProperty
    private final String name;
    @JsonProperty
    private final List<String> path;
    @JsonProperty
    private final List<PathNodeSequence> targets;
    @JsonProperty
    private final Map<String, Constraint> constraints;
    @JsonProperty
    private final PathwayLocks locks;
    @JsonProperty
    private final PathwayLocks childLockDefaults;

    @JsonCreator
    public PathNode(@JsonProperty("uuid") final String uuid,
                    @JsonProperty("name") final String name,
                    @JsonProperty("path") final List<String> path,
                    @JsonProperty("targets") final List<PathNodeSequence> targets,
                    @JsonProperty("constraints") final Map<String, Constraint> constraints,
                    @JsonProperty("locks") final PathwayLocks locks,
                    @JsonProperty("childLockDefaults") final PathwayLocks childLockDefaults) {
        this.uuid = uuid;
        this.name = name;
        this.path = path;
        this.targets = targets == null
                ? new ArrayList<>()
                : new ArrayList<>(targets);
        this.constraints = constraints;
        this.locks = locks != null
                ? locks
                : PathwayLocks.builder().build();
        this.childLockDefaults = childLockDefaults != null
                ? childLockDefaults
                : PathwayLocks.builder().build();
    }

    public PathNode(final String name,
                    final List<String> path) {
        this(UUID.randomUUID().toString(), name, path, null, null, null, null);
    }

    public PathNode(final String name) {
        this(name, Collections.singletonList(name));
    }

    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public List<String> getPath() {
        return path;
    }

    public List<PathNodeSequence> getTargets() {
        return targets;
    }

    public Map<String, Constraint> getConstraints() {
        return constraints;
    }

    public PathwayLocks getLocks() {
        return locks;
    }

    public PathwayLocks getChildLockDefaults() {
        return childLockDefaults;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PathNode node = (PathNode) o;
        return Objects.equals(name, node.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (final String part : path) {
            for (int i = 0; i < depth * 3; i++) {
                sb.append(' ');
            }
            sb.append(part);
            sb.append("\n");
            depth++;
        }
        return sb.toString();
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends AbstractBuilder<PathNode, Builder> {

        private String uuid;
        private String name;
        private List<String> path;
        private List<PathNodeSequence> targets;
        private Map<String, Constraint> constraints;
        private PathwayLocks locks;
        private PathwayLocks childLockDefaults;

        public Builder() {
        }

        public Builder(final PathNode pathNode) {
            this.uuid = pathNode.uuid;
            this.name = pathNode.name;
            this.path = pathNode.path;
            this.targets = pathNode.targets;
            this.constraints = pathNode.constraints;
            this.locks = pathNode.locks;
            this.childLockDefaults = pathNode.childLockDefaults;
        }

        public Builder uuid(final String uuid) {
            this.uuid = uuid;
            return self();
        }

        public Builder name(final String name) {
            this.name = name;
            return self();
        }

        public Builder path(final List<String> path) {
            this.path = path;
            return self();
        }

        public Builder targets(final List<PathNodeSequence> targets) {
            this.targets = targets;
            return self();
        }

        public Builder constraints(final Map<String, Constraint> constraints) {
            this.constraints = constraints;
            return self();
        }

        public Builder locks(final PathwayLocks locks) {
            this.locks = locks;
            return self();
        }

        public Builder childLockDefaults(final PathwayLocks childLockDefaults) {
            this.childLockDefaults = childLockDefaults;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        public PathNode build() {
            return new PathNode(
                    uuid,
                    name,
                    path,
                    targets,
                    constraints,
                    locks,
                    childLockDefaults);
        }
    }
}
