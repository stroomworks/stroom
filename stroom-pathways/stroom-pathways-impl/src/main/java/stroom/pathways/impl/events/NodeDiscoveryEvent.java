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

public class NodeDiscoveryEvent implements PathwayEvent {
    private final String parentUuid;
    private final String nodeUuid;
    private final String nodeName;
    private final PathwayEventType eventType;
    private final NanoTime timestamp;

    public NodeDiscoveryEvent(final String parentUuid,
                              final String nodeUuid,
                              final String nodeName,
                              final PathwayEventType eventType) {
        this.parentUuid = parentUuid;
        this.nodeUuid = nodeUuid;
        this.nodeName = nodeName;
        this.eventType = eventType;
        this.timestamp = NanoTimeUtil.now();
    }

    public NodeDiscoveryEvent(final String parentUuid,
                              final String nodeUuid,
                              final String nodeName,
                              final PathwayEventType eventType,
                              final NanoTime timestamp) {
        this.parentUuid = parentUuid;
        this.nodeUuid = nodeUuid;
        this.nodeName = nodeName;
        this.eventType = eventType;
        this.timestamp = timestamp;
    }

    public String getParentUuid() {
        return parentUuid;
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
    public PathwayEventType getEventType()
    {
        return eventType;
    }

    @Override
    public String getDescription() {
        final String descriptionTail = "new path node: " + nodeName;
        if(eventType == PathwayEventType.VIOLATION) {
            return "VIOLATION: Attempted to add " + descriptionTail;
        }
        return "MUTATION: Added " + descriptionTail;
    }
}
