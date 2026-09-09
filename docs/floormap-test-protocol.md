# Floor Map — test protocol

**Run this document.** It is ordered, each step says exactly what to do and exactly what to expect.
Most expected values were read back from the live instance on **2026-09-07 07:09 UTC**.

**Revised 2026-09-09**, after Plan B gained a server-side latest-per-key read and the Floor Map's
client-side event state was retired behind it. Those revisions are **derived from the code, not read
back from a running instance** — the MCP connection was down — so treat Session R, the entity table
and A0's count as predictions this run is meant to confirm, rather than as observations to match.
Everything dated 2026-09-07 or earlier was observed.

`floormap-test-plan.md` is the companion: it explains *why* each test exists and records what has
already passed. This document is what you work through.

**Estimated time:** Part 1 is about 35 minutes and covers everything built in the last three days.
Parts 2 and 3 are about 20 minutes each and cover older work that has never been exercised by hand.

**The single most informative check** is A0's entity count. It was four and is now five, and the
entity that changed answer — `carol` — is the one whose absence used to prove the six-hour horizon
existed. If she is drawn, the retirement works. If she is not, stop.

---

# Before you start — regenerate the data

## 1. The events CSV changed shape, so the fixtures must be rebuilt

A location is now **two separate fields**: `location` for literal `"x, y"` coordinates and
`locationRef` for the key of the fact an event happened at. One field used to carry both, told
apart by shape. So `events.csv` has a new column and the data in the store is the wrong shape —
nothing will draw until you reload.

```bash
python3 docs/floormap-testdata/generate.py
```

Then upload, through the UI's **Upload** button on each feed:

| File | Feed |
|---|---|
| `docs/floormap-testdata/out/facts.csv` | `FLOOR_MAP_FACTS` |
| `docs/floormap-testdata/out/events.csv` | `FLOOR_MAP_EVENTS` |
| `docs/floormap-testdata/out/events-bulk.csv` | `FLOOR_MAP_EVENTS` — same feed; its `map` column routes it to `floor_map_events_bulk` |

*Not through the API. Two separate reasons, both current: the MCP server's data endpoints fail on
`Meta$Builder` / `FetchMarkerResult` class resolution because its build predates this branch; and
after a **clean database** the server's stored API token no longer authenticates at all
(`AUTH_HEADER_REJECTED`, HTTP 401), because the admin account is newly created. Re-issue a key under
**Tools → API Keys** and update the MCP server's `Authorization` header if you want the query tools
back.*

**The ingest XSLT was updated for you** — `FLOOR_MAP_EVENTS_TO_PLANB` in Stroom is a separate
document from `docs/floormap-testdata/floormap-events.xslt`, and it now reads `locationRef` as well
as `location`. Without that the new CSV would land with no location at all for every entity except
`forklift-7`, and nothing would draw. `FLOOR_MAP_FACTS_TO_SQLSTORE` needed no change: the facts CSV
did not change shape.

**The old rows stay in the store**, because Plan B is keyed on (key, effective time) and the new
generation lands at new times. Harmless, but **less harmless than it was**: the read now takes each
entity's latest row at or before the selected time with no lower bound, so an old row is no longer
ignored for being far in the past — it is simply superseded by a newer one for the same key. A key
that appears *only* in an old generation will now be drawn. If you see an entity this protocol does
not list, that is why; deleting the store's contents between generations avoids it.

## 2. Read your landmark times from the manifest, not from this document

`generate.py` writes every timestamp relative to the moment it runs, so that the fixture always
lands near the timeline's default position. So it also writes **`out/manifest.json`**, which lists
every time this protocol refers to, in UTC and epoch millis:

```bash
python3 -m json.tool docs/floormap-testdata/out/manifest.json
```

Keep it open. Where a test below says **"desk-106 moves"** or **"bob stops"**, that manifest entry
is the time to scrub to.

This replaces the absolute times earlier drafts carried. A stale landmark looks exactly like the bug
the test is checking for — an entity that is not where the document says — which cost a session
before it was noticed.

## 3. Check which time zone your timeline is showing

The manifest is **UTC**. Your Stroom user preference may not be.

Press **Show All** and compare the timeline's right-hand end with the manifest's `generatedAt`. Any
difference is your offset; apply it throughout.

## 4. Hard-reload first

`Ctrl-Shift-R`. GWT caches aggressively and a stale permutation will waste your time on symptoms
that no longer exist.

## 5. If the whole UI freezes with an empty console

