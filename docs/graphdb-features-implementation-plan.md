# Implementation plan: Graph DB feature completion (for a Sonnet implementer)

**Status:** Build plan / proposal
**Audience:** the implementing agent (Claude Sonnet) and its reviewers
**Scope:** The remaining Graph DB feature work discussed in the design set, **excluding the diff operator's delta-table output, which is already done.** Four workstreams: (A) portable `from "GraphDb"` routing, (B) the Graph DB configuration/settings tab, (C) a Cypher sub-query as a StroomQL join side, and (D) the `RETURN GRAPH` element-row output plus Cytoscape.js rendering.
**Companion documents (read the relevant one before starting a workstream):**
- [`graphdb-index.html`](graphdb/archive/graphdb-index.html) — index of the whole set.
- [`graphdb-stroomql-join.html`](graphdb-stroomql-join.html) + [`graphdb-stroomql-join-implementation-plan.md`](graphdb-stroomql-join-implementation-plan.md) — Workstream C detail.
- [`graphdb-settings-surface.html`](graphdb-settings-surface.html) — Workstream B rationale (tiers, altitude).
- [`graphdb-cytoscape-visualisation.html`](graphdb-cytoscape-visualisation.html) — Workstream D detail.
- [`cypher-from-clause-implementation-plan.md`](cypher-from-clause-implementation-plan.md) — Workstream A detail.
- [`temporal-cypher-diff-operator.md`](temporal-cypher-diff-operator.md) — the delta-table diff (done); `RETURN GRAPH` was its deferred D3b.
- **Verification:** [`query-graphdb-test-protocol.md`](query-graphdb-test-protocol.md) — the end-to-end test protocol every workstream must pass (Phase Z).

---

## How to use this plan (for the implementing agent)

- **Work one task at a time, in order within a workstream.** Each task states the files to touch, the change, and an **acceptance check**. Do not start the next task until the current one's acceptance check passes.
- **After every task, build and run the affected module's tests** (e.g. `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`). Never leave the tree with failing tests or a broken build.
- **Match the surrounding code.** Copy the style, naming, licence header, and patterns of the neighbouring classes named in each task. Prefer extending an existing class to inventing a new one.
- **Do not regress existing behaviour.** The diff delta-table output, the shipped Cypher subset, and StroomQL two-source joins all have tests — keep them green.
- **Confirm assumptions before large edits.** If a task's stated file/method has moved or a precondition is false, stop and report rather than guessing.
- **No new external network dependencies.** Third-party JS (Cytoscape) is bundled locally (Workstream D). Stroom deployments are frequently air-gapped.
- **When a workstream finishes, run its slice of the test protocol** (the protocol tags each test with the workstream it covers).

### Coding standards & conventions

**Follow the shared [coding standards](coding-standards.md)** — the repo's build-enforced conventions (Checkstyle at `severity=error`: line length ≤ 120, import order, braces, naming; the Apache-2.0 header; `final`/`@Nullable`/builder/record idioms; JUnit 5 tests; the GWT MVP pattern for client tasks; the CHANGELOG entry). **Every task's acceptance implicitly includes a clean `./gradlew clean build`** (which runs Checkstyle), on top of the task's own check below.

### Dependency graph & recommended order

```
B (settings)        ── independent
A (from "GraphDb")  ── independent; UNBLOCKS the dashboard surfaces
C (join)            ── core independent; C's dashboard use needs A
D (RETURN GRAPH +   ── D2 (element output) independent;
   Cytoscape)          Data-tab view needs D2; dashboard vis needs D2 + A
```

**Recommended order:** **B** (self-contained, low risk) → **A** (unblocks both dashboards) → **C** → **D**. B, and the first task of A, can proceed in parallel if split across agents. Each workstream is independently shippable.

---

## Workstream A — portable `from "GraphDb"` routing

**Goal:** the same Cypher text can run from any text-driven surface (Query doc, `/csv/search`, MCP, embedded dashboard), not only via `SearchRequestSource.ownerDocRef`. This is the prerequisite for running Cypher in a dashboard Query component (needed by C's and D's dashboard uses).

**This workstream is fully specified in [`cypher-from-clause-implementation-plan.md`](cypher-from-clause-implementation-plan.md); follow its phases.** Summary of the seam:

