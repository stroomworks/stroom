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
 * TRANSPORT (frozen contract - P0). Driven over the SAME same-origin postMessage protocol as ui/vis.js: the
 * parent (GraphFrame -> MessageSupport -> PostMessage) posts {frameId, [callbackId,] data:{functionName, params}},
 * and this listener dispatches `functionName.apply(this, params)` against the global `graphManager`:
 *   graphManager.setElements({columns:[...], rows:[[...]...]})  - the RETURN GRAPH element-row table. Fire-and-forget.
 *   graphManager.resize()   - re-fit the viewport.
 *   graphManager.clear()    - drop all elements.
 * Reverse channel: stroom.select([...]) posts a selection back (handled by MessageSupport as a "select" message).
 * A plain node/edge tap posts the tapped element; the "Query this node" context-menu action posts a selection
 * carrying a `__stroomQuery` param, which the parent runs as a new query (the vis->engine bridge, without needing
 * a new transport message type). Fire-and-forget calls must NOT invoke the callback (mirrors vis.js).
 *
 * The row->elements ADAPTER (render() below) is the single reshape the design study (§3, §7) calls for.
 *
 * INTERACTION. On top of Cytoscape's built-in pan/zoom/drag/select, this adds (all optional - each degrades if its
 * vendored extension is absent): a layout picker + Fit toolbar (fcose / dagre / concentric / tree / basic force),
 * hover tooltips showing an element's properties, and a right-click context menu (query this node, highlight
 * neighbourhood, hide, reset). Libraries are vendored under script/cytoscape/ - see graph.html.
 */

var stroomParent;
var stroomFrameId;
var stroomOrigin;

// The minimal `stroom` bridge (a subset of vis.js) - only what a graph interaction needs.
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
 * Captures the parent window/frame/origin from an inbound message so stroom.select can reply. A fire-and-forget
 * call leaves onSuccess/onFailure as no-ops (the parent posts no callbackId and would treat a reply as unexpected).
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
 * Loads element rows into a Cytoscape instance and keeps it laid out, fitted and interactive.
 */
