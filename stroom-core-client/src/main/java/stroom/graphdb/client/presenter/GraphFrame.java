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

package stroom.graphdb.client.presenter;

import stroom.dashboard.client.vis.MessageSupport;
import stroom.dashboard.client.vis.SelectionUiHandlers;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Frame;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.web.bindery.event.shared.EventBus;

/**
 * The Data-tab Cytoscape graph view's iframe host. A deliberately thin analogue of
 * {@link stroom.dashboard.client.vis.VisFrame}: it wraps an iframe loading {@code ui/graph.html}
 * and drives it through the shared {@link MessageSupport}/{@code PostMessage} transport (the same
 * same-origin postMessage protocol the dashboard visualisation framework uses). The row&rarr;elements
 * reshape and all Cytoscape rendering happen inside the sandbox ({@code ui/graph.js}); this class only
 * ships element rows in and relays taps out.
 *
 * <p>Load timing: {@code setElements} before the iframe has loaded would be lost, so the last payload is
 * held and (re)posted once {@code onFrameLoaded} fires.</p>
 */
public class GraphFrame extends Composite {

    private final MessageSupport messageSupport;
    private final Frame frame;
    private final SimplePanel container;

    private boolean loaded;
    private JSONValue pendingElements;

    public GraphFrame(final EventBus eventBus) {
        frame = new Frame("ui/graph.html");
        frame.addStyleName("GraphFrame-frame");
        frame.addLoadHandler(event -> onFrameLoaded());
        messageSupport = new MessageSupport(eventBus, frame.getElement());

        container = new SimplePanel(frame);
        container.addStyleName("GraphFrame-container");
        initWidget(container);
    }

    public void bind() {
        messageSupport.bind();
    }

    public void unbind() {
        messageSupport.unbind();
    }

    /**
     * Register a handler for node/edge taps relayed from the sandbox (P5 wires this to a properties panel;
     * for now the presenter simply records the selection).
     */
    public void setUiHandlers(final SelectionUiHandlers uiHandlers) {
        messageSupport.setUiHandlers(uiHandlers);
    }

    /**
     * Post the {@code RETURN GRAPH} element-row table to the sandbox.
     *
     * @param payload {@code {columns:[<name>...], rows:[[<value>...]...]}} - the raw element rows; the
     *                sandbox's adapter reshapes them into Cytoscape nodes/edges.
     */
    public void setElements(final JSONValue payload) {
        pendingElements = payload;
        flush();
    }

    /**
     * Merge additional element rows into the already-loaded graph (an "Expand neighbours" result), without
     * replacing what is shown. Only meaningful after {@link #setElements} has rendered a graph.
     */
    public void addElements(final JSONValue payload) {
        final JSONArray params = new JSONArray();
        params.set(0, payload);

        final JSONObject message = new JSONObject();
        message.put("functionName", new JSONString("graphManager.addElements"));
        message.put("params", params);

        messageSupport.postMessage(message);
    }

    public void clear() {
        pendingElements = null;
        final JSONObject message = new JSONObject();
        message.put("functionName", new JSONString("graphManager.clear"));
        messageSupport.postMessage(message);
    }

    public void onResize() {
        final JSONObject message = new JSONObject();
        message.put("functionName", new JSONString("graphManager.resize"));
        messageSupport.postMessage(message);
    }

    private void onFrameLoaded() {
        loaded = true;
        flush();
    }

    private void flush() {
        if (!loaded || pendingElements == null) {
            return;
        }

        final JSONArray params = new JSONArray();
        params.set(0, pendingElements);

        final JSONObject message = new JSONObject();
        message.put("functionName", new JSONString("graphManager.setElements"));
        message.put("params", params);

        messageSupport.postMessage(message);
    }
}
