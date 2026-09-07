# Floor Map — test protocol

**Run this document.** It is ordered, each step says exactly what to do and exactly what to expect,
and every expected value below was read back from the live instance on **2026-09-07 07:09 UTC**.

`floormap-test-plan.md` is the companion: it explains *why* each test exists and records what has
already passed. This document is what you work through.

**Estimated time:** Part 1 is about 25 minutes and covers everything built in the last two days.
Parts 2 and 3 are about 20 minutes each and cover older work that has never been exercised by hand.

---

# Before you start — read this, it will save you an hour

## 1. The fixture data is three days old, and the map will look empty

The data was generated on **2026-09-04**. A floor map opens showing **NOW ± 24 hours**, which today
is 2026-09-06 → 2026-09-08. **Every entity is about three days off the left-hand edge of that
window.**

So a freshly opened map shows **the floor plan but no people**, plus a line across the top saying
*"No events at this time"*. **That is correct on all three counts** — and it is also test **G1**, for
free.

**The way in is the "Show All" button on the timeline.** Every test below assumes you have pressed
it, unless it says otherwise.

You could instead regenerate the data so it sits at "now" (`docs/floormap-testdata/README.md`), but
then every timestamp in this document changes and you would have two generations of `alice` in the
store. Absolute times are more checkable. Stay with the old data.

## 2. Check which time zone your timeline is showing

Every time below is **UTC**. Your Stroom user preference may not be.

**Press "Show All" and read the timeline's two end labels.** They should span
**2026-09-04 01:11:47 → 08:24:20**. If they read something else with the same 7h13m span, that
difference is your offset — add it to every time in this document. The epoch-millisecond column is
given throughout as an unambiguous anchor.

## 3. Hard-reload first

`Ctrl-Shift-R`. GWT caches aggressively and a stale permutation will waste your time on symptoms
that no longer exist.

## 4. If the whole UI freezes with an empty console

Not the feature. It is the popup drag-glass defect
(`docs/task-popup-drag-glass-orphaned.md`): a `div.popupPanel-dragGlassVisible` is left over the
page swallowing every click. Delete that element in DevTools and the UI comes back. It is fixed on
this branch, so tell me if it recurs.

---

# The fixture

## Where things are

| | |
|---|---|
| **Main test map** | `System / Floor Map Test / Test Floor Map` |
| Events store (Plan B) | `floor_map_events` |
| Facts store (SQL Temporal) | `floor_map_facts` |
| Empty-store map | `Test Floor Map (empty)` |
| Empty **facts** store, for G9 | `floor_map_facts_empty` — created 2026-09-07, deliberately never written to |
| Over-budget map (~24 000 events) | `Test Floor Map (bulk)` → `floor_map_events_bulk` |
| SQL-store comparison map | `System / Enterprise Floor Mapping Demo / Floor Map` |

## The floor plan — 9 facts, 10 versions

`bg-ground` (background), `area-north`, `area-south` (areas), and `desk-101` … `desk-106`.

All nine were created at **2026-09-02 08:11:47**. One of them moves, and that single move is what
most of Part 1 turns on:

> **`desk-106` moves at 2026-09-04 07:56:47** — from `(320, 240)` to `(460, 240)`, and its label
> changes from "Desk 106" to **"Desk 106 (moved)"**. It is the only fact in the store whose position
> depends on where the timeline is.

## The entities — 6, of which 4 should be drawn

| Entity | Type | Behaviour | Drawn at 08:24:20? |
|---|---|---|---|
| `alice@example.org` | person | hops desk to desk for the whole window. The control: something must move | **yes** — `desk-103` |
| `bob@example.org` | person | moves, then **stops at 08:19:20** | **yes** — `desk-102`, five minutes idle |
| `dave@example.org` | person | parked at `desk-105`, re-emitting an unchanged location | **yes** — `desk-105` |
| `forklift-7` | vehicle | location is **coordinates**, `B-GND, x, 180.0`, not a fact key | **yes** — drifting right |
| `carol@example.org` | person | one event at 01:24:20 and nothing after — beyond the 6 h horizon | **no** |
| `ghost@example.org` | person | location is `desk-999-does-not-exist` | **no**, silently dropped |

## Landmark times

