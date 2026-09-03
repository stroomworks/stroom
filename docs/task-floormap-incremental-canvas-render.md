# Floor Map: incremental SVG rendering instead of a full scene rebuild per frame

**Type:** Performance / refactor
**Component:** Floor Map canvas (`stroom-core-client` / `stroom-core-shared`)
**Priority:** Medium — a performance ceiling, not a correctness bug
**Risk:** High (see *Risks* below — this is the most intricate code in the Floor Map feature)
**Origin:** Floor Map pre-production code review, 21 Aug 2026. Recorded as decision **D8** / finding **F6**
in `docs/floormap-remediation-plan.md`, where the cheap half was taken and this half deliberately deferred.

> Line references are as of commit `7ad6b4d9e4`. The cheap-tier work listed under *Already done* will have
> shifted them; treat them as landmarks, not addresses.

---

## Problem

The Floor Map canvas rebuilds its entire SVG scene as one HTML string and replaces the whole subtree on
**every animation frame** and **every pan mousemove**.

`FloorMapCanvasViewImpl` builds facts, events, clusters, badges and captions into a single builder and ends
with:

```java
// FloorMapCanvasViewImpl.java:786
svgContainer.setHTML(htmlBuilder.toSafeHtml());
```

That is driven from two places in `FloorMapCanvasPresenter`:

- `redraw()` (~1624–1642), called directly from the pan mousemove handler (~1013–1015)
- the animation loop (~2469–2491), which re-draws the whole scene per frame

### Cost

The old SVG subtree — including the floor-plan `<image>` — is discarded and the browser re-parses and
re-lays-out the entire scene because one entity moved. Parse and layout cost scales with **total scene
size**, not with what changed.

At the client's own result caps (1,000 facts + 1,000 events, from the `OffsetRange(0, 1000)` both consumers
request) that is thousands of elements re-created up to 60×/s during panning or playback, in
GWT-compiled JS.

This sets the practical ceiling on how many entities the map can animate smoothly. It is not a correctness
problem — the output is right, it is just far more work than necessary.

---

## Already done (do **not** redo — this is the cheap tier from the same finding)

The low-risk half landed separately. Assume these are in place and build on them:

- `imageFactsByKey` cached rather than rebuilt over all facts per frame (was `FloorMapCanvasPresenter`
  ~2428–2433)
- the z-sorted facts list cached rather than re-sorted per frame via `FloorMapZOrder.sort`
  (was called from both `redraw` and the animation loop)
- `factsExcludingOverlay` no longer building a fresh set + list per frame (was ~2393–2409)
- trails moved to a ring buffer instead of `ArrayList.remove(0)`, which was an O(5000) shift per entity
  per frame at the cap (`FloorMapEntityAnimator.java:372`)
- trail alpha derived from index instead of allocating a `double[3]` per point per frame
- rendered trail length capped well below `TRAIL_MAX_PTS = 5000`
  (`FloorMapEntityAnimator.java:47`), and the path string appended incrementally rather than rebuilt

All caches must invalidate on `setFacts` / `setTypeStyles`.

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

**This is the highest-risk change identified in the Floor Map review.** Specifically:

1. **The failure mode is visual and hard to unit-test.** An incorrect incremental update produces a stale
   or misplaced glyph, not an exception. Existing JVM tests
   (`TestFloorMapEntityAnimator`, `TestFloorMapScreenGeometry`, `TestFloorMapViewport`) cover the geometry
   and animation *data*, but nothing covers the DOM output.
2. **This code has a history of exactly these bugs.** An earlier review pass found and fixed ghost
   entities left behind after a data refresh, a leaked vertex-edit preview on a lost mouseup, and a
   duplicated animation loop. Incremental rendering reintroduces the whole class of "stale element not
   cleaned up" problem that a full rebuild made impossible by construction. Budget for it.
3. **Element lifecycle becomes the new hazard.** Entities appearing, disappearing, being reused across
   refreshes, and teleporting all need explicit add/remove handling that the rebuild currently gives free.
4. **Selection, hit-testing and adorners read the DOM.** Anything that queries rendered geometry needs
   checking against the new structure.

Mitigation: do it behind the layer split described above so the static half is provably untouched per
frame; keep the full-rebuild path available as a fallback during development to A/B the output.

---

## Verification

Beyond the build, a **runtime pass in super dev mode** is mandatory — the review flagged this as
outstanding even for the cheap tier. Cover:

pan · zoom-toward-cursor · drag · fit-to-view · follow-entity · playback start/stop/scrub ·
marquee multi-select · vertex editing · area drawing · cluster badge placement · label collision ·
movement trails · entity appear/disappear mid-playback · teleport

Worth adding a dev-only frame-time readout while working, so the improvement is measured rather than
assumed.

---

## Open question (needs an answer before starting)

**What entity count must animate smoothly?** The review could not settle this. The client currently caps
results at 1,000 facts + 1,000 events, which may already be the intended answer — but if the realistic
working set is 50 entities, the cheap tier may already be sufficient and this task can be closed unstarted.
Get a number first; it decides both whether to do this and how to test it.

---

## References

- `docs/floormap-remediation-plan.md` — finding **F6**, decision **D8**
- `stroom-core-client/src/main/java/stroom/floormap/client/view/FloorMapCanvasViewImpl.java`
- `stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapCanvasPresenter.java`
- `stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapEntityAnimator.java`
- `stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapScreenGeometry.java`
- Tests to extend: `TestFloorMapEntityAnimator`, `TestFloorMapScreenGeometry`, `TestFloorMapViewport`
