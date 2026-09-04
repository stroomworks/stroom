# Floor Map — manual test plan

**Branch:** `enterprise-floormapping-code-review-b`
**Rewritten:** 2026-09-04, against the fixtures actually loaded (see *The data*, below)

Unit tests and `./gradlew check` cover the logic. They cannot cover a canvas, a content pack round
trip, or a Plan B store that has never been written to. This is the part a person has to do.

---

# Read this first — three things that will otherwise waste your time

### 1. A freshly opened map shows NOW ± 24 hours, so your data may be off-screen

`selectedTime` is initialised to `System.currentTimeMillis()`, and the timeline opens at ±24 hours
around it. The fixture data sits in a four-hour window in the recent past, so it is on-screen but
squeezed into a sliver of a 48-hour range — and the current position starts at "now", which is
*after* all of it.

**Open the timeline settings (the cog) and click "Show All"** to fit the range to the data, or set
the range by hand. Everything below assumes you have done one of those.

*(An earlier revision of this document claimed the timeline opened in 1970. That was wrong — the
field has no initialiser at its declaration but is assigned in the constructor, and I had only
checked the declaration.)*

### 2. Hard-reload before you start

Ctrl+Shift+R. The UI was rebuilt at 11:08 and the permutation hash changed, so a normal refresh can
still serve a stale build.

### 3. If the whole UI dies with an empty console, it is not the feature

An orphaned drag glass — see `task-popup-drag-glass-orphaned.md`. Fixed in this build, but if it
recurs, recover with:

```js
document.querySelectorAll('.popupPanel-dragGlassVisible')
        .forEach(e => e.classList.remove('popupPanel-dragGlassVisible'));
```

Then tell whoever owns that fix, because a recurrence is a regression.

---

# The setup

All in **System / Floor Map Test**.

| Document | Type | Purpose |
|---|---|---|
| `floor_map_facts` | SQL Temporal Store | The floor plan. 10 rows |
| `floor_map_events` | Plan B, `TEMPORAL_STATE` | The entities. 207 rows |
| `floor_map_events_bulk` | Plan B, `TEMPORAL_STATE` | 24 000 rows, for **A11** only |
| `floor_map_events_empty` | Plan B, `TEMPORAL_STATE` | **Deliberately empty**, for **A12** only |
| `Test Floor Map` | Floor Map | facts + `floor_map_events`. **Almost every test uses this** |
| `Test Floor Map (bulk)` | Floor Map | facts + `floor_map_events_bulk` |
| `FLOOR_MAP_FACTS` / `FLOOR_MAP_EVENTS` | Feeds | ingest |
| `FLOOR_MAP_CSV_WITH_HEADER` | Text Converter | quoted CSV with a header row |
| `FLOOR_MAP_FACTS_TO_SQLSTORE` / `FLOOR_MAP_EVENTS_TO_PLANB` | XSLTs | CSV → `reference-data:2` |
| `FLOOR_MAP_FACTS_TO_SQLSTORE` / `FLOOR_MAP_EVENTS_TO_PLANB` | Pipelines | + processor filters |

**Still to create:** `Test Floor Map (empty)` on `floor_map_events_empty` (A12), and
`Test Floor Map (SQL)` whose events store is a **SQL Temporal Store** (A10).

---

# The data

## The floor plan

Six desks and two areas, all placed by an identity matrix, so canvas coordinates are the numbers
below.

```
        x=40      80        200       320    400        460    520
   y=50  +-----------------------------------+                        North Wing
         |     desk-101   desk-102   desk-103|                        (blue, x 40-400)
  y=160  +-----------------------------------+
                          forklift-7 runs along y=180  <- in NEITHER area
  y=200  +-----------------------------------------------------+      South Wing
         |     desk-104   desk-105   desk-106 ... desk-106'    |      (green, x 40-520)
  y=310  +-----------------------------------------------------+
```

| Fact | Position | In area |
|---|---|---|
| `desk-101` | 80, 90 | North Wing |
| `desk-102` | 200, 90 | North Wing |
| `desk-103` | 320, 90 | North Wing |
| `desk-104` | 80, 240 | South Wing |
| `desk-105` | 200, 240 | South Wing |
| `desk-106` | 320, 240 **before 07:56:47**, then **460, 240** | South Wing either way |
| `bg-ground` | background | — |

