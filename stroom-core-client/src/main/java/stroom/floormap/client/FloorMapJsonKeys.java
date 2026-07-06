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

package stroom.floormap.client;

/**
 * JSON field-name constants for the temporal-store entry value schema
 * used across the floor map feature.
 */
public final class FloorMapJsonKeys {

    public static final String TYPE = "type";
    public static final String NAME = "name";

    /**
     * Object ID and type identifier for the background layer/object
     * in the floor map canvas.
     */
    public static final String BACKGROUND = "background";

    /**
     * Display name for the background object in the fact list UI.
     */
    public static final String BACKGROUND_DISPLAY_NAME = "Background";

    /**
     * ID prefix applied to SVG {@code <g>} wrapper elements in the canvas.
     *
     * <p>Each map object is rendered inside a {@code <g>} whose ID is
     * {@code SVG_GROUP_PREFIX + objectKey}. The click-detection logic uses
     * this prefix to distinguish wrapper groups (ignored) from the actual
     * clickable shape elements (whose IDs are the raw object keys).</p>
     *
     * <p>The prefix uses a double-underscore convention ({@code "__g_"}) to
     * avoid collisions with user-chosen object keys — users are unlikely to
     * name objects starting with {@code "__"}.</p>
     */
    public static final String SVG_GROUP_PREFIX = "__g_";

    private FloorMapJsonKeys() {
        // Utility class
    }
}
