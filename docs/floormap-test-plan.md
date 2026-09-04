# Floor Map branch — manual test plan

**Branch:** `enterprise-floormapping-code-review-b`
**Written:** 2026-09-04
**Why this exists:** 31 non-merge commits have landed since the 21 Aug review. Unit tests and
`./gradlew check` cover the logic; they cannot cover a canvas, a content pack round trip, or a Plan B
store that has never been written to. This is the part a person has to do.

Ordered by risk, not by area. **Group A is the one that matters** — it is the newest change, the
largest behavioural one, and has zero manual coverage.

Everything diagnostic this feature emits goes to the **browser console**, so keep it open throughout.
That is itself a known gap (**F14** in the remediation plan): a non-developer would see none of it.

---

## Constants the tests refer to

| Constant | Value | Where |
|---|---|---|
| `HORIZON_MS` | **6 hours** | `FloorMapEventState` — how far back a baseline reaches |
| `BASELINE_INTERVAL_MS` | **60 s** | routine re-baseline cadence |
| `JUMP_INTERVAL_MS` | **1 s** | floor between baselines forced by a timeline jump |
| `MAX_ROWS` | **20 000** | baseline row cap |
| `MAX_DELTA_ROWS` | **20 000** | per-tick row cap |
| playback throttle | **300 ms** | `FloorMapTimelinePresenter.PLAYBACK_QUERY_INTERVAL_MS` |

---

## Data you need before starting

Several Group A tests are not runnable without setting these up first. Worth doing in one pass.

| Fixture | How | Used by |
|---|---|---|
| **F-1 · a normal Plan B events store** | `TEMPORAL_STATE`, a handful of entities emitting positions | A1–A9 |
| **F-2 · an entity that goes idle** | stop emitting for one entity while others continue | A1, A2 |
| **F-3 · an entity idle beyond the horizon** | write one event **backdated more than 6 hours** and nothing since. Do **not** try to wait out the horizon | A3 |
| **F-4 · the same store with `condense` on** | flip `condense` **and shorten its duration to a few minutes** — the default 1-day threshold collapses nothing in 4-hour-old data, so A4 would pass vacuously | A4 |
| **F-5 · a never-written Plan B store** | create the Plan B doc, point a floor map at it, write nothing | A12 |
| **F-6 · an over-budget store** | more than **20 000** events inside a 6-hour window (≈0.93 events/s sustained) | A11 |
| **F-7 · a floor map on a SQL Temporal Store** | an existing pre-Plan-B document | A10 |

---

## Already set up and verified at the data level, 2026-09-04

The Stroom content for Groups A–B exists in **System / Floor Map Test**, built and checked through
the MCP server: stores, feeds, text converter, both XSLTs, both pipelines, processor filters, and the
facts and events fixtures ingested. `fetchDependencies` is clean.

Four fixture preconditions were verified by query, so the UI tests below are known to be *capable*
of discriminating rather than merely set up:

| Verified | Result |
|---|---|
| The facts snapshot changes across the desk move | at 07:50 `desk-106` is at `[320,240]`; at 08:05 it is "Desk 106 (moved)" at `[460,240]` — one row per key |
| **A1's precondition** | `bob` is inside the 6 h horizon with his last event 5 minutes before the newest — a baseline read returns him |
| **A3's precondition** | `carol` is 7 hours old and a baseline read over `[T−6h, T]` **does not return her** |
| **A4's precondition** | `dave`'s consecutive values are byte-identical — the runs condense collapses |

Row counts match the generated file exactly, so nothing was corrupted in transit.

**Still to do by hand** (no API reaches these): create the four Floor Map documents, upload
`events-bulk.csv` for A11, and run every UI test below.

## Group A — the events delta/baseline change (`0bab388b8c`)

The riskiest item. Positions are now client-side state: each tick reads only what changed, and a
periodic bounded re-read replaces the state wholesale.

| # | Test | Pass looks like | Failure looks like |
|---|---|---|---|
| **A1** | **Paused idle entity.** Pause with F-2 idle over 20 s | It stays on the map | It vanishes — the old 20-second-window behaviour |
| **A2** | **Counts hold.** Play with F-2 idle; watch area membership and the "*n* of *m*" group counts | Both hold steady | Counts fall with nobody moving |
| **A3** | **Horizon boundary.** **Pause first**, then load F-3 | The entity drops out at the next baseline and the group count falls honestly. Also check the *all* idle case | It lingers for ever, or the count claims a presence the store cannot support |
| **A4** | **`condense` is now safe.** F-4 | The stationary entity remains | It disappears — the reason the setup guide currently says keep `condense` off |
| **A5** | **Scrub backwards** | No position later than the selected time, and entities **teleport** rather than sliding | Entities animate across the jump, or show future positions |
| **A6** | **Scrub forwards** | Same, and a baseline runs | Positions from before the jump persist. This is the case the new discontinuity hook exists for |
| **A7** | **Stop at end** | Correct immediately | Correct only after up to 60 s |
| **A8** | **Loop at high speed** | Map tracks the wrap; baselines are **not** issued per frame | Either a query storm, or a map frozen through each pass. **The defect found late in implementation — test this one properly** |
| **A9** | **Hidden tab, paused.** Switch to another tab of the same document for over a minute | **No queries while away**; one baseline immediately on return | Polling continues while hidden |
| **A10** | **SQL Temporal Store unchanged.** F-7 | Behaves exactly as before; no load reduction | Any behaviour change at all — the defect cannot occur there |
| **A11** | **Truncating baseline.** F-6 | Warns **once** recommending `condense`; the map shows real positions; the next tick is a **delta**, not another baseline | Repeated warnings, an empty map, or back-to-back full scans |
| **A12** | **Never-written store.** F-5 | Map stays empty; error reported **once** | Nagging every minute, or a silent empty map |
| **A13** | **Slow baseline.** F-6, or throttle the network | Reports lateness or failure | Looks indistinguishable from an empty store |

