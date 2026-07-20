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

package stroom.floormap.client.view;

import stroom.floormap.client.presenter.FloorMapEditorPresenter;
import stroom.floormap.client.presenter.FloorMapEditorPresenter.FloorMapEditorView;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.ThinSplitLayoutPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View implementation for the FloorMap Editor tab.
 *
 * <h3>Layout</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │                  Map Canvas  (MAIN)                  │  fills upper area
 * ├──────────────────────────────────────────────────────┤
 * │              Timeline control (TIMELINE)             │  fixed ~60 px
 * ├─────────────────────────┬────────────────────────────┤  ◄─ draggable
 * │    Fact List            │       Time List            │  ~1/3 total height
 * │   (FACT_LIST)           │      (TIME_LIST)           │
 * └─────────────────────────┴────────────────────────────┘
 *           ~50% width      ▲         ~50% width
 *                           └── draggable
 * </pre>
 *
 * Uses three nested {@link ThinSplitLayoutPanel}s:
 * <ul>
 *   <li><b>Outer (vertical)</b> — top area vs bottom strip (draggable).</li>
 *   <li><b>Top-inner (vertical)</b> — canvas (fill) above timeline (fixed south, no splitter).</li>
 *   <li><b>Bottom-inner (horizontal)</b> — three equal columns in the bottom strip (draggable).</li>
 * </ul>
 */
public class FloorMapEditorViewImpl extends ViewImpl implements FloorMapEditorView {

    // -----------------------------------------------------------------------
    // Outer (vertical) split — top area vs bottom strip
    // -----------------------------------------------------------------------

    /** Initial height of the bottom strip in pixels. */
    private static final int BOTTOM_STRIP_INITIAL_HEIGHT = 250;

    /**
     * Proportional height of the bottom strip — 1/3 of total, leaving 2/3
     * for the canvas + timeline area above.
     */
    private static final double BOTTOM_STRIP_SPLIT = 1.0 / 3.0;

    // -----------------------------------------------------------------------
    // Top-inner (vertical) split — canvas above timeline
    // -----------------------------------------------------------------------

    /**
     * Fixed height of the timeline strip in pixels. The timeline is a compact
     * bar (date pickers, scrubber, play button, speed selector) and does not
     * need to be user-resizable — it is anchored to the south with no split
     * ratio, so it keeps this fixed height when the window is resized.
     */
    private static final int TIMELINE_HEIGHT = 110;

    // -----------------------------------------------------------------------
    // Bottom-inner (horizontal) split — three equal columns
    // -----------------------------------------------------------------------

    /** Initial width of each anchored (West) column in pixels. */
    private static final int BOTTOM_COLUMN_INITIAL_WIDTH = 300;

    /** Initial width of the right-hand Layers panel in pixels. */
    private static final int LAYERS_PANEL_INITIAL_WIDTH = 260;

    // -----------------------------------------------------------------------

    /** Root split: Layers panel (East) beside the main editor area (centre). */
    private final ThinSplitLayoutPanel rootSplitPanel;
    private final ThinSplitLayoutPanel outerSplitPanel;

    private final SimplePanel canvasPanel;
    private final SimplePanel timelinePanel;

    private final SimplePanel factListPanel;
    private final SimplePanel timeListPanel;

    private final SimplePanel layersPanel;

    @Inject
    public FloorMapEditorViewImpl() {

        // ---- Top area: canvas (fill) above timeline (fixed south) -----------
        canvasPanel = new SimplePanel();
        canvasPanel.addStyleName("dashboard-panel overflow-hidden");

        timelinePanel = new SimplePanel();
        timelinePanel.addStyleName("dashboard-panel overflow-hidden stroom-border-bottom");

        // ---- Bottom strip: three horizontal columns -------------------------
        factListPanel = new SimplePanel();
        factListPanel.addStyleName("dashboard-panel overflow-hidden");

        timeListPanel = new SimplePanel();
        timeListPanel.addStyleName("dashboard-panel overflow-hidden");

        // Bottom strip: two equal columns
        final ThinSplitLayoutPanel bottomSplitPanel = new ThinSplitLayoutPanel();
        bottomSplitPanel.setSize("100%", "100%");
        bottomSplitPanel.setHSplits(0.5);
        bottomSplitPanel.addWest(factListPanel, BOTTOM_COLUMN_INITIAL_WIDTH);   // Fact List
        bottomSplitPanel.add(timeListPanel);                                     // Time List (fills rest)

        // Combine timeline and bottom
        final DockLayoutPanel bottomPanel = new DockLayoutPanel(Unit.PX);
        bottomPanel.addNorth(timelinePanel, TIMELINE_HEIGHT);
        bottomPanel.add(bottomSplitPanel);

        // ---- Outer vertical split: top area vs bottom strip -----------------
        outerSplitPanel = new ThinSplitLayoutPanel();
        outerSplitPanel.setSize("100%", "100%");
        outerSplitPanel.setVSplits(BOTTOM_STRIP_SPLIT);
        outerSplitPanel.addSouth(bottomPanel, BOTTOM_STRIP_INITIAL_HEIGHT); // bottom strip
        outerSplitPanel.add(canvasPanel);                                       // canvas + timeline (centre)

        // ---- Root horizontal split: Layers panel anchored on the East -------
        layersPanel = new SimplePanel();
        layersPanel.addStyleName("dashboard-panel overflow-hidden");

        rootSplitPanel = new ThinSplitLayoutPanel();
        rootSplitPanel.setSize("100%", "100%");
        rootSplitPanel.addEast(layersPanel, LAYERS_PANEL_INITIAL_WIDTH);   // Layers panel (RHS)
        rootSplitPanel.add(outerSplitPanel);                                    // main editor area
    }

    @Override
    public Widget asWidget() {
        return rootSplitPanel;
    }

    /**
     * Routes GWTP slot content into the correct panel:
     * <ul>
     *   <li>{@link FloorMapEditorPresenter#MAIN}       → canvas panel (top of top area)</li>
     *   <li>{@link FloorMapEditorPresenter#TIMELINE}   → timeline strip (bottom of top area, fixed height)</li>
     *   <li>{@link FloorMapEditorPresenter#FACT_LIST}  → bottom-left column</li>
     *   <li>{@link FloorMapEditorPresenter#TIME_LIST}  → bottom-right column (fills remaining space)</li>
     * </ul>
     * Properties are shown as a modal dialog and have no slot.
     */
    @Override
    public void setInSlot(final Object slot, final Widget content) {
        if (FloorMapEditorPresenter.MAIN.equals(slot)) {
            canvasPanel.setWidget(content);
        } else if (FloorMapEditorPresenter.TIMELINE.equals(slot)) {
            timelinePanel.setWidget(content);
        } else if (FloorMapEditorPresenter.FACT_LIST.equals(slot)) {
            factListPanel.setWidget(content);
        } else if (FloorMapEditorPresenter.TIME_LIST.equals(slot)) {
            timeListPanel.setWidget(content);
        } else if (FloorMapEditorPresenter.LAYERS.equals(slot)) {
            layersPanel.setWidget(content);
        }
    }
}
