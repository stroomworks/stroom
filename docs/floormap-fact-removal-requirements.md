# Requirements: removing a fact from a floor plan

**Component:** Floor Map — the facts a floor plan is made of, and the SQL Temporal Store holding them
**Branch:** `enterprise-floor-mapping-events-last-forever`
**Status:** requirements only. **Nothing is decided and nothing is planned.** Four candidate shapes
are set out with their costs; §6 has the open questions.
**Raised:** 2026-09-14, while reviewing the plan for event expiry
(`docs/floormap-event-expiry-requirements.md`) — *"we also need to think about expiry in facts …
facts will be deleted and we need to work out some of the ways this could work."*

---

## The question

A floor map's **facts** are its static content: desks, areas, backgrounds — the plan itself. They
live in a SQL Temporal Store, are edited in place through the Editor tab, and are drawn as they were
at whatever time the scrubber is on.

Two things follow that nobody has decided:

- a fact, once created, is part of the plan **for ever**; and
- the only way to remove one **erases it from history**.

This document is about what should happen instead. It deliberately does not reuse the answer being
built for events, for the reason in §3.

---

Raised 2026-09-14. **Requirements only — nothing is decided and nothing is planned.** The question is
what should happen to a floor plan's contents over time, given that events are about to gain an
expiry and facts have none.

## 1. Facts have the same defect events had, and no way out of it

`FloorMapFactHistory.snapshotAt(t)` takes the latest row per key with `ms <= t`, with **no concept of
a fact having ended**. So a desk created in 2024 is present in every snapshot from 2024 onwards, for
ever.

That is exactly the shape of the events defect being fixed separately: the Map tab's events read
returns every key the store has ever held, so an entity that left months ago is still drawn. The
cause there was a lost time bound
(`docs/floormap-event-expiry-requirements.md` §1); here there is no bound to lose, because there is
nothing to say a fact has ended.

`FloorMapFieldMapping.Role` confirms there is nowhere to say otherwise: `TYPE`, `LABEL`, `POSITION`,
`IMAGE`, `WORLD_TO_MAP`, `GEOMETRY`, `FILL`, `OPACITY`. No role means "removed", and no value of any
role can mean it either — a fact with no position is a fact with a broken position, not a fact that
has gone.

## 2. The only removal available destroys history

Removal today is a **hard delete of rows**:

- `ChangeOperation.Type.DELETE` carries a `TemporalEntryId` and no entry — it deletes one version,
  identified by `(map, key, effectiveTimeMs)`.
- The Fact List's Delete deletes *every* version of a key, behind a confirmation that says
  **"This cannot be undone."**
- `SqlTemporalStoreResource` also exposes `/clear`, which empties the store.

So the only way to take a desk off the floor plan is to make it never have existed. Scrub back to
last year and the desk is not there either — and events that referenced it by `locationRef` now
resolve to nothing at every point in history, not just after the removal.

**This is the part worth deciding deliberately.** A temporal store's purpose is that the past stays
answerable, and the one editing operation that removes anything is the one that breaks that.

## 3. Why fact expiry is not event expiry

It is tempting to reuse the mechanism, and it would be wrong.

| | Events | Facts |
|---|---|---|
| What a row is | an **observation** — "alice was at desk-1 at 09:30" | a **declaration** — "desk-1 is here, and is called this" |
| What silence means | the entity has not been seen lately, so it may be gone | nothing at all; a desk that nobody edited is still a desk |
| What age implies | staleness | nothing — a floor plan that has not changed in three years is not stale, it is stable |
| Natural end | drifts out of relevance | a **decision**: someone removed the desk, on a date |

An age-based rule applied to facts would delete the floor plan of any building nobody has
refurbished lately. **Facts do not expire; they are retired**, and retirement has a date someone
chose rather than a duration that elapsed.

## 4. Ways this could work

Four shapes, roughly in order of cost. None is chosen.

**(a) A tombstone version.** Removal writes a new temporal entry at the removal time carrying a
"removed" marker, rather than deleting rows. `snapshotAt(t)` then omits a key whose latest version at
`t` is a tombstone. History is intact: scrub before the removal and the desk is there; scrub after
and it is not.
*Costs:* a new `Role` (or a reserved key/value convention), a change to `snapshotAt`, and a decision
about what the Editor's Delete button does — almost certainly "retire", with hard delete kept as a
separate, rarer, admin-flavoured action.
*This is the shape that matches what a temporal store is for*, and everything below is a variation
or a subset of it.

**(b) An explicit validity range per fact.** Facts gain an end time alongside their effective time.
More expressive than a tombstone — it can express "this desk existed 2020–2024" in one row — but it
is a schema change to the store, not just to the floor map, and it duplicates what a tombstone
expresses with the temporal mechanism already there.

**(c) Retention on the facts store.** The SQL Temporal Store could drop rows older than some age, the
way Plan B's retention does. This addresses **storage**, not presentation: it would delete the early
history of facts that still exist, which is precisely backwards — the old versions are the ones a
scrub back in time needs.

**(d) Do nothing, and document it.** A floor plan accumulates every desk ever placed; removing one
means erasing it from history. Cheapest, and defensible if floor plans are small and rarely change —
but it should be a stated position rather than an accident, because right now the "cannot be undone"
warning is the only place the consequence appears.

## 5. What interacts with the events expiry work

Mostly nothing, which is the useful conclusion — the two can be built independently and in either
order. But two points connect, and they are worth holding while the events change lands
(`docs/floormap-event-expiry-plan.md`):

- **An expired entity and a removed desk look different and should stay that way.** An entity that
  expires disappears because it has not been seen; a desk that is gone disappears because someone
  removed it. The existing empty-stage reporter already distinguishes "no events" from "no facts";
  whatever fact removal becomes should not collapse those.
- **`locationRef` resolution is where a removed fact surfaces.** An event referencing a desk that no
  longer exists cannot be placed, and the map reports that as a mapping problem
  (`FloorMapMapPresenter` warns when no entity's `locationRef` matches a fact key). With a tombstone
  that message becomes accurate at every scrub position; with hard deletion it is accurate about
  *now* and misleading about the past.

## 6. Open questions

- Should the Editor's Delete **retire** rather than erase, with erasure kept as a separate action?
- If a fact is retired, what happens to events that reference it at times **after** the retirement —
  unplaced, placed at the last known position, or reported?
- Does a retired fact still appear in the Fact List, the Tracking panel's fact rows, and the Layers
  panel — and if so, how is it shown?
- Is there any appetite for fact *retention* (storage) separately from fact *retirement*
  (presentation), or is the store small enough that retention never matters?
