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

package stroom.floormap.client.event;

import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.event.shared.HasHandlers;

/**
 * Event fired when a map object is dragged to a new position on the floor map canvas.
 * Carries the object identifier and the new map-space coordinates.
 */
public class MapObjectMovedEvent extends GwtEvent<MapObjectMovedEvent.Handler> {

    private static Type<MapObjectMovedEvent.Handler> TYPE;
    private final String objectId;
    private final double x;
    private final double y;

    public MapObjectMovedEvent(final String objectId, final double x, final double y) {
        this.objectId = objectId;
        this.x = x;
        this.y = y;
    }

    public static void fire(final HasHandlers handlers, final String objectId, final double x, final double y) {
        handlers.fireEvent(new MapObjectMovedEvent(objectId, x, y));
    }

    public static Type<MapObjectMovedEvent.Handler> getType() {
        if (TYPE == null) {
            TYPE = new Type<>();
        }
        return TYPE;
    }

    @Override
    public final Type<MapObjectMovedEvent.Handler> getAssociatedType() {
        return getType();
    }

    @Override
    public void dispatch(final MapObjectMovedEvent.Handler handler) {
        handler.onMove(this);
    }

    /**
     * @return the identifier of the object that was moved
     */
    public String getObjectId() {
        return objectId;
    }

    /**
     * @return the new X coordinate in map space
     */
    public double getX() {
        return x;
    }

    /**
     * @return the new Y coordinate in map space
     */
    public double getY() {
        return y;
    }

    // --------------------------------------------------------------------------------

    /**
     * Handler for {@link MapObjectMovedEvent}.
     */
    public interface Handler extends EventHandler {

        void onMove(MapObjectMovedEvent event);
    }

}
