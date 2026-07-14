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

public interface PathwayEvent {

    String getNodeUuid();

    String getNodeName();

    NanoTime getTimestamp();

    PathwayEventType getEventType();

    /**
     * A stable, machine-facing category for this event type (e.g. {@code "CONSTRAINT_MUTATION"}),
     * used in the recall API/UI. Deliberately independent of the implementing class name so
     * renaming a class cannot silently change the API.
     */
    String getCategory();

    String getDescription();
}