Not the feature. It is the popup drag-glass defect
(`docs/task-popup-drag-glass-orphaned.md`): a `div.popupPanel-dragGlassVisible` is left over the
page swallowing every click. Delete that element in DevTools and the UI comes back. It is fixed on
this branch, so tell me if it recurs.

---

# The fixture

## Where things are

Everything lives in **`System / MB Tests`** — verified against the instance on 2026-09-09.

| Document | Facts store | Events store | Used by |
|---|---|---|---|
| `Test Floor Map` | `floor_map_facts` | `floor_map_events` | Sessions A, R, F, G1–G8, and B3/B4 |
| `Test Floor map (bulk)` | `floor_map_facts` | `floor_map_events_bulk` | H2 |
| `Test Floor Map (empty)` | `floor_map_facts` | `floor_map_events_empty` | G5, B5 |
| `Test Floor Map (no facts)` | `floor_map_facts_empty` | `floor_map_events` | G9, G10 |

**Note the casing.** The bulk map is `Test Floor map (bulk)` — lowercase `m` in "map" — which is
what exists, so search for it that way. Everything else is title case.

The stores are `floor_map_facts`, `floor_map_facts_empty` (SQL Temporal) and `floor_map_events`,
`floor_map_events_bulk`, `floor_map_events_empty` (Plan B, all `TEMPORAL_STATE`).

**Two maps this document used to name no longer exist.** The `Enterprise Floor Mapping Demo` map
went with the clean database, and `Test Floor Map (SQL)` — an events store that is a SQL Temporal
Store, for the store-parity comparison — was never rebuilt. That comparison is now covered by
`TestTemporalStoreParity` in the build, which asserts both stores answer identically, so there is
little left for a hand test to add.

## The floor plan — 9 facts, 10 versions

`bg-ground` (background), `area-north`, `area-south` (areas), and `desk-101` … `desk-106`.

All nine are laid out two days before generation. One of them moves, and that single move is what
most of Part 1 turns on:

> **`desk-106` moves** — from `(320, 240)` to `(460, 240)`, and its label changes from "Desk 106" to
> **"Desk 106 (moved)"**. Fifteen minutes before the end of the data; the manifest gives the exact
> time. It is the only fact whose position depends on where the timeline is.

## The entities — 6, of which 4 should be drawn

**Note which location form each uses.** `forklift-7` is the **only** coordinate-form entity, so it
is the only one exercising that path — and the only one that will *not* move when you move a desk.

| Entity | Form | Behaviour | Drawn at the right-hand end? |
|---|---|---|---|
| `alice@example.org` | `locationRef` | hops desk to desk throughout. The control: something must move | **yes** |
| `bob@example.org` | `locationRef` | moves, then **stops 5 minutes before the end** | **yes**, idle |
| `dave@example.org` | `locationRef` | parked at `desk-105`, re-emitting an unchanged value | **yes** |
| `forklift-7` | **`location`** | drifts across the floor on literal coordinates, not a fact key | **yes** |
| `carol@example.org` | `locationRef` | one event 7 hours back | **yes** — see below |
| `ghost@example.org` | `locationRef` | names `desk-999-does-not-exist` | **no**, dropped |

> **`carol` changed answer on 2026-09-09, and is now the most informative row in this table.**
> She used to be the horizon's witness: one event seven hours back, beyond the six-hour re-read, so
> she was *not* drawn and that absence was the point. The horizon is gone — Plan B now answers
> "latest row at or before T" server-side with no lower bound — so **carol is drawn**, at the
> position she reported seven hours ago.
>
> If carol is missing, the retirement of the client-side event state has regressed. If she is
> present, that alone confirms the new read is reaching past any window. She is worth checking
> before anything else in Part 1.

---

# Part 1 — the last three days' work. Do this part first

## Session A — the reference state (2 min)

