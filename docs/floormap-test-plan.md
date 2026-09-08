# Floor Map — manual test plan

> ## This is not the document to run
>
> **[`floormap-test-protocol.md`](floormap-test-protocol.md) is.** It is the ordered, executable
> one: what to do, in what order, and exactly what to expect.
>
> This is the **record** — what has already been run and what it found. It carries no steps and no
> fixture times, deliberately: it used to duplicate both, and once the fixtures were regenerated on
> 2026-09-07 its absolute times pointed at data that no longer existed. A stale expected value is
> indistinguishable from the bug a test is looking for, so the instructions now live in exactly one
> place.
>
> What survives here is the part the protocol has no room for: why each test exists, what its
> failure has already cost, and the store-behaviour reasoning behind the expected values.

**Branch:** `enterprise-floormapping-code-review-b`
**Rewritten:** 2026-09-04, against the fixtures actually loaded (see *The data*, below)

Unit tests and `./gradlew check` cover the logic. They cannot cover a canvas, a content pack round
trip, or a Plan B store that has never been written to. This is the part a person has to do.

---

# Results — 2026-09-07

Run against fixtures regenerated that morning, after the location split and the events column
mapping landed. **Groups A, F and G all pass.**

| Group | What it covers | Result |
|---|---|---|
| **A** | the events delta/baseline change (F13) | **pass** — re-run after the format change |
| **F** | facts no longer queried per tick (F15) | **pass**, 8 tests including the paused-cadence case |
| **G** | the map says why it is empty (F14 option 4) | **pass**, 10 tests including the `NO_FACTS` fixture |

**G3, G4 and G9 passing is the part that matters.** They are the only tests that force a message to
appear — two fault-styled lines and one console message — so they are what makes the *quiet* results
elsewhere meaningful. Without them, G1 and G5 showing nothing alarming would be indistinguishable
from the line never working at all, which is precisely how F14's earlier options passed their test
while reporting nothing.

**F's paused case (F6) is worth calling out.** It pins the defect that writing the protocol found:
the 60-second facts re-read was checked only from a playback tick, so on a paused map it never
happened. The test asserts one request in eighty seconds; zero is the regression.

**Three defects were found by running these**, all fixed, none of which the unit tests could have
caught:

- the `NO_ENTITIES_PARSED` line named the Entity ID column when the *location* roles were at fault —
  a message pointing at the one setting that was right
- a dropdown change did not mark the document dirty, so the mapping could not be saved on its own
- G3 as written was impossible: a role cannot be pointed at a column the query does not select, so
  the reachable fault is unmapping one

## E1 — the bootstrap migration · **pass, 2026-09-08**

Run against a genuinely clean database, which is the only way this test means anything. All five
checks pass:

| | |
|---|---|
| E1a | `updatable_temporal_store` and the three `visualisation_assets*` tables exist |
| E1b | both history tables carry a Flyway baseline row plus their migration, `success = 1` — document-asset at 07:20:19, sqlstore at 07:20:24, i.e. during startup and before the UI was touched |
| E1c | primary key is `(doc_uuid, key_, effective_time)`, so the post-F1 schema, with nothing to migrate |
| E1d | a fact written through the Editor tab saved and reloaded correctly |
| E1e | clean startup, and a 110 MB upload refused naming the 50 MB limit |

**This closes the most expensive finding of the review to verify.** `updatable_temporal_store` was
never created by a normal startup — the sqlstore provider was built after
`haveBootstrapMigrationsBeenDone()` was set, so `FlywayUtil.migrate` returned without consulting
Flyway and the history table stayed *empty*. The table existed only by accident of timing, and once
dropped no restart recreated it. E1b's migration row is the proof that branch now runs.

**Two of the five could only ever be tested here.** E1e's 50 MiB default is exercised only on an
instance with no config override, and every existing config file omits the property — so a null cap
would have disabled the limit on exactly the deployments it was added for. And E1d is the only check
that compares the *migrated* schema against the jOOQ classes generated at build time; a mismatch
fails on a read or write and nowhere else.

## Still outstanding

| | |
|---|---|
| **H1**, **H2** | the two Group A tests skipped as low value |
| **B3**, **B4**, **B5** | content packs — copy, delete, and the no-assets case |
| **E1**, **E2** | fresh-database bootstrap, and the deprecated config keys |

**E1 is the one to prioritise.** It is the only test here that cannot be run after release, and a
broken bootstrap is discovered by a customer rather than by us.

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

# Group B — assets through the content pack

`FloorMapStoreImpl` overrode none of export, import, copy or delete, so assets were silently absent
from every content pack. A broken pack fails quietly, which is why this is worth doing by hand.

| # | Result |
|---|---|
| B1 · export a map with assets, inspect the pack | **pass** 2026-09-04 |
| B2 · import that pack back | **pass** 2026-09-04 |
| B3 · copy the map | outstanding — see the protocol's Part 2 |
| B4 · delete the map | **partly** — a map was deleted and re-imported cleanly, but that does not prove the rows were removed rather than orphaned |
| B5 · export/import a map with **no** assets | outstanding |

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

