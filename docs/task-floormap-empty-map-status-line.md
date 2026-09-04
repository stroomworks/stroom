# Tell the user why the floor map is empty, on the map

**Component:** `stroom-core-client` — `stroom.floormap.client` (canvas presenter and view)
**Origin:** F14 option 4 in `docs/floormap-remediation-plan.md`. Options 2 and 3 landed
2026-09-04 (`7e797509a2`); this is the remaining piece.
**Size:** small, but with real taste risk — see *Wording*.

> **BUILT 2026-09-04.** Kept for the record; nothing here is outstanding. Two things went
> differently from this spec, both recorded in F14's *As built*:
>
> 1. **The API gap named below was resolved the other way.** This asked for a `currentStage()`
>    accessor so the line could read the persistence-filtered state. The line does not use that
>    filter at all: a filter exists because a repeated *log line* is noise, whereas rewriting a
>    status line is invisible — and waiting three observations means saying nothing on a **paused**
>    timeline, where only one events read ever lands, which is exactly when someone is puzzling over
>    an empty map. The real transient is checked directly instead, via `factHistory.isLoaded()`.
> 2. **Bottom-centre became top-centre**, because bottom-centre runs into the scale bar on a narrow
>    pane and the pane is routinely narrow with the dock open.
>
> Building it also turned up a defect in the options this was sequenced after: the persistence
> filter had never let anything through, because `reset()` ran once per tick.

---

## The problem

Four stages of the events pipeline can each produce nothing, and all four look identical on
screen: an empty map. `FloorMapStageReporter` now works out **which** stage came up empty and
says so — but only to `Console.error`, i.e. the browser developer console behind F12.

So the diagnosis exists and the person who needs it cannot see it. An operator gets an empty map
and no explanation, and cannot distinguish any of these:

| Actually happening | What they should conclude |
|---|---|
| The timeline is somewhere with no data | Nothing is wrong. Move the timeline |
| The events store is empty or unreachable | Check ingest |
| The Entity ID / Location ID columns do not match the query | Fix the settings |
| No facts, so entities have nowhere to be placed | Check the facts store |

The first of those is **not a fault at all**, which is exactly why this cannot be built as an
error banner.

This is not hypothetical. During the 2026-09-04 test session, diagnosing why a pre-existing floor
map drew no moving entities required querying its store through the API, because nothing on screen
distinguished "no data at this time" from "misconfigured". That was a developer with API access; an
operator has neither.

## What already exists, and what is missing

Landed, so the logic is done:

- `FloorMapStageReporter.classify(...)` — cascades the four stages, returns the first empty one.
- `FloorMapStageReporter.observe(...)` — adds the persistence filter: a stage must stay empty for
  `PERSISTENCE_TICKS` (3, about a second) before it is reported, and each episode reports once.
- `FloorMapMapPresenter.reportEmptyStage(...)` — classifies at the one point that can see all four
  stages, and emits to the console.

On the canvas, available to build on:

| Exists | Use |
|---|---|
| `refreshAccessibleSummary()` | builds the map's accessible name — "Floor map at 08:24, 4 people…" |
| `announce(String)` | an ARIA live region, already used for zoom, selection and tracking |
| grid overlay, scale bar, current-time text | precedent for chrome over the canvas |

**One API gap, deliberately left.** `observe()` returns a stage only on the *reporting edge*, and
`null` both for "fine" and "not persistent yet". A status line needs the **current** state so it can
appear *and disappear*. So this needs either a `currentStage()` accessor on the reporter, or
`observe` to always return the stage and let the caller decide what to do with it. Small, but it is
work that does not exist yet.

## What to build

An unobtrusive status line on the canvas, shown while a stage is persistently empty and removed as
soon as something is drawn.

**A DOM element overlaid on the canvas, not text painted into it.** Painting would match the scale
bar, but painted text cannot be selected, copied, or read by a screen reader. An
absolutely-positioned element gets all three, and can be styled and hidden with CSS.

Also feed `refreshAccessibleSummary()`, so a screen-reader user gets the same information from the
map's accessible name, and `announce(...)` **once** per episode — never repeatedly.

## Wording

The console messages are far too long for a status line, and the register has to change. The
distinction that matters:

- ✅ "No events at this time" — a statement of fact
- ❌ "Error: events query returned no rows" — crying wolf at a map that is correctly empty

Suggested, to be agreed before any view code is written:

| Stage | Status line | Is it a fault? |
|---|---|---|
| `NO_EVENT_ROWS` | "No events at this time" | **Usually not** — most often the timeline is outside the data |
| `NO_ENTITIES_PARSED` | "Events found, but the Entity ID / Location ID columns do not match the query" | Yes, almost always |
| `NO_FACTS` | "No floor plan at this time — entities have nowhere to be placed" | Usually yes |
| `NO_PLACEMENTS` | "Entities reference locations that do not exist on this floor plan" | Yes |

Note the first and the rest should not *look* the same. A neutral tone for `NO_EVENT_ROWS` and
something more attention-drawing for the other three would be right; a single uniform "warning"
style would make the common, harmless case read as breakage.

## What it must not be

- **Not** a modal, toast, growl or anything that takes focus. An empty map is frequently correct.
- **Not** shown on the reporting edge only — it must clear itself when the map recovers.
- **Not** shown during normal startup. Facts and events come from independent queries, so there is
  routinely a tick or two where events have landed and facts have not. The persistence filter
  already handles this; do not bypass it.
- **Not** on the Editor tab's canvas unless it reads correctly there too — the two tabs share
  `FloorMapCanvasPresenter` but have different expectations about emptiness.

## Acceptance criteria

- With the timeline moved outside the data, the map shows the neutral "no events at this time" line
  and no console-style alarm.
- With the Entity ID column deliberately set to a name the query does not select, the map says so.
- With the facts store empty and events present, the map says the floor plan is missing.
- The line **disappears** within one tick of the map drawing something.
- Nothing appears during a normal open, or during a scrub through a sparse stretch.
- The same information reaches a screen reader through the accessible name, announced once.

## Why it was sequenced after options 2 and 3

Because it needs wording that does not alarm during startup, and that is precisely what the
persistence filter provides. Built first, it would have flickered a warning on every open of every
map — which is worse than the silence it replaces.
