# FloorMap — Layers: Implementation Plan

**Status:** Decisions ratified · updated 2026-07-20 · branch `enterprise-floor-mapping-editor`
**Source proposal:** [`floormap-layers-ux.html`](floormap-layers-ux.html) (UX proposal, 2026-07-17)
**Builds on:** `enterprise-floor-mapping-devnotes/floormap-coordinate-redesign.md` (§6 type settings, §7 selection-as-a-set — **shipped**), `floormap-phase1-plan.md`, `floormap-phase2-plan.md`
**Companion plan:** [`floormap-domain-types-implementation-plan.md`](floormap-domain-types-implementation-plan.md)
**Program plan (decisions & sequencing):** [`floormap-program-plan.md`](floormap-program-plan.md)

## Goal

Give the editor a first-class **Layers** panel — show / hide, solo, lock, dim (opacity) and restack —
so a busy map stays readable and safe to edit. Deliver the UX proposal's recommendation: **ship "a
layer is a type" (Option A) now, design toward "a layer is a rule" (Option C)**.

## Ratified decisions (from the program plan)

- **Layer = type for v1 (Option A), additive** — a domain type is added *alongside* the free-text
  `type` later; existing maps keep working. Design toward Option C (rule-based) but don't build it yet.
- **Visibility, lock, solo and opacity are TRANSIENT — not written to the document, but sticky in the
  client session.** They are live authoring/viewing state held on the editor presenter and applied to
  the canvas; they are **not** written to `FloorMapDoc`. Crucially, they **persist in client memory for
  the life of the open editor** — they survive a save *and the document reload that follows* — and are
  cleared **only** when the document tab is closed and reopened (a fresh presenter). *(Ratified
  2026-07-20.)* `TypeStyle` therefore gains **no** `visible`/`locked` fields; the only persisted layer
  state stays what it is today (per-type `shape`, `colour`, and z-order) plus **presets** (below).
- **Presets are the one persistence mechanism.** If a particular view should survive a reload or open
  by default (especially on the read-only Map tab), it is saved as a named **preset** (Phase 3) — a
  snapshot of visibility (+ opacity). Live toggles stay transient; presets are the explicit "save this
  view" opt-in.