**C1, C2 and C3 pass, run through the MCP server on 2026-09-04.** One stream tested all three at
once, plus an edge none of them names.

| # | Do | Expect | Result |
|---|---|---|---|
| C1 | Ingest through `SqlStoreFilter`; count rows | matches the input exactly | **pass** — 1 000 and 2, exactly as emitted |
| C2 | Exactly **1 000** entries, then a remainder | both exact — this is the batch boundary | **pass** — `fmba` holds exactly 1 000; no off-by-one, no loss, no duplication |
| C3 | `map` changes partway through the stream | rows land under the right map names | **pass** — 1 000 under `fmba`, 2 under `fmbb` |
| — | The map-change flush firing with an **empty** buffer | no error | **pass** — reached because the 1 000th entry flushes, so map B's first entry finds nothing pending |
| — | The remainder flush at `endProcessing` | the trailing 2 land | **pass** |
| C4 | An XSLT `lookup()` against a SQL Temporal Store | resolves, no `Error` stream | **pass** — see below |
| C5 | The store's Data tab | still lists entries | effectively covered — the same rows have been read back by query throughout |

### How it was done, and why not with 1 000 CSV rows

`WRITE_BATCH_SIZE` counts **entries reaching the filter**, not input rows. So
`floormap-batch-test.xslt` fans one input row out into `count` `<temporal-state>` elements, and a
**two-line** CSV produces the 1 000-entry batch:

```
map,prefix,time,count
fmba,a,2026-09-01T00:00:00.000Z,1000
fmbb,b,2026-09-01T00:00:00.000Z,2
```

That also isolates the filter's buffering from CSV parsing, which is the thing under test. Wiring:
feed `FLOOR_MAP_BATCH` → pipeline `FLOOR_MAP_BATCH_TEST` (`CombinedParser` →
`XSLTFilter` → `SqlStoreFilter`) → stores `fmba` and `fmbb`. Kept separate from the fixture
pipeline so it cannot disturb `floor_map_facts`.

### One thing this turned up

**`<time>` must be ISO 8601.** The first attempt used epoch millis and every entry failed with
`Unable to parse string "1788500000001" as datetime`. Both `SqlStoreFilter` and `PlanBFilter` parse
it with `DateUtil.parseNormalDateTimeStringToInstant`, which does not accept epoch millis — whatever
`DateUtil.parseUnknownString` allows elsewhere. Loud rather than silent (one error per entry), but
the events-store guide did not say so, and now does.

Note the keys alone make entries distinct — the upsert key includes the key — so one shared
timestamp serves all 1 000.

### C4 — the lookup path, and how to wire one

Passes. Five cases, all exact, `Error Count: 0`:

| Key | Lookup time | Result |
|---|---|---|
| `a1` | 2026-09-02 | `{"type":"desk","n":1}` |
| `a500` | 2026-09-02 | `{"type":"desk","n":500}` |
| `a1000` | 2026-09-02 | `{"type":"desk","n":1000}` |
| `zzz-does-not-exist` | 2026-09-02 | empty — a miss, not an error |
| `a1` | **2026-08-01** | **empty** — before the entry's effective time |

The last row is the one worth having: the lookup is genuinely temporal. `SqlStoreLookupImpl` queries
with `EQUALS` on the event time, which `getQueryTime` lifts as a **snapshot boundary**, so the answer
is the latest entry at or before that instant. Same key, earlier question, correctly nothing.

**Wiring is not obvious and is the reason this test looked like "separate setup".** The XSLTFilter
needs a `pipelineReference` whose **`pipeline` is the SqlTemporalStore document's own DocRef** — not
a reference loader. `ReferenceData.doGetValue` dispatches on the reference's *type*: a
`SqlTemporalStore` ref routes to `SqlStoreLookupImpl`, a `PlanB` ref to the Plan B lookup, anything
else to a loader pipeline. The map name in `stroom:lookup()` must also equal the document's name,
because that dispatch compares them.

Documents: XSLT and pipeline `FLOOR_MAP_LOOKUP_TEST`, feed `FLOOR_MAP_LOOKUP`, reading from `fmba`.

**Both misses are reported as `WARN`**, naming the store, map, key and lookup time — noticeably
better diagnostics than the floor map gives for its own empty results, which is F14's point in one
line.

---

# Regenerating the fixtures

See the protocol's *Before you start*. `generate.py` writes every timestamp relative to the moment
it runs, because the six-hour horizon is relative to the timeline position, and it writes
`out/manifest.json` listing every landmark the protocol refers to.

**That manifest is why this document no longer quotes any times.** They were written out by hand
here, went stale the first time the data was regenerated, and a stale landmark looks exactly like
the bug the test is checking for — an entity that is not where the document says it should be.