| Task | Files | Change | Acceptance |
|---|---|---|---|
| A1 | Grammar/extractor in `stroom-query-grammar`; `QueryServiceImpl.mapRequest` (`stroom-query/stroom-query-impl/.../QueryServiceImpl.java`) | Extract a leading `from "X"` name from the query text before grammar selection | The leading source name is parsed out for both StroomQL and Cypher text |
| A2 | `DataSourceResolver` (`stroom-query/stroom-query-common/.../language/DataSourceResolver.java`) | Resolve the extracted name → typed `DocRef` | A `GraphDb`-typed name resolves to its `DocRef` |
| A3 | `AlternativeQueryCompilerResolver`, `GraphCypherQueryCompiler` (`stroom-graphdb-impl`) | Dispatch by the **resolved DocRef's type** (GraphDb → Cypher; else StroomQL) instead of relying on `ownerDocRef` | A Cypher query prefixed `from "MyGraph"` runs from a generic surface with no `ownerDocRef` |

**Acceptance (workstream):** Test protocol test **N‑1** passes — a Cypher query runs from `mcp__stroom__csvQuery` / a Query doc using only the `from "GraphDb"` prefix.

---

## Workstream B — the Graph DB configuration surface (Tier‑1 settings tab)

**Goal:** give the three graph-level settings that already exist on the model — `retention`, `temporalPrecision`, `nodeTypeMappings` — a UI editor. See [`graphdb-settings-surface.html`](graphdb-settings-surface.html) for why these three and nothing lower.

**Key facts:**
- [`GraphDbDoc`](../stroom-core-shared/src/main/java/stroom/graphdb/shared/GraphDbDoc.java) already models `description`, `temporalPrecision`, `nodeTypeMappings`, `retention` (builder + serialiser round-trip already exist).
- [`GraphDbPresenter`](../stroom-core-client/src/main/java/stroom/graphdb/client/presenter/GraphDbPresenter.java) is the tabbed editor, mirroring `stroom.sqlstore.client.presenter`. A previous Settings tab was removed **because it re-bound `description`** (which the Documentation tab owns) — do not repeat that.
- [`GraphStores.deleteOldData`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphStores.java) already reads `doc.getRetention()` and applies it across the sub-stores.

| Task | Files | Change | Acceptance |
|---|---|---|---|
| B1 | New `GraphDbSettingsPresenter` + `View` in `stroom-core-client/.../graphdb/client/presenter/` (+ view impl + Gin binding in `GraphDbModule`) | Build a settings pane binding **`retention`** (enabled / duration / use-state-time — reuse the Plan B `RetentionSettings` editor pattern), **`temporalPrecision`** (a dropdown over `TemporalPrecision.ORDERED_LIST`), and **`nodeTypeMappings`** (a list editor). **Do NOT bind `description`.** | The pane reads and writes exactly those three fields. |
| B2 | `GraphDbPresenter` | Register the settings tab as the first tab (before Data / Documentation / Permissions), using the same `DocTabProvider` mechanism as the SQL store editor. | The Graph DB editor shows a Settings tab; visiting it does not clobber `description`. |
| B3 | Round-trip test (client test or a `GraphDbDoc` serialisation test under `stroom-core-shared` / the presenter test pattern) | Set each field, save, reopen; assert persisted values; assert `description` unaffected when the Settings tab is opened and saved. | Values persist; no field clash. |
| B4 | `GraphStores.open`/`provision`/`rebuild` (`stroom-graphdb-impl`) | Verify `temporalPrecision` and `nodeTypeMappings` are honoured at provision/rebuild (retention already is). If precision only takes effect on rebuild, ensure the doc/UI treats a precision change as rebuild-implying. | Provisioning a graph with a non-default precision/mapping uses them; changing precision is flagged as needing a rebuild. |

**Acceptance (workstream):** Test protocol test **N‑2** passes.

**Out of scope (Tier‑2, do not build now):** a storage-size budget, condense policy, snapshot behaviour — see the settings-surface doc.

---

## Workstream C — a Cypher sub-query as a StroomQL join side

**Goal:** let a StroomQL `join` take a bracketed Cypher sub-query (over a Graph DB) as one side, e.g.

