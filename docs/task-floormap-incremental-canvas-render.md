# Floor Map: incremental SVG rendering instead of a full scene rebuild per frame

**Type:** Performance / refactor
**Component:** Floor Map canvas (`stroom-core-client` / `stroom-core-shared`)
**Priority:** Medium — a performance ceiling, not a correctness bug
**Risk:** High (see *Risks* below — this is the most intricate code in the Floor Map feature)
**Status:** open. The cheap half of this problem — per-frame allocations — was taken separately; the
architectural half described here was deliberately deferred, because it is a rewrite of the most
intricate code in the feature.

> Methods are named rather than given line numbers, which drift. Where a number appears it has been
> checked against the current tree.

---

## Problem

The Floor Map canvas rebuilds its entire SVG scene as one HTML string and replaces the whole subtree on
**every animation frame** and **every pan mousemove**.

`FloorMapCanvasViewImpl` builds facts, events, clusters, badges and captions into a single builder and ends
with:

```java
svgContainer.setHTML(htmlBuilder.toSafeHtml());
```

That is the only `setHTML` in the view, and it is driven from two places in
`FloorMapCanvasPresenter`, both of which call `getView().draw(...)`:

- **`redraw()`**, reached from the pan mousemove handler among about twenty other callers
- **`animationCallback`**, the playback loop, which re-draws the whole scene per frame

### Cost

The old SVG subtree — including the floor-plan `<image>` — is discarded and the browser re-parses and
re-lays-out the entire scene because one entity moved. Parse and layout cost scales with **total scene
size**, not with what changed.

The client's own result caps are **20,000 rows** for the facts history and 20,000 for an events read
(`FloorMapFactHistory.MAX_ROWS`, `FloorMapMapPresenter.MAX_DELTA_ROWS`). Those are ceilings rather
than expectations — a floor plan is tens of facts and a map animates tens of entities — but nothing
between the expected and the permitted is guarded, and at the top end that is tens of thousands of
elements re-created up to 60×/s in GWT-compiled JS.

This sets the practical ceiling on how many entities the map can animate smoothly. It is not a correctness
problem — the output is right, it is just far more work than necessary.

---

## What is already optimised, and what is not

**Each of these was checked against the code rather than assumed.** A "do not redo" list is only
useful if it is true, and it is the part of a document like this that ages worst — re-check before
relying on it.

Already in place — build on these:

- **`imageFactsByKey` is cached**, rebuilt only in the `setFacts` path rather than over all facts per
  frame.
- **Trails use a ring buffer** (`FloorMapEntityAnimator.TrailBuffer`) rather than an
  `ArrayList` dropping its oldest point with `remove(0)`, which was an O(5000) shift per entity per
  frame at the cap.
- **Trail alpha is derived from the point's index** rather than stored.

Still allocating per frame — fair game, and cheap wins compared to the rewrite below:

- **`FloorMapZOrder.sort` runs on every frame**, from both `redraw` and the animation loop. It is a
  plain static sort with no memoisation, and nothing caches its result. A cache here must invalidate
  on `setFacts` and `setTypeStyles`.
- **`factsExcludingOverlay` builds a fresh `HashSet` and `ArrayList` per call** whenever the overlay
  is non-empty — which is precisely the playback case. Only an empty overlay short-circuits.
- **Trail alpha still allocates a `double[3]` per point per frame**, even though the alpha itself is
  index-derived.
- **The trail path string is rebuilt per band, per entity, per frame.** Note this is deliberate at
  the design level: the code records that an earlier version decimated trails to a fixed point budget
  and it was removed, because uniform striding looks correct on a straight run and wrong on a winding
  one. So *reduce the cost of rendering every point*; do not reintroduce a point cap.

`TRAIL_MAX_PTS` is 5,000 and every recorded point is rendered.

---

## Proposed approach

Split the scene into a **static layer** and a **dynamic layer**:

