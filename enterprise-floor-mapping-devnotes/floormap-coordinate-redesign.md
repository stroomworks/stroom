# FloorMap — Coordinate System & Rendering Redesign

**Status:** Draft for review · 2026-07-13 · branch `enterprise-floor-mapping-editor`

A single fact/coordinate model that lets images scale, supports many backgrounds, makes map
space Y-up, and prepares the ground for z-ordering, multi-select and transform tools.

> This is the in-repo copy of the shared design artifact. It is the authoritative spec; the
> companion `floormap-phase1-plan.md` breaks the first phase into buildable work.

---

## 1. Why

Today the floor map conflates three ideas — the **grid**, the **background**, and a **fact** —
and bakes several assumptions into the renderer that now get in the way:

- The background image is drawn at a fixed size (a hard-coded `1000`-unit width) and there can
  only be **one**.
- Map space follows the screen convention (**Y-down**), which is awkward whenever higher
  coordinate values should read as "up" — latitude/northing being one example, though the map's
  coordinates can mean anything.
- Object markers are drawn at a fixed **screen** size, so they can't represent a real-world
  footprint that scales with the plan.
- Selection is a single object and dragging only moves **translation** — no room for multi-select
  or rotate/scale.

Each objective below was raised independently, but they converge on one change: **collapse
"background" and "fact" into a single model** anchored in a shared, Y-up map space.

## 2. Objectives

| # | Objective | Notes |
|---|-----------|-------|
| **O1** | Scale images | Give any image its own affine `world-to-map` matrix — position, scale, rotation within map space. |
| **O2** | Multiple backgrounds | Several background images, each covering a portion of map space. A background becomes an ordinary fact. |
| **O3** | Y-up map space | Universally Y-up, so higher Y reads as "up" for any coordinate system (lat/long is just one use). |
| **O4** | Image-driven scaling | A fact *with* an image scales in map space; *without* one it uses a default graphic (shape & colour per type) at fixed screen size. |
| **O5** | Configurable z-order | Ordering is by **type**, arranged on the Settings tab (drag to reorder). Alphabetical default; unknown types on top. |
| **O6** | Multi-select *(model now)* | Selection becomes a set; a move applies to the whole selection. Rubber-band + modifier keys later. |
| **O7** | Transforms *(model now)* | Persist a full affine matrix per fact so rotate/scale handles can be added later without a model change. |

## 3. Current system

A record flows through three transforms to become a pixel. Two affine matrices do the work: a
per-object `world-to-map` that places an object's *point*, and a single `map-to-screen` that lives
on *the* background and transforms the **whole plane** (image and every object together).

```
World ──world-to-map──► Map space ──map-to-screen──► Base canvas ──pan/zoom──► Screen
(object .coords)        (Y-down, 1000 wide)          (whole-plane matrix)
```

Consequences: you can't resize the image relative to objects, there's only one background, north
is down, and the background needs special-case identity handling (a literal `"background"` id).

## 4. Target model

Everything renderable becomes one kind of thing — a **fact**. A background is simply a low-z fact
that carries an image. "Person" is an **event from the event stream**, likewise a fact that may or
may not carry an image.

```
Fact {
  key          identity — (map, key); map is fixed to the store name
  type         background · gate · desk · event · …   (also drives z-order)
  image?       Asset Store URL — optional
  worldToMap   affine → map space (position · scale · rotation)
}
// z-order is NOT stored per fact — it's a map-level ordering by type (see §6)
```

The coordinate stack becomes shared and Y-up. The old whole-plane `map-to-screen` matrix
**retires**: map space *is* the base canvas, and the only view transform is a single Y-flip
composed with pan/zoom.

```
World ──world-to-map──► Map space (Y-up) ──flip Y ∘ pan/zoom──► Screen
(per-fact affine,        (shared authoring space)   (view transform only)
 incl. image → map)
```

**The two flips cancel — for free.** A raster image's `world-to-map` maps its Y-down pixels into
Y-up map space (negative `d`); the render stage flips Y-up map back to Y-down SVG. Composed, an
image lands upright with north at the top, no special case. Only *text* and directional glyphs
need a local counter-flip — the same trick the code already uses to keep labels upright under
rotation.

## 5. Rendering rules

One rule decides scaling: **does the fact have an image?** Type no longer matters for sizing.

| Content | Sizing | Orientation |
|---------|--------|-------------|
| Image (background, fact, or event with `IMG`) | Map space — **scales with zoom**, matches the background | Upright (matrix + render flips cancel) |
| Fact / event *without* `IMG` — default graphic, **shape & colour from the type config** | Fixed screen size | Upright |
| Text label | Fixed screen size | Upright — local counter-flip |
| Symmetric marks (grid, circles, trails) | As today | Unaffected by the flip |

## 6. Type settings

The Settings tab configures each **discovered type**. Types are held as an **ordered list** on the
map (a document setting); their **order is the z-order** — facts carry no z value — and the
renderer paints types in that order (earlier = behind, later = in front).

- A **Discover** button scans the data for distinct type values and adds any missing ones to the
  list **alphabetically**; existing entries keep their position.
