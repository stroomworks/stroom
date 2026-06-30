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

package stroom.pathways.impl.events;

import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Constraint;
import stroom.planb.impl.db.trace.NanoTimeUtil;

public class ConstraintDiscoveryEvent implements PathwayEvent {
    private final String nodeUuid;
    private final String nodeName;
    private final Constraint constraint;
    private final PathwayEventType eventType;
    private final NanoTime timestamp;

    public ConstraintDiscoveryEvent(final String nodeUuid,
                                    final String nodeName,
                                    final Constraint constraint,
                                    final PathwayEventType eventType) {
        this.nodeUuid = nodeUuid;
        this.nodeName = nodeName;
        this.constraint = constraint;
        this.eventType = eventType;
        this.timestamp = NanoTimeUtil.now();
    }

    public ConstraintDiscoveryEvent(final String nodeUuid,
                                    final String nodeName,
                                    final Constraint constraint,
                                    final PathwayEventType eventType,
                                    final NanoTime timestamp) {
        this.nodeUuid = nodeUuid;
        this.nodeName = nodeName;
        this.constraint = constraint;
        this.eventType = eventType;
        this.timestamp = timestamp;
    }
    public Constraint getConstraint() {
        return constraint;
    }

    @Override
    public String getNodeUuid() {
        return nodeUuid;
    }

    @Override
    public String getNodeName() {
        return nodeName;
    }

    @Override
    public NanoTime getTimestamp() {
        return timestamp;
    }

    @Override
    public PathwayEventType getEventType() {
        return eventType;
    }

    @Override
    public String getDescription() {
        final String descriptionTail = "constraint "
                                       + constraint.getName()
                                       + " for node "
                                       + nodeName
                                       + " with value "
                                       + constraint.getValue();
        if(eventType == PathwayEventType.VIOLATION) {
            return "VIOLATION: Attempted to discover " + descriptionTail;
        }
        return "MUTATION: Discovered " + descriptionTail;
    }
}