`desk-106` **moves at 07:56:47** and is renamed "Desk 106 (moved)". That single fact is what makes
the scrub tests decidable — the correct picture depends on where the timeline is.

There is no background image (`ground-floor.png` was never uploaded), so the canvas is blank behind
the desks. That is expected and affects no test.

## The entities

Events run **04:24:20 → 08:24:20**, one event per entity every 5 minutes, plus two outliers.

| Entity | Behaviour | Last seen | Final position |
|---|---|---|---|
| `alice@example.org` | hops desk to desk throughout | 08:24:20 | `desk-103` |
| `bob@example.org` | **stops 5 minutes early** | **08:19:20** | `desk-102` |
| `dave@example.org` | parked, re-emits the **same** value every 5 min | 08:24:20 | `desk-105` |
| `forklift-7` | literal coordinates, not a desk | 08:24:20 | `276, 180` |
| `carol@example.org` | **one event, 7 hours before the end** | **01:24:20** | `desk-103` |
| `ghost@example.org` | references `desk-999-does-not-exist` | 08:23:20 | **never drawn** |

## Two kinds of location, and what that looks like on screen

`location` is either a **fact key** or **literal coordinates**, and the fixture uses both on purpose
— they are separate code paths in `FloorMapLocationResolver`.

| Form | Example | Who | On screen |
|---|---|---|---|
| **Fact key** | `desk-103` | `alice`, `bob`, `dave`, `carol` | Resolved against the facts *at the selected time*, so the entity sits exactly on the desk — and **moving the desk moves its occupants**, retroactively |
| **Coordinates** | `B-GND, 276.0, 180.0` | `forklift-7` | Drawn at those coordinates, full stop. Never on a desk, unaffected by desk moves |

So `forklift-7` tracking along **y = 180** — between the two areas, never on a desk — is correct.
It is in the fixture to exercise the coordinate path. The four people always land exactly on a desk.

**Movement between two positions is a straight line.** `FloorMapEntityAnimator` interpolates from
the previous position to the new one over a fixed duration; there is no pathfinding, no corridors and
no obstacle avoidance. So `alice` going from `desk-101` to `desk-105` crosses the open floor
diagonally rather than routing around anything. Expected.

---

# What "working" looks like

**Set the timeline to 08:24:20** (the last event). You should see **four** entities:

| You should see | Where | Why |
|---|---|---|
| `alice` | `desk-103` — North Wing | moved there at 08:24:20 |
| `bob` | `desk-102` — North Wing | **silent for 5 minutes and still drawn.** This is the whole point of the change |
| `dave` | `desk-105` — South Wing | parked |
| `forklift-7` | `276, 180` — **between** the two areas | literal coordinates |

And you should **not** see:

- `carol` — her only event is 7 hours old, beyond the horizon
- `ghost` — names a desk that does not exist, so there is nowhere to draw it

So: **4 glyphs. North Wing shows 2 occupants, South Wing 1, and one entity in neither.**

If that is what you see, the feature is working and everything below is detail.

---

# Results so far — 2026-09-04

| Test | Result |
|---|---|
| "What working looks like" reference state | **pass** — which is **A1**, the headline behaviour |
| A3 · horizon pruning, both directions | **pass** |
| A5 · scrub backwards | **pass** |
| A6 · scrub forwards | **pass** |
| A7 · stop at end | **pass** |
| A8 · loop at high speed | **pass** |
| A9 · nothing polls while hidden | **pass** |
| A14 · a standing timeline is quiet | **pass** — console silent while parked |
| A11 · truncating baseline | **pass** — warned once |
| A10 · SQL Temporal Store unchanged | **pass** — see the note below |
| A12 · unreadable store | **premise was wrong** — see A12; a never-written store returns clean empty, so silence is correct |
| A4 · condense | **not runnable** — see A4 |
| A2, A13 | outstanding, both low value |

**Group A is effectively complete.** Ten pass, two turned out not to be tests, and the two
remaining are the low-value tail. Confirmed against real data: the delta/baseline machinery, the
horizon in both directions, the discontinuity hook both ways, the stop-at-end reorder, the
hidden-tab behaviour, the standstill cadence, the truncation failure mode, and no behavioural change
on a SQL Temporal Store.

