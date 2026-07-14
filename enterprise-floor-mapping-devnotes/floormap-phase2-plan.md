# FloorMap Redesign — Phase 2 Implementation Plan (Rendering)

**Status:** Draft · 2026-07-13 · branch `enterprise-floor-mapping-editor`
**Spec:** see `floormap-coordinate-redesign.md` · **Precedes:** `floormap-phase1-plan.md`

## Goal

Turn the Phase 1 model into the intended visuals: render directly from the fact list, size images
in map space, make map space **Y-up**, draw imageless facts with their per-type shape & colour,
paint types in the configured z-order, and give the Settings tab the type-management UI. By the end
of Phase 2 the Phase 1 `ParseResult` adapter and the whole-plane `map-to-screen` are gone.

## Preconditions (from Phase 1)

- Parser returns a `List<Fact>` `{ key, type, image?, worldToMap }`; the `ParseResult` adapter still
  exists as scaffolding.
- `FloorMapDoc.typeStyles` (ordered `type` + `shape` + `colour`) exists, with Discover/merge logic.
- Selection is a set; transforms persist as full matrices.

## Character of this phase

Unlike Phase 1, most of this is **GWT client** (`FloorMapCanvasViewImpl`, `FloorMapSettingsPresenter`)
and is verified by **running the editor**, not unit tests. Keep genuinely testable logic (z-order
sort, shape resolution) in small shared/static helpers so it *can* be unit-tested.

## Workstreams

Dependency order: WS1 → WS2, WS3, WS4 (rendering rules) → WS5 (Y-up) → WS6 (settings UI) →
WS7 (cleanup). WS6 can run in parallel once `typeStyles` is read by the renderer.