| Landmark | UTC | epoch ms |
|---|---|---|
| Facts created (all 9) | 2026-09-02 08:11:47 | 1788336707000 |
| `carol`'s only events | 2026-09-04 01:11:47 – 01:24:20 | 1788484307000 – 1788485060000 |
| Events begin | 2026-09-04 04:24:20 | 1788495860000 |
| **`carol` falls out of the horizon** | 2026-09-04 07:24:20 | 1788506660000 |
| **`desk-106` moves** | 2026-09-04 07:56:47 | 1788508607000 |
| `bob` stops | 2026-09-04 08:19:20 | 1788509960000 |
| Events end | 2026-09-04 08:24:20 | 1788510260000 |

---

# Part 1 — the last two days' work. Do this part first

## Session A — the reference state (2 min)

**A0.** Open `Test Floor Map`. Press **Show All**. Drag the scrubber to the far right, i.e.
**08:24:20**, and leave it **paused**.

**Expect:** the floor plan, and **exactly four** entities — `alice` on `desk-103`, `bob` on
`desk-102`, `dave` on `desk-105`, and `forklift-7` out on its own coordinates. No status line. The
Tracking panel lists four.

This single state proves the headline events behaviour: **`bob` is still on the map** although his
last event was five minutes earlier. Before this branch, anything silent for twenty seconds
vanished.

| | Result |
|---|---|
| A0 · four entities, `bob` present, no status line | |

**If A0 fails, stop and tell me** — everything below assumes it.

---

## Session F — facts no longer query per tick (F15) · **8 min**

The whole fact history is now read once a minute and each frame's snapshot is worked out in the
browser. `desk-106`'s one move is the only thing that can show whether that arithmetic is right.

| # | Do | Expect | Result |
|---|---|---|---|
| **F1** | Scrub to **07:30:00** and pause. Look at `desk-106` | Bottom-right at `(320, 240)`, labelled **"Desk 106"** | |
| **F2** | Scrub forward to **08:10:00** | `desk-106` has jumped **right**, to `(460, 240)`, labelled **"Desk 106 (moved)"**. Nothing else has moved | |
| **F3** | Scrub **back** to 07:30:00 · **the headline test** | `desk-106` returns to `(320, 240)` and **"Desk 106"**. This is the case that changed from a server round trip to a browser computation, so a mistake in the time comparison shows here and nowhere else | |
| **F4** | Set the scrubber to 07:50:00, play at **1×** until past 07:56:47, then repeat at **10×** | `desk-106` moves at the same timeline instant both times. It used to be accurate only to one tick of *wall clock* — about three seconds of timeline at 10× | |
| **F5** | DevTools → Network, filter `search`, then play for 30 s | Facts contribute **no** requests while playing. There will be one about every 60 s, and one each time you switch to the Map tab. Before this change there were about three a second | |
| **F6** | Leave the Map visible and **paused** for **80 seconds**, watching Network | **One** facts request appears, and only one. **Zero is a regression** — this is the case that was broken until 2026-09-07, when the cadence was only consulted while playing, so a paused map never re-read at all | |
| **F6b** | Switch to the **Editor** tab and leave it for two minutes, watching Network | **No** facts requests. The cadence must stop when the Map is not the tab on screen, or a backgrounded document keeps polling | |
| **F7** | Move a desk on the **Editor** tab, save, switch to **Map** | The move is there immediately, not 60 s later | |
| **F8** | Play, switch to a **different Stroom document**, wait 30 s, come back | The timeline is **paused where you left it**. Intended — see *Not bugs* | |

**What would tell you it is wrong**

- `desk-106` at `(460, 240)` when the timeline is before 07:56:47 → the time comparison is inverted.
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
| **G1** | Open `Test Floor Map` and **do not** press Show All. Leave it at the default NOW ± 24 h, paused | **"No events at this time"**, in the **quiet** register. Paused is the point: it is when someone is actually puzzling over an empty map, and it is what the first attempt at this got wrong. The floor plan is still drawn — only the people are missing | |
| **G2** | Press Show All and scrub into the data | The line **disappears** as soon as entities are drawn | |
| **G3** | Events Query tab → set **Entity ID Column** to `Nonsense` → back to Map | **"Events found, but no entity matched the Entity ID column"**, in the **fault** register: coloured and bordered. **Then put it back to `Entity ID`** | |
| **G4** | Events Query tab → set **Location ID Column** to `Type` → back to Map | **"Entities reference locations that are not on this floor plan"**, **fault** register. Every entity now claims to be at `person` or `vehicle`, which no fact key matches. **Then put it back to `Location ID`** | |
| **G5** | Open `Test Floor Map (empty)` | The **quiet** "no events" line, **not** a fault. An empty store is not a misconfiguration | |
| **G6** | Reopen `Test Floor Map`, Show All, and watch the **first second** | **Nothing appears at all.** Facts and events arrive from independent reads, so there is a moment where events have landed and facts have not; a "no floor plan" line flashing on every open would be worse than the silence it replaces | |
| **G7** | Play through 04:30 → 08:00, where entities are present throughout | No line, and **no flicker**. Most delta ticks legitimately return no rows — an entity that has not moved emits nothing — so a naive check would blink once per tick | |
| **G8** | Drag the right-hand dock wide so the canvas is narrow, while G3's line is showing | The line stays readable and does not collide with the scale bar bottom-left | |

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
(`B-GND, 100.0, 180.0`) rather than a fact key, so it needs no facts at all and would be placed
even with the facts store empty. One placed entity makes `classify` return `NONE` and the line never
appears — which is correct behaviour and exactly what the reporter's own javadoc warns about, but it
would make this test unrunnable. Excluding it leaves `alice`, `bob`, `carol`, `dave` and `ghost`,
all of which name fact keys.

