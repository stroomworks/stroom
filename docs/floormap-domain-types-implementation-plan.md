# Bringing Domain Types to the Floor Map — Implementation Plan

**Status:** Decisions ratified · 2026-07-20 · branch `enterprise-floor-mapping-editor`
**Source proposal:** [`floormap-domain-types.html`](floormap-domain-types.html) (ideas catalogue, ~20 ideas / 7 themes, 2026-07-17)
**Builds on:** the shipped fact model, the Domain Types module (`stroom-domaintype`) and the existing dashboard "Jump to" mechanism
**Companion plan:** [`floormap-layers-implementation-plan.md`](floormap-layers-implementation-plan.md)
**Program plan (decisions & sequencing):** [`floormap-program-plan.md`](floormap-program-plan.md)

## Goal

Give a map object a **meaning**, not just a look. A fact's `type` is a free-text label that only drives
drawing; a domain type (`Host.ipaddress`, `Asset.location`, wildcards `*.location`) is a semantic tag
Stroom already understands. Connecting them makes an object a jump **destination**, a jump **origin**,
and a **consistently styled** thing — reusing mechanisms that already ship.

## Ratified decisions (from the program plan)

- **Additive** — an object gains a domain type *alongside* its free-text `type`; no migration.
- **v1 = Tier 1** (jump destination + fact-list "Jump to" + pickers); **Tier 2 is a fast-follow**;
  Tier 3 later.
