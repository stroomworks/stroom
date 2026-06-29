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

    public static final String COORDS = "coords";
    public static final String TYPE = "type";
    public static final String NAME = "name";
    public static final String IMG = "img";
    public static final String TM_WORLD_TO_MAP = "tm-world-to-map";
    public static final String TM_MAP_TO_SCREEN = "tm-map-to-screen";

    private FloorMapJsonKeys() {
        // Utility class
    }
}
