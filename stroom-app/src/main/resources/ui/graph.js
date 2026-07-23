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

/*
 * The sandboxed host page for the Graph DB Data tab's Cytoscape.js graph view.
 *
 * TRANSPORT (frozen contract - P0). This page is driven over the SAME same-origin
 * postMessage protocol as ui/vis.js: the parent (GraphFrame -> MessageSupport -> PostMessage)
 * posts {frameId, [callbackId,] data:{functionName, params}}, and this listener dispatches
 * `functionName.apply(this, params)` against the global `graphManager`. Recognised calls:
 *
 *   graphManager.setElements({columns:[<name>...], rows:[[<value>...]...]})
 *       - the RETURN GRAPH element-row table (columns kind,id,labels,source,target,properties
 *         [,changeKind] - see CypherToLogicalPlan.ELEMENT_ROW_COLUMNS). Fire-and-forget.
 *   graphManager.resize()   - re-fit the viewport (call after the pane becomes visible / resizes).
 *   graphManager.clear()    - drop all elements.
 *
 * Reverse channel: a node/edge tap posts stroom.select([{id,kind}]) back to the parent
 * (handled by MessageSupport as a "select" message). Fire-and-forget calls must NOT invoke the
 * callback (mirrors vis.js's setData/resize), or the parent logs an "unexpected message".
 *
 * The row->elements ADAPTER (render() below) is the single reshape the design study (§3, §7)
 * calls for: split rows by kind, de-duplicate nodes by id, build edges from source/target,
 * spread the JSON `properties` map, and style by `changeKind`. Keeping it here means a future
 * dashboard surface can reuse this file unchanged.
 *
 * The library (cytoscape.min.js, optional cytoscape-fcose.js) is loaded locally by graph.html -
 * no CDN - and is vendored separately (air-gap). If it is absent this page shows a hint rather
 * than failing silently.
 */

var stroomParent;
var stroomFrameId;
var stroomOrigin;

// The minimal `stroom` bridge (a subset of vis.js) - only what a graph tap needs.
!function () {
    var stroom = {};

    stroom.select = function (selection) {
        if (stroomParent && stroomFrameId && stroomOrigin) {
            var message = JSON.stringify({
                frameId: stroomFrameId,
                functionName: 'select',
                selection: selection
            });
            stroomParent.postMessage(message, stroomOrigin);
        }
    };

    this.stroom = stroom;
}();

/**
 * Captures the parent window/frame/origin from an inbound message so stroom.select can reply.
 * A fire-and-forget call leaves onSuccess/onFailure as no-ops (the parent posts no callbackId
 * and would treat a reply as an unexpected message).
 */
function Callback(event, frameId, callbackId) {
    stroomParent = event.source;
    stroomFrameId = frameId;
    stroomOrigin = event.origin;

    this.onSuccess = function () {
    };
    this.onFailure = function () {
    };
}

/**
 * Loads element rows into a Cytoscape instance and keeps it laid out and fitted.
 */
