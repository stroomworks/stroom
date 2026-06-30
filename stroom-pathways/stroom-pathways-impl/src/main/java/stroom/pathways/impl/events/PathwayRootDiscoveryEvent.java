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
import stroom.planb.impl.db.trace.NanoTimeUtil;

import java.time.Instant;

public class PathwayRootDiscoveryEvent implements PathwayEvent {
    private final String nodeUuid;
    private final String nodeName;
    private final PathwayEventType eventType;
    private final NanoTime timestamp;

    public PathwayRootDiscoveryEvent(final String nodeUuid, final String nodeName, final PathwayEventType eventType) {
        this.nodeUuid = nodeUuid;
        this.nodeName = nodeName;
        this.eventType = eventType;
        this.timestamp = NanoTimeUtil.now();
    }

    public PathwayRootDiscoveryEvent(final String nodeUuid,
                                     final String nodeName,
                                     final PathwayEventType eventType,
                                     final NanoTime timestamp) {
        this.nodeUuid = nodeUuid;
        this.nodeName = nodeName;
        this.eventType = eventType;
        this.timestamp = timestamp;
    }

    @Override
    public String getNodeName() {
        return nodeName;
    }

    @Override
    public String getNodeUuid() {
        return nodeUuid;
    }

    @Override
    public PathwayEventType getEventType() {
        return eventType;
    }

    @Override
    public NanoTime getTimestamp() {
        return timestamp;
    }

    @Override
    public String getDescription() {
        final String descriptionTail = "a new pathway root: " + nodeName;
        if(eventType == PathwayEventType.VIOLATION) {
            return "VIOLATION: Attempted to discover " + descriptionTail;
        }
        return "MUTATION: Discovered " + descriptionTail;
    }

    @Override
    public String toString() {
        return "PathwayRootDiscoveryEvent{ "
               + "pathwayName='" + nodeName + "'"
               + ", eventType=" + eventType.name()
               + " }";
    }
}
