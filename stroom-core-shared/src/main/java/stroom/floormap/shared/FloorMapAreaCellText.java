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

/**
 * The wording used in the tracking panel's <strong>Area</strong> column.
 *
 * <p>That column has a single meaning on every row — <em>which area is this
 * inside?</em> — so there is one form of words, and it lives here where it can be
 * unit-tested without a GWT presenter.</p>
 *
 * <p>Holds no GWT or DOM types so it can be unit-tested on the JVM.</p>
 */
public final class FloorMapAreaCellText {

    /** Separator between area names. */
    private static final String NAME_SEPARATOR = ", ";

    private FloorMapAreaCellText() {
        // Utility class.
    }

    /**
     * Every containing area named, in the order given (innermost first, so the
     * most specific area reads first).
     *
     * <p>Applies to any row — an entity or a nested area — because the column
     * treats them identically.</p>
     *
     * <p>The grid cell is a single {@code nowrap} line that ellipsises when the
     * column is too narrow, so the full list is safe to emit here — the caller
     * repeats it in the cell's tooltip for when it is clipped.</p>
     *
     * @param names the resolved area display names, in display order; may be
     *              {@code null} or empty
     * @return the comma-separated names, or {@code ""} when there are none
     */
    public static String joinNames(final List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        final StringBuilder joined = new StringBuilder();
        for (final String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(NAME_SEPARATOR);
            }
            joined.append(name);
        }
        return joined.toString();
    }
}
