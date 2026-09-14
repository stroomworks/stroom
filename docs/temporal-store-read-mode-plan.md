# Plan: making the temporal stores' read mode explicit

**Component:** `stroom-sqlstore` (wholly local) and `stroom-planb`'s temporal-state search path
(local additions to upstream files)
**Status:** plan, nothing built. Supersedes the constraint that shaped
`docs/floormap-event-expiry-requirements.md` §5 — see *Why now*.
**Related:** `docs/planb-snapshot-read-proposal.md` (the original ask, still the best statement of
the shape), `docs/floormap-event-expiry-plan.md` (M3, which routes around the problem),
`docs/task-histogram-reads-whole-store-and-truncates-silently.md`

---

## Why now

Every awkward thing in the Floor Map's read paths traces to one design decision, and we had been
treating that decision as someone else's to change. It is not.

- **`stroom-sqlstore` does not exist on `origin/master`.** The whole module is ours.
- **Plan B's snapshot path is ours too.** `getQueryTime`, `removeTimeTerms` and `searchAsAt` were
  added by `b1c8cb2870` (2026-08-27), a local commit. `origin/master` has none of them.

So the constraint that ruled out fixing this at source — that it meant changing upstream's design —
was false. What remains is a change to **our own code**, in one upstream-owned file and one local
module.

## The problem, stated once

**One channel carries two intents.** A time predicate on a temporal store might mean *"filter rows
by time"* or *"give me one row per key as at this instant"*, and the store infers which from the
shape of the terms: `getQueryTime` treats `EQUALS`, `<` or `<=` on the time field as a snapshot
request, and `removeTimeTerms` then discards **every** time term including the lower bound.

It was a reasonable fix for the problem it solved. A UI asking for an instant sends
`TimeRange(t, t)`, which renders as `EffectiveTime >= t AND EffectiveTime < t` — an empty interval
no row can satisfy — so applying it literally returned nothing, always. Lifting the term out was the
right instinct. Inferring the *mode* from it was the part that has cost us.

Three consequences, each now costing real code:

| | Cannot be expressed | What we do instead |
|---|---|---|
| Event expiry | "latest per key at or before `T`, but not older than `F`" — the lower bound is discarded | M3: a `having` clause above the store, with the floor as a per-tick parameter |
| Histogram | "all rows between `A` and `B`" — any upper bound triggers snapshot mode | pass no range at all, read the whole store, bin client-side, cap at 10 000, never detect truncation |
| Readability | the mode is invisible in the query text | the same StroomQL means different things depending on a term the client added afterwards |

## What the workarounds cost, concretely

Worth totalling, because it is the case for fixing the cause:

- **M3** is seven work items of client code, a document field, a per-tick parameter, a neutral-floor
  convention that must be honoured at three call sites, and a `having` clause in user-editable text
  that silently disables expiry if deleted. It also required discovering and working around an
  unrelated upstream defect (`docs/task-query-having-on-an-alias-filters-everything-out.md`).
- **The histogram** transfers the whole store on every range change and is wrong past 10 000 rows,
  in a way nothing reports.
- **Show All** on the Map tab infers its extent from those truncated rows, where the Editor tab asks
  its store and gets an exact answer.
- **`TestTemporalStoreParity`** pins the discarding as intended behaviour, so the workarounds are
  now load-bearing in the test suite too.

## The shape of the fix

**Make the mode explicit, and honour ordinary time predicates literally.** Two capabilities, both
needed by the Floor Map alone:

1. **Snapshot** — one row per key, latest at or before `T`, optionally **with a floor**: omit a key
   whose latest row predates `F`. That is event expiry, expressed where it belongs.
2. **Range** — every row with `A <= time < B`. That is the histogram, and it also makes a store
   browser's time filter mean what it says.

With those, a time predicate that is *not* part of an explicit snapshot request is simply a filter,
as it is everywhere else in Stroom.

## Choosing the syntax

`docs/planb-snapshot-read-proposal.md` §1 sets out five shapes and argues for explicitness. Its
analysis stands; what has changed is that we can now pick one rather than ask for one. In brief:

| | Shape | Note |
|---|---|---|
| **A** | `where StateAt = '...'` — a reserved query field | No grammar change. Reads as a filter but is not one, which is the objection |
| **B** | `from people_events as at '09:45'` | Clearest at the point of use; needs a grammar change |
| **C** | `from snapshot('people_events', '09:45')` | No grammar change if function-valued data sources are supported; they are not today |
| **D** | A request API beside `search`, in the style of `getState` | Explicit, no grammar change, but invisible to anyone writing StroomQL by hand |
| **E** | A store setting `readMode: SNAPSHOT \| HISTORY` | **Rejected in the proposal and still rejected**: the Floor Map needs both answers from one store |

