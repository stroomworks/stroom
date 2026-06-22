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
 * ├────────────────────┬────────────────┬────────────────┤  ◄─ draggable
 * │    Fact List       │   Time List    │   Properties   │  ~1/3 total height
 * │   (FACT_LIST)      │  (TIME_LIST)   │  (PROPERTIES)  │
 * └────────────────────┴────────────────┴────────────────┘
 *       ~1/3 width   ▲    ~1/3 width   ▲    ~1/3 width
 *                    └── draggable ────┘
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
    private static final int TIMELINE_HEIGHT = 138;

    // -----------------------------------------------------------------------
    // Bottom-inner (horizontal) split — three equal columns
    // -----------------------------------------------------------------------

    /** Initial width of each anchored (West) column in pixels. */
    private static final int BOTTOM_COLUMN_INITIAL_WIDTH = 300;

    /**
     * Proportional width of each West column — 1/3 of the bottom strip width.
     * Two values are supplied (one per {@code addWest} call); the centre widget
     * fills the remainder automatically.
     */
    private static final double BOTTOM_COLUMN_SPLIT = 1.0 / 3.0;

    // -----------------------------------------------------------------------

    private final ThinSplitLayoutPanel outerSplitPanel;

    private final SimplePanel canvasPanel;
    private final SimplePanel timelinePanel;

    private final SimplePanel factListPanel;
    private final SimplePanel timeListPanel;
    private final SimplePanel propertiesPanel;

    @Inject
    public FloorMapEditorViewImpl() {

        // ---- Top area: canvas (fill) above timeline (fixed south) -----------
        canvasPanel = new SimplePanel();
        canvasPanel.addStyleName("dashboard-panel overflow-hidden");

        timelinePanel = new SimplePanel();
        timelinePanel.addStyleName("dashboard-panel overflow-hidden");

        // Top area: canvas + timeline
        final ThinSplitLayoutPanel topSplitPanel = new ThinSplitLayoutPanel();
        topSplitPanel.setSize("100%", "100%");
        // No setVSplits — timeline keeps its fixed pixel height on resize.
        topSplitPanel.addSouth(timelinePanel, TIMELINE_HEIGHT);  // timeline (fixed)
        topSplitPanel.add(canvasPanel);                          // canvas (fills remaining)

        // ---- Bottom strip: three horizontal columns -------------------------
        factListPanel = new SimplePanel();
        factListPanel.addStyleName("dashboard-panel overflow-hidden");

        timeListPanel = new SimplePanel();
        timeListPanel.addStyleName("dashboard-panel overflow-hidden");

        propertiesPanel = new SimplePanel();
        propertiesPanel.addStyleName("dashboard-panel overflow-hidden");

        // Bottom strip: three columns
        final ThinSplitLayoutPanel bottomSplitPanel = new ThinSplitLayoutPanel();
        bottomSplitPanel.setSize("100%", "100%");
        // Each West column gets 1/3 of total width; the centre fills the rest.
        bottomSplitPanel.setHSplits(BOTTOM_COLUMN_SPLIT, BOTTOM_COLUMN_SPLIT);
        bottomSplitPanel.addWest(factListPanel, BOTTOM_COLUMN_INITIAL_WIDTH);   // Fact List
        bottomSplitPanel.addWest(timeListPanel, BOTTOM_COLUMN_INITIAL_WIDTH);   // Time List
        bottomSplitPanel.add(propertiesPanel);                                   // Properties (centre)

        // ---- Outer vertical split: top area vs bottom strip -----------------
        outerSplitPanel = new ThinSplitLayoutPanel();
        outerSplitPanel.setSize("100%", "100%");
        outerSplitPanel.setVSplits(BOTTOM_STRIP_SPLIT);
        outerSplitPanel.addSouth(bottomSplitPanel, BOTTOM_STRIP_INITIAL_HEIGHT); // bottom strip
        outerSplitPanel.add(topSplitPanel);                                       // canvas + timeline (centre)
    }

    @Override
    public Widget asWidget() {
        return outerSplitPanel;
    }

    /**
     * Routes GWTP slot content into the correct panel:
     * <ul>
     *   <li>{@link FloorMapEditorPresenter#MAIN}       → canvas panel (top of top area)</li>
     *   <li>{@link FloorMapEditorPresenter#TIMELINE}   → timeline strip (bottom of top area, fixed height)</li>
     *   <li>{@link FloorMapEditorPresenter#FACT_LIST}  → bottom-left column</li>
     *   <li>{@link FloorMapEditorPresenter#TIME_LIST}  → bottom-centre column</li>
     *   <li>{@link FloorMapEditorPresenter#PROPERTIES} → bottom-right column</li>
     * </ul>
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
        } else if (FloorMapEditorPresenter.PROPERTIES.equals(slot)) {
            propertiesPanel.setWidget(content);
        }
    }
}
