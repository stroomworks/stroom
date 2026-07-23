# Implementation plan: Cytoscape.js graph view on the Graph DB Data tab

**Status:** Build plan / proposal
**Audience:** Stroom engineering (GWT client, graph query)
**Scope:** Add a **Graph** view mode to the Graph DB document's **Data** tab, rendering a `RETURN GRAPH`
result with **Cytoscape.js**, plus a handful of demonstration interactions and a **size guardrail**
(soft warning, then a hard limit + clear error) so a large subgraph can never wedge the browser.
**Dashboard integration is explicitly out of scope** (that is Surface B — see the feasibility study).
**Companion documents:**
- [`graphdb-cytoscape-visualisation.html`](graphdb-cytoscape-visualisation.html) — the feasibility & design study. **Read first**; this plan builds **§2** (what Cytoscape needs), **§3** (the element-row contract), **§5** (Surface A — the Data tab), **§7** (the one shared adapter) and **§8** (interaction & analysis).
- [`temporal-cypher-graph.html`](temporal-cypher-graph.html) — the Graph DB and `RETURN GRAPH` element output (this is the surface's data source).
- [`coding-standards.md`](coding-standards.md) — the shared, build-enforced coding standards every task follows (Checkstyle, licence header, tests, CHANGELOG). Applies to all Java and to the bundled JS.

---

## Shape of the work

The design study establishes that this is the **lower-effort of the two surfaces**: the Data tab already
runs an editable Cypher query and shows the result in a table, using the shared query UI
([`GraphDbDataPresenter`](../stroom-core-client/src/main/java/stroom/graphdb/client/presenter/GraphDbDataPresenter.java)
extends [`AbstractQueryDataPresenter`](../stroom-core-client/src/main/java/stroom/query/client/presenter/AbstractQueryDataPresenter.java),
rendering through [`QueryResultTablePresenter`](../stroom-core-client/src/main/java/stroom/query/client/presenter/QueryResultTablePresenter.java)).
The tab resolves its target graph from its **own doc-ref** (`SearchRequestSource.ownerDocRef`), so — unlike a
dashboard Query component — it needs **no `from "GraphDb"` routing prefix**. So there is **no new query path**:
the graph view is an additive *view mode* fed by the rows the tab already fetches.

The whole feature is client-side. Two things are genuinely new: (1) hosting the **Cytoscape library + the
row→elements adapter** in the GWT client (an embedding decision — the gating spike), and (2) the **size
guardrail**. Everything else is a view toggle and event wiring.

**The single load-bearing decision — how the client hosts a third-party JS graph library — is resolved by a
spike (P0) that gates the rest.** Stroom already runs untrusted visualisation JS in a **sandboxed iframe**
(`VisFrame` + `MessageSupport` + [`PostMessage`](../stroom-core-client/src/main/java/stroom/dashboard/client/vis/PostMessage.java),
loading [`ui/vis.html`](../stroom-app/src/main/resources/ui/vis.html) / `vis.js`), and also has a direct
script-injection path ([`MyScriptInjector`](../stroom-core-client/src/main/java/stroom/dashboard/client/vis/MyScriptInjector.java)).
The spike picks between them for this tab.

**Phasing at a glance:**

| Phase | Deliverable | Gates |
|---|---|---|
| **P0** | Embedding + transport decision (spike) | **Gate: no view code before this closes** |
| **P1** | Cytoscape bundled as a local UI asset (air-gap); licence recorded | P0 |
| **P2** | The row→elements adapter + Cytoscape host page | P0, P1 |
| **P3** | Data tab: Table / Graph view toggle, fed by the tab's result store | P2 |
| **P4** | **Size guardrail — soft warning, then hard limit + error** | P3 |
| **P5** | Demonstration interactions (select, tooltip, layout, highlight, path) | P3 |
| **P6** | Tests + a manual test protocol | P3–P5 |
| **P7** | User docs | P6 |

---

## P0 — Embedding + transport decision (spike, gating)

**Why first.** How Cytoscape is hosted decides the module the adapter lives in, how rows reach it, how selection
comes back, and the security posture. Freeze it before writing view code.

**The choice:**

- **Option 1 — sandboxed iframe (recommended).** Host a small `graph.html` + `cytoscape.min.js` in an iframe,
  reusing the existing `VisFrame` / `MessageSupport` / `PostMessage` transport (the same mechanism the dashboard
  vis uses, minus the dashboard's `Store`-tree data path). Rows go in over `postMessage`; a node tap comes back
  the same way. **+** Matches how Stroom already isolates third-party vis JS (CSP / same-origin sandbox), keeps
  Cytoscape out of the main GWT window, no JSInterop surface. **−** A message-contract to define; slightly more
  plumbing than a direct mount.
- **Option 2 — direct in-window mount.** Inject `cytoscape.min.js` with `MyScriptInjector` and mount Cytoscape on
  a GWT-managed `<div>`, driven by a thin JSInterop/JSNI wrapper. **+** Simplest data path (call methods directly,
  no postMessage). **−** Runs third-party JS in the main app window (weaker isolation than the vis sandbox), and
  adds a JSInterop wrapper to maintain.

**Decide and write down:**
- Iframe vs direct mount (recommendation: **iframe**, to match the established isolation posture).
- The **message/data contract** the host page receives — the element-row columns (`kind`/`id`/`labels`/`type`/
  `source`/`target`/`props[/changeKind]`, per study §3) as a compact JSON payload — and the **selection callback**
  shape (which element id / row was tapped).
- **Where the adapter lives** so it is authored once: the study's §7 point is that the row→elements mapping is
  identical to the dashboard surface. Keep it in the bundle (`graph.js`) so a future Surface B reuses the file.

**Acceptance:** a one-page written contract + a throwaway spike that renders ~10 hard-coded elements in the chosen
host and round-trips one tap event back to the presenter. No production wiring yet.

---

## P1 — Bundle Cytoscape as a local UI asset (air-gap); record the licence

**Why.** Stroom deployments are frequently **air-gapped**, so the library must be self-hosted — no CDN
(study §5). UI assets already ship under [`stroom-app/src/main/resources/ui/`](../stroom-app/src/main/resources/ui/)
(that is where `vis.js`, `markdown-preview.js` etc. live).

**Change:**
- Add `cytoscape.min.js` (and a layout extension — **fcose** or **cola** — if the built-in `cose` is not good
  enough) under `ui/` alongside a new `graph.html` / `graph.js` (the host + adapter from P0/P2).
- **Record the licence** in [`NOTICE.md`](../NOTICE.md) — Cytoscape.js is **MIT** — following the same
  third-party-licence discipline already applied to ANTLR 4 and the openCypher grammar. Note the layout extension's
  licence too (fcose/cola are MIT).
- Pin the version; note it in the CHANGELOG.

**Acceptance:** the library loads from the local origin with the network blocked; `NOTICE.md` lists Cytoscape.js
(+ any extension) with version and MIT licence.

---

## P2 — The row→elements adapter + Cytoscape host page

**Files (new):** `ui/graph.html`, `ui/graph.js` (or a `ScriptDoc`-style asset per the P0 decision).

**Change** — implement the study's **row→elements adapter** (§3), the single reshape both surfaces would share:
- Split rows by `kind` (or infer: a row with `source`+`target` is an edge, else a node).
- **De-duplicate node rows by `id`** (one node even when it appears on many edges).
- Build edges from `source`/`target`, giving each a stable `data.id` (e.g. `source|type|target`).
- Map remaining columns to `data.<prop>` for **style mapping / tooltips**; map `changeKind` (diff mode) to a
  per-element style class (`ADDED`/`REMOVED`/`MODIFIED`/`UNCHANGED`).
- Supply **no positions** — run a client-side **layout** (fcose/cola/dagre) and `cy.fit()`.

**Acceptance:** given the study's worked-example rows, the adapter produces the documented 5 nodes / 3 edges with
`U1` de-duplicated; a diff result colours elements by `changeKind`; unit-tested in isolation (P6).

---

## P3 — Data tab: a Table / Graph view toggle

**Files:**
- [`GraphDbDataPresenter`](../stroom-core-client/src/main/java/stroom/graphdb/client/presenter/GraphDbDataPresenter.java)
  and its `GraphDbDataView`.
- [`AbstractQueryDataPresenter`](../stroom-core-client/src/main/java/stroom/query/client/presenter/AbstractQueryDataPresenter.java)
  — `QueryModel`, the registered `TABLE_COMPONENT_ID` result component, `QueryDataView`.
- A new `GraphResultPresenter` (the client-side widget wrapping the P2 host).

**Change:**
- Add a **Table / Graph toggle** to the Data view. The **Table view is unchanged**. Optionally **auto-select
  Graph** when the query text is a `RETURN GRAPH` (and Table otherwise).
- **Feed the graph view from the tab's existing result store** — no new query. Two options, decide in review:
  (a) register the graph presenter as a **second result component** on the `QueryModel` alongside the table (both
  consume the same search's rows); or (b) have the graph presenter read the **table presenter's already-fetched
  rows** on toggle. Prefer (a) for a clean data path if the result-component API allows two consumers of one
  component id; else (b).
- On new results (and on toggle to Graph), hand the element rows to the P2 adapter and render; on `resize`,
  `cy.resize()`/`cy.fit()`.
- **Non-`RETURN GRAPH` results in the Graph view:** show a neutral "this result isn't a graph shape — switch to
  Table, or use `RETURN GRAPH`" message rather than an empty canvas. (Column-mapping / Option C is **out of scope**
  here — see study §4.)

**Acceptance:** running the default/`RETURN GRAPH` query and toggling to Graph renders the subgraph from the tab's
own result store; toggling back shows the unchanged table; a scalar result in Graph view shows the guidance message,
not a blank pane; the tab still resolves its graph from `ownerDocRef` (no `FROM` needed).

---

## P4 — Size guardrail: soft warning, then hard limit + error

**Why (explicit requirement).** The canvas renderer and force layouts degrade past a few thousand elements
(study §8, §10). A big subgraph must **warn**, and beyond a hard ceiling must **refuse to render with a clear
error** — never silently hang the browser or OOM the tab.

**Two layers, both surfaced (never a silent truncation):**

1. **Server / query lever (already present):** the analyst's `LIMIT` and the tab's max-result cap bound how many
   element rows come back. The guardrail's error text should point here ("add or lower `LIMIT`, or narrow the
   `MATCH`").
2. **Client render cap (new, the backstop):** before layout, the adapter counts **nodes + edges** (known from the
   rows without rendering) and applies two thresholds:
   - **Soft warning** (default ~**1,000** elements): render, but show a **non-blocking banner** — "Showing N nodes
     / M edges; large graphs may be slow to lay out." Offer a "render anyway / stop" affordance if layout is the
     cost.
   - **Hard limit** (default ~**5,000** elements): **do not render.** Show a blocking **error** with the counts and
     remediation (lower `LIMIT`, narrow the pattern, or use a more selective anchor). This mirrors the graph-side
     join guardrail's "surfaced, not silently truncated" contract.

**Files:** the P2 adapter (counting + threshold check), the `GraphResultPresenter` / view (banner + error surface),
and a source for the thresholds — a `UiConfig` graph-view section (so ops can tune), defaulting in the client if
absent. (Per-doc caps could later move into the Graph DB **settings tab** — see `graphdb-settings-surface.html` —
but a global default is enough here.)

**Acceptance:** a result just over the soft threshold renders with the warning banner; a result over the hard limit
shows the error with the element counts and remediation and renders **nothing**; both thresholds are configurable;
neither path throws or hangs. Include a test with a synthetic oversized element set.

---

## P5 — Demonstration interactions

**Why.** Show the library earns its place (study §8). All of these are **pure client-side** over the loaded
subgraph — no server round-trip — so they are safe demonstrations.

**Interactions (built-in Cytoscape core):**
- **Pan / zoom / drag** and a **Fit / reset** button (core viewport API).
- **Tap-to-select** → highlight the element and open its **properties** (the `data` map) in a side panel; wire the
  tap back through the P0 selection callback so the presenter knows the selected element/row.
- **Hover tooltip** showing the element's label/type + key properties.
- **Layout picker** (fcose / cola / concentric / breadthfirst) — re-run layout on change, then `cy.fit()`.
- **Neighbourhood highlight** — on node tap, dim the graph and highlight the tapped node's `neighborhood()` (a core
  traversal call), to show local structure.

**Analysis demonstrations (built-in graph-theory API, study §8):**
- **Shortest path between two selected nodes** — select two nodes, run `eles.dijkstra()` / `aStar()` and highlight
  the path.
- *(optional)* **Degree-based sizing** — size nodes by `degree()` to surface hubs at a glance.

**Stretch (note as future, not built here): expand-on-demand** — tapping a node issues a *new* Cypher query for its
neighbours and adds them to the graph (study §8's vis↔engine bridge). On the Data tab this means re-running a
scoped Cypher against the tab's own graph; call it out as the natural next step but keep it out of this plan to
avoid a per-interaction query path.

**Acceptance:** each listed interaction works on a rendered subgraph; selection drives the properties panel; the
shortest-path demo highlights a correct path on a known fixture; nothing here calls the server.

---

## P6 — Tests + manual test protocol

- **Adapter unit tests (JS):** row sets → expected `{nodes, edges}`, covering node de-dup, edge-id construction,
  `changeKind` styling, and the **guardrail counts** (soft/hard boundaries). This is the highest-value automated
  layer — the adapter is plain, testable JS.
- **Presenter logic:** view-toggle state, auto-select on `RETURN GRAPH`, the scalar-in-Graph-view guidance path,
  and the error/warning surfacing (GWT client testing is limited — cover what is unit-testable and put the rest in
  the manual protocol).
- **Manual protocol:** empty graph (0 rows) → empty-but-clean canvas; small subgraph → renders + all P5
  interactions; just-over-soft → banner; over-hard → error, no render; diff result → `changeKind` colours;
  air-gap load (network blocked) → library still loads; resize/fit behaviour.

**Acceptance:** the adapter + guardrail have automated tests; the manual protocol is written and runs green on a
dev instance.

---

## P7 — User docs

- A short "Visualising a graph on the Data tab" section: the Table/Graph toggle, `RETURN GRAPH` vs scalar, the
  interactions available, and — importantly — the **size limits** (the warning, the hard cap, and how to stay under
  it with `LIMIT` / a tighter `MATCH`).
- Fold a pointer into the feasibility study (`graphdb-cytoscape-visualisation.html` §5) noting the surface is built.

---

## Dependencies & sequencing

- **P0 gates everything** — the embedding/transport decision determines the module layout for P2–P3.
- **P1 → P2 → P3** is the critical path; **P4 (guardrail)** and **P5 (interactions)** both hang off P3 and are
  independently schedulable; **P4 is the priority of the two** (it is the safety requirement).
- No server or grammar work is required — the tab's query path already produces `RETURN GRAPH` element rows.

## Risks

In order: the **embedding choice** (P0 — isolation posture and JSInterop/message surface); **library size &
air-gap** loading (P1); the **oversized-graph** failure mode (P4 — the explicit guardrail); **adapter/UI JS
duplication** with a future dashboard surface (study §7 / the review's `assembleRow` divergence warning — mitigate
by authoring the adapter once in the shared bundle); and **layout performance** on mid-size graphs (P4 soft warning
+ layout choice).

## Out of scope

- **Dashboard integration (Surface B)** — the `Visualisation` doc + asset bundle + `vis.html` iframe + the
  `from "GraphDb"` routing prerequisite. This plan is the Data tab only.
- **Option C column-mapping** (driving the graph from an arbitrary scalar/joined value table) and **Option E**
  (entity-valued cells) — study §4; both are separate features.
- **Expand-on-demand** (a query per interaction) — noted as the P5 stretch/next step, not built here.
- **Server-side graph analytics** — Cytoscape's analysis runs client-side on the loaded subgraph only (study §8);
  whole-graph algorithms belong in the engine and are a separate concern.

---

*This document is a build plan for a proposal, not a commitment. The P0 embedding spike is the piece to prototype
first; P4's guardrail is the non-negotiable safety requirement; the rest is a view toggle and event wiring over a
query path that already exists.*