- **Minimal jump-plumbing generalisation now (WS-G0)** — an interface + the floor-map store, not the
  full shared registry (that's Tier 3, G19).
- **Styling lands on `DomainTypeDoc` at Theme C** (it has no style field today); the swatch/legend it
  feeds are **Layers-owned** components (see the companion plan).
- **Vocabulary ownership** — domain types are owned **per Content Pack via its `DomainTypeDoc`**.

### Explicitly out of scope
- **Consistency of styling across domain types** — out of scope.
- **Consistency between content packs** — a future problem, not addressed here.

## Where we're starting from (grounding)

The reusable "Jump to" machinery already exists — but it is **dashboard-only**, and the FloorMap has
**zero** domain-type integration today (grep of `floormap` for `domaintype|findByType|jumpTo` finds
nothing). The keystone work is generalising the dashboard-specific plumbing.

- **A domain type is a `classPart.attributePart` tag with wildcard matching.**
  [`DomainType.canAccept(other)`](../stroom-core-shared/src/main/java/stroom/domaintype/shared/DomainType.java:88)
  (`*` or exact match on each part). The declared side is the pattern (`Host.*` accepts
  `Host.ipaddress`), the value's type is the argument.
- **Columns carry a domain type as a `String`.** [`Column.domainType`](../stroom-query/stroom-query-api/src/main/java/stroom/query/api/Column.java:64),
  [`QueryField`](../stroom-query/stroom-query-api/src/main/java/stroom/query/api/datasource/QueryField.java:61),
  `IndexField.getDomainType()`, and the grid's `ColSettings.domainType`.
- **"Jump to" is one reusable class.**
  [`MyDataGridDomainTypeSupportImpl`](../stroom-core-client/src/main/java/stroom/data/grid/client/MyDataGridDomainTypeSupportImpl.java):
  `createContextMenu(row,col)` reads the column's domain type → `DashboardResource.findByType(...)` →
  builds a **"Jump to ▸ <dashboards>"** submenu; `jumpTo(...)` emits `paramName=cellValue` for every
  domain-typed column plus `timeRange.from/to`, then fires `OpenDocumentEvent` →
  `setParamsFromLink(params)`.
- **Dashboards declare handled types via `DashboardDoc.domainTypes`** (`List<DomainType>`), edited in
  `DashboardSettingsPresenter` (~173) using
  [`AddDomainTypePresenter`](../stroom-core-client/src/main/java/stroom/dashboard/client/main/AddDomainTypePresenter.java).
  Destination lookup is a **live scan, no registry**: `DashboardStoreImpl.findByType(...)` (~209).
- **Two hard-wirings block reuse.** `MyDataGridDomainTypeSupportImpl` hard-codes `DASHBOARD_RESOURCE`
  (destinations) and a `DashboardContext` supplier (time range + `setParamsFromLink` callback).
- **No per-domain-type styling exists.**
  [`DomainTypeDoc`](../stroom-core-shared/src/main/java/stroom/domaintype/shared/DomainTypeDoc.java)
  holds only `description` + a `domainTypes` list — no icon, colour, or handler. FloorMap's own
  `TypeStyle` is keyed on the **free-text fact `type`**, not a `DomainType`.
- **A `Fact` does not carry field values or a domain type.**
  [`Fact`](../stroom-core-shared/src/main/java/stroom/floormap/shared/Fact.java) has key/type/image/
  matrix/position only; raw values live in the underlying `TemporalEntry`, read via the
  [value schema](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapFieldMapping.java).
  `MapContextMenuEvent` carries only `objectId` + coords, so object-level "Jump to" must look the
  fact/entry up by id.
- **`decorateDocRef` is unrelated.** It resolves a partial `DocRef` into a full one; nothing to do with
  domain-type actions. Do **not** conflate.

## Roadmap (the proposal's three tiers)

| Tier | Ideas | Milestone(s) |
|------|-------|--------------|
| **Tier 1 (v1)** — reuse existing plumbing | WS-G0 generalise jump lookup; B6 map tables get "Jump to"; D12 standard pickers; A1·A2 maps register & become destinations; D14 validate | M1–M2 |
| **Tier 2 (fast-follow)** — new canvas UI | B4·B5 jump/act from an object & tooltip; C8·C9 style by meaning; D13 auto-detect identity/location columns; C10·C11 smarter Discover & semantic legend | M3 |
| **Tier 3 (later)** — deeper & cross-cutting | E15 bind by meaning; E16 map-to-map drill-down; F17·F18 entity annotations & where-used; B7·G19·G20 cross-highlight, registry, deps | M4 |

---

## Tier 1 (v1) — Quick wins

### Workstreams
- **WS-G0 — Generalise the destination lookup & jump context** *(prereq for A/B)*. Introduce a
  doc-type-agnostic "who handles this domain type?" lookup (a new floor-map endpoint or a small
  cross-doc service scanning stores that declare `domainTypes`), returning `DocRef`s tagged with their
  doc type. Extract the `DashboardContext` / `DashboardSuperPresenter` binding in
  `MyDataGridDomainTypeSupportImpl` behind a small interface (raw time range + a param-delivery
  callback). Keep the param encoding (`convertToParamName`, `escape`, `timeRange.from/to`) intact —
  it's the wire contract with `setParamsFromLink`. *Seed of G19, not the full registry.*
- **WS-B6 — Map tables get the grid "Jump to".** Wire the support into the Fact List grid
  (`FloorMapFactListPresenter`, `MyDataGrid<FactObject>`, ~81) and the events-query results grid, as
  `TablePresenter` (~237) does, using WS-G0's generalised context. Precondition: columns carry a
  `domainType` in `ColSettings` (via `addResizableColumn(..., domainType)`, ~1017).
- **WS-D12 — Reuse the standard class/attribute pickers.** Add an optional `domainType` to
  [`FloorMapFieldMapping`](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapFieldMapping.java)
  (NON_NULL, back-compat), edited with `AddDomainTypePresenter` (drawing vocab from
  `DomainTypeClient.fetchClassParts` / `fetchAttributeParts`). This gives the Fact List columns and
  object jumps their per-field domain type.
- **WS-A1·A2 — Maps register as jump destinations.** Add `domainTypes` (`List<DomainType>`) to
  `FloorMapDoc`, mirroring `DashboardDoc.domainTypes`, edited in Settings with `AddDomainTypePresenter`.
  Register the floor-map store in WS-G0's lookup. **A3 (land at the right moment):** on open, apply the
  incoming `timeRange.from/to` to the scrubber (seed `currentTime`/range from link params) and
  select/frame the fact whose domain-typed value matches.
- **WS-D14 — Validate against the catalogue.** Warn at authoring time when a mapped/declared domain
  type isn't in the `DomainTypeDoc` catalogue or when no handler accepts it (a dead-end link), reusing
  the catalogue scans + WS-G0 lookup.

### UI visible at the end of Tier 1
- Right-clicking a **domain-typed cell anywhere in Stroom** (a dashboard/query grid) now lists **floor
  maps** — not just dashboards — under **"Jump to ▸ …"**; picking one opens the map focused on the
  matching object at the source's time range.
- The floor map's own **Fact List** and **events-query grid** show the same **"Jump to ▸ …"** on
  right-click of a domain-typed cell.
- The **Settings tab** gains: a **domain type** picker per value-schema field, and a **"Handled domain
  types"** editor (the map declares what it answers for) — both using the standard class/attribute
  pickers.
- A **validation hint** flags a domain type that no destination handles.

### How to test Tier 1
- **Unit (`stroom-core-shared`):** extend `TestDomainType` wildcard cases; the cross-doc
  "who-handles-this-type?" resolver (floor map matched by `canAccept`); `FloorMapDoc.domainTypes` and
  `FloorMapFieldMapping.domainType` round-trips (legacy back-compat); param-encoding round-trips against
  `ParamUtil.parse`. Run `./gradlew :stroom-core-shared:test`.
- **Manual (running app):**
  1. Author a floor map, declare it handles e.g. `*.location`; map a value-schema field to
     `Asset.location`; save.
  2. On a dashboard with an `Asset.location`-typed column, right-click a cell → **Jump to** lists the
     map → it opens focused on the matching room at the cell's time range.
  3. On the map's Fact List / events grid, right-click a domain-typed cell → **Jump to** offers the
     dashboards/maps that handle it.
  4. Declare a type nothing handles → the validation hint appears.
  5. Legacy map with no `domainTypes` still loads and renders.

---

## Tier 2 (fast-follow) — Medium (new canvas interaction)

### Workstreams
- **WS-B4·B5 — Jump & act from an object / actionable tooltip.** Append a **"Jump to ▸ …"** submenu to
  the canvas right-click (`FloorMapEditorPresenter.showCanvasContextMenu`, ~962), sourced from the
  fact's own domain-typed values (look the fact/entry up by `objectId`, read values via the value
  schema, pair each with its field `domainType` from WS-D12). Render the tooltip
  (`FloorMapDoc.template`) attributes as live actions (jump / annotate / copy).
