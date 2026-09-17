# How floor map data is stored and how it reaches the client

**Audience:** anyone working on the Floor Map, or wondering why it is fast, or about to make it slow.
**Scope:** the events path — the moving entities. Facts (the floor plan itself) come from a SQL
Temporal Store and are read once per document, so they are not the interesting half.
**Status:** describes the code as of 2026-09-17. Figures are marked as measured or estimated.

---

## 1. The three questions, and why they are different

The UI asks the store three questions. Conflating them is the single most common way to make this
slow, so they are worth separating up front.

| | Question | Answer size | How often |
|---|---|---|---|
| **Map** | where was every entity at time T | one row **per entity** | up to 1 per 300 ms while playing |
| **Histogram** | how much activity per bucket across the visible range | 12–400 rows | on a range change |
| **Show All** | what is the data's full time extent | 1 row, 2 values | once per document open |

They have different shapes *and* different cadences. A design that answers all three the same way
either makes the map too slow or the histogram wrong.

**The scrubber and variable speed do not add a fourth question.** Playback *samples*: the timeline
advances by wall clock × speed and queries are throttled to 300 ms, so at ×10000 each sample is about
35 days further on and everything between is never read. This is the property everything else hangs
off — **cost per tick is independent of how much history sits between samples**, so speed is free.

---

## 2. How it is stored

A **FloorMap Event Store** is a Plan B document with `stateType = TEMPORAL_STATE`, backed by LMDB.
Plan B owns the storage entirely: ingest, merging, shards, condense, retention and deletion are all
its machinery, unchanged. Only the *read* is ours.

### The key layout is the whole design

```
key   =  entityId ‖ 0x00 ‖ effectiveTime        value = the event payload
         └── TERMINATED_STRING ──┘ └ 8 bytes ┘
```

Two properties matter, and both are load-bearing:

**Entity first, time second.** Every row for one entity is therefore contiguous and in ascending time
order. That is what allows a point-in-time read to *seek* to an entity's answer rather than scan to
it (§4).

**The `0x00` terminator makes the encoding prefix-free.** No key's bytes can be a prefix of another's.
Without it, `door1` prefixes `door10`, and a reader stepping past `door1` by jumping beyond
`door1‖0xFF…` jumps over every `door10` row as well — that entity silently never appears. The
terminator also *preserves ordering*, unlike a length prefix, which would be equally prefix-free but
would sort length-major (`z` before `aa`) and foreclose any future key-range scan.

The encoding is fixed by the document type rather than offered as a setting, so a store the read
cannot serve cannot be created. `TestTerminatedStringKeySerde` and `TestTemporalStateDbSnapshot` hold
that down, the latter with the exact `door1`/`door10`/`door11` case.

### Value encoding is worth about 5×

Values use Plan B's `VARIABLE` type, which deduplicates repeated values through a lookup table. Most
floor map traffic is a stationary entity re-reporting the same position, so this is the difference
between two very different stores:

| Value cardinality | Bytes/row | One year at 3 000 entities × 100 events/day (109.5 M rows) |
|---|---|---|
| Low — values repeat | 162 | **~18 GB** |
| Unique per event | 725–1 350 | **~79–148 GB** |

*Measured; see `floormap-single-read-feasibility.md` §5.* Keeping event values low-cardinality is the
cheapest storage decision available and costs nothing.

`maxStoreSize` defaults to 10 GiB, which is below the figures above. It is an LMDB map size — virtual
address space on a 64-bit system — so raising it to 150 GiB is a document setting, not an
architecture change.

### Two settings that delete or rewrite data

- **Retention** deletes rows older than its duration. It bounds how far back the timeline can scrub.
- **Condense** collapses a run of identical values to the run's **earliest** row. It is how a store of
  stationary entities stays small — and it rewrites a stationary entity's "last seen" time backwards,
  so under expiry that entity disappears while still emitting. Offered with a warning rather than
  forbidden. `TestTemporalStateDbSnapshot.condenseMovesAStationaryEntityBehindTheFloor` pins it.