- Users **drag** the listed types into the desired paint order.
- A type seen in the data but **not yet discovered / placed** is painted **above everything else**,
  so it stays visible until someone adds it.
- Within a single type, facts paint by **stable order, then effective time**.

```
back ───────────────────────────────────────► front
[background] [desk] [gate] [room] ┊ [ new / unconfigured → on top ]
      Settings order = paint order · drag to reorder · Discover adds alphabetically
```

Each type's row also carries the **default graphic** — the shape and colour drawn for a fact of
that type when it has *no* image (see §5):

```
TypeStyle {
  type     the discovered type name
  shape    circle · square · triangle · diamond · pin · …   (imageless default)
  colour   fill for the default graphic
}
```

Shape and colour are per type, like the ordering — not per fact. A fact *with* an image ignores
them.

## 7. Selection & transforms

Two model changes land **now** so the UI features can arrive later without touching data or core
code.

**Selection is a set.** Replace the single selected key with a **set of keys**. Highlighting, the
context menu and dragging all generalise; a move applies **one delta to every selected fact**.
Rubber-band rectangle selection and Ctrl/Shift modifiers are pure UI on top *later*. Because each
fact transforms **independently** (see §11), multi-select is the *only* way to move or transform a
group as one — so set-based selection is core, not a convenience bolted on later.

**Full-matrix transforms.** Generalise persistence from translation-only to a **full affine
write** — `recordFactTransform(key, matrix)`. Dragging updates the translation; future rotate/scale
handles write the other components *later*. The model can already hold rotation and scale (the
matrix's `a, b, c, d`); this just lets us persist them.

## 8. Phasing

Model first, UI later. Every phase leaves the app working.

| Phase | Scope | |
|-------|-------|---|
| **1 · Model & core** | Unified fact parse (parser → *list*); `world-to-map` for all incl. images; per-type settings on the map — order (z) + default shape/colour, alphabetical default; selection as a set; full-matrix transform persistence; multiple backgrounds; migration. | now |
| **2 · Rendering** | Y-up map space + single render flip (labels kept upright); image sizing in map space; imageless facts drawn with the per-type shape & colour; paint types in configured z-order (unconfigured on top); Settings tab: Discover button, drag-to-reorder, shape/colour pickers. | now |
| **3 · Editor UI** | Rubber-band + Ctrl/Shift multi-select; drag-to-resize and rotate handles. | later |
| **Backlog** | Cluster events & facts when zoomed out to cut on-screen clutter and object counts. | later |

## 9. Migration

- Existing single-background docs: convert the old whole-plane `map-to-screen` into the background
  fact's own `world-to-map`.
- Decide whether that matrix's rotation/scale should carry into the image alone — and, if objects
  were visually pinned to it, whether to re-apply it to their `world-to-map` so the map looks
  unchanged after upgrade.
- Backgrounds gain real unique keys; the literal `"background"` id and the key-or-type
  special-casing retire. Regular objects are unchanged.

## 10. Code touchpoints

| Area | File | Change |
|------|------|--------|
| Parse | `FloorMapEntryParser` | Return a *list* of facts (incl. images + `world-to-map`); drop single-background collapse. |
| Matrix | `FloorMapTransformationMatrix` | Reused as-is; compose Y-flip; image matrices carry negative Y scale. |
| Render | `FloorMapCanvasViewImpl` | Paint facts in type z-order (unconfigured on top); per-image transform; imageless facts use the per-type shape/colour; single Y-flip; upright labels; drop fixed 1000 rect. |
| Canvas | `FloorMapCanvasPresenter` | Selection as a set; `screenToMapCoords` negates Y; batch move. |
| Model | `FloorMapEditorModel` | Full-matrix transform persistence; batch move; selection set. |
| Schema | `FloorMapFieldMapping.Role` | Reframe `WORLD_TO_MAP` as "to map space" so it applies to images too. |
| Doc | `FloorMapDoc` | Add an ordered `typeStyles` setting — per type: order (z) + default shape + colour. |
| Settings UI | `FloorMapSettingsPresenter` | Discover button (scan data for types); drag to reorder; per-row shape + colour pickers; alphabetical default; persist to the doc. |

## 11. Decisions & deferred

**Resolved**

- **Facts transform independently.** Transforming a fact — including its image — affects only that
  fact; nothing is transform-linked to a background. Moving/transforming a group is done via
  multi-select, which is precisely why set-based selection is core to Phase 1.
- **Coordinates are generic.** Map coordinates can represent anything — physical space, lat/long,
  or abstract concepts. No projection machinery: at the scales in use, treating lat/long as planar
  has negligible distortion, so `world-to-map` stays a plain affine.
- **Shape & colour** for imageless facts are per-type (fill colour, fixed screen size).
- **Discovery** is triggered by a **Discover** button on the Settings tab.
- **Tie-break** within a type: stable order, then effective time.

**Deferred**

- **Clustering when zoomed out.** Grouping events and facts at low zoom to cut clutter and object
  counts — later, but the fact model doesn't preclude it.