**Recommendation: B, with D as the mechanism underneath.** The floor belongs in the same clause —
`as at '09:45' since '06:45'` — because it is part of one question, not two filters that happen to
be adjacent. If a grammar change is judged too costly, A expresses the same thing with
`StateAt`/`StateSince` reserved fields and no parser work.

**Decide this before writing anything**, because it determines whether the work is a parser change
or a criteria change.

## Phases

**Phase 0 — mark what we already own.** `b1c8cb2870` added 395 lines to two upstream-owned Plan B
files with **no `STROOMWORKS-LOCAL` markers**. A merge from master could silently drop or conflict
with the snapshot path the Floor Map depends on. This is independent of everything else here and
should happen regardless of what is decided below.

**Phase 1 — choose the syntax.** Above. One decision, no code.

**Phase 2 — implement in both stores.**
- Plan B: `searchAsAt` gains a floor — one condition on the retention test, because entries arrive
  time-ascending per key so a latest-below-floor implies all-below-floor. `getQueryTime` becomes an
  explicit-request reader rather than a term sniffer. `removeTimeTerms` stops discarding what the
  caller meant literally.
- SQL store: the same floor on the `max(effective_time)` sub-select, and the same explicit trigger.
- `TestTemporalStoreParity` is rewritten: the case pinning "a lower bound adds nothing" becomes its
  inverse, and both stores are asserted to answer the explicit forms identically.

**Phase 3 — the Floor Map switches to it.**
- Expiry becomes the snapshot floor. No `having`, no per-tick parameter, no neutral-floor
  convention, no clause in user-editable text.
- The histogram asks for a range and stops reading the whole store; the 10 000 cap stops being
  reachable in normal use, and truncation detection becomes a safety net rather than the only
  defence.
- Show All can use a real extent — though a Plan B `getTimeRange` (option 4 of the histogram
  write-up) is still the better answer for that specific symptom.

**Phase 4 — retire the workarounds**, and delete rather than keep: the `having` clause, the neutral
floor, and the parts of the expiry plan that exist only to route around the inference.

## What to do about event expiry meanwhile

Two honest routes.

**Route 1 — do the store work first, and let expiry come with it.** Expiry becomes a floor on a
snapshot request: a document field, one argument at one call site, and nothing else. Most of M3's
seven work items disappear. The cost is that expiry waits for Phases 1–2.

**Route 2 — ship M3 now, migrate later.** Expiry lands sooner, then Phase 3 unwinds the `having`
clause, the parameter and the neutral-floor convention — including from any document whose query
text a user has edited by then.

**Recommendation: Route 1**, unless expiry is urgent. M3 is a substantial amount of client code
whose main purpose is to avoid touching a store we now know we own, and building it in order to
delete it is the expensive way round. Route 2 is defensible if the map is needed before the store
work can be scheduled — but it should be a deliberate choice, not a default.

## Blast radius — smaller than feared

A13 in the expiry requirements assumed this touches "every consumer of both stores". Checked:

- **Reference-data lookups are unaffected.** `ReferenceData` dispatches to `planBLookup.lookup` and
  `sqlStoreLookup.lookup` — the **lookup** path, not `search`. That is the highest-volume and most
  correctness-critical consumer of both stores, and it never goes near `getQueryTime`.
- **Affected:** the Floor Map; the store data-browsing UIs, but only if a user types a time
  predicate; and any hand-written dashboard, query or `View` against a temporal store.
- **Unknowable:** ad-hoc queries people have saved. With no production data and setups that can be
  rebuilt, this is a smaller risk here than it would normally be.

So A13 is still the real objection, but it is narrower than stated: this changes what a time
predicate means for *queries*, not for lookups.

## Assumptions

**B1 — we are free to change both stores' read semantics**, including breaking the current inferred
behaviour. Stated by the user, and consistent with there being no production data.

**B2 — the lookup path is genuinely separate** and needs no change. Rests on `ReferenceData:255–264`
dispatching to `lookup` rather than `search`.

**B3 — a floor filters correctly at the retention test in `searchAsAt`**, because entries arrive
time-ascending per key. Reasoned, not yet tested; Phase 2 should test it first, the way A1 was
tested before the expiry plan relied on it.

**B4 — the SQL store's `max()` sub-select accepts the same treatment**, for the same reason: the max
of a floor-filtered set is either above the floor or the set is empty.

**B5 — no upstream merge will bring a competing implementation.** `origin/master` has no snapshot
path today. If upstream later adds one, ours will conflict — which is what Phase 0's markers exist
to make visible rather than silent.

**B6 — the Floor Map is the only consumer that needs both modes from one store.** If something else
turns out to need it too, that strengthens the case rather than weakening it, and rules out
option E again.
