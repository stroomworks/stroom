/*
 * Copyright 2026 Crown Copyright
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

package stroom.dependency.client.layout;

/// How much of the graph is drawn, which the zoom decides unless a pin overrides it.
///
/// Collapsing belongs to the layout rather than the renderer, because replacing several nodes with one
/// changes a lane's height and therefore where every lane below it goes.
public enum DetailLevel {

    /// Everything is drawn.
    EVERYTHING(0),

    /// Each lane's reference row is folded into one summary.
    REFERENCES_COLLAPSED(1),

    /// Each lane is folded into one summary.
    LANES_COLLAPSED(2);

    // Below these zoom levels the graph shows less. Tuned against the harness's rendering (decision 5);
    // revisit with real folders. There is deliberately no hysteresis.
    private static final double LANES_COLLAPSED_BELOW = 0.42;
    private static final double REFERENCES_COLLAPSED_BELOW = 0.62;

    private final int level;

    DetailLevel(final int level) {
        this.level = level;
    }

    /// @param zoom The canvas zoom, where 1 is actual size.
    /// @return the detail level to lay the graph out at, for that zoom.
    public static DetailLevel forZoom(final double zoom) {
        if (zoom < LANES_COLLAPSED_BELOW) {
            return LANES_COLLAPSED;
        } else if (zoom < REFERENCES_COLLAPSED_BELOW) {
            return REFERENCES_COLLAPSED;
        } else {
            return EVERYTHING;
        }
    }

    /// @param level 0, 1 or 2, as the reference harness numbers them.
    /// @return the matching detail level.
    /// @throws IllegalArgumentException if `level` is not 0, 1 or 2.
    public static DetailLevel fromLevel(final int level) {
        for (final DetailLevel detailLevel : values()) {
            if (detailLevel.level == level) {
                return detailLevel;
            }
        }
        throw new IllegalArgumentException("No detail level " + level);
    }

    /// @return 0, 1 or 2: the higher, the less is drawn.
    public int getLevel() {
        return level;
    }
}