function GraphManager() {
    var cy = null;
    var currentLayout = 'fcose';
    var tooltip = null;

    var RESERVED_DATA_KEYS = {
        id: true, source: true, target: true, label: true, labels: true, type: true, changeKind: true
    };

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
        // Neighbourhood-highlight (context menu).
        {selector: '.faded', style: {'opacity': 0.12}},
        {selector: '.highlighted', style: {'z-index': 20}},
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
        try {
            run(currentLayout);
        } catch (ex) {
            try {
                run('cose');
            } catch (ex2) {
                cy.fit(undefined, 30);
            }
        }
    };

    var escapeHtml = function (text) {
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    };

    // ---- interaction wiring (attached once, when the Cytoscape instance is first created) ----

    var buildToolbar = function () {
        if (document.getElementById('graph-toolbar')) {
            return;
        }
        var bar = document.createElement('div');
        bar.id = 'graph-toolbar';

        var select = document.createElement('select');
        select.title = 'Layout';
        var layouts = [
            {value: 'fcose', text: 'Force'},
            {value: 'dagre', text: 'Hierarchy'},
            {value: 'concentric', text: 'Concentric'},
            {value: 'breadthfirst', text: 'Tree'},
            {value: 'cose', text: 'Force (basic)'}
        ];
        for (var i = 0; i < layouts.length; i++) {
            var opt = document.createElement('option');
            opt.value = layouts[i].value;
            opt.text = layouts[i].text;
            select.appendChild(opt);
        }
        select.value = currentLayout;
        select.onchange = function () {
            currentLayout = select.value;
            runLayout();
        };

        var fit = document.createElement('button');
        fit.type = 'button';
        fit.textContent = 'Fit';
        fit.title = 'Fit the graph to the view';
        fit.onclick = function () {
            if (cy) {
                cy.fit(undefined, 30);
            }
        };

        bar.appendChild(select);
        bar.appendChild(fit);
        document.body.appendChild(bar);
    };

    var setupTooltip = function () {
        tooltip = document.getElementById('graph-tooltip');
        if (!tooltip) {
            tooltip = document.createElement('div');
            tooltip.id = 'graph-tooltip';
            document.body.appendChild(tooltip);
        }

        var show = function (evt) {
            var target = evt.target;
            var data = target.data();
            var isEdge = target.isEdge();
            var html = '<div><b>' + escapeHtml(isEdge ? (data.type || 'edge') : (data.label || data.id)) + '</b></div>';
            html += '<div class="k">' + (isEdge ? 'relationship' : 'node') + ' &middot; ' + escapeHtml(data.id) + '</div>';
            for (var key in data) {
                if (data.hasOwnProperty(key) && !RESERVED_DATA_KEYS[key] && data[key] !== null && data[key] !== undefined) {
                    html += '<div><span class="k">' + escapeHtml(key) + ':</span> ' + escapeHtml(data[key]) + '</div>';
                }
            }
            if (data.changeKind) {
                html += '<div class="k">changeKind: ' + escapeHtml(data.changeKind) + '</div>';
            }
            tooltip.innerHTML = html;
            tooltip.style.display = 'block';
        };
        var move = function (evt) {
            if (tooltip.style.display === 'block' && evt.originalEvent) {
                tooltip.style.left = (evt.originalEvent.clientX + 12) + 'px';
                tooltip.style.top = (evt.originalEvent.clientY + 12) + 'px';
            }
        };
        var hide = function () {
            tooltip.style.display = 'none';
        };

        cy.on('mouseover', 'node, edge', show);
        cy.on('mousemove', move);
        cy.on('mouseout', 'node, edge', hide);
        cy.on('pan zoom drag', hide);
    };

    var highlightNeighbourhood = function (node) {
        cy.elements().addClass('faded');
        var neighbourhood = node.closedNeighborhood();
        neighbourhood.removeClass('faded').addClass('highlighted');
    };

    var clearHighlight = function () {
        cy.elements().removeClass('faded').removeClass('highlighted');
    };

    // Best-effort anchored query for a node: its first label + id (returns rows where that pair is indexed;
    // otherwise a valid, editable starting query). Sent to the parent to run via the select channel.
    var nodeQuery = function (node) {
        var labels = node.data('labels');
        var id = node.data('id');
        if (!labels || !id) {
            return null;
        }
        var label = String(labels).split(',')[0];
        return "MATCH (n:" + label + " {id: '" + String(id).replace(/'/g, "\\'") + "'}) RETURN GRAPH";
    };

    var sendQuery = function (query) {
        if (query) {
            stroom.select([{__stroomQuery: query}]);
        }
    };

    // Ask the parent to expand this node's neighbours (all edge types, both directions) and merge them in.
    var sendExpand = function (nodeId) {
        if (nodeId) {
            stroom.select([{__stroomExpand: nodeId}]);
        }
    };

    // Merge additional elements into the current graph (the result of an "Expand neighbours" request), skipping
    // any element already present, then re-layout.
    var addElements = function (payload) {
        if (typeof cytoscape === 'undefined') {
            return;
        }
        if (!cy) {
            render(payload);
            return;
        }
        var elements = toElements(payload);
        var fresh = [];
        for (var i = 0; i < elements.length; i++) {
            var el = elements[i];
            if (el.data && el.data.id && cy.getElementById(el.data.id).length === 0) {
                fresh.push(el);
            }
        }
        if (fresh.length > 0) {
            cy.add(fresh);
            runLayout();
        }
    };

    var setupContextMenu = function () {
        if (typeof cy.contextMenus !== 'function') {
            return; // extension not vendored - skip gracefully
        }
        cy.contextMenus({
            menuItems: [
                {
                    id: 'expand-node',
                    content: 'Expand neighbours',
                    selector: 'node',
                    onClickFunction: function (evt) {
                        sendExpand(evt.target.data('id'));
                    }
                },
                {
                    id: 'query-node',
                    content: 'Query this node',
                    selector: 'node',
                    onClickFunction: function (evt) {
                        sendQuery(nodeQuery(evt.target));
                    }
                },
                {
                    id: 'highlight-neighbourhood',
                    content: 'Highlight neighbourhood',
                    selector: 'node',
                    onClickFunction: function (evt) {
                        highlightNeighbourhood(evt.target);
                    }
                },
                {
                    id: 'hide-element',
                    content: 'Hide',
                    selector: 'node, edge',
                    onClickFunction: function (evt) {
                        evt.target.remove();
                    }
                },
                {
                    id: 'reset-view',
                    content: 'Reset view',
                    coreAsWell: true,
                    onClickFunction: function () {
                        clearHighlight();
                        cy.fit(undefined, 30);
                    }
                }
            ]
        });
    };

    var wireInteractions = function () {
        cy.on('tap', 'node, edge', function (evt) {
            var target = evt.target;
            var data = target.data();
            stroom.select([{id: data.id, kind: target.isEdge() ? 'EDGE' : 'NODE'}]);
        });
        buildToolbar();
        setupTooltip();
        setupContextMenu();
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

        if (!cy) {
            cy = cytoscape({
                container: document.getElementById('cy'),
                elements: elements,
                style: STYLE,
                layout: {name: 'preset'}
            });
            wireInteractions();
        } else {
            if (tooltip) {
                tooltip.style.display = 'none';
            }
            cy.elements().remove();
            cy.add(elements);
        }
        runLayout();
    };

    this.setElements = function (payload, callback) {
        render(payload);
        // Fire-and-forget: do not call the callback.
    };

    this.addElements = function (payload, callback) {
        addElements(payload);
        // Fire-and-forget.
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