| # | Do | Expect | Result |
|---|---|---|---|
| **G9** | Open `Test Floor Map (no facts)`, press Show All, scrub to **08:00:00** | **"No floor plan at this time, so entities have nowhere to be placed"**, in the **fault** register. The canvas is completely bare — no desks, no areas, no entities | |
| **G10** | Console, while G9 is showing | *"entities were found but there are no facts to place them on"* — **once**, not once a minute | |

**If G9 shows "No events at this time" instead**, the `where` clause has excluded too much, or the
timeline is outside the data. If it shows **nothing at all**, `forklift-7` is still being placed —
check the `where` line saved.

**Do not add a time term to that `where` clause.** On a Plan B store a hand-written time term
returns zero rows without an error, so the map would go blank and the status line would say "No
events at this time" — misleading, since the store is full. Noticed while building this fixture and
recorded in the remediation plan; the map's own horizon is unaffected, because it passes ranges as a
`TimeRange` rather than as query text.

**What would tell you it is wrong**

- A **fault**-styled line for G1 or G5 — the two cases that are *not* faults. This is the failure
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
| **H1** (was A2) | Set the timeline to **08:24:20** and read the Groups panel's occupancy counts. Note them. Now play forward — there is nothing after 08:24:20, so the clock runs on with no new events | The counts **hold**. They must not fall while `bob` sits idle on `desk-102`. Before this branch, an idle entity dropped out of area membership after twenty seconds while its glyph stayed on screen | |
| **H2** (was A13) | Open `Test Floor Map (bulk)`, Show All, and watch the console while it loads | Either nothing, or **one** message about the 20 000-row limit — not a repeat every minute. ~24 000 events is deliberately over budget | |

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

| # | Do | Expect | Result |
|---|---|---|---|
| **E1** | Start Stroom against a **fresh, empty database** | The SQL Temporal Store migration runs at bootstrap with **no manual step**. **Do this one.** It is the only test here that cannot be run after release, and a broken bootstrap is discovered by a customer rather than by us | |
| **E2** | Start with the **old** config keys `visualisationAsset` / `visualisationAssetDb` | Accepted, with a deprecation warning. Do **not** add the new keys alongside — the last occurrence wins, so the test would prove nothing | |

E3 and E4 passed on 2026-09-04.

---

# Also outstanding, not a test

Two orphaned processor filters need deleting in **Monitoring → Processing**. No API covers this, so
it needs the UI:

- `19925dea-cb9b-45df-9735-de0014ae531a`
- `1994b331-f3c9-44ad-ba03-82abeda5d1f3`

---

# Not bugs — do not file these

- **Playback stopping while you were on another Stroom document.** Intended as of 2026-09-04: you
  come back to the position you left rather than to wherever the clock ran on to. Inner-tab switches
  have always paused; this makes the outer tab behave the same way.
- **The timeline opening at NOW ± 24 hours** rather than fitted to the data. Press Show All.
- **A one-tick flicker** when a baseline lands while deltas were in flight: it replaces state
  wholesale, so an entity that moved during its flight can jump back one tick before the next delta
  corrects it.
- **During playback**, a dropped entity's counts and roster update while its **glyph persists**.
  Pre-existing, which is why the horizon tests say pause first.
- **The horizon is not enforced on a SQL Temporal Store**, which strips all time terms — so
  horizon-drop behaviour is Plan B only.
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