- **WS-C8·C9 — Style objects by meaning.** Add a **style (icon + colour) field to `DomainTypeDoc`**
  (none today) with class-level default + attribute-level override; resolve an object's look from its
  domain type and **reuse the Layers-owned swatch/legend** so the same real-world thing looks the same
  on map, grid and dashboard. **Precompute** the domain-type → style resolution and cache it for the
  render loop (never per frame). *Consistency of styling across domain types is out of scope.*
- **WS-D13 — Auto-detect identity & location columns.** Infer `FloorMapDoc.entityIdColumn` /
  `locationIdColumn` (~155 / ~164) from the events-query columns' `Column.domainType`, offered as an
  overridable default.
- **WS-C10·C11 — Smarter Discover & semantic legend.** The Layers panel's Discover pre-assigns a new
  type's icon/colour from its domain type (C10); the panel's legend/filter toggles/spotlights by
  meaning (C11) — the **same surface** the Layers plan builds, shared.

### UI visible at the end of Tier 2
- Right-click a **map object** → **"Jump to ▸ …"** carrying that object's values; the **tooltip** shows
  clickable actions (jump / annotate / copy) instead of plain text.
- The **same real-world thing shares one icon + colour** across the map, the Fact List and dashboards
  (driven by its domain type's style on `DomainTypeDoc`).
- Creating a new map: the **identity/location columns auto-populate** from the query's domain types
  (overridable).
- The Layers panel's **legend filters by meaning** ("show only cameras").

### How to test Tier 2
- **Unit:** `DomainTypeDoc` style round-trip (class default vs attribute override); domain-type → style
  resolution + cache; identity/location inference from column domain types. Run
  `./gradlew :stroom-core-shared:test`.
- **Manual:**
  1. Right-click a map object → **Jump to** carries its values to the destination, pre-filtered.
  2. Give `Camera.*` a diamond/amber style on its `DomainTypeDoc`; confirm every camera on the map and
     in the Fact List shows it; `Camera.ptz` override differs.
  3. New map from an events query with typed columns → identity/location pre-filled.
  4. Toggle the semantic legend → only that meaning shows; scrub time → styling stays smooth (no
     stutter — confirms precompute).

---

## Tier 3 (later) — Ambitious (deeper & cross-cutting)

Deferred until Tiers 1–2 prove value; these change shared infrastructure.

- **E15 — Bind by meaning:** match events to facts by semantic value + wildcard (not exact key); touches
  `FloorMapMapPresenter.parseFacts`. **E16 — Map-to-map drill-down:** a place-typed object jumps to that
  place's own map (reuses A2). **F17 — Annotate the entity** (semantic identity via `stroom-annotation`).
  **F18 — Where-used** across maps/dashboards/indexes. **B7 — Live cross-highlight** (reuses the shared
  selection set). **G19 — Full shared action registry** (promote WS-G0). **G20 — Domain types in the
  dependency graph.**
- **UI at end / testing:** deferred — specified when scheduled.

---

## Testing / verification (overall)

- **Shared (`stroom-core-shared`):** `DomainType.canAccept`; the cross-doc resolver;
  `FloorMapDoc.domainTypes` / `FloorMapFieldMapping.domainType` round-trips; `DomainTypeDoc` style
  round-trip (Tier 2); param encoding vs `ParamUtil.parse`. `./gradlew :stroom-core-shared:test`.
- **Manual (running app):** the per-tier scripts above.
- `checkstyle` + `:stroom-core-shared:test` + relevant client tests green; GWT compile clean.

## Out of scope

- **Replacing** the free-text fact type / migrating existing maps — additive only.
- The **full** shared action registry (G19) and dependency-graph (G20) beyond the WS-G0 seed — Tier 3.
- **Consistency of styling across domain types** and **consistency between content packs** — explicit
  program directive; not addressed.

## Risks / open questions

- **Destination lookup is dashboard-only.** WS-G0 must generalise it (single heaviest item), or A/B
  fragment into copy-pasted variants.
- **Two type systems.** `TypeStyle.type` (free text) vs `DomainType` are separate; Theme C bridges them
  by adding style to `DomainTypeDoc` and resolving an object's look from its domain type.
- **Matching while animating.** Semantic styling/binding must be precomputed & cached, not per frame.
- **Fact carries no values.** Object jumps depend on resolving values from the `TemporalEntry` via the
  value schema + a per-field `domainType` (WS-D12) — sequence D12 before B4.
- *(Resolved)* Vocabulary ownership — **owned per Content Pack via its `DomainTypeDoc`**; cross-pack
  consistency deferred (out of scope).
- **Don't conflate `decorateDocRef`** — it is `DocRef` resolution, unrelated.

## Code touchpoints

| Area | File | Change |
|------|------|--------|
| Matching | [`DomainType`](../stroom-core-shared/src/main/java/stroom/domaintype/shared/DomainType.java:88) | Reused as-is (`canAccept`); extend tests. |
| Destination lookup | [`DashboardResource.findByType`](../stroom-core-shared/src/main/java/stroom/dashboard/shared/DashboardResource.java:61), `DashboardStoreImpl.findByType` (~209) | WS-G0: generalise to a cross-doc lookup incl. the floor-map store. |
| Jump plumbing | [`MyDataGridDomainTypeSupportImpl`](../stroom-core-client/src/main/java/stroom/data/grid/client/MyDataGridDomainTypeSupportImpl.java) | WS-G0: extract the `DashboardContext`/`DashboardSuperPresenter` binding behind an interface; keep param encoding. |
| Doc (destination) | [`FloorMapDoc`](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapDoc.java) | WS-A1/A2: add `domainTypes` list (mirror `DashboardDoc.domainTypes`). |
| Value schema | [`FloorMapFieldMapping`](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapFieldMapping.java) | WS-D12: add optional per-field `domainType`. |
| Pickers | [`AddDomainTypePresenter`](../stroom-core-client/src/main/java/stroom/dashboard/client/main/AddDomainTypePresenter.java), `DomainTypeClient` | WS-D12: reuse class/attribute pickers on the Settings tab. |
| Fact List / events grid | `FloorMapFactListPresenter` (~81), events-query grid | WS-B6: `setDomainTypeSupport(...)`; columns carry a `domainType`. |
| Canvas menu | `FloorMapCanvasPresenter` (`MapContextMenuEvent`), `FloorMapEditorPresenter.showCanvasContextMenu` (~962) | WS-B4/B5: "Jump to" from the fact's values; actionable tooltip. |
| Styling | [`DomainTypeDoc`](../stroom-core-shared/src/main/java/stroom/domaintype/shared/DomainTypeDoc.java) | WS-C8/C9: add a per-domain-type style (icon+colour); precomputed; feeds the Layers-owned swatch/legend. |
| Identity/location | `FloorMapDoc.entityIdColumn`/`locationIdColumn` (~155/164), events `Column.domainType` | WS-D13: infer from column domain types. |
| Discover / legend | Layers panel (Discover + legend, shared) | WS-C10/C11: pre-assign style on discover; one legend shared with Layers. |
| Binding (Tier 3) | `FloorMapMapPresenter.parseFacts` | WS-E15: match event→fact by semantic value + wildcard. |

## Definition of done (v1 = Tier 1)

A map declares the domain types it handles and appears in "Jump to" from any domain-typed value in
Stroom (framed and time-scrubbed); the map's tables offer the same "Jump to"; fields are typed with the
standard pickers; dead-end types are flagged — all on a generalised jump mechanism that no longer
assumes the destination is a dashboard. Tier 2 adds act-from-object, style-by-meaning and column
auto-detect.