**A10 was run against `Enterprise Floor Mapping Demo / Floor Map`, not a purpose-built fixture** —
and that was the better choice. It is a genuine pre-existing map on a genuine SQL Temporal Store
(`map_mysql_store`, 5 090 rows spanning 2006 → 2026-08-07), so it exercises the real configuration
rather than a reconstruction of it. Note two things about running it:

- With the timeline at "now" the entities appear but **do not move**, and that is correct. The store's
  data ends 2026-08-07, and a SQL Temporal Store reinterprets the range as a snapshot at `T`, so you
  get each entity's last known position, static. Movement needs the timeline moved to **2026-08-07**
  (177 events that day, densest in the afternoon).
- That the entities appear at all, a month stale, *is* the A10 assertion: the horizon is not enforced
  on a SQL store. On Plan B they would have been pruned — which is exactly the contrast **A3**
  demonstrates from the other side.

A14 is worth calling out: it pins the one defect that manual testing was always most likely to
catch. `nextRead` classified a repeated tick at the same instant as a timeline jump, which put it on
the one-second interval and re-read the whole store every second for as long as the document stayed
open. Unit tests now cover it too, but the console going quiet while parked is the observation that
matters.

**A11 is ready.** The bulk store holds 24 000 rows spanning 02:24:20 → 08:24:19, and at timeline
position **08:25:00** the six-hour horizon contains **23 955** of them — verified by query — which is
comfortably over the 20 000 cap, so the truncation path will be exercised rather than skirted.

# Group A — the events change. Do these first

Use **`Test Floor Map`** unless a test says otherwise. **Keep the browser console open** — several
tests are about what it says.

## If "What working looks like" passes, this is the short list

That reference state already proves **A1** — the headline behaviour. What it does *not* touch is
everything below, in descending order of value:

| Priority | Tests | Why they are not covered by the reference state |
|---|---|---|
| **Do these** | **A3, A5, A6, A8, A14** | A3 is the *opposite* direction — without pruning the occupancy counts are a lie. A5/A6 exercise the discontinuity hook, a separate path from playback. A8 is where implementation found a defect four reviews missed. A14 pins a bug fixed today |
| **Then** | **A12, A11** | Both are failure-mode tests, and both need a document creating first. A12 is the cheaper and more likely of the two |
| **If time** | A2, A7, A9, A13, A10 | Each narrows one behaviour, none is load-bearing on its own. A10 is a regression check on a store the defect cannot affect |
| **Skip** | **A4** | Not runnable against this fixture — see below |

Outside Group A, **Group B is the one to do regardless of how Group A goes.** It is independent of
the events change, has never been exercised by hand, and fails silently — a content pack missing its
assets looks fine until someone imports it somewhere else.

### A1 · An idle entity stays on the map · *the headline test*

Set the timeline to **08:24:20** and leave it paused.

- **Expect:** `bob` is drawn at `desk-102`, even though his last event was 08:19:20 — five minutes
  earlier.
- **Fail:** `bob` is missing. That is the old behaviour, where anything silent for 20 seconds
  vanished.

### A2 · Area occupancy holds while an entity is idle

Play from about **08:00** through to the end.

- **Expect:** North Wing keeps showing 2 occupants right through, including after `bob` goes quiet
  at 08:19:20.
- **Fail:** the North Wing count drops to 1 with nobody having moved.

### A3 · An entity beyond the horizon is dropped · *reversible, so do it twice*

The baseline reaches back **6 hours** from wherever the timeline is. `carol`'s only event is at
01:24:20, so she is in scope or out of scope depending purely on the timeline position.

1. **Pause**, then set the timeline to **07:00:00** — horizon 01:00 → 07:00, which **includes** her.
   - **Expect:** `carol` appears at `desk-103`.
2. Now set it to **08:00:00** — horizon 02:00 → 08:00, which **excludes** her.
   - **Expect:** `carol` disappears, and North Wing's occupancy falls accordingly.

- **Fail:** she never appears at 07:00 (the horizon is not reaching back), or never disappears at
  08:00 (state is never pruned, which would make the occupant counts a lie).

