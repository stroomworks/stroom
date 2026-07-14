# FloorMap Redesign — Phase 1 Implementation Plan (Model & Core)

**Status:** Draft · 2026-07-13 · branch `enterprise-floor-mapping-editor`
**Spec:** see `floormap-coordinate-redesign.md`

## Goal

Lay the data-model and coordinate foundations for the redesign while **keeping the app working at
every step**. Phase 1 introduces the unified fact model, per-type settings, set-based selection and
full-matrix transform persistence. Visible polish — scaled multi-image rendering, per-type
shape/colour draw, z-ordered paint, editor tools — is Phase 2/3.

## Principles

- **Model-first.** Prefer shared, unit-testable code (`stroom-core-shared`) over GWT client.
- **Always green.** Each workstream compiles, tests pass, and an existing map still renders as it
  does today (via an adapter) until the Phase 2 renderer rework.
- **No user-visible change** lands in Phase 1 — the app renders identically until Phase 2.

## Workstreams

Rough dependency order: WS1 → (WS2, WS3, WS4 in parallel). Migration (WS5) has been
**dropped** — old maps will not be migrated (see WS5 below).

### WS1 — Fact model + parser returns a list
- Introduce a shared `Fact` representation `{ key, type, image?, worldToMap }`. Decide up front
  whether to **extend `FloorMapObject`** or add a new type — avoid maintaining two parallel shapes.
- `FloorMapEntryParser`: produce a **`List<Fact>`** for all entries. Every entry carries its
  `world-to-map` (`.tm-world-to-map`) and optional image (`.img`); `type` from `.type`. The
  background is **no longer special-cased**.
- **Adapter to keep rendering unchanged:** keep the existing `ParseResult`
  (`backgroundImage` + `bgMatrix` + `objects`) as a thin view *derived* from the fact list — pick
  the first `background`-type fact with an image as "the background". The current
  `FloorMapCanvasViewImpl` then renders exactly as today this phase.
- **Tests:** extend `TestFloorMapEntryParser` — list output, image/matrix per fact, and
  adapter-equivalence against the old single-background behaviour.

### WS2 — Doc setting: `typeStyles` + Discover + alphabetical default
- `FloorMapDoc`: add an ordered `List<TypeStyle>` (`type`, `shape`, `colour`); list order = z.
  Version/serialise it (default empty).
- **Discover** (shared/model): scan the current facts for distinct `type` values and merge missing
  ones **alphabetically**, preserving existing positions.
- Storage + Discover logic land now; the drag/pickers UI is Phase 2 (wire a minimal Discover
  button only if cheap).
- **Tests:** discover/merge (adds missing alphabetically, preserves order, idempotent).

### WS3 — Selection as a set
- `FloorMapEditorModel`: replace `selectedFactKey` (String) with a `Set<String>` plus helpers
  (`select`, `add/remove/toggle`, `clear`, `isSelected`, and a notion of a *primary* selection for
  the properties panel).
- `FloorMapCanvasPresenter`: `selectedObjectId` → a set; highlight **all** selected; a drag applies
  the delta across the set.
- UI stays single-select for now (a click selects exactly one → set size 0/1). Rubber-band +
  modifiers are Phase 3.
- **Tests:** selection-set operations; batch-move applies one delta to every selected fact.

### WS4 — Full-matrix transform persistence
- `FloorMapEditorModel`: generalise translation-only `recordObjectMove` →
  **`recordFactTransform(keys, …)`** that writes the full `world-to-map` (`a,b,c,d,e,f`). Drag =
  translate now; rotate/scale handles write the other components later.
- Apply one transform/delta across the selected set (batch).
- This **absorbs the earlier background `e,f` fix** as a special case (writing the full matrix,
  identity by default).
- **Tests:** full-matrix write; batch across a set; background and regular facts; preserve
  `a,b,c,d` on a translate.

### WS5 — Migration → ~~moved to Phase 2~~ **dropped**
**Not required (decision, 2026-07-14).** Migration was originally scoped here, then deferred to
Phase 2; it has since been **dropped entirely** — old maps will not be migrated. See
`floormap-coordinate-redesign.md` §9. Nothing in Phase 1 depends on it.

## Testing / verification

- **Shared unit tests** for: parser list + adapter equivalence, discover/merge, selection set,
  transform persistence (`stroom-core-shared` — no GWT needed).
- **Manual GWT smoke:** open an existing map → renders unchanged (adapter); select/drag → persists;
  save/reload round-trips.
- `checkstyle` + module tests green (`:stroom-core-shared:test`, `:stroom-core-client:test`).

## Out of scope (Phase 2/3)

- **Y-up map space + render flip** (with upright labels) — moved to Phase 2, bundled with the
  renderer rework to avoid a half-way visible regression.
- **Migration of legacy background data** — **dropped** (decision, 2026-07-14); old maps will not
  be migrated (see WS5 and `floormap-coordinate-redesign.md` §9).
- Scaled multi-image rendering; per-type shape/colour draw; z-ordered paint.
- Settings drag-to-reorder + shape/colour pickers UI.
- Rubber-band + modifier multi-select; rotate/scale handles.
- Zoom-out clustering.

## Risks / open

- **`Fact` vs `FloorMapObject`** — commit to one representation to avoid dual types drifting.
- **Adapter lifetime** — the `ParseResult` adapter is scaffolding; track its removal in Phase 2 so
  it doesn't calcify.