- **Single editing surface.** The Layers panel is a **right-hand-side slide-in / pop-out panel on both
  the Map and Editor tabs**, and the *only* place types/layers are edited — the **Settings tab's Type
  Styles grid is retired**, its Discover / reorder / shape+colour editing moving into the panel. (This
  is now purely a UX consolidation of the *persisted* type editing; it is no longer entangled with
  visibility, since visibility isn't persisted — so it can be sequenced freely.)
- **Viewer mode.** On the read-only Map tab the panel is **toggle-only** — eye + opacity + presets; no
  lock, reorder, or membership editing (proposal §9). One component, a `readOnly` flag.
- **Layers owns the shared styling components** — the styling accessor, the swatch, and the
  legend/filter surface (which the Domain Types work later consumes).

## Where we're starting from (grounding)

The coordinate/rendering redesign already shipped the machinery this feature grows from — this is a
control surface over existing state plus a set of transient live controls, not a new persisted model:

- **A layer ≈ a fact `type`.** [`TypeStyle`](../stroom-core-shared/src/main/java/stroom/floormap/shared/TypeStyle.java)
  holds `type` + `Shape` + `colour`; the **list order is the z-order**. It lives on
  [`FloorMapDoc.typeStyles`](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapDoc.java:246)
  and is applied by [`FloorMapZOrder.sort(...)`](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapZOrder.java:67)
  (unconfigured types sort last → paint on top). **These fields (shape/colour/order) are the persisted
  layer state; visibility and lock are not added here.**
- **The Settings tab already holds the editable list.** `FloorMapSettingsPresenter` renders a
  `MyDataGrid<TypeStyle>` with a **Discover** button (`onDiscoverTypes`, ~519), **up/down reorder**
  (`moveSelectedType`, ~496) and per-row **shape + colour** pickers (`initTypeStyleColumns`, ~212),
  persisting through `onWrite` (~648). **This editing is relocated into the Layers panel and the grid
  retired** — the Settings tab keeps only store refs / value format / value schema.
- **Selection is already a `Set`.** `FloorMapCanvasPresenter.selectedObjectIds` (`LinkedHashSet`, ~204)
  and `FloorMapEditorModel.selectedFactKeys` (~72). Multi-select, marquee, scale and rotate landed
  (commit `458158d966`).
- **One render choke-point.** `FloorMapCanvasPresenter.redraw()` (~771) and the animation-loop draw
  (~1126) call `FloorMapZOrder.sort(...)` then `view.draw(...)`. The view
  (`FloorMapCanvasViewImpl.draw`, ~207) **rebuilds the whole SVG DOM every frame** — so "not built"
  genuinely means "not drawn", and hiding a heavy layer is a real perf win (proposal §5, §10).
- **Layout scaffolding for the panel already exists.** The Editor (`FloorMapEditorViewImpl`) is built
  from nested `ThinSplitLayoutPanel`s — a RHS panel is a natural `addEast(...)` (already done in the P1
  increment). The Map tab (`FloorMapMapViewImpl`) is flat `SimplePanel`s and needs a small layout
  change to host the panel. The closest **slide-in / toggle** precedent is `AskStroomAiPresenter`.
- **No visible/locked/opacity in the renderer today.** The only `opacity` is the marquee fill and the
  event-trail alpha.

## Guiding principles

- **Live layer controls are transient presenter state — sticky across save/reload.** Visibility, lock,
  solo and opacity live as in-memory state on the editor presenter (e.g. `Set<String> hiddenTypes`,
  `Set<String> lockedTypes`, `String soloType`, `Map<String,Double> opacityByType`) — applied to the
  canvas on every draw, never written to `FloorMapDoc` and never routed through
  `FloorMapPendingChanges`. Because they live on the presenter instance, the **save→reload path
  (`onSave` → `reloadAllPanels` → `updateCanvas`) must re-read data and re-apply this state, not reset
  it** — only closing and reopening the document (a fresh presenter) clears it. (The built increment
  already satisfies this: those fields are mutated only by user actions and by pruning types that have
  genuinely disappeared.)
- **The only persisted layer state is shape/colour/order + presets.** Type editing (shape/colour) and
  z-order persist as today via `FloorMapDoc.typeStyles`; a saved *view* persists as a preset (Phase 3).
- **One source of truth for a layer's look.** The retired Settings grid's shape/colour and the panel's
  swatch are the *same* setting shown once. When the Domain Types work lands Theme C styling, that
  single source becomes the domain type's shared style — read through one accessor so the switch is a
  data-source change, not a UI rewrite.
- **Keep testable logic in shared static helpers.** Visibility/solo/opacity/lock resolution and
  layer-count derivation go in `stroom-core-shared` so they unit-test without GWT.

## Phasing

Each phase ships something usable and leaves the app green.

| Phase | Scope | Character |
|-------|-------|-----------|
| **P1 · Panel + visibility (live)** | RHS Layers panel; **eye** + **solo** (transient); hidden layers skipped in the draw build; time-aware counts. Then: relocate Discover/reorder/shape+colour off Settings & **retire the Settings grid**; host on the Map tab (viewer mode). | model + GWT |
| **P2 · Lock + opacity (live)** | Transient `lockedTypes` + per-type opacity; lock filters hit-testing & selection and disables handles; opacity dims the fact group. No document changes. | model + GWT |
| **P3 · Presets** | Named **presets** (the persistence mechanism) — a saved snapshot of visibility (+ opacity); default-on-open; exposed as switches on the read-only Map tab. | model + GWT |
| **Later · Smart / grouped layers** | A layer may be a **rule** (type / query / pinned). Deferred. | model + GWT |

> **Restack note:** because the Settings grid is retired in P1, the panel inherits its up/down reorder
> (persisted, unchanged) — so "restack from the panel" is part of P1. HTML5 drag-to-reorder is deferred
> polish (program-plan decision #8).

> **Increment status (2026-07-20): Phase 1 implemented** (compile + checkstyle + shared-unit verified;
> GWT smoke-test still required). One shared `FloorMapLayersPresenter` used in two modes:
> - **Viewer mode** on the **Editor** and **Map** tabs — swatch / name / count + eye / solo / show-all;
>   transient visibility wired into the canvas render filter (`setHiddenTypes` / `setSoloType`).
> - **Editor mode** on the **Settings** tab — the bespoke Type Styles grid is **retired** and replaced
>   by the same panel (Discover / reorder / shape+colour).
>
> **Deviation from decision #6 (accepted, lower-risk):** persisted type editing stays on the **Settings**
> tab (via the shared panel), so `typeStyles` persistence is unchanged (`FloorMapSettingsPresenter.onWrite`)
> — the document save path was *not* rewired. The panel is therefore not yet an editable surface *next to
> the canvas* on the Editor tab; that (and slide-in polish) is deferred. Live visibility/lock remain
> transient and sticky-per-session everywhere.

---

## Phase 1 — Panel + visibility (live)

### Workstreams
- **WS1.1 — Transient visibility model + render filter.** Hold hidden layers as a presenter
  `Set<String>` and the isolate as a `String soloType` (transient). Shared static helper
  `FloorMapLayers.visibleFacts(facts, hiddenTypes, soloType)` drops hidden types (solo keeps only one).
  Apply it in `FloorMapCanvasPresenter.redraw()` (~771) **and** the animation-loop draw (~1126),
  *before* `FloorMapZOrder.sort(...)`, so hidden layers are never built (perf, not `display:none`).
  *(Implemented as a first cut; see the cleanup note under Code touchpoints.)*
- **WS1.2 — The Layers panel** (new presenter/view + gin). Rows: swatch + name, **count**, **eye**,
  **solo**, and (Editor only, after WS1.4) **▲▼ reorder** + shape/colour. Time-aware counts group
  `FloorMapEditorModel.activeEntriesAtSelectedTime(...)` (~382) by type.
- **WS1.3 — Slide-in on the Editor tab.** `addEast(...)` on the outer `ThinSplitLayoutPanel`; toggle
  button + slide-in polish follow the `AskStroomAiPresenter` dock pattern.
- **WS1.4 — Retire the Settings Type Styles grid.** Move Discover / reorder / shape+colour editing
  (all persisted) off `FloorMapSettingsPresenter` / `FloorMapSettingsViewImpl` into the panel; the
  Settings tab keeps store refs / value format / value schema. Extract the shared editing logic (don't
  duplicate). *No persistence-conflict risk, since visibility isn't persisted — sequence freely.*
- **WS1.5 — Host on the Map tab (viewer mode).** Small layout change to `FloorMapMapViewImpl`;
  `readOnly` flag hides lock/reorder/editing, leaving eye + counts (+ opacity/presets later).

### UI visible at the end of Phase 1
- A **Layers** panel on the right of the **Editor** (and, after WS1.5, the **Map**) tab, one row per
  type: swatch, name, live **count**, **eye**, **solo**.
- Clicking the **eye** hides that type's objects from the canvas — including during playback; **solo**
  isolates one type; **Show all** resets.
- The Settings tab **no longer has a Type Styles grid** — type editing lives only in the panel.
- On the **Map tab** the panel is toggle-only (eye + counts).

### How to test Phase 1
- **Unit (`stroom-core-shared`, no GWT):** `FloorMapLayers` — hide one type, solo one type, unconfigured
  type stays visible, blank solo = no solo. Run `./gradlew :stroom-core-shared:test`.
- **Manual (running the Editor):**
  1. Open a FloorMap → Editor tab → the Layers panel lists each type with the right swatch and a count
     matching the Fact List.
  2. Hide a layer → those objects vanish from the canvas; scrub/play → they stay hidden and the count
     tracks the current time.
  3. Solo a layer → only it shows; Show all → restores.
  4. Confirm the **Settings tab has no Type Styles grid**; Discover / reorder / shape / colour work from
     the panel and still **save** (these are the persisted bits).
  5. **Save the document → the hidden/solo state is retained** through the reload that follows (it lives
     in client memory, not the doc); shape/colour/order persist. **Close and reopen the map → live state
     clears.** Open the **Map tab** → panel present, toggle-only.
  6. Regression: selecting/moving objects, multi-select and the Fact List are unaffected.

---

## Phase 2 — Lock + opacity (live)

> **Status (2026-07-20): implemented** (compile + checkstyle + shared-unit verified; GWT smoke-test
> pending). Lock and dim are toolbar toggles on the viewer panel (Editor + Map); both are transient and
> sticky-per-session like visibility. `FloorMapLayers.isLocked` / `resolveOpacity` cover the logic;
> `FloorMapCanvasPresenter` gained `setLockedTypes` / `setOpacityByType`, lock filtering in
> `hitObjectId` / marquee / `setSelectedObjectIds`, and per-fact `<g opacity>` wrapping in the view's
> draw loop. **Opacity is a two-state "Dim" toggle (~0.3 ↔ 1.0)**, not a continuous per-row slider —
> the slider is deferred polish (the render pipeline already accepts any `0..1` value).

### Workstreams
- **WS2.1 — Transient lock + opacity model.** Presenter `Set<String> lockedTypes` and
  `Map<String,Double> opacityByType` (transient). Shared helpers `isLocked(type, lockedTypes)` and
  opacity resolution/clamping (`0..1`).
- **WS2.2 — Opacity in the renderer.** Pass a `type → opacity` lookup into
  `FloorMapCanvasViewImpl.draw(...)`; emit `opacity` on the wrapping `<g>` in `appendImageFact` (~537)
  and `appendStyledGlyph` (~683). Add the compact opacity slider per row; optional "dim others".
- **WS2.3 — Lock excludes facts from hit-testing & selection.** Filter on the existing selection set:
  reject locked-layer facts in `FloorMapCanvasPresenter.hitObjectId(...)` (~800); skip them in the
  marquee test `FloorMapCanvasViewImpl.hitTestScreenRect(...)` (~318); disable handles for locked
  selections; make `applySelection` (~687) refuse locked facts.

### UI visible at the end of Phase 2
- Each panel row gains a **lock** toggle and an **opacity slider**.
- Opacity dims a type live (e.g. Background at ~30% as a tracing sheet) without hiding it.
- **Locking** a layer leaves it drawn but inert: clicks fall through, rubber-band / "select all" skip
  it, handles don't appear — so you can't nudge the Background while editing desks.

### How to test Phase 2
- **Unit:** `FloorMapLayers` opacity clamping and `isLocked(...)`. Run `./gradlew :stroom-core-shared:test`.
- **Manual (Editor):**
  1. Lower a layer's opacity → dims live; 0 → invisible but still counted.
  2. Lock the Background → dragging it selects a desk behind instead; rubber-band skips it; no handles.
  3. Unlock → normal. **Save → lock + opacity are retained** through the reload (in client memory, not
     the doc); they clear on close/reopen. To persist a view for others, save it as a preset.

---

## Phase 3 — Presets & the read-only Map tab

> **Status (2026-07-20): implemented** (compile + checkstyle + shared-unit + serialisation verified;
> GWT smoke-test pending). `FloorMapLayerPreset` (name + hidden types + opacity + `defaultOnOpen`) is
> persisted on **`FloorMapDoc.layerPresets`** — the one persisted layer state. The panel gains a preset
> **`SelectionBox` picker** (apply on change) + a **"Save current view as preset"** button (Editor
> only); default preset applied on first open; picker present on both Editor and Map (Map = apply-only).
> **Persistence:** the **Editor** tab writes `layerPresets` in its `onWrite` (it is the sole writer —
> Settings doesn't touch this field, so no conflict; this is a targeted addition, narrower than the
> deferred full single-writer rewire). **Sticky state preserved:** the default preset is applied only on
> the *first* read (`firstLayersRead`), so live visibility/lock/opacity still survive a save→reload.
> **Deferred polish:** presets are auto-named "View N" (no rename/delete dialog yet).

### Workstreams
- **WS3.1 — Saved presets ("views") — the persistence mechanism.** Add a persisted structure to
  `FloorMapDoc` — a named preset = a snapshot of `type → {visible, opacity}`
  (`List<FloorMapLayerPreset>`, NON_NULL) + an optional default preset used on open. This is the **only**
  place a visibility/opacity choice is written to the document. Shared apply/round-trip unit tests.
- **WS3.2 — Presets on the Map tab.** `FloorMapMapPresenter` exposes presets as one-click switches and
  applies the default preset on load. Viewer may toggle live + pick presets but not lock/reorder/edit.
  Visibility is orthogonal to time — apply as a post-query filter (`onTimeChange`, ~408), don't animate.

### UI visible at the end of Phase 3
- The panel gains a **preset picker** (e.g. *Security review*, *Facilities*, *Live ops*) and a "save
  current view as preset" action in the Editor.
- On the **Map tab** a viewer picks a preset and the map shows just that view; a shared map opens on its
  default preset rather than everything-on.
- Live toggles remain transient; only presets persist.

### How to test Phase 3
- **Unit:** preset apply → resolved visibility/opacity; unknown type ignored; default-preset resolution.
  Run `./gradlew :stroom-core-shared:test`.
- **Manual:**
  1. In the Editor, hide events + desks, "save as preset" → *Coverage*; save the doc.
  2. Open the Map tab → the preset switches appear; picking *Coverage* flips the layers; the map opens
     on its default preset.
  3. Scrub time → the preset's visibility holds (not animated).
  4. Confirm the viewer cannot lock/reorder/edit membership.

---

## Later — Smart / grouped layers (design toward C)

- Generalise a layer from "a type" to a named, ordered **rule**: `type = X`, `query/filter`, or
  `pinned` keys; membership **computed** so it stays correct as the timeline moves.
- **Overlap precedence:** the **topmost matching layer owns** a fact (program-plan decision #9).
- The panel's legend/filter surface is the same control the Domain Types *semantic legend & filter*
  (C11) needs — **built once here, shared**.
- **Deferred** until P1–P3 have shown value.

---

## Testing / verification (overall)

- **Shared (`stroom-core-shared`, no GWT):** `FloorMapLayers` visibility/solo/opacity/lock resolution;
  layer-count grouping; preset apply/round-trip (P3). `./gradlew :stroom-core-shared:test`.
- **Manual (running Editor/Map):** the per-phase scripts above.
- `checkstyle` + `:stroom-core-shared:test` + `:stroom-core-client:test` green; GWT compile clean.

## Out of scope

- **Persisting live visibility / lock / opacity per type** — deliberately not done; use a preset to
  save a view (this is the ratified model).
- Per-**fact** (not per-type) overrides / manual filing — Option B, only if proven needed.
- Onion-skinning / per-layer event trails; AutoCAD-style **freeze**; zoom-out clustering.
- **Consistency of styling across domain types**, and **consistency between content packs** — both
  explicitly out of scope (program directive).
- HTML5 drag-to-reorder (arrows ship; drag is deferred polish).

## Risks / open questions

- **Map-tab layout change.** `FloorMapMapViewImpl` is flat `SimplePanel`s today; hosting the RHS dock is
  the one genuinely new bit of layout in P1 — size it explicitly.
- **Redraw cost.** Visibility must short-circuit *before* building a layer's facts each frame, not via
  `display:none`.
- **Events as a layer.** Treated as a layer for v1 (program-plan #7); revisit clustering/trails later.
- **Shared styling decision (Domain Types).** The swatch's source of truth may later move to a
  domain-type shared style; keep the swatch reading one accessor.
- *(Resolved)* Two edit surfaces drifting — eliminated by the single-surface decision. *(Resolved)*
  Persistence two-writer conflict for visibility — **moot**, visibility isn't persisted.

## Code touchpoints

| Area | File | Change |
|------|------|--------|
| Logic | new `FloorMapLayers` (shared) beside [`FloorMapZOrder`](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapZOrder.java) | Visibility/solo (P1) + opacity/lock (P2) resolution over **transient sets**; layer counts; preset apply (P3). |
| Render (presenter) | `FloorMapCanvasPresenter` (`redraw` ~771, anim loop ~1126, `hitObjectId` ~800, `applySelection` ~687) | Filter hidden facts before sort (done); pass type→opacity (P2); lock in hit-test/selection (P2). Holds transient `soloType`. |
| Render (view) | `FloorMapCanvasViewImpl` (`draw` ~207, `appendImageFact` ~537, `appendStyledGlyph` ~683, `hitTestScreenRect` ~318) | Per-group opacity; skip locked facts in marquee test (P2). |
| Layers panel | `FloorMapLayersPresenter` / `FloorMapLayersViewImpl` + gin | RHS panel; transient hide/solo/counts (done, Editor); add reorder/shape/colour (WS1.4), lock/opacity (P2), presets (P3), `readOnly` Map mode (WS1.5). |
| Editor wiring | `FloorMapEditorPresenter` (`updateCanvas` ~624) | Holds transient `hiddenLayerTypes` / `soloLayerType`; applies to canvas; refreshes panel counts (done). |
| Editor layout | `FloorMapEditorViewImpl` | `addEast(...)` the panel dock (done). |
| Settings (retire grid) | `FloorMapSettingsPresenter` / `FloorMapSettingsViewImpl` | WS1.4: remove the Type Styles grid; relocate Discover/reorder/pickers into the panel. |
| Map layout | `FloorMapMapViewImpl`, `FloorMapMapPresenter` (`onTimeChange` ~408) | WS1.5 + P3: host the RHS dock; preset switches; toggle-only. |
| Doc | [`FloorMapDoc`](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapDoc.java:246) | **P3 only:** add `layerPresets`. No `visible`/`locked` fields are added to `TypeStyle`. |
| Selection | `FloorMapEditorModel` (`selectedFactKeys` ~72, `activeEntriesAtSelectedTime` ~382) | Exclude locked facts (P2); drive time-aware counts. |

> **Cleanup note (done 2026-07-20):** `TypeStyle` carries **no** `visible` field. Transient visibility
> is held as a `Set<String> hiddenLayerTypes` on `FloorMapEditorPresenter`, passed to
> `FloorMapCanvasPresenter.setHiddenTypes(...)`, and applied by
> `FloorMapLayers.visibleFacts(facts, hiddenTypes, soloType)` at the render choke-point. `TypeStyle` is
> back to its original `type` / `shape` / `colour` form.

## Definition of done (feature)

A single RHS Layers panel on both tabs gives **live, transient** hide / solo / lock / dim / restack with
time-aware counts (Settings grid retired); the only persisted layer state is shape/colour/order plus
**named presets**, which the read-only Map tab offers as switches (with a default on open); and the door
is open to smart/rule-based layers later.