**Expiry ≤ retention** is checked on save. Violate it and the map silently under-reports: an entity
idle beyond retention is dropped even though the expiry rule says to show it, and a missing entity
reads as a data problem rather than a configuration one.

---

## 3. How it reaches the client

All three reads go through ordinary StroomQL and the standard search stack — `SearchRequest` →
coprocessors → `ResultStore` → `TableResult` of string rows. What differs is what the store is asked
for.

### The read mode is stated, never inferred

```
readMode=snapshot  +  asAt=<epoch millis>     →  one row per entity
neither                                       →  an ordinary range read
```

Both parameters are required together; either alone is rejected by name. `asAt` is epoch
milliseconds only — **no date parser takes part in deciding what a query means**, so the answer cannot
depend on how a literal is spelled or on the viewer's time zone.

This replaced a mechanism that inferred the mode from whether a time term happened to be `<` rather
than `>`, inside shared Plan B code, for every temporal state store in the system. See
`temporal-store-parity-report.md` for the defect and `floormap-events-backend-design.md` §13 for what
replaced it.

### Map — the per-tick read

Sends `readMode=snapshot`, `asAt`, and **no `TimeRange`**. Expiry is not sent: the server derives the
floor from the store document, which is what makes two maps sharing a store agree.

Returns **one row per entity**. Roughly 3 000 rows per tick at the sizing above. The client replaces
its held positions wholesale and retains nothing between ticks except animation state — where each
entity was last *drawn*, so movement interpolates rather than teleports.

### Histogram — on range change

```
from param('EventStore')
eval bucket = floorTime(EffectiveTime, param('bucketWidth'))
group by bucket
sort by bucket
select bucket, count()
```

Counted **server-side**: one row per bucket, not one per event. Bucket width follows the visible range
along a ladder from 5 minutes to 30 days, giving 12–400 rows. Bounded at both ends to the visible
range.

### Show All — once per document

```
from param('EventStore')
select min(EffectiveTime), max(EffectiveTime)
```

One row, two values. Deliberately **unbounded** — a bounded read could never reach data outside the
range, which is the one thing Show All exists to do.

### The fetch cap

`MAX_ROWS = 20 000` bounds one response. Since the map read returns one row per entity, **this is now
an entity ceiling, not a history cap.** Reaching it means the store holds more distinct entities than
the cap allows; narrowing the time range would not help.

---

## 4. Why this is efficient

**Seek, don't scan.** The map read seeks straight to each entity's answer and steps back, rather than
reading its whole history. O(entities × log n) instead of O(all rows), and the rows in between are
never deserialised.

Measured at floor-map scale — 3 000 entities, snapshot taken with the whole history behind it:

| Store | Rows returned | Cold | Warm |
|---|---|---|---|
| 300 000 rows | 3 000 | 29.0 ms | 8.2 ms |
| **6 000 000 rows** (20× deeper) | 3 000 | **6.7 ms** | **5.0 ms** |

**Flat in history depth** — the deeper store is no slower. That is the property that matters: cost
stops growing with retention. A year at ~109.5 M rows adds roughly four B-tree levels over the 6 M
measured, so call it ~6 ms against a 300 ms tick.

> **Caveat, stated plainly.** These figures were taken before the seek had a test, with a benchmark
> that is not in the tree, and on a prefix-free encoding that at the time was not the default. Treat
> them as indicative of the shape, not as a benchmark you can reproduce.

**Aggregate at the server.** The histogram returns counts per bucket rather than events. The read it
replaced returned every event in the store and bucketed them on the client, which is the one read
whose size grows without bound.

**Two values, not a bucket per day.** Show All uses `min`/`max` rather than grouping into day buckets,
which previously cost ~365 rows a year and could only place the timeline on the right *day*.