- **Static layer** — floor-plan image, areas, non-moving facts, grid. Rebuilt only when the data changes
  (`setFacts`, `setTypeStyles`, viewport scale change), never per frame. The floor-plan `<image>` in
  particular must stop being re-created.
- **Dynamic layer** — moving entities, trails, clusters, badges, captions. Per frame, update only the
  `transform` attribute of elements whose position changed; create and remove elements only when the
  entity set changes.

This means holding stable references to per-entity DOM nodes (keyed by entity id) rather than
regenerating markup. Pan and zoom should become a transform on a container rather than a scene rebuild.

An intermediate step, if the full split proves too large: keep the string-building approach but split it
in two, so panning and animation only rebuild the dynamic half. Less benefit, much less risk, and it
establishes the layer boundary that the full version needs anyway.

---

## Acceptance criteria

- [ ] A frame in which one entity moves does not re-create the floor-plan image or any static element.
- [ ] Per-frame DOM work is proportional to the number of entities that **moved**, not the scene size.
- [ ] Panning does not rebuild the scene.
- [ ] No visual regression in: pan, zoom-toward-cursor, drag, fit-to-view, follow-entity, playback,
      marquee selection, vertex editing, area drawing, cluster badges, labels, trails.
- [ ] An agreed entity-count target animates smoothly (see *Open question*).
- [ ] `./gradlew check` green and `./gradlew :stroom-app-gwt:gwtDraftCompile` clean.
- [ ] Runtime pass in super dev mode signed off (see *Verification*).

---

## Risks

**Treat this as the highest-risk change in the Floor Map feature.** Specifically:

1. **The failure mode is visual and hard to unit-test.** An incorrect incremental update produces a stale
   or misplaced glyph, not an exception. Existing JVM tests
   (`TestFloorMapEntityAnimator`, `TestFloorMapScreenGeometry`, `TestFloorMapViewport`) cover the geometry
   and animation *data*, but nothing covers the DOM output.
2. **This code has a history of exactly these bugs.** Already found and fixed here: ghost entities
   left behind after a data refresh, a leaked vertex-edit preview on a lost mouseup, and a duplicated
   animation loop. Incremental rendering reintroduces the whole class of "stale element not cleaned
   up" problem that a full rebuild makes impossible by construction. Budget for it.
3. **Element lifecycle becomes the new hazard.** Entities appearing, disappearing, being reused across
   refreshes, and teleporting all need explicit add/remove handling that the rebuild currently gives free.
4. **Selection, hit-testing and adorners read the DOM.** Anything that queries rendered geometry needs
   checking against the new structure.

Mitigation: do it behind the layer split described above so the static half is provably untouched per
frame; keep the full-rebuild path available as a fallback during development to A/B the output.

---

## Verification

Beyond the build, a **runtime pass in super dev mode** is mandatory — no JVM test sees the DOM.
Cover:

pan · zoom-toward-cursor · drag · fit-to-view · follow-entity · playback start/stop/scrub ·
marquee multi-select · vertex editing · area drawing · cluster badge placement · label collision ·
movement trails · entity appear/disappear mid-playback · teleport

Worth adding a dev-only frame-time readout while working, so the improvement is measured rather than
assumed.

---

## Open question (needs an answer before starting)

**What entity count must animate smoothly?** This is unsettled, and it decides whether the work is
worth doing at all.

The client's 20,000-row caps are ceilings, not expectations — they exist to stop a misconfigured
store exhausting the browser, not to describe a working set. If the realistic answer is tens of
entities on tens of facts, the per-frame allocations listed above are the whole of the problem and
this task can be closed unstarted. If it is thousands, the rewrite is unavoidable.

**Get that number before starting.** It decides both whether to do this and how to test it.

---

## References

- `stroom-core-client/src/main/java/stroom/floormap/client/view/FloorMapCanvasViewImpl.java`
- `stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapCanvasPresenter.java`
- `stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapEntityAnimator.java`
- `stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapScreenGeometry.java`
- Tests to extend: `TestFloorMapEntityAnimator`, `TestFloorMapScreenGeometry`, `TestFloorMapViewport`
