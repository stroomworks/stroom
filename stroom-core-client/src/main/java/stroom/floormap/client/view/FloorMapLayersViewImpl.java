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

import stroom.floormap.client.presenter.FloorMapLayersPresenter.FloorMapLayersView;

import com.google.gwt.dom.client.Element;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View implementation for the Layers panel — a scrolling list of layer rows.
 * The panel needs no title of its own (the dock tab already reads "Layers").
 */
public class FloorMapLayersViewImpl extends ViewImpl implements FloorMapLayersView {

    private final FlowPanel root;
    private final ScrollPanel scroll;
    private final Label statusRegion;

    private String lastAnnouncement;

    @Inject
    public FloorMapLayersViewImpl() {
        scroll = new ScrollPanel();
        scroll.setSize("100%", "100%");

        // The live region sits beside the scroll panel rather than inside the list,
        // because the presenter's rebuild() clears the list on every reorder — and a
        // live region that is removed and re-created never announces, since assistive
        // technology only reports changes to a region it was already watching.
        statusRegion = new Label();
        statusRegion.addStyleName("stroom-floormap-visually-hidden");
        final Element status = statusRegion.getElement();
        // role="status" carries an implicit aria-live="polite"; both are set because
        // some screen readers honour only one. atomic so the region is read as a whole
        // sentence. Mirrors the canvas view's region.
        status.setAttribute("role", "status");
        status.setAttribute("aria-live", "polite");
        status.setAttribute("aria-atomic", "true");

        root = new FlowPanel();
        root.addStyleName("max");
        root.add(scroll);
        root.add(statusRegion);
    }

    @Override
    public Widget asWidget() {
        return root;
    }

    @Override
    public void setList(final Widget listWidget) {
        scroll.setWidget(listWidget);
    }

    @Override
    public void announce(final String message) {
        if (message == null || message.isEmpty() || message.equals(lastAnnouncement)) {
            return;
        }
        lastAnnouncement = message;
        statusRegion.setText(message);
    }
}
