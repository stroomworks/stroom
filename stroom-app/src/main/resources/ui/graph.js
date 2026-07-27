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
 *   graphManager.addElements({columns:[...], rows:[[...]...]})  - merge more rows in (Expand neighbours).
 *   graphManager.setClassName('stroom-theme-dark')  - track the app's light/dark theme (mirrors vis.js).
 *   graphManager.resize()   - re-fit the viewport.
 *   graphManager.clear()    - drop all elements.
 * Reverse channel: stroom.select([...]) posts a selection back (handled by MessageSupport as a "select" message).
 * A plain node/edge tap posts the tapped element; the context-menu / inspector actions post a selection carrying a
 * command param instead - `__stroomExpand` (expand this node's neighbours, merged in) or `__stroomFocus` (replace
 * the view with this node + its neighbours) - which the parent resolves identity-based via the /expand endpoint (the
 * vis->engine bridge, without needing a new transport message type). Fire-and-forget calls must NOT invoke the
 * callback (mirrors vis.js).
 *
 * The row->elements ADAPTER (render() below) is the single reshape the design study (§3, §7) calls for.
 *
 * INTERACTION. On top of Cytoscape's built-in pan/zoom/drag/select, this adds (all optional - each degrades if its
 * vendored extension is absent):
 *   - a layout picker + Fit toolbar (fcose / dagre / concentric / tree / basic force);
 *   - VISUAL ENCODING: a deterministic colour + shape per node label (stable across runs and expansions), an
 *     optional "size by degree" mode that makes hubs pop, and an edge-label on/off toggle;
 *   - FIND & FILTER: a search box (matches id / label / any property value -> fades the rest, zooms to hits) and an
 *     interactive legend whose entries toggle a whole node-label or relationship-type in/out of view;
 *   - an INSPECTOR panel showing a tapped element's full properties with per-element actions (expand, focus,
 *     highlight neighbourhood, hide, copy id) - the persistent counterpart to the hover tooltip;
 *   - a right-click context menu (expand neighbours, focus, highlight neighbourhood, hide, reset).
 * Libraries are vendored under script/cytoscape/ - see graph.html.
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
    var inspector = null;
    var legend = null;
    var searchInput = null;

    // Interaction state persisted across re-renders/expansions so the view keeps its encoding + filters.
    var sizeByDegree = false;
    var showEdgeLabels = true;
    var hiddenLabels = {};      // primary node label -> true when hidden
    var hiddenEdgeTypes = {};   // edge type -> true when hidden
    var pathSource = null;      // node id armed as the shortest-path start
    var centrality = null;      // lazily-computed pageRank/betweenness, cached until the graph changes
    var statusEl = null;        // transient status pill (path results etc.)
    var autoDeclutter = false;  // hide labels when zoomed out
    var captionKey = null;      // node caption source: null=default (labels||id), '__id'=id, else a property key
    var captionSelect = null;   // the caption picker <select> (options rebuilt as the graph changes)
    var edgeWidthKey = null;    // null = uniform width, else a numeric edge property mapped to thickness
    var edgeWidthSelect = null; // the edge-width picker <select> (options rebuilt as the graph changes)
    var timeTravelActive = false; // whether the parent-owned time-travel panel is shown (toggled from the toolbar)

    var DECLUTTER_ZOOM = 0.5;   // below this zoom level, labels are hidden when auto-declutter is on
    var EDGE_WIDTH_MIN = 1;     // px, thinnest edge when mapping a property to thickness
    var EDGE_WIDTH_MAX = 8;     // px, thickest edge
    var EDGE_WIDTH_UNIFORM = 1.5;

    var RESERVED_DATA_KEYS = {
        id: true, source: true, target: true, label: true, labels: true, type: true, changeKind: true
    };

    // ---- visual encoding: a deterministic colour + shape per node label ----
    // Assigned in first-seen order and cached for the life of this manager, so a label keeps its look across
    // re-runs and "Expand neighbours" merges. changeKind (.added/.removed/...) classes still override, being
    // more specific than the base `node` selector's function mappers.
    var PALETTE = [
        '#3a7bd5', '#2e9e5b', '#d9a441', '#b8547d', '#8b5cf6', '#0ea5a4',
        '#d64545', '#5b7db1', '#7a913a', '#c9772e', '#4a8db5', '#16a085',
        '#9b59b6', '#e08e0b', '#c0392b', '#2c82c9'
    ];
    var SHAPES = ['ellipse', 'round-rectangle', 'diamond', 'hexagon', 'triangle', 'pentagon'];
    var NO_LABEL = '(no label)';
    var labelStyle = {};        // label -> {color, shape}
    var labelOrder = [];        // discovery order (drives palette assignment + legend order)

    var primaryLabel = function (labels) {
        if (!labels) {
            return NO_LABEL;
        }
        // A node's labels may arrive colon- or comma-separated; the first token drives its look.
        var parts = String(labels).split(/[:,]/);
        var first = (parts[0] || '').trim();
        return first === '' ? NO_LABEL : first;
    };

    var ensureLabelStyle = function (label) {
        if (!labelStyle[label]) {
            var i = labelOrder.length;
            labelStyle[label] = {
                color: PALETTE[i % PALETTE.length],
                shape: SHAPES[i % SHAPES.length]
            };
            labelOrder.push(label);
        }
        return labelStyle[label];
    };

    var colorForLabel = function (labels) {
        return ensureLabelStyle(primaryLabel(labels)).color;
    };
    var shapeForLabel = function (labels) {
        return ensureLabelStyle(primaryLabel(labels)).shape;
    };

    // Pre-assign a look to every label present, before styling runs, so the stylesheet's function mappers resolve.
    var assignStyles = function (elements) {
        for (var i = 0; i < elements.length; i++) {
            if (elements[i].group === 'nodes') {
                ensureLabelStyle(primaryLabel(elements[i].data.labels));
            }
        }
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
                'text-outline-color': function (ele) {
                    return colorForLabel(ele.data('labels'));
                },
                'background-color': function (ele) {
                    return colorForLabel(ele.data('labels'));
                },
                'shape': function (ele) {
                    return shapeForLabel(ele.data('labels'));
                },
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
        {selector: '.added', style: {'background-color': '#2e9e5b', 'line-color': '#2e9e5b', 'target-arrow-color': '#2e9e5b', 'text-outline-color': '#2e9e5b'}},
        {selector: '.removed', style: {'background-color': '#d64545', 'line-color': '#d64545', 'target-arrow-color': '#d64545', 'text-outline-color': '#d64545'}},
        {selector: '.modified', style: {'background-color': '#d9a441', 'line-color': '#d9a441', 'target-arrow-color': '#d9a441', 'text-outline-color': '#d9a441'}},
        {selector: '.unchanged', style: {'opacity': 0.55}},
        // Neighbourhood-highlight (context menu) + search.
        {selector: '.faded', style: {'opacity': 0.12}},
        {selector: '.highlighted', style: {'z-index': 20, 'border-width': 3, 'border-color': '#111111'}},
        {selector: '.pinned', style: {'border-width': 3, 'border-color': '#e0a800', 'border-style': 'double'}},
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

        // Toggle: size nodes by degree so hub nodes stand out.
        var degreeBtn = document.createElement('button');
        degreeBtn.type = 'button';
        degreeBtn.textContent = 'Size by degree';
        degreeBtn.title = 'Scale each node by how many edges it has';
        degreeBtn.onclick = function () {
            sizeByDegree = !sizeByDegree;
            setActive(degreeBtn, sizeByDegree);
            applyNodeSizing();
        };

        // Toggle: hide edge labels to de-clutter dense graphs.
        var edgeLabelBtn = document.createElement('button');
        edgeLabelBtn.type = 'button';
        edgeLabelBtn.textContent = 'Edge labels';
        edgeLabelBtn.title = 'Show or hide relationship-type labels on edges';
        setActive(edgeLabelBtn, showEdgeLabels);
        edgeLabelBtn.onclick = function () {
            showEdgeLabels = !showEdgeLabels;
            setActive(edgeLabelBtn, showEdgeLabels);
            applyEdgeLabels();
        };

        // Toggle: auto-hide labels when zoomed out, so a big graph stays legible.
        var declutterBtn = document.createElement('button');
        declutterBtn.type = 'button';
        declutterBtn.textContent = 'Declutter';
        declutterBtn.title = 'Hide labels automatically when zoomed out';
        declutterBtn.onclick = function () {
            autoDeclutter = !autoDeclutter;
            setActive(declutterBtn, autoDeclutter);
            applyDeclutter();
        };

        // Toggle: reveal the time-travel controls. The temporal panel (slider / play / compare) lives in the
        // parent GWT widget, so this button just relays an on/off command up the reverse channel (the same
        // mechanism as the "Expand"/"Focus" context-menu actions); the parent shows or hides its panel.
        var timeTravelBtn = document.createElement('button');
        timeTravelBtn.type = 'button';
        timeTravelBtn.textContent = 'Time travel';
        timeTravelBtn.title = 'Show the time-travel controls to view the graph as of a past instant';
        setActive(timeTravelBtn, timeTravelActive);
        timeTravelBtn.onclick = function () {
            timeTravelActive = !timeTravelActive;
            setActive(timeTravelBtn, timeTravelActive);
            stroom.select([{__stroomTimeTravel: timeTravelActive ? 'on' : 'off'}]);
        };

        // Discover: ask the parent to toggle the schema-discovery panel (starter queries built from the graph's
        // labels/ids). That panel is a parent GWT widget, so relay a toggle command up the reverse channel; the
        // parent's onDiscover handles show/hide.
        var discoverBtn = document.createElement('button');
        discoverBtn.type = 'button';
        discoverBtn.textContent = 'Discover';
        discoverBtn.title = 'Discover what\'s in this graph';
        discoverBtn.onclick = function () {
            stroom.select([{__stroomDiscover: 'toggle'}]);
        };

        // Caption picker: choose which property (or label / id) is shown on nodes. Options rebuilt per graph.
        captionSelect = document.createElement('select');
        captionSelect.title = 'Node caption';
        captionSelect.onchange = function () {
            captionKey = captionSelect.value === '' ? null : captionSelect.value;
            applyCaption();
        };

        // Edge-width picker: map a numeric edge property to line thickness. Options rebuilt per graph.
        edgeWidthSelect = document.createElement('select');
        edgeWidthSelect.title = 'Edge width by property';
        edgeWidthSelect.onchange = function () {
            edgeWidthKey = edgeWidthSelect.value === '' ? null : edgeWidthSelect.value;
            applyEdgeWidth();
        };

        // Export menu: PNG image, or the current graph as an element table (CSV) / element list (JSON).
        var exportSelect = document.createElement('select');
        exportSelect.title = 'Export the current graph';
        var exportOptions = [
            {value: '', text: 'Export…'},
            {value: 'png', text: 'PNG image'},
            {value: 'csv', text: 'CSV (elements)'},
            {value: 'json', text: 'JSON (elements)'}
        ];
        for (var xi = 0; xi < exportOptions.length; xi++) {
            var xopt = document.createElement('option');
            xopt.value = exportOptions[xi].value;
            xopt.text = exportOptions[xi].text;
            exportSelect.appendChild(xopt);
        }
        exportSelect.onchange = function () {
            exportGraph(exportSelect.value);
            exportSelect.value = '';
        };

        // Search box: highlight nodes matching id / label / any property value and zoom to them.
        searchInput = document.createElement('input');
        searchInput.type = 'search';
        searchInput.id = 'graph-search';
        searchInput.placeholder = 'Find nodes...';
        searchInput.title = 'Highlight nodes matching id, label or any property value';
        searchInput.oninput = function () {
            applySearch();
        };
        searchInput.onkeydown = function (evt) {
            if (evt.key === 'Escape') {
                searchInput.value = '';
                applySearch();
            }
        };

        bar.appendChild(select);
        bar.appendChild(fit);
        bar.appendChild(degreeBtn);
        bar.appendChild(edgeLabelBtn);
        bar.appendChild(declutterBtn);
        bar.appendChild(timeTravelBtn);
        bar.appendChild(discoverBtn);
        bar.appendChild(captionSelect);
        bar.appendChild(edgeWidthSelect);
        bar.appendChild(exportSelect);
        bar.appendChild(searchInput);
        document.body.appendChild(bar);
    };

    var setActive = function (btn, on) {
        if (on) {
            btn.className = 'active';
        } else {
            btn.className = '';
        }
    };

    // ---- visual encoding toggles ----

    var applyNodeSizing = function () {
        if (!cy) {
            return;
        }
        cy.nodes().forEach(function (n) {
            var size = 26;
            if (sizeByDegree) {
                var d = n.degree(false);
                size = Math.max(20, Math.min(70, 18 + d * 6));
            }
            n.style({width: size, height: size});
        });
    };

    var applyEdgeLabels = function () {
        if (!cy) {
            return;
        }
        cy.edges().style('text-opacity', showEdgeLabels ? 1 : 0);
    };

    // Auto-declutter: below a zoom threshold, drop labels so a dense graph stays readable.
    var applyDeclutter = function () {
        if (!cy) {
            return;
        }
        var hideLabels = autoDeclutter && cy.zoom() < DECLUTTER_ZOOM;
        cy.nodes().style('text-opacity', hideLabels ? 0 : 1);
        if (hideLabels) {
            cy.edges().style('text-opacity', 0);
        } else {
            applyEdgeLabels(); // restore, respecting the edge-label toggle
        }
    };

    // Node caption: set each node's displayed label from the chosen source (default = labels || id).
    var applyCaption = function () {
        if (!cy) {
            return;
        }
        cy.nodes().forEach(function (n) {
            var d = n.data();
            var caption;
            if (!captionKey) {
                caption = d.labels || d.id;
            } else if (captionKey === '__id') {
                caption = d.id;
            } else {
                caption = (d[captionKey] === undefined || d[captionKey] === null) ? '' : String(d[captionKey]);
            }
            n.data('label', caption);
        });
    };

    // Rebuild the caption picker's options from the property keys present, preserving the current choice.
    var rebuildCaptionOptions = function () {
        if (!captionSelect) {
            return;
        }
        var keys = {};
        cy.nodes().forEach(function (n) {
            var d = n.data();
            for (var k in d) {
                if (d.hasOwnProperty(k) && !RESERVED_DATA_KEYS[k]) {
                    keys[k] = true;
                }
            }
        });
        var sorted = [];
        for (var key in keys) {
            if (keys.hasOwnProperty(key)) {
                sorted.push(key);
            }
        }
        sorted.sort();

        var previous = captionSelect.value;
        captionSelect.innerHTML = '';
        var add = function (value, text) {
            var opt = document.createElement('option');
            opt.value = value;
            opt.text = text;
            captionSelect.appendChild(opt);
        };
        add('', 'Caption: label');
        add('__id', 'Caption: id');
        for (var i = 0; i < sorted.length; i++) {
            add(sorted[i], 'Caption: ' + sorted[i]);
        }
        // Keep the previous choice if it still exists, else fall back to default.
        captionSelect.value = previous;
        if (captionSelect.selectedIndex < 0) {
            captionSelect.value = '';
            captionKey = null;
        }
    };

    // A strict numeric read of a data value (actual number, or a fully-numeric string); NaN otherwise.
    var numericValue = function (value) {
        if (value === null || value === undefined || value === '') {
            return NaN;
        }
        return Number(value);
    };

    // Edge width: map the chosen numeric property onto [EDGE_WIDTH_MIN, EDGE_WIDTH_MAX], or a uniform width.
    var applyEdgeWidth = function () {
        if (!cy) {
            return;
        }
        if (!edgeWidthKey) {
            cy.edges().style('width', EDGE_WIDTH_UNIFORM);
            return;
        }
        var min = Infinity;
        var max = -Infinity;
        cy.edges().forEach(function (e) {
            var v = numericValue(e.data(edgeWidthKey));
            if (!isNaN(v)) {
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
            }
        });
        var span = max - min;
        cy.edges().forEach(function (e) {
            var v = numericValue(e.data(edgeWidthKey));
            var width = EDGE_WIDTH_UNIFORM;
            if (!isNaN(v)) {
                width = span > 0
                    ? EDGE_WIDTH_MIN + (EDGE_WIDTH_MAX - EDGE_WIDTH_MIN) * ((v - min) / span)
                    : (EDGE_WIDTH_MIN + EDGE_WIDTH_MAX) / 2;
            }
            e.style('width', width);
        });
    };

    // Rebuild the edge-width picker from the numeric edge properties present, preserving the current choice.
    var rebuildEdgeWidthOptions = function () {
        if (!edgeWidthSelect) {
            return;
        }
        var numericKeys = {};
        cy.edges().forEach(function (e) {
            var d = e.data();
            for (var k in d) {
                if (d.hasOwnProperty(k) && !RESERVED_DATA_KEYS[k] && !isNaN(numericValue(d[k]))) {
                    numericKeys[k] = true;
                }
            }
        });
        var sorted = [];
        for (var key in numericKeys) {
            if (numericKeys.hasOwnProperty(key)) {
                sorted.push(key);
            }
        }
        sorted.sort();

        var previous = edgeWidthSelect.value;
        edgeWidthSelect.innerHTML = '';
        var add = function (value, text) {
            var opt = document.createElement('option');
            opt.value = value;
            opt.text = text;
            edgeWidthSelect.appendChild(opt);
        };
        add('', 'Edge width: uniform');
        for (var i = 0; i < sorted.length; i++) {
            add(sorted[i], 'Edge width: ' + sorted[i]);
        }
        edgeWidthSelect.value = previous;
        if (edgeWidthSelect.selectedIndex < 0) {
            edgeWidthSelect.value = '';
            edgeWidthKey = null;
        }
    };

    // The connected component (undirected) containing a node - all nodes reachable from it, plus their edges.
    var componentOf = function (node) {
        var comps = cy.elements().components();
        for (var i = 0; i < comps.length; i++) {
            if (comps[i].filter(function (e) {
                return e.isNode() && e.id() === node.id();
            }).length > 0) {
                return comps[i];
            }
        }
        return node.closedNeighborhood();
    };

    var highlightComponent = function (node) {
        cy.elements().addClass('faded');
        componentOf(node).removeClass('faded').addClass('highlighted');
    };

    // ---- export ----

    // Trigger a browser download of either a data: URI (isDataUri) or in-memory text (built into a Blob URL).
    var download = function (filename, mimeType, content, isDataUri) {
        var anchor = document.createElement('a');
        anchor.download = filename;
        var revokeUrl = null;
        if (isDataUri) {
            anchor.href = content;
        } else {
            var blob = new Blob([content], {type: mimeType});
            revokeUrl = URL.createObjectURL(blob);
            anchor.href = revokeUrl;
        }
        document.body.appendChild(anchor);
        anchor.click();
        document.body.removeChild(anchor);
        if (revokeUrl) {
            setTimeout(function () {
                URL.revokeObjectURL(revokeUrl);
            }, 0);
        }
    };

    var csvCell = function (value) {
        var s = (value === null || value === undefined) ? '' : String(value);
        if (s.indexOf('"') !== -1 || s.indexOf(',') !== -1 || s.indexOf('\n') !== -1 || s.indexOf('\r') !== -1) {
            return '"' + s.replace(/"/g, '""') + '"';
        }
        return s;
    };

    // Collect an element's non-plumbing properties back into a plain object (the inverse of the adapter's spread).
    var elementProps = function (data) {
        var props = {};
        for (var key in data) {
            if (data.hasOwnProperty(key) && !RESERVED_DATA_KEYS[key] && data[key] !== null && data[key] !== undefined) {
                props[key] = data[key];
            }
        }
        return props;
    };

    // Rebuild the RETURN GRAPH element table (kind,id,labels,source,target,properties) from what is on screen.
    var exportCsv = function () {
        var lines = ['kind,id,labels,source,target,properties'];
        cy.nodes().forEach(function (n) {
            var d = n.data();
            lines.push(['NODE', d.id, d.labels || '', '', '', JSON.stringify(elementProps(d))].map(csvCell).join(','));
        });
        cy.edges().forEach(function (e) {
            var d = e.data();
            lines.push(['EDGE', d.id, d.type || '', d.source, d.target, JSON.stringify(elementProps(d))]
                .map(csvCell).join(','));
        });
        download('graph.csv', 'text/csv', lines.join('\n'), false);
    };

    var exportJson = function () {
        var nodes = [];
        cy.nodes().forEach(function (n) {
            nodes.push(n.data());
        });
        var edges = [];
        cy.edges().forEach(function (e) {
            edges.push(e.data());
        });
        download('graph.json', 'application/json', JSON.stringify({nodes: nodes, edges: edges}, null, 2), false);
    };

    var exportPng = function () {
        // Transparent background so it drops onto any page; 2x for a crisp image.
        var uri = cy.png({full: true, scale: 2, bg: 'transparent'});
        download('graph.png', 'image/png', uri, true);
    };

    var exportGraph = function (kind) {
        if (!cy || !kind) {
            return;
        }
        if (kind === 'png') {
            exportPng();
        } else if (kind === 'csv') {
            exportCsv();
        } else if (kind === 'json') {
            exportJson();
        }
    };

    // ---- pin / unpin: lock a node's position so re-layout leaves it anchored ----

    var togglePin = function (node) {
        if (node.locked()) {
            node.unlock();
            node.removeClass('pinned');
        } else {
            node.lock();
            node.addClass('pinned');
        }
    };

    // ---- analysis: centrality metrics + shortest path (Cytoscape core algorithms, no extension needed) ----

    // pageRank is cheap; betweenness is O(V*E), so skip it above a size threshold. Cached until the graph changes
    // (invalidated in refreshUi).
    var BETWEENNESS_MAX_NODES = 300;

    var getCentrality = function () {
        if (centrality) {
            return centrality;
        }
        var nodeCount = cy.nodes().length;
        centrality = {
            pr: cy.elements().pageRank(),
            bc: nodeCount <= BETWEENNESS_MAX_NODES ? cy.elements().betweennessCentrality({directed: false}) : null
        };
        return centrality;
    };

    var showStatus = function (text) {
        if (!statusEl) {
            statusEl = document.createElement('div');
            statusEl.id = 'graph-status';
            document.body.appendChild(statusEl);
        }
        statusEl.textContent = text;
        statusEl.style.display = 'block';
        if (showStatus.timer) {
            clearTimeout(showStatus.timer);
        }
        showStatus.timer = setTimeout(function () {
            if (statusEl) {
                statusEl.style.display = 'none';
            }
        }, 4000);
    };

    var clearPath = function () {
        pathSource = null;
        clearHighlight();
    };

    // Shortest path (unweighted, undirected) over the loaded graph, from the armed source to targetId.
    var computePathTo = function (targetId) {
        if (!pathSource || !targetId || pathSource === targetId) {
            return;
        }
        var source = cy.getElementById(pathSource);
        var goal = cy.getElementById(targetId);
        if (source.length === 0 || goal.length === 0) {
            showStatus('Path start is no longer in the graph.');
            pathSource = null;
            return;
        }
        var result = cy.elements().aStar({root: source, goal: goal, directed: false});
        if (result.found) {
            cy.elements().addClass('faded');
            result.path.removeClass('faded').addClass('highlighted');
            var hops = result.path.edges().length;
            showStatus('Shortest path: ' + hops + (hops === 1 ? ' hop' : ' hops'));
        } else {
            showStatus('No path found between the two nodes.');
        }
    };

    // ---- find & filter ----

    var matchesQuery = function (node, q) {
        var data = node.data();
        for (var key in data) {
            if (data.hasOwnProperty(key) && data[key] !== null && data[key] !== undefined) {
                if (String(data[key]).toLowerCase().indexOf(q) !== -1) {
                    return true;
                }
            }
        }
        return false;
    };

    var applySearch = function () {
        if (!cy) {
            return;
        }
        var q = searchInput ? String(searchInput.value || '').toLowerCase().trim() : '';
        if (q === '') {
            cy.elements().removeClass('faded').removeClass('highlighted');
            return;
        }
        var matches = cy.nodes().filter(function (n) {
            return matchesQuery(n, q);
        });
        cy.elements().addClass('faded');
        matches.removeClass('faded').addClass('highlighted');
        matches.connectedEdges().removeClass('faded');
        if (matches.length > 0) {
            cy.animate({fit: {eles: matches, padding: 60}}, {duration: 250});
        }
    };

    var reapplySearch = function () {
        if (searchInput && String(searchInput.value || '').trim() !== '') {
            applySearch();
        }
    };

    // Hide/show a whole node label or relationship type. A hidden node's edges drop out with it; edge-type
    // hiding is applied on top.
    var applyFilters = function () {
        if (!cy) {
            return;
        }
        cy.nodes().forEach(function (n) {
            var hidden = !!hiddenLabels[primaryLabel(n.data('labels'))];
            n.style('display', hidden ? 'none' : 'element');
        });
        cy.edges().forEach(function (e) {
            var hidden = !!hiddenEdgeTypes[e.data('type') || ''];
            e.style('display', hidden ? 'none' : 'element');
        });
    };

    // ---- interactive legend (doubles as the label / relationship-type filter) ----

    var presentNodeLabels = function () {
        var counts = {};
        cy.nodes().forEach(function (n) {
            var l = primaryLabel(n.data('labels'));
            counts[l] = (counts[l] || 0) + 1;
        });
        return counts;
    };

    var presentEdgeTypes = function () {
        var counts = {};
        cy.edges().forEach(function (e) {
            var t = e.data('type') || '';
            counts[t] = (counts[t] || 0) + 1;
        });
        return counts;
    };

    var buildLegend = function () {
        if (!cy) {
            return;
        }
        if (!legend) {
            legend = document.createElement('div');
            legend.id = 'graph-legend';
            document.body.appendChild(legend);
        }
        legend.innerHTML = '';

        var nodeCounts = presentNodeLabels();
        var edgeCounts = presentEdgeTypes();

        var header = function (text) {
            var h = document.createElement('div');
            h.className = 'graph-legend-header';
            h.textContent = text;
            return h;
        };

        // Node labels, in the stable palette-assignment order, restricted to what's on screen.
        var nodeLabelsPresent = labelOrder.filter(function (l) {
            return nodeCounts[l] !== undefined;
        });
        if (nodeLabelsPresent.length > 0) {
            legend.appendChild(header('Node labels'));
            nodeLabelsPresent.forEach(function (label) {
                var style = ensureLabelStyle(label);
                var row = document.createElement('div');
                row.className = 'graph-legend-row' + (hiddenLabels[label] ? ' off' : '');
                row.title = 'Click to show / hide ' + label;

                var swatch = document.createElement('span');
                swatch.className = 'graph-legend-swatch shape-' + style.shape;
                swatch.style.backgroundColor = style.color;

                var text = document.createElement('span');
                text.className = 'graph-legend-text';
                text.textContent = label + ' (' + nodeCounts[label] + ')';

                row.appendChild(swatch);
                row.appendChild(text);
                row.onclick = function () {
                    if (hiddenLabels[label]) {
                        delete hiddenLabels[label];
                    } else {
                        hiddenLabels[label] = true;
                    }
                    applyFilters();
                    buildLegend();
                };
                legend.appendChild(row);
            });
        }

        // Relationship types.
        var edgeTypesPresent = Object.keys(edgeCounts).filter(function (t) {
            return t !== '';
        }).sort();
        if (edgeTypesPresent.length > 0) {
            legend.appendChild(header('Relationships'));
            edgeTypesPresent.forEach(function (type) {
                var row = document.createElement('div');
                row.className = 'graph-legend-row' + (hiddenEdgeTypes[type] ? ' off' : '');
                row.title = 'Click to show / hide ' + type;

                var swatch = document.createElement('span');
                swatch.className = 'graph-legend-swatch graph-legend-edge';

                var text = document.createElement('span');
                text.className = 'graph-legend-text';
                text.textContent = type + ' (' + edgeCounts[type] + ')';

                row.appendChild(swatch);
                row.appendChild(text);
                row.onclick = function () {
                    if (hiddenEdgeTypes[type]) {
                        delete hiddenEdgeTypes[type];
                    } else {
                        hiddenEdgeTypes[type] = true;
                    }
                    applyFilters();
                    buildLegend();
                };
                legend.appendChild(row);
            });
        }

        legend.style.display = (nodeLabelsPresent.length > 0 || edgeTypesPresent.length > 0) ? 'block' : 'none';
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

    // ---- inspector panel: a tapped element's full properties + per-element actions ----

    var setupInspector = function () {
        inspector = document.getElementById('graph-inspector');
        if (!inspector) {
            inspector = document.createElement('div');
            inspector.id = 'graph-inspector';
            inspector.style.display = 'none';
            document.body.appendChild(inspector);
        }
    };

    var actionButton = function (label, title, onClick) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.textContent = label;
        btn.title = title;
        btn.onclick = onClick;
        return btn;
    };

    var addMetric = function (container, name, value) {
        var row = document.createElement('div');
        row.className = 'graph-inspector-prop';
        var k = document.createElement('span');
        k.className = 'graph-inspector-key';
        k.textContent = name;
        var v = document.createElement('span');
        v.className = 'graph-inspector-val';
        v.textContent = value;
        row.appendChild(k);
        row.appendChild(v);
        container.appendChild(row);
    };

    var showInspector = function (target) {
        if (!inspector) {
            return;
        }
        var data = target.data();
        var isEdge = target.isEdge();
        inspector.innerHTML = '';

        var head = document.createElement('div');
        head.className = 'graph-inspector-head';

        var title = document.createElement('div');
        title.className = 'graph-inspector-title';
        title.textContent = isEdge ? (data.type || 'relationship') : (data.label || data.id);

        var close = document.createElement('span');
        close.className = 'graph-inspector-close';
        close.textContent = '×';
        close.title = 'Close';
        close.onclick = hideInspector;

        head.appendChild(title);
        head.appendChild(close);
        inspector.appendChild(head);

        var kind = document.createElement('div');
        kind.className = 'graph-inspector-kind';
        kind.textContent = (isEdge ? 'relationship' : 'node') + '  ·  ' + (data.id || '');
        inspector.appendChild(kind);

        // Properties table (everything that isn't reserved plumbing).
        var props = document.createElement('div');
        props.className = 'graph-inspector-props';
        var any = false;
        for (var key in data) {
            if (data.hasOwnProperty(key) && !RESERVED_DATA_KEYS[key] && data[key] !== null && data[key] !== undefined) {
                any = true;
                var row = document.createElement('div');
                row.className = 'graph-inspector-prop';
                var k = document.createElement('span');
                k.className = 'graph-inspector-key';
                k.textContent = key;
                var v = document.createElement('span');
                v.className = 'graph-inspector-val';
                v.textContent = String(data[key]);
                row.appendChild(k);
                row.appendChild(v);
                props.appendChild(row);
            }
        }
        if (data.changeKind) {
            var ck = document.createElement('div');
            ck.className = 'graph-inspector-prop';
            ck.innerHTML = '<span class="graph-inspector-key">changeKind</span>'
                + '<span class="graph-inspector-val">' + escapeHtml(data.changeKind) + '</span>';
            props.appendChild(ck);
            any = true;
        }
        if (!any) {
            var none = document.createElement('div');
            none.className = 'graph-inspector-empty';
            none.textContent = 'No properties.';
            props.appendChild(none);
        }
        inspector.appendChild(props);

        // Metrics (nodes only): degree is free; pageRank/betweenness come from the cached centrality computation.
        if (!isEdge) {
            var metricsHead = document.createElement('div');
            metricsHead.className = 'graph-inspector-subhead';
            metricsHead.textContent = 'Metrics';
            inspector.appendChild(metricsHead);

            var metrics = document.createElement('div');
            metrics.className = 'graph-inspector-props';
            var c = getCentrality();
            addMetric(metrics, 'degree', String(target.degree(false)));
            addMetric(metrics, 'pageRank', c.pr.rank(target).toFixed(4));
            addMetric(metrics, 'betweenness',
                c.bc ? c.bc.betweennessNormalized(target).toFixed(4) : 'n/a (graph too large)');
            inspector.appendChild(metrics);
        }

        // Actions.
        var actions = document.createElement('div');
        actions.className = 'graph-inspector-actions';
        if (!isEdge) {
            actions.appendChild(actionButton('Expand', 'Expand this node’s neighbours into the graph', function () {
                sendExpand(data.id);
            }));
            actions.appendChild(actionButton('Focus', 'Replace the view with this node and its neighbours', function () {
                sendFocus(data.id);
            }));
            actions.appendChild(actionButton('Highlight', 'Highlight this node’s neighbourhood', function () {
                highlightNeighbourhood(target);
            }));
            actions.appendChild(actionButton('Component', 'Highlight the whole connected component', function () {
                highlightComponent(target);
            }));
            actions.appendChild(actionButton(target.locked() ? 'Unpin' : 'Pin',
                'Pin this node so re-layout leaves it anchored', function () {
                    togglePin(target);
                    showInspector(target);
                }));
            // Shortest path: arm this node as the start, or (once one is armed) find the path to it.
            actions.appendChild(actionButton('Path start', 'Set this node as the shortest-path start', function () {
                pathSource = data.id;
                showStatus('Path start set. Open another node and choose "Path to here".');
            }));
            if (pathSource && pathSource !== data.id) {
                actions.appendChild(actionButton('Path to here', 'Find the shortest path from the armed start',
                    function () {
                        computePathTo(data.id);
                    }));
            }
        }
        actions.appendChild(actionButton('Hide', 'Remove this element from the view', function () {
            target.remove();
            hideInspector();
            buildLegend();
        }));
        actions.appendChild(actionButton('Copy id', 'Copy this element’s id to the clipboard', function () {
            copyToClipboard(data.id);
        }));
        inspector.appendChild(actions);

        inspector.style.display = 'block';
    };

    var hideInspector = function () {
        if (inspector) {
            inspector.style.display = 'none';
        }
    };

    var copyToClipboard = function (text) {
        if (!text) {
            return;
        }
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(String(text));
                return;
            }
        } catch (ex) {
            // fall through to the textarea fallback
        }
        var ta = document.createElement('textarea');
        ta.value = String(text);
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        try {
            document.execCommand('copy');
        } catch (ex2) {
            // best-effort only
        }
        document.body.removeChild(ta);
    };

    var highlightNeighbourhood = function (node) {
        cy.elements().addClass('faded');
        var neighbourhood = node.closedNeighborhood();
        neighbourhood.removeClass('faded').addClass('highlighted');
    };

    var clearHighlight = function () {
        cy.elements().removeClass('faded').removeClass('highlighted');
        if (searchInput) {
            searchInput.value = '';
        }
    };

    // Ask the parent to expand this node's neighbours (all edge types, both directions) and merge them in.
    var sendExpand = function (nodeId) {
        if (nodeId) {
            stroom.select([{__stroomExpand: nodeId}]);
        }
    };

    // Ask the parent to focus on this node - fetch it and its immediate neighbours (identity-based, so it always
    // resolves) and REPLACE the view with them. Distinct from expand, which merges into the current graph.
    var sendFocus = function (nodeId) {
        if (nodeId) {
            stroom.select([{__stroomFocus: nodeId}]);
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
        assignStyles(elements);
        var fresh = [];
        for (var i = 0; i < elements.length; i++) {
            var el = elements[i];
            if (el.data && el.data.id && cy.getElementById(el.data.id).length === 0) {
                fresh.push(el);
            }
        }
        if (fresh.length > 0) {
            cy.add(fresh);
            refreshUi();
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
                    id: 'focus-node',
                    content: 'Focus on this node',
                    selector: 'node',
                    onClickFunction: function (evt) {
                        sendFocus(evt.target.data('id'));
                    }
                },
                {
                    id: 'inspect-element',
                    content: 'Inspect',
                    selector: 'node, edge',
                    onClickFunction: function (evt) {
                        showInspector(evt.target);
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
                    id: 'highlight-component',
                    content: 'Highlight component',
                    selector: 'node',
                    onClickFunction: function (evt) {
                        highlightComponent(evt.target);
                    }
                },
                {
                    id: 'pin-node',
                    content: 'Pin / Unpin',
                    selector: 'node',
                    onClickFunction: function (evt) {
                        togglePin(evt.target);
                    }
                },
                {
                    id: 'path-start',
                    content: 'Set as path start',
                    selector: 'node',
                    onClickFunction: function (evt) {
                        pathSource = evt.target.data('id');
                        showStatus('Path start set. Right-click another node → "Shortest path to here".');
                    }
                },
                {
                    id: 'path-to',
                    content: 'Shortest path to here',
                    selector: 'node',
                    onClickFunction: function (evt) {
                        computePathTo(evt.target.data('id'));
                    }
                },
                {
                    id: 'hide-element',
                    content: 'Hide',
                    selector: 'node, edge',
                    onClickFunction: function (evt) {
                        evt.target.remove();
                        buildLegend();
                    }
                },
                {
                    id: 'reset-view',
                    content: 'Reset view',
                    coreAsWell: true,
                    onClickFunction: function () {
                        clearPath();
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
            // Open the inspector locally (it has the full element data already) and relay the tap to the parent
            // for future dashboard-style selection linking (P5).
            showInspector(target);
            stroom.select([{id: data.id, kind: target.isEdge() ? 'EDGE' : 'NODE'}]);
        });
        // A tap on empty canvas closes the inspector and clears any active highlight (search,
        // neighbourhood, or shortest-path), returning the graph to its normal, fully-visible state.
        cy.on('tap', function (evt) {
            if (evt.target === cy) {
                hideInspector();
                if (searchInput) {
                    searchInput.value = '';
                }
                clearHighlight();
            }
        });
        // Auto-declutter reacts to zoom level.
        cy.on('zoom', applyDeclutter);
        buildToolbar();
        setupTooltip();
        setupInspector();
        setupContextMenu();
    };

    var refreshUi = function () {
        centrality = null; // the graph changed; recompute metrics on next request
        rebuildCaptionOptions();
        applyCaption();
        rebuildEdgeWidthOptions();
        applyEdgeWidth();
        applyNodeSizing();
        applyEdgeLabels();
        applyDeclutter();
        applyFilters();
        buildLegend();
        reapplySearch();
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
        assignStyles(elements);

        if (!cy) {
            cy = cytoscape({
                container: document.getElementById('cy'),
                elements: elements,
                style: STYLE,
                layout: {name: 'preset'}
            });
            wireInteractions();
            refreshUi();
            runLayout();
            return;
        }

        // Re-render into the existing instance. Preserve the positions of nodes that survive (matched by id) so a
        // re-run of the same query - and each time-slider tick - keeps a stable layout instead of reshuffling.
        if (tooltip) {
            tooltip.style.display = 'none';
        }
        hideInspector();

        var oldPos = {};
        cy.nodes().forEach(function (n) {
            oldPos[n.id()] = {x: n.position('x'), y: n.position('y')};
        });

        cy.elements().remove();
        cy.add(elements);
        cy.nodes().forEach(function (n) {
            if (oldPos[n.id()]) {
                n.position(oldPos[n.id()]);
            }
        });

        refreshUi();

        var fresh = cy.nodes().filter(function (n) {
            return !oldPos[n.id()];
        });
        var survivors = cy.nodes().filter(function (n) {
            return !!oldPos[n.id()];
        });

        if (survivors.length === 0) {
            runLayout();                 // an entirely new graph - lay it all out
        } else if (fresh.length === 0) {
            cy.fit(undefined, 30);       // nothing new - keep the stable layout, just re-fit
        } else {
            // Mixed: keep survivors fixed and lay out only the new nodes around them.
            try {
                survivors.lock();
                var layout = cy.layout({name: currentLayout, animate: false, padding: 30, fit: false});
                layout.one('layoutstop', function () {
                    survivors.unlock();
                    cy.fit(undefined, 30);
                });
                layout.run();
            } catch (ex) {
                survivors.unlock();
                runLayout();
            }
        }
    };

    this.setElements = function (payload, callback) {
        render(payload);
        // Fire-and-forget: do not call the callback.
    };

    this.addElements = function (payload, callback) {
        addElements(payload);
        // Fire-and-forget.
    };

    // Track the app's light/dark theme (mirrors vis.js): the class drives the sandbox chrome's colours (graph.html).
    this.setClassName = function (className, callback) {
        if (document.body) {
            document.body.className = className || '';
        }
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
        hideInspector();
        if (legend) {
            legend.style.display = 'none';
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