**Sample, don't stream.** Nothing is prefetched and nothing accumulates. Each tick is independent, so
a dropped or late tick costs nothing and a scrub is not a special case — it is the same query at a
different T.

**Deduplicate values.** ~5× on storage for the common case, free.

---

## 5. Performance issues

Ordered by what will bite first.

### 5.1 The histogram and Show All still scan the whole store — server-side

**This is the big one.** They are bounded *on the wire* — 12–400 rows and 1 row respectively — but not
on the server. Plan B iterates the whole store whatever range is asked for.

It is structural, not laziness: **the key is `entity ‖ time`, so time is the key *suffix*.** No
key-range scan can prune by time. The standing `TODO` in `PlanBSearchHelper` about narrowing iteration
by criteria is about *key* criteria and does not help here.

Consequences:

- Every range change costs a scan proportional to total store size.
- Every document open costs one too, for Show All.
- Both grow with retention, while the map read does not.

The map read — the one that runs 3× a second — is unaffected. So the symptom is a UI that plays back
smoothly but stutters when you zoom, and takes a while to open.

**Fixing it needs a counts store keyed by bucket, or a secondary index** — block C in
`floormap-events-backend-design.md` §11.5, deferred as D8. Not a small change, and deliberately not
done yet.

### 5.2 The seek is linear in entity count

O(entities × log n). Flat in history, **not** flat in entities. At 3 000 entities ≈ 6 ms; at 100 000
it would be ≈ 200 ms, which is most of a tick. Design §12.4 sets the trigger for reintroducing
checkpoints at "tens of thousands of entities", and that remains the right threshold to watch.

### 5.3 Cold cache dominates document open

Warm reads are single-digit milliseconds; a cold one was measured at 29 ms on Plan B, and the earlier
MySQL comparison showed 2.4 s cold against 232 ms warm. Opening a document pays a cold read *and* the
unbounded Show All scan (5.1) at the same moment. This is the worst latency in the feature and the
first thing a user notices.

### 5.4 The 20 000-row fetch cap is an entity ceiling

Above ~20 000 distinct entities the map silently truncates. It is reported, but it is a cap that no
amount of narrowing the time range will avoid — only a larger cap helps.

### 5.5 Condense trades storage for correctness

Enabling it is the main lever on store size for stationary entities, and it makes those entities
expire while still emitting. The settings tab warns, but it remains a trap worth knowing about before
someone turns it on to reclaim disk.

### 5.6 Cluster reads

A query for this store is now pinned to the node that holds it, unless the store asks for snapshot
reads. Before that fix it always fell back to a node's own snapshot — correct data, but periodically
refreshed rather than live. If someone enables `useSnapshotsForQuery`, the map will show stale
positions and nothing will say so.

### 5.7 A result store per tick

Each tick creates and destroys a `ResultStore` on the server. At 300 ms that is a steady churn of
short-lived search machinery. It has not been measured, and it has not been a problem — but it is the
obvious next thing to look at if per-tick cost ever turns out to be higher than the read itself.

---

## 6. If you change something here

- **Do not add an upper time bound to Show All.** It exists to reach outside the visible range.
- **Do not put the bucket width into the query text.** It changes on every zoom; substituting it would
  mean rewriting a query the user may have edited. It travels as `param('bucketWidth')`.
- **Do not assume a time bound prunes anything server-side.** See 5.1.
- **Do not change the key encoding** without re-reading §2. A Plan B key schema is immutable once data
  is written, and the seek's correctness depends on prefix-freeness.
- **Measure the histogram, not the map**, if you are chasing a slowdown. The map read is the one that
  is already fast.

## Related

- `floormap-events-backend-design.md` — the design, especially §13 (what was built) and §11.5 (block C)
- `floormap-planb-events-store.md` — how to create and configure a store
- `floormap-event-expiry-requirements.md` — expiry, and its interaction with condense and retention
- `temporal-store-parity-report.md` — the inferred-read-mode defect this all came from