```
from "AuthEvents" as e
inner join ( from "CorpGraph" match (u:User)-[:MEMBER_OF]->(g:Group) return u.id as userId, g.name as groupName ) as ident
  on e.user = ident.userId
select e.time, e.user, ident.groupName
```

**This workstream is fully specified in [`graphdb-stroomql-join-implementation-plan.md`](graphdb-stroomql-join-implementation-plan.md); follow its phases P0–P6.** Condensed task list with the confirmed seams:

| Task | Files | Change | Acceptance |
|---|---|---|---|
| C0 | (spike/doc) | Freeze the **schema-from-`RETURN`** contract: `AS` aliases mandatory; type inference policy; reject no-scalar-schema returns (bare `RETURN n`). **Gate: no grammar code first.** | Written contract mapping example `RETURN` lists → `(name,type)` columns. |
| C1 | `StroomQL.g4`; `AstJoin` (`stroom-query-grammar/.../ast/AstJoin.java`, whose `source` is today a bare `AstToken`); `AstBuilder` | Allow a join source to be a parenthesised sub-query; add a sealed name-or-subquery join-source AST; require an alias on a sub-query side. | The example query parses into an `AstJoin` carrying a sub-query source. |
| C2 | `Binder` (`stroom-query-planner/.../bind/Binder.java`) | Derive the graph side's columns from its Cypher `RETURN … AS` (reuse `CypherCompiler.buildResultRequests`' column extraction) and bind `alias.field` against them; reuse `validateDomainTypeCompatibility`. | `ident.userId`/`ident.groupName` bind; a missing column is a positioned error. |
| C3 | `OptimisingQueryCompiler` (`stroom-query-common/.../language/OptimisingQueryCompiler.java`) — `createJoin`, `compileJoinSide` | Emit the graph side as a sub-`SearchRequest` carrying a **`GraphSpec`** (not a synthesised `from "…" select *`); relax `createJoin`'s "plain Scan per side" check to admit a graph sub-query operand; keep the single-join/two-source limit. `JoinSpec` already accepts any compiled `SearchRequest` as a side. | Compiling the example yields a `StroomQLJoin` request whose side is a `GraphSpec` request of type `GraphDb`. |
| C4 | `JoinSearchProvider` (`stroom-search/stroom-searchable-impl/.../JoinSearchProvider.java`) — verification | Confirm `openSide` routes the graph side by `DocRef.getType()` and the streaming hash-join / `LEFT` null-padding / cross-side `where` work over its `Val[]`. Add a graph-side result cap (surfaced, not silent). | INNER/LEFT joins with a graph side return correct rows; oversized graph side surfaces a limit. |
| C5 | Tests under `stroom-searchable-impl` + `stroom-query-planner` | INNER/LEFT with a graph side; schema-from-`RETURN`; temporal `AS OF` inside the side; guardrail. | Tests green. |

**Acceptance (workstream):** Test protocol test **N‑3** passes.

---

## Workstream D — `RETURN GRAPH` element output + Cytoscape rendering

**Goal:** (D2) add a whole-element output mode so a query returns a **single element-row table** (each row a node or edge with identity/labels/props and, for edges, `source`/`target`; in a diff, a `changeKind`); then render it with Cytoscape.js (D4) on the Data tab and (D5) as a dashboard visualisation. See [`graphdb-cytoscape-visualisation.html`](graphdb-cytoscape-visualisation.html).

