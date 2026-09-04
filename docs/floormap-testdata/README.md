# Floor Map test data

Fixtures for [`../floormap-test-plan.md`](../floormap-test-plan.md). Generates the data, and the
Stroom content to ingest it, for the Group A tests plus the facts floor plan everything else needs.

**Regenerate before every test session.** Every timestamp is relative to the moment of generation,
because two fixtures depend on it: the baseline horizon reaches six hours back from the *selected*
time, and the timeline opens on the span of the data. Yesterday's files put every entity outside the
horizon, which looks exactly like the bug A3 is testing for.

---

## What is here

| File | Purpose |
|---|---|
| `generate.py` | Writes the CSV fixtures into `out/` (git-ignored) |
| `CSV_WITH_HEADER.TextConverter.xml` | Data Splitter for the CSV. Stock Stroom converter, copied so this is self-contained — if your instance has one, use that |
| `floormap-facts.xslt` | `records:2` → `reference-data:2` for the **SQL Temporal Store** |
| `floormap-events.xslt` | `records:2` → `reference-data:2` for the **Plan B** store |

The CSV stays flat and the XSLTs assemble the JSON value. That is deliberate: the value is a JSON
object full of double quotes, which no CSV container can carry cleanly, and it puts the value schema
in one readable place. Only `location` contains commas (the coordinate form,
`"B-GND, 120.5, 340"`), which the splitter's container chars handle.

---

## 1. Create the stores

| Document | Type | Name | Settings that matter |
|---|---|---|---|
| Facts | **SQL Temporal Store** | `floor_map_facts` | none |
| Events | **Plan B** | `floor_map_events` | State Type **Temporal State**; Condense **off**; snapshots off |
| Events, over budget | **Plan B** | `floor_map_events_bulk` | as above — for **A11** |
| Events, never written | **Plan B** | `floor_map_events_empty` | as above, and **ingest nothing into it** — for **A12** |

Plan B names must match `^[a-z_0-9]+$`. The name is substituted into the events query's
`param('EventStore')`, so it ends up in a `from` clause.

`stateType` and the key/value schema are **immutable once data is written** — a change means
delete and recreate, which wipes the store. Get these right first.

> **A4 needs `condense` on, and its default threshold is useless here.** Create the store with
> condense **off**, ingest, confirm the map works, then turn it on and re-check — the other order
> cannot distinguish "condense broke it" from "it never worked".
>
> When you turn it on, **shorten the duration**. Condense only removes runs *older than* its
> threshold, and the default is **1 day** while this data is at most 4 hours old — so enabling it
> with defaults collapses nothing and A4 passes vacuously. Set it to a few minutes
> (`condense: {enabled: true, duration: {time: 5, timeUnit: MINUTES}}`) so `dave`'s repeats
> actually collapse.

---

## 2. Create the content

1. **Text converter** — new **Text Converter**, type *Data Splitter*, paste
   `CSV_WITH_HEADER.TextConverter.xml`.
2. **Two XSLTs** — new **XSLT** documents, paste `floormap-facts.xslt` and `floormap-events.xslt`.
3. **Two feeds** — e.g. `FLOOR_MAP_FACTS` and `FLOOR_MAP_EVENTS`. Status **Receive**.
4. **Two pipelines.** Both are `Source → CombinedParser (with the text converter) → XSLTFilter
   (with the XSLT) → destination`; only the destination differs:

   | Pipeline | Destination element | Feeds |
   |---|---|---|
   | Facts | **SqlStoreFilter** | `FLOOR_MAP_FACTS` |
   | Events | **PlanBFilter** | `FLOOR_MAP_EVENTS` |

   Inherit from a suitable template pipeline and override the element properties rather than
   authoring `pipelineData` by hand.

   **Do not put a `SchemaFilter` before either destination.** Both consume `reference-data:2`,
   which extends the reference-data schema rather than `records:2`, so a SchemaFilter positioned
   there fails on valid output.

5. **Verify the wiring before ingesting anything:** `fetchDependencies` on each pipeline — every
   entry must be `ok`. A broken XSLT reference produces empty output rather than an error.

6. **Processor filters** on both pipelines for their feeds.

---

## 3. Generate and ingest

```bash
python3 docs/floormap-testdata/generate.py
```

Then upload each file to its feed:

| File | Feed | `<map>` it targets |
|---|---|---|
| `out/facts.csv` | `FLOOR_MAP_FACTS` | `floor_map_facts` |
| `out/events.csv` | `FLOOR_MAP_EVENTS` | `floor_map_events` |
| `out/events-bulk.csv` | `FLOOR_MAP_EVENTS` | `floor_map_events_bulk` |