function GraphManager() {
    var cy = null;
    var lastElements = null;

    var STYLE = [
        {
            selector: 'node',
            style: {
                'label': 'data(label)',
                'font-size': '10px',
                'text-valign': 'center',
                'text-halign': 'center',
                'color': '#ffffff',
                'text-outline-width': 2,
                'text-outline-color': '#3a7bd5',
                'background-color': '#3a7bd5',
                'width': 26,
                'height': 26
            }
        },
        {
            selector: 'edge',
            style: {
                'label': 'data(type)',
                'font-size': '9px',
                'color': '#888888',
                'width': 1.5,
                'line-color': '#b0b0b0',
                'target-arrow-color': '#b0b0b0',
                'target-arrow-shape': 'triangle',
                'curve-style': 'bezier'
            }
        },
        // changeKind styling (DIFF ... RETURN GRAPH).
        {selector: '.added', style: {'background-color': '#2e9e5b', 'line-color': '#2e9e5b', 'target-arrow-color': '#2e9e5b'}},
        {selector: '.removed', style: {'background-color': '#d64545', 'line-color': '#d64545', 'target-arrow-color': '#d64545'}},
        {selector: '.modified', style: {'background-color': '#d9a441', 'line-color': '#d9a441', 'target-arrow-color': '#d9a441'}},
        {selector: '.unchanged', style: {'opacity': 0.55}},
        {selector: ':selected', style: {'border-width': 3, 'border-color': '#111111'}}
    ];

    var cellValue = function (row, index, name) {
        var i = index[name];
        if (i === undefined || i === null || i < 0 || i >= row.length) {
            return null;
        }
        var v = row[i];
        return (v === undefined || v === null || v === '') ? null : String(v);
    };

    var parseProps = function (json) {
        if (!json) {
            return {};
        }
        try {
            var obj = JSON.parse(json);
            return (obj && typeof obj === 'object') ? obj : {};
        } catch (ex) {
            return {};
        }
    };

    var ensureNode = function (nodes, id) {
        if (id && !nodes[id]) {
            nodes[id] = {group: 'nodes', data: {id: id, label: id}};
        }
    };

    // The row -> elements adapter.
    var toElements = function (payload) {
        var columns = (payload && payload.columns) ? payload.columns : [];
        var rows = (payload && payload.rows) ? payload.rows : [];

        var index = {};
        for (var c = 0; c < columns.length; c++) {
            index[String(columns[c]).toLowerCase()] = c;
        }

        var nodes = {};
        var edges = [];

        for (var r = 0; r < rows.length; r++) {
            var row = rows[r];
            var kind = cellValue(row, index, 'kind');
            var id = cellValue(row, index, 'id');
            var labels = cellValue(row, index, 'labels');
            var source = cellValue(row, index, 'source');
            var target = cellValue(row, index, 'target');
            var props = parseProps(cellValue(row, index, 'properties'));
            var changeKind = cellValue(row, index, 'changekind');
            var cls = changeKind ? changeKind.toLowerCase() : undefined;

            var isEdge = (kind && kind.toUpperCase() === 'EDGE') || (!!source && !!target);

            if (isEdge) {
                var edgeData = {};
                for (var pk in props) {
                    if (props.hasOwnProperty(pk)) {
                        edgeData[pk] = props[pk];
                    }
                }
                edgeData.id = id || (source + '|' + labels + '|' + target + '|' + r);
                edgeData.source = source;
                edgeData.target = target;
                edgeData.type = labels || '';
                if (changeKind) {
                    edgeData.changeKind = changeKind;
                }
                ensureNode(nodes, source);
                ensureNode(nodes, target);
                edges.push({group: 'edges', data: edgeData, classes: cls});
            } else if (id) {
                var nodeData = {};
                for (var nk in props) {
                    if (props.hasOwnProperty(nk)) {
                        nodeData[nk] = props[nk];
                    }
                }
                nodeData.id = id;
                nodeData.label = labels || id;
                nodeData.labels = labels || '';
                if (changeKind) {
                    nodeData.changeKind = changeKind;
                }
                // A real node row overwrites any stub added by an earlier edge endpoint.
                nodes[id] = {group: 'nodes', data: nodeData, classes: cls};
            }
        }

        var elements = [];
        for (var k in nodes) {
            if (nodes.hasOwnProperty(k)) {
                elements.push(nodes[k]);
            }
        }
        for (var e = 0; e < edges.length; e++) {
            // Drop dangling edges whose endpoints never resolved to a node id.
            if (edges[e].data.source && edges[e].data.target) {
                elements.push(edges[e]);
            }
        }
        return elements;
    };

    var runLayout = function () {
        if (!cy) {
            return;
        }
        var run = function (name) {
            var layout = cy.layout({name: name, animate: false, padding: 30});
            layout.one('layoutstop', function () {
                cy.fit(undefined, 30);
            });
            layout.run();
        };
        // Prefer fcose when its extension is present; fall back to the built-in cose; then to a plain fit.
        try {
            run('fcose');
        } catch (ex) {
            try {
                run('cose');
            } catch (ex2) {
                cy.fit(undefined, 30);
            }
        }
    };

    var showHint = function (text) {
        var el = document.getElementById('cy');
        if (el) {
            el.innerHTML = '<div class="graph-hint">' + text + '</div>';
        }
    };

    var render = function (payload) {
        if (typeof cytoscape === 'undefined') {
            showHint('The Cytoscape graph library is not installed on this deployment. '
                + 'Place cytoscape.min.js under the UI assets to enable the graph view.');
            return;
        }

        var elements = toElements(payload);
        lastElements = elements;

        if (!cy) {
            cy = cytoscape({
                container: document.getElementById('cy'),
                elements: elements,
                style: STYLE,
                layout: {name: 'preset'}
            });
            cy.on('tap', 'node, edge', function (evt) {
                var target = evt.target;
                var data = target.data();
                stroom.select([{id: data.id, kind: target.isEdge() ? 'EDGE' : 'NODE'}]);
            });
        } else {
            cy.elements().remove();
            cy.add(elements);
        }
        runLayout();
    };

    this.setElements = function (payload, callback) {
        render(payload);
        // Fire-and-forget: do not call the callback.
    };

    this.resize = function (callback) {
        if (cy) {
            cy.resize();
            cy.fit(undefined, 30);
        }
    };

    this.clear = function (callback) {
        if (cy) {
            cy.elements().remove();
        }
    };
}

var graphManager = new GraphManager();

/**
 * LISTEN TO WINDOW MESSAGES (same-origin only), mirroring ui/vis.js.
 */
var messageListener = function (event) {
    var origin = event.origin;
    var hostname = window.location.hostname;

    // Stop this script being driven from other domains.
    var eventLocation = document.createElement('a');
    eventLocation.href = origin;
    var eventHostname = eventLocation.hostname;
    if (eventHostname != hostname) {
        console.error("Ignoring event as host names do not match: hostname='" + hostname
            + "' eventHostname='" + eventHostname + "'");
        return;
    }

    var json = JSON.parse(event.data);

    if (json.data) {
        var frameId;
        var callbackId;

        if (json.frameId) {
            frameId = json.frameId;
        }
        if (json.callbackId) {
            callbackId = json.callbackId;
        }

        var callback = new Callback(event, frameId, callbackId);
        var params = json.data.params;
        if (!params) {
            params = [];
        }
        params.push(callback);

        eval(json.data.functionName + ".apply(this, params);");
    }
};

if (window.addEventListener) {
    addEventListener("message", messageListener, false);
} else {
    attachEvent("onmessage", messageListener);
}
