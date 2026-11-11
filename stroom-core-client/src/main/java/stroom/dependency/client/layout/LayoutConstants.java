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

/// Geometry for the Structure view's layout.
///
/// These are the only hand-chosen numbers in the layout. Everything else is derived from the graph.
/// Ported from the reference harness (`docs/references/folder-data-flow/stroom-structure-layout-harness.html`).
public final class LayoutConstants {

    /// Width of a node on the flow row.
    public static final int W = 296;
    /// Height of a node on the flow row.
    public static final int H = 74;
    /// Width of a referenced document.
    public static final int RW = 210;
    /// Height of a referenced document.
    public static final int RH = 60;
    /// Width of an endpoint outside Stroom.
    public static final int EPW = 236;
    /// Height of an endpoint outside Stroom.
    public static final int EPH = 54;
    /// Width of a pipeline element chip, inside an expanded pipeline.
    public static final int EW = 252;
    /// Height of a pipeline element chip, inside an expanded pipeline.
    public static final int EH = 38;

    /// Horizontal gap between flow columns.
    public static final int COL_GAP = 124;
    /// Vertical gap between the endpoint row and the row above it.
    public static final int EP_GAP = 52;
    /// Vertical gap between lanes.
    public static final int LANE_GAP = 70;
    /// Margin around the whole graph.
    public static final int PAD = 60;

    /// Distance from one flow column to the next.
    public static final int COL_STEP = W + COL_GAP;
    /// Distance from one referenced document to the next along a reference row.
    public static final int REF_STEP = RW + 22;
    /// Distance from one endpoint to the next along an endpoint row.
    public static final int EP_STEP = EPW + 24;

    /// Vertical gap between two flow nodes stacked in the same lane and column.
    public static final int STACK_GAP = 14;
    /// Vertical gap between the flow row and the reference row.
    public static final int REF_ROW_GAP = 40;
    /// How far a lane's background extends above its first row.
    public static final int LANE_BOX_TOP = 26;
    /// How far a lane's background extends below its last row.
    public static final int LANE_BOX_BOTTOM = 22;

    /// Height of an expanded pipeline's header, above its element chips.
    public static final int EXPANDED_HEADER = 44;
    /// Vertical gap between element chips in an expanded pipeline.
    public static final int EXPANDED_CHIP_GAP = 7;
    /// Height below the last element chip of an expanded pipeline.
    public static final int EXPANDED_FOOTER = 26;
    /// How much wider an expanded pipeline is than its element chips.
    public static final int EXPANDED_MARGIN = 26;

    private LayoutConstants() {
        // Constants only.
    }
}