**Pause first.** During playback a dropped entity's counts update but its glyph lingers, because the
draw list re-emits from the animator's last positions and only prunes on the teleport path. That is
pre-existing and not a regression.

### A4 · `condense` · **not runnable against this fixture — skip it**

This test cannot be run as written, and the reason is worth knowing because it also corrects the
advice this feature used to give.

Condense only collapses runs **older than its threshold**, and the Plan B settings' unit dropdown
starts at **days** — 1 day is the shortest you can set. The baseline horizon is **6 hours**. So
nothing the map reads at a live timeline position is ever condensed, whatever you set: the window is
always four times narrower than the shortest threshold.

Consequences:

- **The old hazard cannot occur near the live end.** A stationary entity's repeats are only
  collapsed once they are a day old, by which time they are long outside any horizon that would
  have shown them.
- **Condense does not make the baseline cheaper either**, which is what the events-store guide and
  the truncation warning both used to claim. Corrected in both. The knobs that apply to a
  truncating baseline are the **horizon** and the **row cap**.
- Where condense still matters is playback further back than its threshold — and that is also the
  only place it can still drop a stationary entity.

**To test it properly** you would need events spanning more than a day and a timeline scrubbed back
beyond the condense threshold, which is a different fixture from this one. Low value: the behaviour
is bounded, understood, and off by default.

### A5 · Scrub backwards · *uses the desk that moves*

From 08:24:20, scrub back to **08:19:20**, then to **07:30:00**.

- **Expect at 08:19:20:** `alice` is at `desk-106` in its **moved** position (460, 240) — the far
  right of South Wing. Entities **jump** to their new positions; they do not slide.
- **Expect at 07:30:00:** `desk-106` is back at its **original** position (320, 240) and named
  "Desk 106" — you have scrubbed to before the 07:56:47 move.
- **Fail:** any entity shown at a position later than the selected time, or entities animating
  smoothly across the jump instead of teleporting.

### A6 · Scrub forwards

From 07:30:00 scrub forward to 08:24:20.

- **Expect:** the map catches up immediately — `alice` at `desk-103`, `bob` at `desk-102`,
  `desk-106` back at 460, 240.
- **Fail:** positions from before the jump persist. A forward jump looks just like a large playback
  tick, so this is the case the discontinuity hook exists for.

### A7 · Stop at end

Turn **off** loop playback in the timeline settings. Play to the end of the range.

- **Expect:** playback stops and the final positions are correct **immediately**.
- **Fail:** the map is stale and only corrects itself up to a minute later.

### A8 · Loop at high speed · *test this one hardest*

**One read per wrap is correct** — a wrap sends the timeline backwards, no delta can fix that, and a
fresh baseline is the only right answer. This test is about the two ways that can go wrong, and both
are visible **without reading the console**.

Do not try to judge it from `startNewSearch()`. Every `QueryModel` logs that line: the facts query
logs one per throttled tick and so does the events delta, so there are already about six a second
before any baseline. The message cannot distinguish them.

Turn loop playback **on**, narrow the range to about **two minutes** (08:22 → 08:24), and set the
speed high enough that it wraps every second or two. Let it run for two minutes.

- **Expect:** playback stays smooth, the browser stays responsive, and each wrap redraws within about
  a second.
- **Fail — storm:** the browser turns sluggish and the Network panel's request count climbs by an
  order of magnitude. Two things bound this, so both would have to fail: the 300 ms playback
  throttle, and the one-second floor between jump-triggered baselines.
- **Fail — freeze:** after each wrap the map holds its pre-wrap positions for up to a minute before
  catching up. That is the defect found during implementation, which the plan and four reviews all
  missed: a wrap was being held back by the 60-second routine interval instead of the one-second
  jump interval.

**If you want to count baselines precisely:** in the Network panel, Ctrl+F searches request bodies —
search `eventsBaselineTable`, which only the baseline sends (the delta sends `eventsTable`, the facts
query `factsTable`). With sub-second wraps you should see at most about one baseline a second, not
one per wrap.

### A9 · Nothing polls while hidden

With the timeline **paused**, switch to another tab of the same document (Editor, Settings) for over
a minute, then come back to Map.

