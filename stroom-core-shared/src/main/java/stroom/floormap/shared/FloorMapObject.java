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

package stroom.floormap.shared;

import java.util.List;

public class FloorMapObject {
    private final String id;
    private final String type;

    private double x;
    private double y;

    /**
     * Optional movement trail for animated person objects during playback.
     * Each entry is a {@code double[3]} of {@code [mapX, mapY, alpha]}, where
     * {@code alpha} is in [0.0, 1.0] and represents how opaque that trail segment
     * should be (1.0 = fully visible, 0.0 = invisible).
     * <p>
     * This field is purely a client-side decoration and is never serialised to
     * the server.  It is {@code null} for all non-person or non-animated objects.
     */
    private List<double[]> trail;

    public FloorMapObject(final String id,
                          final String type,
                          final double x,
                          final double y) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public void setX(final double x) {
        this.x = x;
    }

    public void setY(final double y) {
        this.y = y;
    }

    /**
     * Returns the movement trail for this object, or {@code null} if no trail is present.
     * Each element is {@code [mapX, mapY, alpha]} where alpha ∈ [0, 1].
     */
    public List<double[]> getTrail() {
        return trail;
    }

    /**
     * Attaches a movement trail to this object.  Only set by the client-side animation
     * system; never read or written on the server side.
     *
     * @param trail List of {@code [mapX, mapY, alpha]} triples, oldest first.
     */
    public void setTrail(final List<double[]> trail) {
        this.trail = trail;
    }
}
