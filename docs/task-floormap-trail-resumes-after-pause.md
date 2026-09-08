# Floor Map: a trail comes back at full opacity when an entity starts moving again

**Component:** `stroom-core-shared` — `FloorMapEntityAnimator.advanceFrame` (the fade-cancel
branch), `.recordTrailPoint`
**Severity:** low. Cosmetic, on a feature whose whole point is being looked at — so cosmetic is not
the same as unimportant.
**Reported as:** *"trails becoming activated again when a person moves — this looks wrong."*
**Status:** **not reproduced and not diagnosed.** Two candidate mechanisms are identified below from
reading the code; neither has been confirmed against the running feature.

---

## The report

An entity moves, stops, and its trail begins to fade. It then moves again — and the *old* trail
reappears at full strength rather than a new one starting.

Recorded rather than diagnosed, deliberately — the two candidates below come from reading the code,
not from observing it, and guessing between them is not worth doing on paper.

## Two candidate mechanisms, from reading the code

Both are real properties of `FloorMapEntityAnimator`. Either would produce the reported symptom, and
they are not mutually exclusive.

### 1. Cancelling a fade does not discard the trail

`advanceFrame` cancels a fade the moment the entity animates again:

```java
for (final Map.Entry<String, Double> fade : trailFadeStartTimes.entrySet()) {
    final String id = fade.getKey();
    if (activeAnimations.containsKey(id)) {
        doneFading.add(id);                 // moving again — cancel the fade
    } else if (timestampMs - fade.getValue() >= TRAIL_FADE_DURATION_MS) {
        entityTrails.remove(id);            // fully faded
        doneFading.add(id);
    }
}
```

Note the asymmetry: the *completed* branch removes the entry from `entityTrails`, and the
*cancelled* branch does not. So the points accumulated before the pause are still there — and with
the entry gone from `trailFadeStartTimes`, the `fadeFactor` computed in `attachTrail` reverts to
`1.0`. The result is the old trail, at full opacity, continuous with the new movement.

Whether that is wrong is a **design question, not a bug report**. A single trail through a pause is
arguably right for someone who stopped for ten seconds at a desk; it is clearly wrong for someone
who stopped for an hour. `TRAIL_MAX_AGE_MS`'s own javadoc says the cancellation is deliberate — *"the
fade is cancelled the moment the entity moves again, so an entity that moves intermittently never
lost any"* — so the current behaviour is intended, and what the report challenges is the intent.

### 2. Age trimming only runs when a point is recorded

```java
private void recordTrailPoint(...) {
    final TrailBuffer trail = entityTrails.computeIfAbsent(id, k -> new TrailBuffer(TRAIL_MAX_PTS));
    trail.add(x, y, timestampMs);
    trail.dropOlderThan(timestampMs - TRAIL_MAX_AGE_MS);
}
```

`dropOlderThan` is called *only* from here, and this is only called for an entity with an active
animation. So a stationary entity's points are never aged out. Stand still for longer than
`TRAIL_MAX_AGE_MS` (20 s) and every point survives; move again and they are all dropped on that
first frame — an old trail flashing into view and vanishing, which matches "activated again" as
well as mechanism 1 does.

This one is more clearly a defect than a design choice: the trail's documented contract is a
20-second window, and a paused entity silently keeps more than that.

## A third possibility that should be ruled out first

The events read has a **known and accepted one-tick flicker**. Playback applies cheap incremental
reads and corrects them with a periodic full read; that full read replaces the known state
wholesale, so an entity that moved while it was in flight can jump back one tick before the next
incremental read corrects it.

The animator would see that jump as movement and record trail points for it. A one-tick position
flicker is easy to miss; a trail drawn through it is not. So a trail that "looks wrong" may be
correctly drawing an accepted position artefact — in which case the fix is on the events side, not
the animator's, and this issue should be closed in favour of revisiting whether that flicker is
still acceptable now that something draws attention to it.

**Rule this out before changing the animator.** It is the cheapest of the three to check and the
only one where the animator is behaving correctly.

## How to reproduce

Not yet established. What to try, in order:

1. Play until an entity stops emitting, watch its trail fade, then scrub back so it is moving again.
2. Pause for **more than `TRAIL_MAX_AGE_MS` of timeline** on an entity that is stationary but still
   emitting — one re-sending an unchanged location — then resume. Mechanism 2 predicts a flash of
   old trail on the first frame of movement.
3. Watch a single entity across a full events read (every 60 s during playback) for a one-tick jump
   with a trail drawn through it. That is the third possibility.

The repository's floor-map test fixtures include entities of each shape — one that stops part-way,
one parked and re-emitting — and the generator writes a manifest of the times involved.

## Verification, whichever mechanism it turns out to be

`TestFloorMapEntityAnimator` already exists and is GWT-free with time passed in, so all of this is
testable without a canvas:

- a fade cancelled by new movement — assert whatever is decided about the old points
- a stationary entity for longer than `TRAIL_MAX_AGE_MS`, then movement — assert the surviving
  points respect the age window at the moment they are *drawn*, not only when one is added
- a fade that runs to completion still clears the trail, unchanged

## Why this is worth an issue despite being cosmetic

The trail is how a viewer reads direction and recent history at a glance. A trail that reappears
after a pause tells them someone moved when they did not, which is a wrong statement about the data
rather than an ugly one. It is still low severity — nothing is lost and nothing is stuck — but it
should not be filed as polish.