- **Expect:** the console is quiet while you are away, and there is exactly one read on return.
- **Fail:** searches continue while the Map tab is hidden.

### A10 · A SQL Temporal Store behaves exactly as before

Needs **`Test Floor Map (SQL)`**. Point its events store at a SQL Temporal Store — the simplest way
is to re-ingest `out/events.csv` through the *facts* pipeline with the `map` column rewritten to a
SQL store's name.

- **Expect:** no behavioural change of any kind against that store.
- **Fail:** any difference. The defect being fixed cannot occur there, because that store reinterprets
  a time range as a snapshot.

### A11 · A store too big for one baseline

Needs **`Test Floor Map (bulk)`** and the bulk data loaded.

**Set the timeline to 08:25:00.** This matters: the bulk data spans 02:24 → 08:24, and the baseline
only sees 6 hours back from wherever the timeline is. At 08:25 the horizon covers essentially all
24 000 rows, which exceeds the 20 000-row cap. Sit the timeline at "now" instead and only about
15 000 rows are in scope — under the cap, so nothing truncates and the test proves nothing.

- **Expect:** the console warns **once** that the baseline hit its 20 000-row limit and recommends
  `condense`; the map still draws real positions; the next tick is a delta rather than another
  whole-store read.
- **Fail:** repeated warnings, an empty map, or back-to-back full scans.

### A12 · A store that cannot be read · **premise corrected 2026-09-04**

**As originally written this test was wrong, and an empty console is the correct result.**

The test assumed a never-written Plan B store would *error*, because `StateSearchProvider` catches
scan failures — including `"Local Plan B shard not found"` from `StoreShard.open` — records the error
and then still signals completion. That is real code, but a store whose document exists and has
simply never been written does **not** reach it. Verified by query against
`floor_map_events_empty`: `errors: []`, `errorMessages: []`, `complete: true`, 0 rows.

So the baseline succeeds, returns nothing, and the map empties correctly and silently. There is no
message to look for.

**What that actually demonstrates is F14's reporting gap**, empirically: a store with no data and a
store with no data *in the horizon* are indistinguishable, and both are silent. That silence is a
known finding, not a fault in this change.

**To exercise the failure path** you need a store that genuinely cannot be read. The cleanest
trigger is to point a floor map at a Plan B document and then **delete the document** — the map's
stored reference then resolves to nothing. `floor_map_events_empty` holds no data, so deleting it
costs nothing.

- **Expect then:** the map keeps whatever positions it had, and the console reports
  `the events baseline query failed` **once** — not once a minute.
- **Fail:** repeated reporting, or an empty map with no explanation.

Until that is run, the failure path is covered by reasoning and unit-level argument only. Worth
knowing, and not a blocker: the once-only reporting mechanism itself is proven, because **A11**
exercises the same guard on the truncation branch.

### A13 · A slow baseline reports itself

Throttle the network in DevTools, or use the bulk map, and watch a baseline take several seconds.

- **Expect:** lateness or failure is reported.
- **Fail:** it looks indistinguishable from an empty store.

### A14 · A standing timeline is quiet

Park the timeline anywhere and leave the Map tab open and paused for three minutes.

- **Expect:** no repeating `startNewSearch` in the console. At most one read a minute.
- **Fail:** a read roughly every second. That was a real bug — a repeated tick at the same instant
  was misclassified as a timeline jump — and this pins the fix.

---

# Group B — assets through the content pack

`FloorMapStoreImpl` overrode none of export, import, copy or delete, so assets were silently absent
from every content pack. A broken pack fails quietly, which is why this is worth doing by hand.

You need a map with assets: upload an image on the Assets tab and reference it from a fact's `img`.

| # | Do | Expect | Result |
|---|---|---|---|
| B1 | Export a map with assets; inspect the pack | asset files present | **pass** 2026-09-04 |
| B2 | Import that pack back | assets restored **and rendering** on the canvas | **pass** 2026-09-04 |
| B3 | Copy the map | the copy has its own assets; editing one does not affect the other | outstanding |
| B4 | Delete the map | assets removed, not orphaned | partly — a map was deleted and re-imported cleanly, but that does not prove the rows were removed rather than orphaned |
| B5 | Export/import a map with **no** assets | works, no errors | outstanding |