### WS1 — Render directly from the fact list
- `FloorMapCanvasViewImpl.draw` consumes `List<Fact>` instead of `backgroundImage + matrix + objects`.
- `FloorMapCanvasPresenter` passes the fact list through; the Phase 1 `ParseResult` adapter is
  retired here (tracked so it doesn't calcify).
- Behaviour target for this step alone: visual parity with today (single background + markers),
  now driven by the list.

### WS2 — Per-fact image rendering & scaling  *(O1, O2, O4)*
- For each fact **with** an image, emit an `<image>` transformed by that fact's `world-to-map`,
  drawn at a **local size** (natural pixels / unit box) so the matrix's scale sets its map-space
  footprint. Drop the hard-coded `IMAGE_DISPLAY_WIDTH = 1000` rect.
- **Multiple backgrounds** now fall out for free — they're simply facts with images.
- Reconcile the existing aspect-ratio cache (`loadImageAspectRatio`) with per-fact local sizing.

### WS3 — Imageless default graphics  *(O4)*
- For each fact **without** an image, draw the default graphic at **fixed screen size** using the
  **shape + colour** from its type's `TypeStyle`. Shapes: circle · square · triangle · diamond · pin.
- A type with no configured style (undiscovered) uses a neutral fallback graphic.
- Keep a small static `shape → SVG` resolver (unit-testable).

### WS4 — Z-order paint  *(O5)*
- Build a `type → index` map from `typeStyles`; sort the draw list by
  `(typeIndex, stableOrder, effectiveTime)`. Types absent from the list sort **last** (painted on
  top). People/events (their type) sit wherever the user placed them.

### WS5 — Y-up map space + render flip  *(O3 — moved here from Phase 1)*
- Map space is Y-up. Apply a **single flip** (`scale(1,-1)`) at the map→screen boundary, composed
  with pan/zoom; keep pan/zoom scale positive.
- `FloorMapCanvasPresenter.screenToMapCoords` negates Y so clicks/drags/placement stay correct.
- **Counter-flip glyphs:** text labels and directional marks get a local `scale(1,-1)` so they
  stay upright — extend the existing counter-rotation logic. Symmetric marks (circles, trails) are
  unaffected.
- **Images need no special case:** their `world-to-map` maps Y-down pixels into Y-up map space
  (negative `d`), so the two flips cancel and images render upright, north-up. Verify on a real
  image.

### WS6 — Settings tab: type management UI  *(O5)*
- `FloorMapSettingsPresenter`: a **Discover** button (invokes the Phase 1 scan/merge), a
  **drag-to-reorder** list of types, and per-row **shape + colour** pickers. Persist to
  `FloorMapDoc.typeStyles`; mark dirty; re-render on change.

### WS7 — Retire the old model & cleanup
- Remove the whole-plane `map-to-screen` usage from rendering (each fact carries its own matrix).
- Remove the Phase 1 `ParseResult` adapter and the literal `"background"` id handling in the
  canvas (selection/drag now key off real fact identity).
- Delete the now-dead fixed-rect / single-background code paths.

> **Open cleanup (2026-07-14): remove the literal `"background"` id/type special-casing.**
> Now unblocked — with migration dropped (WS8) a background is just an ordinary image fact, so this
> can be **deleted outright** rather than phased out behind a data rewrite. It is **not yet done**
> and is wider than "the canvas" — the `FloorMapJsonKeys.BACKGROUND` literal (`= "background"`) is
> branched on across:
> - `FloorMapEntryParser` — treats an entry as a background when its key *or* type is `"background"`.
> - `Fact.isBackground()` — same key-or-type test.
> - `FloorMapEditorModel` — several `wantBackground` / `isBackground` branches.
> - `FloorMapCanvasPresenter` — the Ctrl/Shift-pan-over-background guard.
> - `FloorMapMapPresenter` — canvas-id mapping, the synthesised background `Fact`, and the type check.
> - `FloorMapObjectEditPresenter` / `FloorMapObjectEditViewImpl` — the `Background` display name and
>   the map→screen-vs-world→map matrix toggle.
>
> Target: identify a background by its real fact identity + presence of an image (and its low
> z-order), not a magic key/type string; keep `BACKGROUND_DISPLAY_NAME` only if a background still
> needs a friendly label. Scope the full removal before starting — it touches shared model code, so
> re-run `:stroom-core-shared:test` + `:stroom-core-client:test`.

### WS8 — Migration ~~(moved here from Phase 1)~~ — **dropped**
**Not required (decision, 2026-07-14).** Old maps will not be migrated — there is no legacy data
that must be preserved in place, so this workstream is dropped. See `floormap-coordinate-redesign.md`
§9.

Consequence for the other workstreams: the literal `"background"` id and the key-or-type
special-casing (WS7) can simply be **removed** rather than phased out behind a migration — no
temporal-store data rewrite is needed.

*(Superseded plan, kept for context: this would have been an idempotent batch over each map's
temporal-store entries, converting the old whole-plane `map-to-screen` into each background fact's
`world-to-map` and giving backgrounds real unique keys.)*

## Testing / verification

- **Shared unit tests** where logic allows: z-order sort (incl. unconfigured-on-top + tie-break),
  `shape → SVG` resolver, type→index mapping.
- **Manual (running editor)** — the bulk of verification:
  1. Two overlapping background images at different scales render correctly and **scale together**
     with zoom.
  2. An imageless fact shows its **type shape & colour**; changing the type's colour in Settings
     updates it.
  3. Reordering types in Settings changes paint order; a **new/undiscovered** type draws on top.
  4. **Y-up:** higher-Y content is higher on screen; a north-up image is upright; **labels are
     upright**; clicking still selects the right fact (inverse transform correct).
  5. Drag/scale still persist and round-trip (Phase 1 behaviour intact).
- `checkstyle` + module tests green; GWT compile clean.

## Out of scope (Phase 3 / backlog)

- Rubber-band + Ctrl/Shift multi-select; drag-to-resize and rotate **handles** (the model already
  supports the transforms; this is the graphical UI).
- Zoom-out clustering of events & facts.
- Custom shape sets beyond the built-in enum.

## Risks / open

- **Label counter-flip** correctness under combined rotation + Y-flip — the fiddliest bit; test
  with a rotated image and off-axis labels.
- **Performance** with many facts/images (per-fact `<image>` + transforms) — watch redraw cost;
  the animation loop already rebuilds the DOM each frame.
- **Aspect-ratio handling** per fact vs the current single cached ratio.
- **Adapter removal timing** (WS7) — do it once WS1–WS5 are proven, not before.