### Accepted behaviours — do not file these as bugs

- A baseline landing after deltas were in flight replaces wholesale, so an entity that moved during
  its flight can **flicker back one tick** before the next delta corrects it.
- A **delta** slower than one tick is destroyed by the next tick and its range is never re-queried, so
  a mover can be stale until the next baseline. The old overlapping window self-healed in one tick;
  this is the price of not overlapping.
- **During playback** a pruned entity's counts, roster and membership update but the **glyph
  persists**, because the draw list re-emits from the animator's last positions and prunes only on the
  teleport path. Pre-existing, not a regression — which is why A3 says pause first.
- The horizon is **not enforced on a SQL Temporal Store**, which strips all time terms. Horizon-drop
  and map-empties behaviours are Plan B only.

---

## Group B — assets through the content pack (`ec9c6298e3`)

`FloorMapStoreImpl` overrode **none** of export, import, copy or delete, so assets were silently
absent from every content pack. Four operations, and the round trip is the point.

| # | Test | Pass looks like |
|---|---|---|
| **B1** | Export a floor map with assets, inspect the pack | Asset files are present |
| **B2** | Import that pack into a clean instance | Assets restored and rendering — backgrounds and icons visible on the canvas |
| **B3** | Copy a floor map with assets | The copy has its own assets, and editing one does not affect the other |
| **B4** | Delete a floor map with assets | Assets are removed, not orphaned |
| **B5** | Export/import a floor map with **no** assets | Works, no errors |

---

## Group C — reference-data ingest (`a6432bd258`, `8cc85ee889`, `9400f3359c`)

Writes now batch at 1 000 and the lookup store resolves once per pipeline run rather than per event.
Row counts at the batch boundary are unit-tested (`TestSqlStoreFilter`); the pipeline is not.

| # | Test | Pass looks like |
|---|---|---|
| **C1** | Ingest a reference-data stream through `SqlStoreFilter` | Row count matches the input exactly |
| **C2** | Ingest a stream of **exactly 1 000** entries, and one of 1 001 | Both correct — the boundary |
| **C3** | A stream whose map name changes partway | Rows land under the right map names |
| **C4** | An XSLT `lookup()` against a SQL Temporal Store | Still resolves; check an `Error` stream is not produced |
| **C5** | The store's own Data tab | Still lists entries |

---

## Group D — canvas and trails (five commits)

Purely visual; unit tests cannot see any of it.

| # | Test | Pass looks like |
|---|---|---|
| **D1** | Movement trails during playback | Taper smoothly, no glowing joins, no hard-edged bands |
| **D2** | Trails after a time jump | Cleared, not stretched across the jump |
| **D3** | Trails on a long-lived entity | Fade by age; no unbounded growth or slowdown over several minutes |
| **D4** | A degenerate canvas transform — zoom fully in and out, resize the pane very small | No exceptions in the console; the matrix-invertibility screening should absorb it |
| **D5** | Clustering on and off with entities crowded | Glyphs merge and split; cluster tooltips name members consistently with the Tracking and Groups panels |

---

## Group E — setup and migration

| # | Test | Pass looks like |
|---|---|---|
| **E1** | Start against a **fresh, empty database** | The SQL temporal store's Flyway migration runs at bootstrap; no manual step |
| **E2** | Start with a config using the **old** `visualisationAsset` / `visualisationAssetDb` keys | Accepted, with a deprecation warning; do not add the new keys alongside — the last occurrence wins |
| **E3** | Create a new floor map end to end | Init dialog sets the events store, both column settings and the value schema; layers appear; events draw |
| **E4** | The store chooser popups on the events/facts setup dialog | Titled "Choose Event Store" / "Choose Fact Store" |

---

## Already confirmed — recorded so they are not redone

Reported working during this session's development:

- Creating a new floor map; opening an existing one
- Multi-select delete and duplicate
- Entity ID and Location ID column settings populated correctly
- Layers appearing once the value schema and format match
- Events drawing on the map after the window fix

---

## What is *not* in scope here

- **F15** (facts query polling) — written up, not implemented. Nothing to test.
- **F10** (`try (this)`) — not done; blocked on D7, which is now answerable.
- **F9 / D6** — documentation only; no behaviour changed.
- The three `@Disabled` cases in `TestTemporalStoreParity` — they record a known Plan B gap and are
  expected to fail until Plan B gains a latest-per-key read.
