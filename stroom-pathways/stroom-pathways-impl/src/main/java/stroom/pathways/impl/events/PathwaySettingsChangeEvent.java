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

/**
 * Placeholder for an event describing a change to a pathways doc's settings.
 *
 * <p>TODO: Implement later post-locks.
 */
public class PathwaySettingsChangeEvent implements PathwayEvent {

    private final PathwayEventType eventType;
    private final NanoTime timestamp;

    public PathwaySettingsChangeEvent(final PathwayEventType eventType) {
        this.eventType = eventType;
        this.timestamp = NanoTimeUtil.now();
    }

    @Override
    public String getNodeUuid() {
        return null;
    }

    @Override
    public String getNodeName() {
        return null;
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
    public String getCategory() {
        return "SETTINGS_CHANGE";
    }

    @Override
    public String getDescription() {
        // TODO: describe the settings change once the payload is defined.
        return "Pathway settings changed";
    }
}
