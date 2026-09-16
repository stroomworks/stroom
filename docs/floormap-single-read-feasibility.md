# Feasibility: reading events without transferring all history

---

## 1. Correction: two documents in this repo are now stale

> **Status: both fixed.** The parity report is now at revision 2 and the test javadoc is
> corrected. This section is kept as the record of what was wrong and why it mattered.

Found while verifying the read semantics, and both are load-bearing elsewhere.

**`docs/temporal-store-parity-report.md` is out of date.** It reports "8 tests, 3 failures" and that
the stores differ on upper bound, exact time and both bounds. Parity has since been reached —
`TestTemporalStoreParity`'s own javadoc says so and says the report *"predates it and needs
revising"*. Only one `@Disabled` remains in that file.

**`TestTemporalStoreParity`'s javadoc misattributes the change.** It says Plan B's latest-per-key
read *"arrived with upstream's Traces work"* on 2026-09-09. It did not:

```
b1c8cb2870  2026-08-27  stroomworks  "Fix map to handle events plan b store"
            PlanBSearchHelper.java   +73      ← getQueryTime, removeTimeTerms
            TemporalStateDb.java     +113     ← searchAsAt
            TestTemporalStateDb.java +209
17371533de  2026-09-07  upstream     "Traces processing (#5772)"
```

`origin/master` contains **zero** occurrences of `getQueryTime` or `searchAsAt`. The behaviour is
**ours**, added eleven days *before* Traces, to two upstream-owned files. This is the same false
claim corrected in four documents earlier; it survives in the test.

It matters beyond tidiness: if the behaviour is believed to be upstream's, nobody marks it
`STROOMWORKS-LOCAL`, and the next merge from master silently reverts the map's read semantics.

---

## 2. Experiments on a PlanB Temporal Store with Queries

This query returns the expected results:
```
from "floor_map_events" where EffectiveTime < "2026-09-09T08:48:02.0" and EffectiveTime > "2026-09-09T07:00:00.0" select EffectiveTime, Key, Value
```

However, we don't get the temporal state effect - we will not see any events before the start time.

For example, the query above returns:

|EffectiveTime|Key|Value|
|-------------|---|-----|
|2026-09-09T07:28:01.000Z|alice@example.org|"{...}"|
|2026-09-09T08:08:01.000Z|alice@example.org|"{...}"|
|2026-09-09T08:48:01.000Z|alice@example.org|"{...}"|
|2026-09-09T08:08:01.000Z|bob@example.org|"{...}"|
|2026-09-09T08:48:01.000Z|bob@example.org|"{...}"|


If we change the start time, so the query reads:
```
from "floor_map_events" where EffectiveTime < "2026-09-09T08:48:02.0" and EffectiveTime > "2026-09-09T07:30:00.0" select EffectiveTime, Key, Value
```

We get this:

|EffectiveTime|Key|Value|
|-------------|---|-----|
|2026-09-09T08:08:01.000Z|alice@example.org|"{...}"|
|2026-09-09T08:48:01.000Z|alice@example.org|"{...}"|
|2026-09-09T08:08:01.000Z|bob@example.org|"{...}"|
|2026-09-09T08:48:01.000Z|bob@example.org|"{...}"|

We've lost the event at `EffectiveTime == 2026-09-09T07:28:01.000Z`

Does this matter? It does matter if we want the correct starting state. If we're not bothered then it doesn't matter.

Proposal is to run forwards from the starting time, showing items as they appear.

1. Run the query to populate the ResultSetStore between start time and end time.
   Add columns as necessary to provide all the data in a useful format.
2. Client starts at the start of the ResultSetStore and pages forwards as it displays events.
   Only the events that appear are displayed - anything that happened before the start time are not displayed,
   even if the should be.
3. If the user scrubs the timeline, the client must play the events to find the state at that point in time.
   There is no way to just jump to that state. This isn't great.

---

## 3. The histogram is the one that transfers all history

The bars need *counts per bucket*, not events. The query language already has what that needs:

```
from param('EventStore')
eval bucket = floorTime(EffectiveTime, 'PT5M')
group by bucket
select bucket, count()
```

For example:
```
from "floor_map_events" eval bucket = floorTime(EffectiveTime, "PT5M") group by bucket sort by bucket select bucket, count()
```

`floorTime(time, duration)` floors to an arbitrary ISO-8601 duration, so the bucket width can be
derived from the visible range and the 100-bin target. `count()` is a standard aggregate.

Grouping is a **write-time** aggregation into the result store, so:

- the store holds ~100 rows, not *N* events;
- transfer is bounded by bin count, **independent of store size**;
- the 10 000-row cap stops being reachable, and the silent truncation goes with it.

The server still scans the whole Plan B store — nothing here narrows that, and
`PlanBSearchHelper`'s standing `TODO` still applies. **Transfer** is what the constraint is about,
and transfer becomes bounded.