The map name is a **column in the CSV**, so one feed and one pipeline serve every store — routing
is in the data, not the configuration.

Processing is asynchronous and **Plan B takes two stages**, so budget about five minutes:

| Stage | Cadence | Measured |
|---|---|---|
| Processor filter task creation | polls on an interval | ~90 s to pick up an uploaded stream |
| `PlanBFilter` writes a shard | with the task | — |
| **Plan B state store merge** | cron, every minute | shard becomes queryable |

The SQL Temporal Store has no merge stage, so facts appear as soon as the task runs. Plan B does,
which is why an events upload looks like it has failed for several minutes. **A Plan B query can
also return transiently empty while a merge is in flight** — re-run it before concluding anything.

Neither pipeline writes an `Events` output stream: both end in a destination filter, not a writer.
So "no Events stream" is normal here and is *not* a symptom.

Confirm before moving on:

- `Events` output streams exist and there are **no `Error` streams** on either feed
- the Plan B document's **Data** tab shows rows
- the SQL Temporal Store's **Data** tab shows rows

If a Plan B ingest fails, the two messages worth recognising are
`Temporal state 'time' is null` (no `<time>` and no stream effective time) and
`Unexpected Plan B store type for Temporal State` (the store is not `TEMPORAL_STATE`).

---

## 4. Create the floor maps

| Floor map | Facts store | Events store | Serves |
|---|---|---|---|
| `Test Floor Map` | `floor_map_facts` | `floor_map_events` | **A1–A9, A13**, and Groups D/E |
| `Test Floor Map (bulk)` | `floor_map_facts` | `floor_map_events_bulk` | **A11** |
| `Test Floor Map (empty)` | `floor_map_facts` | `floor_map_events_empty` | **A12** |
| `Test Floor Map (SQL)` | `floor_map_facts` | **a SQL Temporal Store** | **A10** — must show no change |

Leave the generated events query alone. It already reads the value schema this data writes, and
**A10 depends on it being the default** — a custom query with a `sort`, or an entity id not taken
from `Key`, deliberately switches the map to a weaker row reduction.

For **A10** you need an events store that is a SQL Temporal Store. Ingest `events.csv` a second
time through a *facts*-style pipeline (`SqlStoreFilter`) with the map column rewritten to a SQL
store's name — or point the map at a pre-Plan-B document if you still have one.

---

## What each fixture is for

Every entity in `events.csv` exists to make exactly one test decidable.

| Entity | Behaviour | Test | Expected |
|---|---|---|---|
| `alice@example.org` | hops desk to desk every 10 s for 30 min | control | moves, animates, leaves a trail |
| `bob@example.org` | moves, then **stops 5 minutes before now** | **A1** | **stays** — idle far past the old 20 s window, inside the 6 h horizon |
| `carol@example.org` | one event **7 hours ago**, nothing since | **A3** | **drops** at the next baseline, and the group count falls with her |
| `dave@example.org` | parked at `desk-105`, re-emitting the **same** value every 5 s | **A4** | survives with `condense` **on** — these are the rows it collapses |
| `forklift-7` | coordinate form, `"B-GND, x, y"` | both location forms | drawn at literal coordinates, unaffected by moving desks |
| `ghost@example.org` | references `desk-999-does-not-exist` | F14 reporting gap | **silently dropped** — expected. Present so the console message is recognisable when it is *not* expected |

And in `facts.csv`:

| Fact | Why |
|---|---|
| `desk-106` written **twice** — moved 15 min before generation | Makes **A5/A6** and **D2** decidable: the correct picture at T depends on T, so a stale facts snapshot is visible rather than merely suspected. Whoever is at `desk-106` should move with it |
| `area-north`, `area-south` | Area containment and the "*n* of *m*" group counts (**A2**) |
| `bg-ground` | Background placed by `tm-world-to-map`, like every other fact |

`events-bulk.csv` is 24 000 rows across 60 entities inside a six-hour window, against a 20 000-row
cap — so **A11**'s baseline truncates. That is not a contrived number: 20 000 rows over six hours is
0.93 events/second sustained, which a hundred entities emitting once a minute already exceeds.

---

## Traps

- **`ground-floor.png` does not exist.** The background fact names an image that is not uploaded, so
  no background renders. Upload one as a document asset and set `img` to its name if you want the
  backdrop; none of the Group A tests need it.
- **Do not wait out the horizon.** It is six hours. `carol` is backdated instead, which is why A3 is
  a two-minute test rather than an afternoon.
- **A3 says pause first.** During playback a pruned entity's counts, roster and membership update
  but the glyph persists, because the draw list re-emits from the animator's last positions and
  prunes only on the teleport path. Pre-existing, not a regression.
- **Regenerate.** Said twice on purpose.