**No clean instance is needed for B2.** Importing into the same instance is an *update*, not a
clash: `StoreImpl.importDocument` looks for an existing document by UUID and, finding one, keeps its
name and updates in place. So the round trip is **delete the map, then import the pack** — which
also exercises B4. Do not edit UUIDs to force a second copy; that tests a configuration that never
occurs, since the pack's asset paths stay keyed to the original UUID.

**B3 is the one most likely to find something.** `copyLiveAssets` is documented as *"Does not delete
assets in the destination. Will throw an error if assets already exist"* — so a copy onto a target
that already has assets is a distinct path from the empty case.

---

# Group C — reference-data ingest

Writes now batch at 1 000 and the lookup store resolves once per pipeline run. Row counts at the
batch boundary are unit-tested; the pipeline is not.

| # | Do | Expect |
|---|---|---|
| C1 | Ingest a reference stream through `SqlStoreFilter` | row count matches the input exactly |
| C2 | Ingest exactly **1 000** rows, then **1 001** | both exact — this is the boundary |
| C3 | A stream whose `map` column changes partway | rows land under the right map names |
| C4 | An XSLT `lookup()` against a SQL Temporal Store | resolves, and produces no `Error` stream |
| C5 | The store's Data tab | still lists entries |

---

# Group D — canvas and trails

Visual only; no test can see any of it.

| # | Do | Expect |
|---|---|---|
| D1 | Play and watch a mover's trail | tapers smoothly, no glowing joins, no hard bands |
| D2 | Scrub across the 07:56:47 desk move | trails clear rather than stretching across the jump |
| D3 | Play for several minutes | trails fade by age; no slowdown, no unbounded growth |
| D4 | Zoom fully in and out; shrink the pane very small | no console exceptions |
| D5 | Toggle clustering with entities crowded | glyphs merge and split; cluster tooltips name members consistently with the Tracking and Groups panels |

---

# Group E — setup and migration

| # | Do | Expect |
|---|---|---|
| E1 | Start against a **fresh, empty database** | the SQL temporal store migration runs at bootstrap, no manual step |
| E2 | Start with the **old** `visualisationAsset` / `visualisationAssetDb` config keys | accepted, with a deprecation warning. Do not add the new keys alongside — the last occurrence wins |
| E3 | Create a new Floor Map end to end | the init dialog sets both stores, both column settings and the value schema; layers appear; events draw |
| E4 | The store choosers on the init dialog | titled "Choose Facts Store" / "Choose Events Store" |

---

# Do not file these as bugs

- A **one-tick flicker** when a baseline lands after deltas were in flight: it replaces state
  wholesale, so an entity that moved during its flight can jump back one tick before the next delta
  corrects it.
- During **playback**, a dropped entity's counts and roster update while its **glyph persists**.
  Pre-existing, which is why A3 says pause first.
- The horizon is **not enforced on a SQL Temporal Store**, which strips all time terms — so
  horizon-drop behaviour is Plan B only.
- **No background image.** `ground-floor.png` was never uploaded.
- The timeline opening at **NOW ± 24 hours** rather than fitted to the data. Click "Show All".

---

# When the data goes stale

Every timestamp above is relative to when the fixtures were generated, and the 6-hour horizon is
relative to the timeline position. Old data puts every entity out of scope, which looks exactly like
the bug A3 tests for.

To refresh:

```bash
python3 docs/floormap-testdata/generate.py
```

Then re-upload `out/facts.csv` and `out/events.csv` to their feeds, and `out/events-bulk.csv` if you
are doing A11. Wait about five minutes — Plan B ingests in two stages, a processor task then a merge
on an every-minute cron.

**All the times in this document shift.** The *relationships* are what matter, and this query
re-derives the ones you need:

```
from "floor_map_events" group by Key select Key, max(EffectiveTime) as "Last Seen", count() as "Rows"
```

- the largest `Last Seen` is the "end of data" position used throughout
- `bob`'s is 5 minutes earlier — A1
- `carol`'s is 7 hours earlier — A3's two positions are 30 minutes either side of `carol + 6h`