### Store limits — and why grouping lifts the one that matters

Three different caps get conflated as "the 10 000/20 000 limit". Only one of them is about what the
store can *hold*:

| Limit | Value | Governs | Where |
|---|---|---|---|
| `OffsetRange.length` | 10 000 histogram, 20 000 events/facts | rows returned **per fetch** | the client's `getRequestedRange()` |
| **Put filter** | **1 000 000** | rows **admitted to the store** | `LmdbPutFilterFactory` |
| Read limit per depth | 1 000 000 / 100 / 10 / 1 | rows returned per depth at fetch time | `DataStoreSettings.maxResults` |
| LMDB env | **10 GiB** | physical ceiling per result store | `ResultStoreLmdbConfig` |
| `maxSortedItems` | 500 000 | the sorted window | `AbstractResultStoreConfig` |

**The put filter is conditional, and that is the useful part:**

```java
final boolean limitResultCount = maxResults < Sizes.MAX_SIZE && !hasSort && !compiledDepths.hasGroup();
```

- **Flat, ungrouped, unsorted** — today's events and histogram queries — get `LimitedPutFilter`. At
  1 000 000 rows it stops accepting and calls `completionState.signalComplete()`: it does not merely
  cap the store, it **ends the search early**.
- **Grouped or sorted** get `BasicPutFilter` — **no write cap at all**. Every row is admitted.

**So the `group by` proposal above is not merely smaller, it is also more complete.** A grouped
histogram query aggregates over the *entire* store rather than the first million rows, and ~100
buckets is nowhere near the depth-0 read limit. The counts are right however big the store gets.

The price is the other half of the same rule: a grouped query can never terminate early, so it
always scans everything. Consistent with `PlanBSearchHelper`'s standing `TODO`, and it is server
cost rather than transfer, which is the constraint being worked to.

**One caveat, reasoned rather than measured.** The 1 000 000 put cap looks invisible to the
truncation detection this code relies on: `Outcome.truncated()` compares `getTotalResults()` against
the rows returned, and `getTotalResults()` is counted while iterating **what is in the store**. A
store put-filtered at 1 000 000 holds exactly that and reports it, so it should read as a complete
result rather than a truncated one. Worth a test if it ever becomes load-bearing; it only bites above
a million rows on an ungrouped query.

### Decision: add a seprate query for the histogram

- Use a parameter to define the bucket size based on the duration shown by the timeline
- The number of buckets does not need to be fixed - it just needs to look useful

| Duration   | Bucket size |
|------------|-------------|
| >= 1 year  | 1 month     |
| >= 3 days  | 1 day       |
| >= 1 day   | 1 hour      |
| >= 3 hours | 10 minutes  |
| < 3 hours  | 5 minutes   |

### When run

The query for the histogram is run when the time line start or end time is changed.
We can provide a Refresh button somewhere (possibly in the timeline settings dialog) so the user can manually run the query again.

### Decision: If it isn't possible to get the "Show All" time range in another way, run the histogram query with 1 hour buckets

Then take the time of the first bucket as the starting time and the time of the last bucket + time period as the end.

1 hour resolution is ok for a first guess at the time range.
If this becomes irritating to users then we can change it later.

---


## 4. Can we put something in front of the store so it acts as something we can query?

**Yes — and the extension point already exists, but it is not the SearchResultStore.** It is
`Searchable`.

### The mechanism

```java
public interface Searchable extends DataSourceProvider {
    void search(ExpressionCriteria criteria,
                FieldIndex fieldIndex,
                DateTimeSettings dateTimeSettings,
                ValuesConsumer consumer,
                ErrorConsumer errorConsumer);
}
```

One method. Bind an implementation into the `Map<String, Searchable>` and
`SearchProviderRegistryImpl` wraps it in a `SearchableSearchProvider`, which supplies **everything
else for free**: StroomQL parsing, field info, coprocessors, the result store, paging, security, and
`param()` substitution. Existing implementors are `AnnotationService`,
`PlanBShardInfoServiceImpl`, `SearchableTaskProgress` and `SearchableDual` — so this is a
well-trodden path, not a speculative one.

**The consequence that matters: anything we can iterate, we can make queryable — and we write
`search()`, so we define its read semantics.** No inference from term shape, no stripped lower
bounds, no dependence on whether a date carries a `Z` (§1 / parity report *What parity cost* §3).
And because it would live in `stroom-floormap-impl`, it **edits no upstream files** and carries no
merge exposure.