**A0.** Open `Test Floor Map`. Press **Show All**. Drag the scrubber to the **far right** (the
manifest's `generatedAt`) and leave it **paused**.

**Expect:** the floor plan, and **exactly five** entities — `alice`, `bob` and `dave` each on a
desk, `forklift-7` out on its own coordinates away from any desk, and `carol` on the desk she
reported seven hours ago. Only `ghost` is absent. No status line. The Tracking panel lists five.

**This count changed on 2026-09-09 and the change is the point.** It was four: `bob` was the
witness that an entity silent for five minutes stays on the map, where before this branch anything
silent for twenty seconds vanished. `carol` was *excluded* as the witness for the six-hour horizon.
The horizon is gone — Plan B answers "latest row at or before the selected time" server-side — so
carol is drawn and the answer is five.

So this single state now proves both halves: an entity idle for minutes is kept, and an entity idle
for hours is kept too.

| | Result |
|---|---|
| A0 · **five** entities, `bob` and `carol` both present, `ghost` absent, no status line | |

**If A0 fails, stop and tell me** — everything below assumes it.

---

## Session R — the client-side event state is retired (F13) · **10 min**

Plan B gained a server-side latest-per-key read, so the machinery that kept positions in the
browser — a delta per tick, a six-hour re-baseline to correct it, a row-cap policy, two cadence
intervals — is deleted. One query per tick now asks for every entity's latest row at or before the
selected time, and the answer replaces what is drawn.

**Nothing here has ever been run by hand.** The build proves the semantics
(`TestTemporalStoreParity`), not the wiring.

| # | Do | Expect | Result |
|---|---|---|---|
| **R1** | From A0's paused far-right position, open the browser's Network tab, clear it, and **wait 90 seconds** without touching anything | **No events requests at all.** Nothing polls: reads happen on a timeline tick, and a paused timeline does not tick. Facts are separate — one facts request is expected (Session F) and is not this | **pass** 2026-09-09 |
| **R2** | Press play and watch Network | One events request per throttled tick, roughly **three a second**, each a fresh search. Same rate as before; what changed is what comes back, not how often | **pass** 2026-09-09 |
| **R3** | Pick any events request and read its response | **One row per entity**, not a window of history. Five rows for this fixture. If you see several rows for `alice`, the server is not reducing and everything else here is unsafe | **pass** 2026-09-09 |
| **R4** | Scrub **backwards** to the middle of the data, pause | Entities **teleport** rather than sliding, and positions are those at the scrubbed-to instant — no position later than it. `carol` stays drawn throughout | **pass** 2026-09-09 |
| **R5** | Scrub **forwards** past the end of the data | Positions hold at their last reported values; `carol` and `bob` both remain. Nothing blanks | **pass** 2026-09-09 |
| **R6** | Turn **Condense** on for `floor_map_events` **with a threshold of a few minutes**, wait for the next 10-minute boundary, and reload the map | `dave` is **still drawn**, at `desk-105`. His identical rows collapse to the earliest one, and the read takes each entity's latest row at or before the selected time whatever that row is — so a collapsed run no longer costs him his position. Before the retirement a collapsed run could fall outside the six-hour window and he would vanish | **pass** 2026-09-09 — store level, then the map |
| **R7** | Point the document's events store at a **Plan B store that has never been written to**, and open the Map | The map is empty and the console reports the read failed **once** — not once per tick. Then set it back | **pass** 2026-09-09 |

**R6 needs the API, and is worthless without it.** The Plan B settings UI's duration dropdown
starts at **days**, and this fixture is four hours old — so turning Condense on through the UI
collapses nothing at all and R6 passes without testing anything. Set the threshold below a day
first, e.g.

```
condense: { enabled: true, duration: { time: 5, timeUnit: MINUTES } }
```

If you cannot set it, **skip R6 rather than recording it as a pass** — a quiet result here means
the knob never engaged, not that the behaviour is right.

> **R6 was run at the store level on 2026-09-09 and passed**, which means the UI half is all that
> is left to confirm. Condense with a 5-minute threshold took the 29-row fixture to 21: `dave` 7
> rows to 1, and — not anticipated — `alice` 7 to 6 and `bob` 6 to 5, because each had two
> consecutive events at the same desk. `forklift-7` was untouched, every coordinate being different.
> A snapshot then still returned all six entities, `dave` among them at `desk-105` from his single
> surviving row.
>
> The bound was pushed out to **18:00** as well, putting `dave`'s row 11 h 52 m in the past and
> `carol`'s 14 h 52 m. Both still came back. That is the check worth copying if you repeat this:
> staying inside six hours would have passed under the old horizon too, so only a gap wider than
> the horizon distinguishes "no lower bound" from "a window that happened to be big enough".
>
> **Condense has been set back to off**, which is the documented default for this fixture. The rows
> it collapsed do not come back with it — re-upload `events.csv` to restore them, which you need to
> do anyway for the full fixture.

**And condense does not run when you enable it.** It is driven by the *Plan B state store maintain*
job, which is `EVERY_10_MINUTES` (`PlanBModule`), not by the every-minute merge — so nothing happens
until the next :00, :10, :20 and so on. Checking a minute after enabling it shows the uncondensed
store and proves nothing. `checkInterval` in the settings does not change this; the job schedule
does.

**Why R1 and R7 are the two that matter.** R1 is the one that regressed most easily: the old code
checked a cadence on demand *because* nothing was allowed to poll, and if the retirement
accidentally reintroduced a timer this is where it shows. R7 exercises the only remaining
report-once flag on this path; a store that has never been written to reports an error on every
read, so a missing flag turns into console spam three times a second.

**What would tell you it is wrong**

- Several rows for one entity in R3 — the server is not reducing, and R4/R5 become meaningless.
- `carol` disappearing at any point. She is seven hours stale, so she is the canary for a lower
  bound creeping back into the query.
- Any events request while paused in R1.
- A repeated console message in R7.

---

## Session F — facts no longer query per tick (F15) · **8 min**

The whole fact history is now read once a minute and each frame's snapshot is worked out in the
browser. `desk-106`'s one move is the only thing that can show whether that arithmetic is right.

| # | Do | Expect | Result |
|---|---|---|---|
| **F1** | Scrub to **30 minutes before** the desk-106 move and pause. Look at `desk-106` | Bottom-right at `(320, 240)`, labelled **"Desk 106"** | **pass** 2026-09-09 |
| **F2** | Scrub **past** the move | `desk-106` has jumped **right**, to `(460, 240)`, labelled **"Desk 106 (moved)"**. Nothing else has moved | **pass** 2026-09-09 |
| **F3** | Scrub **back** before it again · **the headline test** | `desk-106` returns to `(320, 240)` and **"Desk 106"**. This is the case that changed from a server round trip to a browser computation, so a mistake in the time comparison shows here and nowhere else | **pass** 2026-09-09 |
| **F4** | Start a few minutes before the move and play at **1×** past it, then repeat at **10×** | `desk-106` moves at the same timeline instant both times. It used to be accurate only to one tick of *wall clock* — about three seconds of timeline at 10× | **pass** 2026-09-09 |
| **F5** | DevTools → Network, filter `search`, then play for 30 s | Facts contribute **no** requests while playing. There will be one about every 60 s, and one each time you switch to the Map tab. Before this change there were about three a second | **pass** 2026-09-09 |
| **F6** | Leave the Map visible and **paused** for **80 seconds**, watching Network | **One** facts request appears, and only one. **Zero is a regression** — this is the case that was broken until 2026-09-07, when the cadence was only consulted while playing, so a paused map never re-read at all | **pass** 2026-09-09 |
| **F6b** | Switch to the **Editor** tab and leave it for two minutes, watching Network | **No** facts requests. The cadence must stop when the Map is not the tab on screen, or a backgrounded document keeps polling | **pass** 2026-09-09 |
| **F7** | Move a desk on the **Editor** tab, save, switch to **Map** | The move is there immediately, not 60 s later | **pass** 2026-09-09 |
| **F8** | Play, switch to a **different Stroom document**, wait 30 s, come back | The timeline is **paused where you left it**. Intended — see *Not bugs* | **pass** 2026-09-09 |

**What would tell you it is wrong**

- `desk-106` at `(460, 240)` when the timeline is before its move → the time comparison is inverted.
- `desk-106` never moving at all → the snapshot is not filtering by time.
- The floor plan **empty** while entities still animate → a facts read failed and was refused
  (correct) with nothing having been read successfully first.
- Any of these in the console, each of which should appear **at most once**:
  - *"the facts query failed, so the floor plan shown is the last that was read successfully"*
  - *"the facts store holds more than 20000 historical entries"* — there are **10**, so this would
    mean the map is pointed at the wrong store
  - *"the facts query is not returning the \"Effective Time Ms\" column"* — the generated query lost
    its `toLong(EffectiveTime)` column and the plan is showing latest-regardless-of-time

---

## Session G — the map now says why it is empty (F14 option 4) · **10 min**

Four things can leave the map blank and they used to look identical. There is now a line across the
top of the canvas naming which one. **Map tab only** — the Editor has its own canvas.

**Two registers, and telling them apart is most of what this session checks:**

- **quiet** — faint grey text, no border. *Nothing is wrong; there is simply nothing here.*
- **fault** — accent colour, bordered, slight shadow. *Something is misconfigured.*

| # | Do | Expect | Result |
|---|---|---|---|
| **G1** | Open `Test Floor Map`, then scrub the timeline **well past the end of the data** — a day ahead — and leave it **paused** | **"No events at this time"**, in the **quiet** register. Paused is the point: it is when someone is actually puzzling over an empty map, and it is what the first attempt at this got wrong. The floor plan is still drawn — only the people are missing | |
| **G2** | Press Show All to come back to the data | The line **disappears** as soon as entities are drawn | |
| **G3** | Events Query tab → **press Run first** (see below), then set the **Entity ID** dropdown to the **blank** entry at the top of the list → back to Map | **"Events found, but no entity could be read — check the column mapping"**, in the **fault** register: coloured and bordered. The **console** names which role is unset and lists the result's actual columns — that pairing is the design: the canvas says which stage, the console says why. **Then set Entity ID back** | |
| **G4** | Events Query tab (Run pressed) → set **Location Ref** to the **`Type`** column, and set **Location** to blank → back to Map | **"Entities reference locations that are not on this floor plan"**, **fault** register. Every entity now claims to be at `person` or `vehicle`, which no fact key matches. **Then put both back** | |
| **G5** | Open `Test Floor Map (empty)` | **Either** the quiet "no events" line **or** a reported read failure — and which one you get is the finding, not a pass/fail. See the note below; record which you saw | |
| **G6** | Reopen `Test Floor Map`, Show All, and watch the **first second** | **Nothing appears at all.** Facts and events arrive from independent reads, so there is a moment where events have landed and facts have not; a "no floor plan" line flashing on every open would be worse than the silence it replaces | |
| **G7** | Play through the middle of the data, where entities are present throughout | No line, and **no flicker**. Every read now returns the whole set rather than only what changed, so a tick returning no rows means the store genuinely holds nothing at or before that instant — which is what the line is for | |
| **G8** | Drag the right-hand dock wide so the canvas is narrow, while G3's line is showing | The line stays readable and does not collide with the scale bar bottom-left | |

### Why G3 and G4 need Run pressed first

The dropdowns hold their values from the moment the tab opens, but their **lists are empty until a
query result arrives on that tab** — they are populated from the result's columns, so with no result
there is nothing to choose. The values are still there and still displayed; you simply cannot change
them. Press **Run**, and all four lists fill with the query's column names plus a blank entry at the
top.

**And you cannot point a role at a column that does not exist.** The list only ever offers columns
the query *does* select, so the reachable fault is to select the **blank** — unmapping the role.
That is the better test anyway: unmapping is something a user can do by accident, whereas a role
naming a column the query does not select can only arise by editing the *query* after the mapping,
which is a different route with its own console message.

**G3, G4 and G9 are the ones to be most confident about.** They are the only tests that produce a
*fault*-styled line, so between them they prove the two registers really are different. If they show
a quiet line, the whole distinction is broken and G1/G5 passing means nothing.

### G9 — the fourth stage, `NO_FACTS`

This one needs a one-off setup, because it is the only stage that requires events **present** and
facts **absent**, and no such pair existed. `floor_map_facts_empty` now does (created through the
API on 2026-09-07, never written to). You need to make the map that pairs it with the populated
events store — two edits, once, and then it is there for good.

**Setup — do this once**

1. Right-click `Test Floor Map` → **Copy**. Rename the copy **`Test Floor Map (no facts)`**.
   Copying rather than creating is deliberate: it brings the value schema and both column settings
   with it, which a new document would make you retype.
2. **Settings** tab → change the **Facts Store** to `floor_map_facts_empty`. Leave the Events Store
   as `floor_map_events`.
3. **Events Query** tab → add one line, so the query reads:

   ```
   from param('EventStore')
   where Key != 'forklift-7'
   select EffectiveTime as "Effective Time",
     Key as "Entity ID",
     jq(Value, '.location') as "Location ID",
     jq(Value, '.type') as "Type",
     jq(Value, '.status') as "Status",
     jq(Value, '.message') as "Message"
   ```

4. Save.

**Why `forklift-7` has to go.** It is the one entity whose location is **coordinates**
(`100.0, 180.0`) rather than a fact key, so it needs no facts at all and would be placed
even with the facts store empty. One placed entity makes `classify` return `NONE` and the line never
appears — which is correct behaviour and exactly what the reporter's own javadoc warns about, but it
would make this test unrunnable. Excluding it leaves `alice`, `bob`, `carol`, `dave` and `ghost`,
all of which name fact keys.

| # | Do | Expect | Result |
|---|---|---|---|
| **G9** | Open `Test Floor Map (no facts)`, press Show All, scrub to about an hour before the end | **"No floor plan at this time, so entities have nowhere to be placed"**, in the **fault** register. The canvas is completely bare — no desks, no areas, no entities | |
| **G10** | Console, while G9 is showing | *"entities were found but there are no facts to place them on"* — **once**, not once a minute | |

**If G9 shows "No events at this time" instead**, the `where` clause has excluded too much, or the
timeline is outside the data. If it shows **nothing at all**, `forklift-7` is still being placed —
check the `where` line saved.

**Do not remove the `Effective Time` column from that query, and do not add a time term of your
own.** On a Plan B store, a `where` term on a field the `select` list omits filters out **every**
row, silently — so either change would blank the map while the status line said "No events at this
time", which is exactly the misleading case. Found while building this fixture; written up as
`docs/task-planb-where-field-not-selected.md`. The map is safe from it twice over — the read's own
time bound now routes the query to Plan B's snapshot path, where the field ordering is correct, and
the generated query selects `EffectiveTime` anyway — but a term on a **non-time** field you add
yourself is covered by neither.

> **G5 and R7 point at the same store, and this document used to expect opposite things of it.**
> `floor_map_events_empty` has never been written to. G5 assumed that reads as a legitimately empty
> store and asserted the quiet line; R7 assumes it reads as a *failure* and asserts an error
> reported once. Only one can be right, and the code says R7: `StoreShard.open` throws
> "Local Plan B shard not found" for a store with no shard, `StateSearchProvider` catches it, adds
> it to the result store and signals completion anyway — so the read reports failure rather than
> emptiness.
>
> But that depends on whether a merge has ever created an empty shard, which is not something this
> document can assert for you. **So run G5, record which of the two you see, and treat the answer as
> data**: the quiet line means an empty shard exists and emptiness is distinguishable from breakage;
> the error means it is not, which is the hazard written up in
> `docs/task-planb-where-field-not-selected.md` — a store that was never written and a query that
> could not run look the same to a caller.
>
> If you want the *unambiguously* empty case for G5's original purpose, point
> `Test Floor Map (empty)` at `floor_map_events` and scrub to before the data starts instead.
>
> **R7 passed on 2026-09-09**, and R7 is this same condition: a never-written store reported the
> read as *failed*, once. So expect G5 to show the error rather than the quiet line, and the code's
> answer is the one that holds — a store that was never written is not distinguishable from a query
> that could not run. Confirm it at G5 rather than assuming, since R7 pointed an existing document
> at the empty store while G5 opens a document already configured that way.

**What would tell you it is wrong**

- A **fault**-styled line for G1 — one of the cases that is *not* a fault. This is the failure
  that matters most, because it teaches people to ignore the line.
- A line on the **Editor** tab.
- A line during G6, or blinking during G7.
- A line still up after the map has drawn something (G2).

---

## Session H — the events change, the two tests never run (A2, A13) · **5 min**

Ten of this group passed on 2026-09-04. These two were skipped as low value; they are cheap now that
you are here.

| # | Do | Expect | Result |
|---|---|---|---|
| **H1** (was A2) | Set the timeline to the **far right** and read the Groups panel's occupancy counts. Note them. Now play forward — there is nothing after the end of the data, so the clock runs on with no new events | The counts **hold**, and now hold indefinitely rather than for a bounded window. `bob` is idle and `carol` has been idle for seven hours; both must stay counted. Before this branch an idle entity dropped out of area membership after twenty seconds while its glyph stayed on screen | |
| **H2** (was A13) | Open `Test Floor Map (bulk)`, Show All, and watch the console while it loads | **Nothing.** This test used to expect a row-cap message, because ~24 000 events exceeded the 20 000-row read. The read is one row per *entity* now, and the bulk fixture has far fewer than 20 000 entities, so the cap is unreachable by event volume. A cap message here means the read is no longer reducing server-side — report it | |

---

# Part 2 — content packs. Never exercised by hand · **15 min**

`FloorMapStoreImpl` overrode none of export, import, copy or delete, so assets were silently absent
from every content pack. A broken pack fails quietly, which is why this needs doing by hand.

B1 and B2 passed on 2026-09-04. These three did not.

| # | Do | Expect | Result |
|---|---|---|---|
| **B3** | Right-click `Test Floor Map` → Copy. Open the copy's Assets tab | The copy has **its own** assets. Upload a different image to the copy and confirm the original is unchanged | |
| **B4** | Delete the copy. Then check no orphaned asset rows remain | Assets removed, not orphaned. This needs a DB look or a fresh export to confirm properly — flag it if you cannot | |
| **B5** | Export `Test Floor Map (empty)`, which has **no** assets, then import it back | Works, no errors. The empty case is exactly where a new code path tends to break | |

**On B3** — you said copying onto a map that already has assets does not make sense as a user
action, and you are right. `copyLiveAssets` is documented as *"will throw an error if assets already
exist"*, so that path exists in the code whether or not the UI can reach it. If B3's simple case
passes, the honest follow-up is to make the impossible case impossible rather than to test it.

---

# Part 3 — setup and migration. The one that cannot be run later · **20 min**

## E1 — the bootstrap migration · **check this before you touch anything else**

> **Order matters more than anything else in this test.** The defect was that the SQL Temporal
> Store's connection provider was created *long after* bootstrap, by which time
> `DbMigrationState.haveBootstrapMigrationsBeenDone()` was already true and `FlywayUtil.migrate`
> returned without consulting Flyway at all. So `updatable_temporal_store` was never created on a
> clean start, and once dropped no restart recreated it.
>
> If you create a Floor Map or a SQL Temporal Store document first, you cannot tell a table that
> was created at bootstrap from one created later. **Check the database before using the UI for
> anything.**

**E1a — the tables exist.** Against the fresh database, before anything else:

```sql
SHOW TABLES LIKE 'updatable_temporal_store';
SHOW TABLES LIKE 'visualisation_assets%';
```

Expect `updatable_temporal_store`, and three document-asset tables:
`visualisation_assets`, `visualisation_assets_draft`, `visualisation_assets_update_delete`.

**`updatable_temporal_store` missing is the regression** — that is precisely the defect, and it is
the one assertion this test exists for.

**E1b — Flyway ran, rather than something else creating them.** Each module keeps its own history
table:

```sql
SELECT version, description, success, installed_on FROM sqlstore_schema_history;
SELECT version, description, success, installed_on FROM visualisation_assets_schema_history;
```

Expect **two rows each**: a `<< Flyway Baseline >>` row at version `1`, then the migration —
`07.13.00.001` for sqlstore, `07.11.00.001` for document-asset — all with `success = 1`. The
baseline row is there because `FlywayUtil` sets `baselineOnMigrate(true)`; it is normal, not a sign
of a pre-existing schema.

**The migration row is the assertion, and its absence is the regression** — note *absence*, not
failure. `FlywayUtil.migrate` branches on
`DbMigrationState.haveBootstrapMigrationsBeenDone()`: if the flag is already set it logs *"Skipping
database migration for module …"* and returns without consulting Flyway at all. Under the old
wiring the sqlstore provider was created after the flag was set, so that branch was taken and the
history table stayed **empty**. A row with `success = 1` is proof the migrating branch ran.

Read the timestamps too: within seconds of each other and of startup, because both providers are
resolved from the same `Set<DataSource>` that `BootstrapUtil` forces into existence. Minutes later
would mean they are still being created lazily on first use — which is what E1a's
"before you touch the UI" rule is there to catch.

**E1c — the schema is the post-F1 one.** There is only one sqlstore migration, and F1's fix is baked
into it rather than added by a second migration with a name→UUID backfill. So a clean database
should start correct with nothing to migrate:

```sql
SHOW CREATE TABLE updatable_temporal_store;
```

Expect `PRIMARY KEY (doc_uuid, key_, effective_time)` — **keyed on `doc_uuid`, not `map_name`**.
`map_name` should be present but only as a denormalised label with its own non-unique index. If the
primary key mentions `map_name`, the wrong migration ran.

**E1d — then write to it, not just create it.** Creating a SQL Temporal Store *document* proves
nothing about the table: `updatable_temporal_store` is a single shared table scoped by `doc_uuid`,
and a new document adds no row to it. The document lives in the doc store.

So a **write and a read back** are needed, and they test something the first three checks cannot:
the jOOQ classes are generated from the schema at build time, so if the migration on a clean
database produced a schema that differs from the one the generated code expects, every read and
write fails — and only a read or write shows it.

Cheapest route with no ingest pipeline on a clean instance: create a **Floor Map** through the init
dialog pointing at the store, then on the **Editor** tab add an object and save. That flushes
through `SqlTemporalStoreResource.applyChanges` straight into the table. Reload the document and
confirm the object is still there — that is the read back.

**E1e — while you have a clean instance**, two things only a fresh database can show:

- **No error banner or stack trace at startup**, and nothing in the log about a failed or skipped
  migration for `stroom-sqlstore` or `stroom-document-asset`.
- **`documentAsset.maxUploadSize` defaults to 50 MiB** with no config override present. Every
  existing config file omits the property, so a fresh instance is the only place the absent-value
  default is exercised for real — and a null cap would have disabled the limit on exactly the
  deployments it was added for.

| # | Result |
|---|---|
| E1a · `updatable_temporal_store` and the three asset tables exist | **pass** 2026-09-08 |
| E1b · both history tables carry baseline + migration, `success = 1`, timed with startup | **pass** 2026-09-08 — document-asset 07:20:19, sqlstore 07:20:24 |
| E1c · primary key is `(doc_uuid, key_, effective_time)` | **pass** 2026-09-08 |
| E1d · a store can be written to and read back | **pass** 2026-09-08 — object saved and reloaded correctly, so the migrated schema matches the generated jOOQ classes |
| E1e · clean startup, and the 50 MiB upload default applies | **pass** 2026-09-08 — a 110 MB upload was refused, naming the 50 MB limit |

---

## E2 — the deprecated config keys

| # | Do | Expect | Result |
|---|---|---|---|
| **E2** | Start with the **old** config keys `visualisationAsset` / `visualisationAssetDb` | **Boot fails**, naming the new spelling: *"'appConfig.visualisationAsset' is now 'appConfig.documentAsset'"*. That is the intended behaviour as of 2026-09-08 — a rename the operator must know about is the case where failing is right. Rename the key and Stroom starts | **pass** 2026-09-08 — boot failed naming the replacement; renaming the key started Stroom with the Properties screen showing `1M`, source **YAML** |

**This test was rewritten twice, and both mistakes are worth knowing.** It first expected a
*deprecation warning* — nothing logged one, so that sent the tester hunting for a line that could
not appear. It then expected the key to be *accepted*, and "it started" turned out not to be an
assertion at all: a key that is tolerated-and-ignored looks identical to one that is
tolerated-and-applied, which is exactly how the defect survived a fortnight and a passing unit test.
What settles it now is a boot that fails and says what to rename.

E3 and E4 passed on 2026-09-04.

---

# Also outstanding, not a test

~~Two orphaned processor filters need deleting in **Monitoring → Processing**~~ — **resolved
2026-09-08** by the clean database, which took them with it. Kept here only so that a reappearance
is recognised rather than rediscovered: they were `19925dea-cb9b-45df-9735-de0014ae531a` and
`1994b331-f3c9-44ad-ba03-82abeda5d1f3`, and no API covers deleting one, so a recurrence needs the UI.

---

# Not bugs — do not file these

- **Playback stopping while you were on another Stroom document.** Intended as of 2026-09-04: you
  come back to the position you left rather than to wherever the clock ran on to. Inner-tab switches
  have always paused; this makes the outer tab behave the same way.
- **The timeline opening at NOW ± 24 hours** rather than fitted to the data. Press Show All.
- ~~**A one-tick flicker** when a baseline lands while deltas were in flight~~ — **gone
  2026-09-09.** There are no deltas and no baseline; each read is one snapshot that replaces the
  drawn set, so there is nothing in flight to be overtaken. A position jumping backwards during
  playback is now worth reporting rather than expecting.
- ~~**During playback**, a dropped entity's counts and roster update while its **glyph
  persists**~~ — **cannot happen now.** Nothing is dropped for being idle, because nothing prunes
  on absence. If counts and glyphs disagree, report it.
- ~~**The horizon is not enforced on a SQL Temporal Store**~~ — **there is no horizon on either
  store.** Both now answer "latest row at or before the selected time", so the two behave the same
  way here. That was the whole point of retiring the client-side state.
- **An entity that stopped emitting long ago is still drawn**, at the position it last reported.
  This is the deliberate counterpart of the horizon going: absence is no longer distinguishable
  from stillness, so the positioned count is even less of a head-count than before. `carol` is the
  fixture's witness for it.
- **No background image** on `Test Floor Map` unless you have uploaded one.
- **Trails resuming at full opacity** when an entity starts moving again. Known and untriaged —
  **F16** in the remediation plan. Report anything *else* trail-shaped.

---

# One warning about interpreting results

Building F14 option 4 turned up that F14's earlier options had **never reported anything**: a
per-tick `reset()` pinned the persistence counter at 1, so its threshold of 3 was unreachable. The
silence was recorded as a passing test, because quiet was the expected result there.

So if a whole session comes back clean, that is worth a moment's suspicion rather than relief.
**G3, G4 and G9 are the antidote**: they are the only tests here that force a message to appear, and
G9 forces one on the console too. If those three produce their lines, the quiet results elsewhere
mean something. If they do not, nothing else in Session G does.