**Key facts:**
- Today `RETURN` items must be scalar `variable.property`; whole-node/`RETURN *`/`RETURN path` are rejected ([`CypherToLogicalPlan`](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/CypherToLogicalPlan.java)). `RETURN GRAPH` is **net-new** (it was the diff doc's deferred D3b).
- Element identity + property comparison already exist: [`DiffOperator`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/DiffOperator.java) works on identity tuples + `flatRow()`; reuse that identity notion for element ids.
- The engine emits `Val[]` into coprocessors ([`GraphTraversalEngine`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java)); result columns are derived in [`CypherCompiler`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/CypherCompiler.java) (`buildResultRequests`).
- Dashboard vis framework (from research): `VisualisationDoc` (`functionName`/`scriptRef`/`settings`), a same-origin `ui/vis.html`+`vis.js` iframe, the `setData(context,settings,data)` contract, table-shaped data via `VisJson`→`VisResult`, and an **asset-bundle** path (`/assets/{uuid}/index.html` served by `DocumentAssetServlet`) — the recommended home for a bundled `cytoscape.min.js`.

| Task | Files | Change | Acceptance |
|---|---|---|---|
| D1 | `Cypher.g4`; a `RETURN GRAPH` AST form (`stroom-query-grammar/.../ast/cypher/`) | Add the `RETURN GRAPH` return form (and `DIFF … RETURN GRAPH`). | `RETURN GRAPH` parses; scalar `RETURN` unaffected. |
| D2 | `CypherToLogicalPlan`, `CompiledCypherPlan`, `GraphTraversalEngine`, `DiffOperator` | Emit the **element-row output**: the de-duplicated union of matched nodes and edges as `Val[]` rows with columns `kind, id, labels, source, target, <props…>` (+ `changeKind` when under DIFF). Reuse the diff identity/merge for ids and dedupe. | A `RETURN GRAPH` query returns one row per distinct node and edge; a `DIFF … RETURN GRAPH` adds `changeKind`; `UNCHANGED` elements are included (context). |
| D3 | `CypherCompiler.buildResultRequests` | Produce the fixed element-row column schema for a `RETURN GRAPH` query so downstream table/vis consumers see stable columns. | The result advertises the element-row columns. |
| D4 | `GraphDbDataPresenter` (+ a new Cytoscape client widget/presenter; bundle `cytoscape.min.js` as a client asset) | Add a **Table / Graph** view toggle to the Data tab; the Graph view feeds the tab's existing element-row result store into a Cytoscape instance via a **row→elements adapter** (split by `kind`, dedupe nodes by `id`, build edges from `source`/`target`, style by `changeKind`). No new query path; no `FROM` needed (uses `ownerDocRef`). | A `RETURN GRAPH` query on the Data tab renders a graph; the Table view still works. |
| D5 | A `VisualisationDoc` + asset bundle (`index.html` + `cytoscape.min.js` + a layout ext), settings schema, and the shared adapter | Create a Cytoscape **dashboard visualisation**: settings `tabs[].controls[]` field-map element-row columns → `id`/`source`/`target`/`label` roles + limit controls; a `data.structure` projecting a flat element list; `index.html` implements the `element`/`setData`/`resize` contract and reuses the **same** row→elements adapter as D4; wire `stroom.select(...)` for node-tap selection. **Depends on A** (Cypher from a dashboard Query). | A dashboard vis renders the graph from a Cypher Query→Table; tapping a node drives dashboard selection. |
| D6 | Tests: engine (`stroom-graphdb-impl`, e.g. `TestGraphTraversalEngine`) for D2/D3; a client/adapter test for the row→elements mapping | Element-row union/dedup/connectivity; `changeKind` styling data present; adapter maps rows→elements correctly. | Tests green. |

**Acceptance (workstream):** Test protocol tests **N‑4, N‑5, N‑6** pass. (D4 needs only D2; D5 also needs A.)

---

## Phase Z — run the full test protocol

After the workstreams land, execute [`query-graphdb-test-protocol.md`](query-graphdb-test-protocol.md) end-to-end against a running Stroom (the query optimiser mode flag set to `ON`). It loads the shared **CorpNet** test data once and exercises the new query system, the full shipped Graph DB Cypher surface (including the already-done diff), and every feature added by workstreams A–D. All tests must pass (or a deviation must be recorded with justification).

## Risks (cross-workstream)

- **D2 is the critical new capability** — element serialisation gates all of Cytoscape; freeze the element-row schema (D2/D3) before building the adapters (D4/D5) so both consume one stable shape.
- **C's schema-from-`RETURN` contract (C0)** is load-bearing; do it first.
- **Dashboards depend on A** — don't attempt C's or D5's dashboard paths before A lands.
- **Bundling** — Cytoscape must be a local asset (client asset for D4, document asset for D5); no CDN.

---

*This is a build plan for a proposal, not a commitment. It is written to be executed task-by-task by an implementing agent; each task carries its own acceptance check, and Phase Z is the end-to-end gate.*