> **One cost that is not free, though.** Plan B has a single module, `stroom-planb-impl`, with no
> `-api` counterpart, and `stroom-floormap-impl` does not depend on it today. Reading a Plan B store
> means injecting `ShardManager` (public, and `get(mapName, Function<Db<?,?>, R>)` is the entry
> point `StateSearchProvider` itself uses), which needs one of: an impl-on-impl Gradle dependency
> — quickest, and against the grain of the module layout; a new small `stroom-planb-api`; or putting
> the `Searchable` inside `stroom-planb-impl`, which reintroduces exactly the merge exposure this
> avoids. **The first is the pragmatic choice and the third is the one to refuse.**
>
> What it does *not* need is any change to Plan B's own code. The `Searchable` would construct the
> `ExpressionCriteria` it passes down, so it drives the existing inference deliberately rather than
> being subject to it — `EffectiveTime <= start` in epoch millis for the snapshot, a lone
> `EffectiveTime >= start` for the range — and combines the two results itself. The inference stops
> being a trap when there is exactly one caller and it knows what it is asking for.

Everything the Floor Map reads becomes an ordinary StroomQL query against it, including `group by`,
`having`, `count()` and `floorTime()` — so §3's histogram is served by the same surface.

### It also fixes the problem §2 ran into

§2's proposal loses the starting state: playing forward from `start` never shows an entity that has
not moved since before `start`. That is unavoidable with a *range* read, and there is no way to ask
these stores for "state at T **plus** events after T" in one query.

A `Searchable` we own can answer exactly that, because we decide what the criteria mean:

```
from "FloorMapEvents"
where EffectiveTime >= <start> and EffectiveTime <= <end>
select EffectiveTime, Key, Value
```

implemented as **the snapshot at `start`, followed by every event in `(start, end]`** — the correct
initial state and the events to play forward, one query, one pass. §2's step 3 ("if the user scrubs,
the client must play the events... This isn't great") stops being a problem, because a scrub is just
a new query with a new `start`.

### What sits behind it — four candidates

| | Behind the `Searchable` | Verdict |
|---|---|---|
| **A** | **Read through to Plan B**, implementing explicit read modes | **Start here.** No cache, no coherence problem, no lifecycle. Every query still scans the whole store — so it fixes *semantics and transfer*, not server cost. It is Option C of the parity report, built locally, needing nothing from the Plan B maintainer. |
| **B** | A **server-side cache** of the events, kept fresh | Fixes server cost too, and makes repeated scrubs cheap. But it is a materialised view: population, freshness, memory, and multi-node coherence all become ours. Worth it only if Q3 (server scan cost) turns out to matter. |
| **C** | A **live result store**, addressed by `QueryKey` — *the literal question* | **Poor substrate.** A result store is per-user, per-search, destroyed on tab close, 24 h lifespan, and its rows are already shaped by the `TableSettings` of the search that made it. You would also be running a search to read a search's output, doubling the stores. The idea is right; the result store is the wrong thing to hang it on. |
| **D** | A second **Plan B store**, written by a pipeline | A data-engineering answer. Heavy, and it moves the freshness problem into processing. |

**A is the recommendation**: it is the smallest thing that removes the dependence on inferred read
modes, and B is a later optimisation behind the same interface, invisible to the client.

### One mechanism that looks relevant and is not

**One search can already feed several differently-shaped result stores.**
`CoprocessorsFactory.createSettings` groups `ResultRequest`s by their `TableSettings` and creates one
coprocessor — and one `DataStore` — per distinct group. Dashboards use this in production, sending a
list of `ComponentResultRequest`s that `SearchRequestMapper` turns into multiple `ResultRequest`s.

So "one scan, two result sets" is real and free. **It still does not fit the map-plus-histogram
case**, because every coprocessor is fed from the *same source scan*, and that scan's read mode is
fixed by the expression: a snapshot scan emits one row per entity, so a histogram built from it
counts entities rather than events; an all-history scan can feed the histogram but cannot give the
map latest-at-T, because a `group by` with `last()` aggregates at write time and would give the
latest *ever*.

It is the right mechanism for two views of the *same rows* — two histogram resolutions, or a count
alongside a table — and worth remembering for that. It is not a way to serve two different read
modes.

### What to prototype

1. A `Searchable` named `FloorMapEvents` over the configured Plan B store, backed by option **A**.
2. Two read modes only: **snapshot at T**, and **snapshot at start + events in `(start, end]`**.
3. Point the histogram at it with the `group by floorTime(...)` query from §3 and confirm the
   transfer is bin-count bounded.

Steps 1 and 2 are the whole idea; step 3 is the measurement that says whether it worked.

---

## 5. Plan B store capacity — what fills it, and how fast

Prompted by: *"What happens when the PlanB store fills up? If there are 3000 people being tracked,
and each generates 100 events per day, how long will it take to fill the PlanB store?"*

**Short answer: between about four weeks and seven months, and the difference is not event rate.**
It is whether event *values* repeat.

### What happens when it fills

Three stages, and the middle one is the one to design against:

| Usage | Behaviour |
|---|---|
| **≥ 95%** | `HoldingAreaMergeStrategy.mergeAllBatches` **stops merging** and logs a WARN naming the store and its usage. Ingest does not fail — batches back up in the holding area, **unmerged and therefore not queryable**. |
| **100%** | LMDB throws `Env.MapFullException`; `PlanBEnv.throwIfStoreFull` translates it to `PlanBStoreFullException` and the write transaction is **aborted**. |
| afterwards | From that class's own javadoc: *"pages freed by deletes are never returned to the OS, so a full store stays full until data is deleted **and the file is compacted**, or `maxStoreSize` is raised."* |

The 95% stage is the dangerous one, because it is silent to a user: the map keeps working, queries
keep returning, and the data simply stops being current. The only signal is a WARN in the log.

### The defaults that decide when you get there

| Setting | Default | Source |
|---|---|---|
| `maxStoreSize` | **10 GiB** | `AbstractPlanBSettings.DEFAULT_MAX_STORE_SIZE` |
| `retention` | **disabled** (1 year if enabled) | `RetentionSettings.DEFAULT_ENABLED = false` |
| `condense` | disabled (1 day if enabled) | `DurationSetting` |
| key type | `VARIABLE`, millisecond precision | `StateKeySchema` / `TemporalStateKeySchema` |
| value type | `VARIABLE` | `StateValueSchema.DEFAULT_VALUE_TYPE` |

**Nothing prunes by default.** A store left alone grows until it stops merging.

### Measured, at exactly the volume asked about

3 000 people × 100 events/day = **300 000 events/day**, written to a real `TemporalStateDb` with
default settings, 22-character keys (`person0000@example.org`) and JSON values of the shape the
default events query reads:

| Value pattern | Bytes/row | Days to 10 GiB | Days to the 95% merge stop |
|---|---|---|---|
| Low cardinality (500 distinct values) | 162 | **222** | 211 |
| …plus 100 B of *repeated* content | 162 | 221 | 210 |
| …plus 280 B of *repeated* content | 163 | 220 | 209 |
| **Unique ~110 B values** | **725** | **49** | 47 |
| **Unique ~290 B values** | **1 350** | **27** | 26 |

### Why value cardinality is the lever, not payload size

The default `StateValueType` is `VARIABLE`, documented as *"use string, UID lookup or hash lookup
depending on the length of the string"* — and both lookup forms **deduplicate**. So:

- **Repeated values** cost a short reference in the row, with the payload stored once in the lookup
  table. Adding 280 bytes of repeated content to every event changed the cost by **one byte per
  row**.
- **Unique values** put one entry per row in the lookup table *and* a reference in the row, so you
  pay the payload plus the lookup machinery — roughly **4–5× the raw row content**.

**The practical consequence.** Anything per-event and unique in the event JSON — an embedded
timestamp, a sequence number, a message with a count in it — defeats deduplication and costs about
five times the store. That is a larger lever than event rate, and it is fixed at the point the event
schema is decided, which is a long way from where the symptom appears.

### This revises what §3 says about `condense`

§3 and `FloorMapFullReadQueryHelper.MAX_ROWS` are right that `condense` does nothing for the *fetch*
cap: a snapshot read returns one row per entity and there is nothing to collapse. **For store
capacity it matters a great deal** — collapsing runs of identical positions is precisely what keeps
values low-cardinality, which is the difference between the top and bottom of the table above. The
two statements are about different limits and both hold.

### Options, in the order worth considering them

1. **Keep event values low-cardinality.** Free, and worth more than everything below. Drop
   per-event unique content from the value; if it is needed, put it in the key's time rather than
   the payload.
2. **Enable `condense`.** Directly attacks cardinality for stationary entities.
3. **Enable `retention`.** Off by default; the honest fix once expiry semantics are settled — see
   `docs/floormap-event-expiry-requirements.md`.
4. **Raise `maxStoreSize`.** Fine as headroom, but it is a fixed LMDB map size set at env open, so
   it buys linear time against a linear problem.

### How these numbers were produced

A throwaway JUnit test built a `TemporalStateDb` in a temp directory with default settings, inserted
300 000 rows in the shapes above, and read `db.getUsage().usedBytes()` — which is
`(lastPageNumber + 1) × pageSize`, i.e. **pages allocated including pages freed by deletes**, which
is what actually determines whether the next write succeeds. The probe was deleted after use; it is
a dozen lines and easy to recreate from this description.

**One thing measured but not fully explained:** the 4–5× overhead on unique values. The lookup-table
entry plus the row's reference plus B-tree page fragmentation all plausibly contribute, and
`usedBytes` counting freed pages inflates it further, but the split between them was not
established. The ratio is reproducible; the attribution is a hypothesis.
