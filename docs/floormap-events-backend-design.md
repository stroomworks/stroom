# Design note: an events backend for the Floor Map

**Component:** Floor Map — the events store and the reads the Map tab makes of it
**Status:** design note, revision 1. Nothing built. Sequencing proposed in §8.
**Background:** `docs/floormap-query-call-paths.md` (what runs today),
`docs/floormap-single-read-feasibility.md` (what the stores can and cannot answer, store capacity).

## Objectives

| | |
|---|---|
| **O1** | The UI stays as it is — full scrubber, variable-speed forward playback |
| **O2** | Minimal server CPU and IO |
| **O3** | Minimal data transfer to the client |
| **O4** | At least a year of **unique** event data, ideally unlimited subject to disk. Implementation may be deferred; the plan may not |
| **O5** | Works in a cluster without requiring more than one node |

---

## 1. What the UI actually demands

Three questions. They have very different shapes and it is worth not conflating them.

| | Question | Answer size | Frequency |
|---|---|---|---|
| **Map** | the state of every entity at T | one row per **entity** | ≤ 1 per 300 ms |
| **Histogram** | event counts per bucket over the visible range | ~100 rows | on range change |
| **Show All** | the overall time extent | 2 values | once per document |

**The scrubber and variable speed do not add a fourth question.** Playback is *sampling*, not replay:
the timeline advances by wall-clock × speed, and queries are throttled to 300 ms, so at ×10000 each
sample is ~35 days further on and everything between is never read
(`docs/floormap-query-call-paths.md` §3).

> **The property to preserve, and the one design constraint that matters most:** cost per tick must
> be independent of how much history sits between samples. Any design that replays events forward
> makes speed expensive and violates O1 and O2 together.

---

## 2. The three hard problems

1. **Snapshot at T without scanning everything.** Today every tick is a whole-store scan —
   `PlanBSearchHelper.search` iterates the entire store and applies a row predicate.
2. **Density counts without reading every event.** The histogram sends no time range at all,
   deliberately, and so reads all history.
3. **A year or more without hitting the 10 GiB store ceiling.**

---

## 3. Building blocks already in the tree

Most of what is needed exists. Three of these arrived with upstream's Traces work
(`17371533de`, 2026-09-07).

### 3.1 Time-bucketed archives with partition pruning

```
Db.publish(publishBefore, bucketBaseDir)        generic default on Db; only TraceDb implements it
BucketGranularityUtil.label(granularity, t)     HOUR / DAY / WEEK
ArchiveShardLocator.findRelevantShards(doc, shardIndex, fromMs, toMs)
ShardManager.getArchive(doc, shardIndex, ref, fn)   cached read-only local copy per bucket
```

A query is handed only the buckets overlapping its range: cost scales with the range examined, not
with the store.

**Status, honestly:** the write side is live for Traces. The read side is built and tested but **not
wired into any query path** — `getArchive` has no caller in main source and `findRelevantShards` is
called only from `TestSharedFileStoreTraceRoundTrip`. Proven machinery awaiting a consumer.

**And we probably do not need it, which is worth saying plainly.** An earlier revision called this
"the answer to O4". It is not, because `maxStoreSize` is an uncapped `Long` passed straight to LMDB's
`setMapSize`, and an LMDB map on a 64-bit system is virtual address space. A year of *unique* event
data measures at ~79 GB (§6); set the map to 150 GiB and one ordinary store holds it.

So archives earn their place for **genuinely unbounded** retention, and for tiering cold data onto
shared storage — not for "at least a year", which needs only a larger number in a document setting.
**That makes the only Traces dependency in this design optional and deferrable**, which matters given
its read path has no consumer yet. Everything in §11 works without it.

### 3.2 A pre-aggregating HISTOGRAM state type

`StateType.HISTOGRAM` keys by `TemporalKey` at a configurable `TemporalResolution`
(YEAR…SECOND) and packs an array of sub-period counts into the value — `MinuteOfHourTemporalIndex`
gives 60 slots per hour row, `SecondOfHourTemporalIndex` 3600. One row per coarse period per tag
combination, instead of one row per event.

### 3.3 Secondary indexes

`TraceSecondaryIndex` keeps separate DBIs keyed by start time, duration, operation and so on, each
with the primary id appended, so a range filter becomes a key-range scan rather than a predicate over
a full scan. The pattern, not the code, is what transfers.

### 3.4 Single-node viability

`SharedFileStoreSettings` is `sharedPath` + `shardCount`. One node, `shardCount = 1`, a local
directory. **O5 is satisfied by configuration, not by new code.**

---

## 4. The change that buys the most: seek, don't scan

Plan B temporal keys are `<prefix><big-endian time>`, so every entity's rows are contiguous and
time-ordered. Snapshot-at-T does not need a scan:

```
for each distinct prefix:
    seek to  prefix‖T
    step back one            → that entity's state at T
    seek to  prefix‖0xFF…    → jump to the next entity, reading nothing in between
```

**O(entities × log n)** instead of O(all rows), and the intervening rows are never touched. For the
figures in use — 3 000 entities against ~100 M rows — that is 3 000 seeks rather than 100 M row
reads.

This is exactly what the standing `TODO : It would be faster if we limit the iteration to keys based
on the criteria` in `PlanBSearchHelper` points at. It needs no new store, no format change and no
document migration, and it makes per-tick cost independent of history depth — the property §1 says
must hold.

**It is also the single largest O2 win available**, and it is worth doing whether or not anything
else in this note happens.

---

## 5. The catch that shapes everything else

**Naive time bucketing breaks snapshot-at-T.** An entity that has not moved for three months is not
in the bucket containing T, so answering "where is everyone" means walking backwards through buckets
until every entity has been found — worst case, all of them. That would make O4 and O2 mutually
exclusive.

The fix is the one §2 of the feasibility note was circling when it found that a range read loses the
starting state:

> **Every bucket is self-contained: an opening checkpoint plus the events within it.**

```
bucket(day N) = opening snapshot   one row per entity known at the start of day N
              + events             everything that happened during day N
```

| Operation | Cost |
|---|---|
| snapshot at T | open bucket(T), read the checkpoint, apply events ≤ T — bounded by entities + one bucket |
| playback | consecutive ticks hit the same bucket; it stays in the shard cache |
| scrub anywhere | one bucket. No walking back, no replay from the beginning |
| a year | 365 day-buckets; a query touches one |

This is checkpoint-plus-delta. It is also what makes publishing safe: once a bucket carries its own
opening state, everything before it can be archived or dropped without making the bucket unreadable.

**The new work is the checkpoint.** Publishing today *moves* data; it does not compute an opening
state. That computation is the load-bearing part of this design and the part to prototype first.

### 5.1 Variable expiry, and why the checkpoint must not encode it

**The obvious checkpoint is wrong.** "Every entity known at the start of the bucket" is ambiguous once
expiry exists: an entity whose last event was 25 hours before the bucket starts is present under a
48-hour expiry rule and absent under a 24-hour one. Bake one rule in and every checkpoint is
invalidated the moment a user edits the setting — which is per-document, user-editable and defaults
to 24 hours (`docs/floormap-event-expiry-requirements.md`, D2).

**The decisive point is not the edit, though — it is that one events store can back several floor map
documents with different expiry settings.** There is therefore no single correct filtered checkpoint,
and no amount of rebuilding produces one. The checkpoint belongs to the **store**; expiry belongs to
the **document**.

So the checkpoint records, for every entity, its **state and its effective time**, unfiltered:

```
checkpoint(bucket N) = { entity → (state, effective_time) }   as at the start of bucket N
```

and expiry becomes a read-time predicate, exactly as the expiry requirements already concluded it
must be:

```
show entity  ⟺  effective_time >= T − expiry
```

Changing the setting needs no rebuild. Two documents with different expiries read the same checkpoint
and both get the right answer. Expiry stays where it was decided to live.

**What this costs.** The checkpoint now holds every entity *ever seen* up to that point, not just the
active ones. At 3 000 tracked people that is 3 000 rows per bucket — trivial beside a bucket's ~300 000
events. It grows with **entity churn** rather than event volume: visitors, contractors, replaced
devices. A store with high turnover accumulates dead entities in every checkpoint forever.

**Bounding it without inventing a concept.** Carry an entity forward only if it was seen within the
store's **retention period** — which O4 already fixes at a year or more, and which the store already
has as a setting (`RetentionSettings`). The checkpoint horizon is then not a new knob: it is retention,
and an entity that has aged out of the store is one there is no longer any data to show.

**The invariant this creates, which must be enforced rather than assumed:**

> **display expiry ≤ store retention**

Violate it and the map **silently under-reports**: a document asking for a 30-day expiry against a
store that carries 7 days forward will drop an entity idle for 10 days, even though its own rule says
to show it. No error, no warning, and the symptom — an entity missing from the map — looks like a
data problem rather than a configuration one. Validate it where the expiry is set, and say so.

**One pleasing consequence.** Computing a checkpoint *is* the §4 snapshot query run at the bucket
boundary. Publishing a bucket becomes "seek-snapshot at the boundary, store the result" — the same
code path, not a second implementation. If §4 is built first, §5 gets its hardest piece for free.

---

## 6. Sizing

A year at 3 000 entities × 100 events/day = **109.5 M events**. At the rates measured in
`docs/floormap-single-read-feasibility.md` §5:

| Value cardinality | Bytes/row | One year |
|---|---|---|
| Low (values repeat; `VARIABLE` dedupes them) | 162 | **~18 GB** |
| Unique per event | 725–1350 | **~79–148 GB** |

Both exceed the 10 GiB **default** `maxStoreSize` — but that is a default, not a ceiling (§3.1). One
store with its map set to 150 GiB holds a year of unique data. Archives become necessary only beyond
that, for unbounded retention or cold-data tiering; day buckets would put each at roughly 50–400 MB.

Note that O4 says *unique* event data, which points at the upper rows. Keeping values
low-cardinality is worth about 5× and is free; see §5 of the feasibility note.

---

## 7. The alternative: just do it in MySQL

Not hypothetical — **`stroom-sqlstore` is already a MySQL-backed temporal store**, it passes the
parity suite against Plan B, and it has its own separately-configurable datasource
(`SqlStoreDbConnProvider extends DataSourceProxy`, its own `SqlStoreDbConfig` and Flyway module), so
it can point at its own instance rather than sharing Stroom's.

Its schema is:

```sql
PRIMARY KEY (doc_uuid, key_, effective_time)
```

**which is the same layout as Plan B's `<prefix><time>`** — and InnoDB clusters on the primary key,
so one entity's rows are physically adjacent and time-ordered, exactly as in LMDB.

### Advantages

| | |
|---|---|
| **A1** | **§4's optimisation is declarative rather than hand-written.** The existing query already groups by `(doc_uuid, key_)` and takes `MAX(effective_time)` with `effective_time <= ?` — a shape MySQL 8 can serve by loose index scan over the clustered PK. Plan B needs the cursor logic writing and testing; MySQL needs an `EXPLAIN`. |
| **A2** | **§5's bucketing is `PARTITION BY RANGE (effective_time)`.** Partition pruning is automatic and needs no publish machinery, no bucket labels, no archive locator, no cached read-only copies. |
| **A3** | **Retention is `DROP PARTITION`** — near-instant, no rewrite, no compaction. Compare Plan B, where "pages freed by deletes are never returned to the OS". |
| **A4** | **No fixed store ceiling.** No `maxStoreSize`, no 95% merge stop, no `MapFullException`. Size is bounded by disk. |
| **A5** | **The histogram is a `GROUP BY`** over an indexed column — no second store, no HISTOGRAM state type, no ingest change. |
| **A6** | **O5 falls out.** One instance, every node sees the same data. No shards, no merge, no snapshot distribution, no shared-filesystem requirement. |
| **A7** | **Operationally ordinary.** Backup, restore, monitoring, `EXPLAIN`, index tuning, point-in-time recovery — all standard, all already understood by whoever runs Stroom. |
| **A8** | **It exists and works today.** The floor map already reads facts from one. |

### Disadvantages

| | |
|---|---|
| **D1** | **Values are `longtext` with no deduplication.** Plan B's `VARIABLE` type dedupes repeated values, worth ~5× as measured. A year of low-cardinality data is ~18 GB on Plan B but would be the full ~79 GB on MySQL. InnoDB page compression recovers some of that; it does not recover the 5×. |
| **D2** | **Write path.** 300 k events/day is 3.5/s on average, which is nothing — but Stroom ingest is bursty and pipeline-driven, and Plan B is built for that with local LMDB writes and a merge stage. Sustained bursts land on the database instead. |
| **D3** | **Every read is a network round trip.** Plan B reads a local LMDB file; MySQL reads over a socket, with connection-pool and round-trip costs on every one of the 300 ms ticks. Lower per-query CPU, higher per-query latency. |
| **D4** | **It is a fork-local module.** `stroom-sqlstore` does not exist on `origin/master`. Betting the floor map on it means owning it forever, with no upstream fixes, no upstream review, and full merge exposure. |
| **D5** | **It swims against where Stroom is investing.** Traces, metrics, histograms, shared-file-store shards and snapshot distribution are all Plan B. Anything built on the SQL store gets none of that and will keep needing its own version of each. |
| **D6** | **No snapshot/read-replica story.** Plan B distributes read-only shard snapshots to query nodes; the MySQL equivalent is replication, which is a different operational commitment. |
| **D7** | **A1 and A2 are optimiser-dependent.** Whether MySQL actually picks a loose index scan, and how partition pruning behaves with the correlated subquery, are `EXPLAIN` questions on real data — not things this note has established. |

### The honest summary

**MySQL wins on everything except data volume, write path and strategic direction — and those are
exactly the three that O2 and O4 are about.** A1–A3 are genuinely strong: partitioning and
`DROP PARTITION` give, for free and immediately, the bucketing and retention that §5 asks us to
build. Against that, D1 alone turns 18 GB of yearly data into 79 GB, and D4/D5 mean the floor map
would sit on a module nobody upstream maintains.

**The decisive question is D7 plus D2**: if a `PARTITION BY RANGE` table with 100 M rows serves the
snapshot query in single-digit milliseconds, MySQL is the pragmatic answer and §5's checkpoint design
is unnecessary work. If the optimiser will not cooperate at that scale, Plan B with §4 and §5 is the
only route that meets O2.

**That was a measurement, not an argument. It has been run — see §7.1.** The short version: it does
not serve it in single-digit milliseconds, and partition pruning turns out not to help the snapshot
at all, so §5 is needed either way.

---

## 7.1 Step 0 was run — results

**Setup.** 99 999 000 rows (3 000 entities × 33 333 events over 2026), same columns and primary key
as `updatable_temporal_store`, partitioned `BY RANGE (effective_time)` into 13 monthly partitions.
MySQL 8.4.3. Generated in primary-key order; loaded in 484 s; **22.02 GiB**, rising to 32.9 GiB once
two secondary indexes were added.

### Headline: MySQL picks the right plan, unprompted

```
Extra: Using where; Using index for group-by
-> Covering index skip scan for grouping on t2 using PRIMARY over (effective_time <= …)
```

That is §4's seek-per-entity optimisation, selected by the optimiser with no cursor code written. It
returns 3 000 rows from 100 M without reading the rest, and the outer join is an `eq_ref` primary-key
lookup per entity. **Q1 is answered yes on plan.**

### Timings

| Configuration | Snapshot at T | Histogram, 1 day |
|---|---|---|
| 128 MB buffer pool (the default), cold | 2 408 ms | 6 780 ms |
| 8 GB buffer pool, warm | **232 ms** | 6 337 ms |
| 8 GB + index `(doc_uuid, effective_time)` | 248 ms | **147 ms** |
| 8 GB + narrow covering index `(doc_uuid, key_, effective_time)` | 227–245 ms | — |

**The buffer pool was the whole story on the snapshot**: 2 408 ms → 232 ms on identical data and an
identical plan. The 128 MB default is not representative of any deployment.

**The histogram needed an index, and got 46× for one line of DDL.** The cause is structural: with
`key_` unconstrained, `effective_time` is the third column of the primary key and cannot drive a
range scan, so the query read a whole month's partition (8.5 M rows) to find 274 k.
**Plan B's key layout is the same `<prefix><time>`, so it has the same weakness** — and outside
Traces it has no secondary-index mechanism to fix it with.

**A narrow covering index did not help the snapshot.** `idx_snap` was chosen and cut the row estimate
from 358 k to 82 k, but wall time was unchanged. The cost is B-tree descents, not page width.

### The result that matters most: pruning cannot help a snapshot

Snapshot cost against how many partitions precede T:

| T | Partitions spanned | Time |
|---|---|---|
| January | 1 | 172 ms |
| April | 4 | 304 ms |
| July | 7 | 228 ms |
| December | 12 | 366 ms |

Noisy — April and July are out of order — but the trend from 172 ms to 366 ms is real, and the reason
is not a MySQL quirk:

> **`effective_time <= T` cannot prune, because any earlier partition might hold an entity's latest
> row.** The later T is, the more partitions the skip scan must consider. The common case — scrubbing
> near "now" — is therefore the slowest.

**This is precisely the problem §5 describes, and it appears identically in MySQL.** Partition pruning
serves the histogram, which is a true range query, and cannot serve the snapshot, which needs
latest-before-T with unbounded lookback.

**So §5's checkpoint design is required whichever store wins.** On Plan B it is a per-bucket
checkpoint; on MySQL it is a materialised "state at boundary" table. The same design, the same
§5.1 rules about expiry, and the same work. That removes what looked like MySQL's biggest advantage
(A2: "partitioning gives us §5 for free" — it does not).

### Verdict against §7's decisive question

§7 said: *if a partitioned table serves the snapshot in single-digit milliseconds, MySQL is the
pragmatic answer and §5 is unnecessary work.* It does not — **~230 ms warm, ~2.4 s cold, and rising
with retention depth.** Against a 300 ms playback tick that is viable for one user and has no
headroom for several.

So D1 (5× storage — 22 GiB here for one year, against ~18 GB measured on Plan B for the same data),
D2 (ingest) and D4/D5 (fork-local module, against the grain of Plan B investment) stop being
tiebreakers and become the decision. **Neither store is fast enough without §5.**

### Caveats on these numbers

- Values were ~130 bytes of *repeating* JSON. Plan B would deduplicate these; MySQL does not. With
  unique values MySQL's table would be unchanged but Plan B's would grow ~5× (§6), narrowing D1.
- Single user, no concurrency. The 232 ms is one query with the working set cached in 8 GB.
- `ANALYZE TABLE` was run before measuring; timings are the best of several warm runs.

---

## 8. Proposed sequencing

> **Superseded by §11.7.** This section records the sequencing as it stood when Step 0 was
> commissioned; §11.7 is the current order and differs in two ways — it drops the Plan B
> `HISTOGRAM` store in favour of a `GROUP BY` (§11.5), and it adds the query surface. Kept
> because the reasoning below is what Step 0 was designed to settle.

**Step 0 — done (§7.1).** It answered more than it was asked. The optimiser does pick the skip scan,
but the snapshot costs ~230 ms warm and ~2.4 s cold, rising with retention depth — and **partition
pruning cannot help it**, because any earlier partition may hold an entity's latest row. That last
point is store-independent, so it changes the order below: **§5 moved from deferred to required**,
and it is now the thing that decides whether either store meets O2, rather than an O4 nicety.

Revised order, whichever store wins:

1. **Seek-based snapshot (§4).** On Plan B this is the cursor change; on MySQL it is confirming the
   optimiser already does it. Biggest O2 win either way, self-contained, testable against the
   existing parity suite.
2. **Bounded histogram (§3.2 or A5).** Removes the last all-history read. On Plan B, a second store
   of type `HISTOGRAM`; on MySQL, a `GROUP BY`. *(§11.5 revises this: use the `GROUP BY` on either
   store, and defer a purpose-built count store.)*
3. **Checkpoints (§5).** No longer deferred and no longer only about O4 — §7.1 shows it is what makes
   the snapshot bounded on *either* store. On Plan B a per-bucket checkpoint; on MySQL a materialised
   "state at boundary" table. Same design, same §5.1 expiry rules, same work.
4. **Then choose the store**, on the grounds that are left once §5 is a given: storage (D1), ingest
   (D2) and where the code should live (D4/D5). Not on query speed, which §7.1 shows is comparable
   and inadequate on both without §5.

---

## 9. Open questions

| | Question | Why it decides something |
|---|---|---|
| ~~**Q1**~~ | ~~Does MySQL serve the snapshot query by loose index scan at 100 M rows?~~ | **Answered — §7.1.** Yes on plan, no on speed. Pruning cannot help the snapshot, so §5 is required on both stores. |
| **Q2** | Who computes a bucket's opening checkpoint, and when? | The load-bearing new work in §5. Publishing today moves data; it does not checkpoint. |
| **Q3** | Does §4's seek optimisation interact with `condense`? | Condense rewrites runs of identical values; if it changes which key is "last before T", the two need testing together. |
| **Q4** | Are event values low- or high-cardinality in practice? | 5× on storage (§6), and it decides whether O4 needs 18 GB or 148 GB. |
| **Q5** | Is per-tick latency or per-tick CPU the binding constraint? | Decides D3. A local LMDB read and a MySQL round trip fail in different directions. |

---

## 10. Assumptions

| | Assumption | If wrong |
|---|---|---|
| **A1** | Playback stays sampling-based. Nothing in the UI requires every event to be seen. | Cost becomes proportional to event count and every design here fails O2 at speed. |
| **A2** | Entity count is bounded in the low thousands, and grows far more slowly than event count. | §4's seek-per-entity stops being cheap and the 20 000-row fetch cap starts binding. |
| **A3** | A bucket's events plus its checkpoint fit comfortably in one read. | Bucket granularity becomes a tuning parameter rather than a constant; HOUR/DAY/WEEK already exist. |
| **A4** | The archive read path works as its tests suggest, once given a consumer. | §3.1's status note becomes a blocker rather than a caveat. |
| **A5** | Floor Map events may be written to a second store (e.g. a HISTOGRAM) at ingest. | §3.2 needs the counts deriving at query time instead, which is the problem it was meant to solve. |
| **A6** | No production data in these systems, so Step 0 can be run destructively on real hardware. | Stated by the user for this branch. |

---

## 11. The architecture

### 11.1 The one fact everything follows from

The events store is asked **two questions with incompatible access patterns**:

| | Shape | Prunes by time? |
|---|---|---|
| "state at T" | a point lookup per entity, with **unbounded lookback** | **No** — any earlier partition may hold an entity's latest row |
| "activity over a range" | a bounded range scan | Yes |

§7.1 demonstrated the first empirically on MySQL, and the reasoning is store-independent: **no single
key layout serves both.** Every dead end reached so far — the 20-second window, holding history
client-side, delta reads, partition pruning — is a consequence of asking one structure both questions.

So the architecture **materialises two derived structures from one event stream**, and moves the
expensive query from read time to write time.

### 11.2 The blocks

```
                      ingest (pipeline, unchanged)
                                 │
                                 ▼
  ┌───────────────────────────────────────────────────────┐
  │  A. EVENT LOG        key: (entity, time)              │
  │     append-only, time-partitioned, retention-managed  │
  └───────────────────────────────────────────────────────┘
          │                                    │
          │  D. CHECKPOINT BUILDER             │  aggregate
          │  periodic, one pass per boundary   │
          ▼                                    ▼
  ┌──────────────────────────────┐   ┌──────────────────────────┐
  │  B. CHECKPOINT STORE         │   │  C. COUNT STORE          │
  │     key: (boundary, entity)  │   │     key: (bucket)        │
  │     value: state + eff.time  │   │     value: count         │
  └──────────────────────────────┘   └──────────────────────────┘
          │                    │                │
          └────────┬───────────┴────────────────┘
                   ▼
  ┌───────────────────────────────────────────────────────┐
  │  E. QUERY SURFACE — a `Searchable` we own             │
  │     three read modes, semantics defined by us         │
  └───────────────────────────────────────────────────────┘
                   │  ordinary StroomQL
                   ▼
  ┌───────────────────────────────────────────────────────┐
  │  F. CLIENT — unchanged. Scrubber, variable speed.     │
  └───────────────────────────────────────────────────────┘
```

**A — Event log.** What exists today: keyed entity-first so one entity's history is contiguous,
time-partitioned for retention and range queries. Nothing changes here except switching retention on.

**B — Checkpoint store.** The new idea, and **note the key order: boundary first, entity second** —
deliberately the opposite of A. "Every entity as at boundary N" becomes a single contiguous prefix
scan, one seek and ~3 000 rows, instead of a per-entity lookup with unbounded lookback. It carries
each entity's **effective time** alongside its state, unfiltered, so expiry remains a read-time
predicate (§5.1).

**C — Count store.** Pre-aggregated bucket counts, so the histogram stops reading events. See §11.5.

**D — Checkpoint builder.** A periodic job running the expensive snapshot query **once per boundary**
and writing the result to B. This is the whole trick: at hourly boundaries that is **24 expensive
queries a day instead of three per second of playback**. It runs at **boundary + grace**, not at the
boundary — see §11.11. Scheduled on the same footing as Plan B's existing condense and retention jobs.

**E — Query surface.** A `Searchable` in `stroom-floormap-impl` (§4 of
`docs/floormap-single-read-feasibility.md`) exposing exactly three reads. Because we write
`search()`, the semantics are ours: no inference from term shape, no stripped lower bounds, no
dependence on whether a date literal carries a `Z`.

**F — Client.** Unchanged. Still samples; still one query per 300 ms tick.

### 11.3 The read paths

```
snapshot at T   →  B: prefix scan at ⌊T⌋       ~3 000 rows, one seek
                +  A: events in (⌊T⌋, T]       ≤ one boundary's worth, prunes to one partition
                →  fold, apply expiry, return one row per entity

histogram       →  C: read buckets in range    ~100 rows

time extent     →  C: first and last bucket    2 values
```

**Both halves of the snapshot are bounded and independent of total history.** That is the O2 win and
the property the current design lacks.

**The fold window is `interval + grace`, not `interval`** (§11.11), because the newest checkpoint is
always one grace period old. At 300 k events/day with hourly boundaries and a 15-minute grace, the
worst case is 75 minutes ≈ **15 600 events** to fold — still a pruned range scan over a single
partition. **The boundary interval is the tuning knob**: shorter means more checkpoints and less
folding.

### 11.4 Deliberately excluded

| | Why |
|---|---|
| Client-side history | The transfer constraint rules it out (`floormap-single-read-feasibility.md`) |
| Replay-style playback | Cost would scale with event count, so speed would stop being free. Sampling stays — see §1 |
| Per-tick full scans | The thing being removed |

### 11.5 Block C: not the Plan B `HISTOGRAM` store

The `HISTOGRAM` state type looked like an off-the-shelf fit for C. **It has been suggested that it is
not suitable.** The reason was not given, so what follows is this document's own reading and should be
checked with whoever raised it — they may have a better reason than any of these.

Candidate reasons, from the code:

1. **Resolution is fixed per store.** `HistogramKeySchema` carries one `TemporalResolution`. The
   timeline needs bucket widths from 5 minutes to 1 month (§3's decision table). Storing at the finest
   and rolling up with `group by` works, but then the finest resolution sets the store size — SECOND
   resolution over a year is ~31.5 M slots per key.
2. **The key model is unfinished.** `HistogramFields` carries `// TODO : Multi tags`.
3. **It models a different question.** Its fields are `Key, Time, Resolution, Value` — counts *per
   key* per slot. The density bars want total activity per bucket, with no per-entity breakdown.
4. **Ingest would need a second path.** Floor map events are written to a `TEMPORAL_STATE` store; a
   `HISTOGRAM` store needs its own pipeline branch emitting the right XML.
5. It carries the same `TODO : It would be faster if we limit the iteration to keys based on the
   criteria` as every other Plan B DAO — though on a store this small that matters far less.

**None of this is a problem, because what C needs is trivial.** One row per bucket, one count, no
tags, no per-entity breakdown. That is a much smaller thing than `HistogramDb` models, and building it
is not hard.

**It may not be needed at all initially.** §7.1 measured a `GROUP BY` over the event log with a
`(doc_uuid, effective_time)` index at **147 ms for a one-day range** — good enough to ship without C.
C earns its place on **wide** ranges, where that `GROUP BY` scans proportionally more: a year-wide
histogram reads a year of events, a count store reads ~100 rows. So:

> **Start with the `GROUP BY` and a time index (§3). Add C when the wide-range case becomes the
> complaint.** Same read path from E's point of view, so swapping one for the other is invisible to
> the client.

### 11.6 Which store for which block

Deliberately deferred; the architecture does not depend on it.

| Block | Suits | Why |
|---|---|---|
| **A** | Plan B | Value deduplication is worth ~5× (§6), and ingest is built for bursts |
| **B** | Either | Small — 3 000 rows per boundary — and read by prefix |
| **C** | Either | Trivial either way once §11.5 is accepted |

**Choose on storage, ingest and where the code should live — not on query speed**, which §7.1 showed
is comparable and, without B, inadequate on both.

### 11.7 Sequencing

1. **Seek-based snapshot (§4)** — the biggest immediate win, no new stores, and it becomes D's
   implementation.
2. **Bounded histogram (§3)** — `GROUP BY` plus a time index. Removes the last all-history read.
3. **Checkpoints (B + D)** — the bounded snapshot. §5 and §5.1 are its design; §11.11 is its build
   policy, including the grace period D must wait out before building each one.
4. **Query surface (E)** — may come earlier if convenient; nothing depends on its ordering.
5. **Count store (C)** — only when §11.5's wide-range case bites.

**Steps 1 and 2 are independently shippable and each fixes a defect that exists today.**

### 11.8 Multiple result sets per request

**The capability exists; the floor map's entry point does not expose it.** `SearchRequest` carries a
*list* of `ResultRequest`s, and `CoprocessorsFactory.createSettings` groups them by `TableSettings`
and creates **one coprocessor and one `DataStore` per distinct group**, all fed from a single source
scan. Dashboards use this in production — `DashboardSearchRequest.componentResultRequests` →
`SearchRequestMapper` → multiple `ResultRequest`s.

What blocks it here is the request shape, not the machinery: the floor map uses `QuerySearchRequest`,
whose `mapRequest` derives result requests from **one StroomQL string**, so it yields exactly one
table. Using the multi-result path means adopting the dashboard request shape or adding a resource —
so "a custom REST resource" is one option of three, not a requirement.

**The benefit is small, because the three reads differ in cadence, not only in shape:**

| Read | Cadence |
|---|---|
| snapshot | every tick |
| histogram | on range change |
| extent | once per document |

Bundling reads with different cadences spends the slow one's bandwidth on every tick. **Per-tick
bundling is negative value.** The one case that genuinely benefits is **document open**, where all
three are wanted at once — saving two round trips of three. Real, but small beside the cold-cache
query cost that dominates that moment (§7.1: 2.4 s cold against 232 ms warm).

**The tempting version does not work.** Returning the snapshot at T *plus* the next N of events, so
the client advances locally, is replay — excluded by §11.4. At ×10000 a 300 ms tick covers ~35 days of
timeline, so the prefetch window is unbounded. It helps only at low speed, and the uniform sampling
design already handles every speed identically.

**And block E makes the question moot.** Once we own `search()`, a single result set can carry any
shape we like — a discriminator column, or a wider row. Multiple result sets stop being necessary
rather than becoming useful.

> **Decision recorded: stay on the generic query path with a single result set.** Revisit only if
> document-open latency becomes an actual complaint. See D5 in §11.10 for the related question of
> whether the generic path is the right one at all.

### 11.9 What this architecture assumes

Distinct from §10, which covers the note as a whole. These are specific to §11 and each one, if
false, changes a block.

| | Assumption | If wrong |
|---|---|---|
| **AA1** | **Playback stays sampling-based.** Nothing in the UI requires every event to be seen. | Cost becomes proportional to event count; §11.4's exclusions collapse and speed stops being free. |
| **AA2** | **Entity count stays in the low thousands and grows far more slowly than event count.** Block B is sized entities × boundaries. | B stops being small. At 100 k entities an hourly boundary is 876 M checkpoint rows a year, and B needs its own retention. |
| **AA3** | **A boundary interval exists that keeps folding cheap** — ≤ one boundary's events, pruned to one partition. | Either checkpoints get expensive (short interval) or folding does (long). The interval becomes a per-store tuning parameter rather than a constant. |
| **AA4** | **Computing a checkpoint is the same query as serving a snapshot** (§5.1). | D needs its own implementation and the §4 work stops being reusable. |
| **AA5** | **A stale position for at most one boundary interval is tolerable.** A checkpoint at boundary B is only correct if every event with `effective_time < B` arrived before it was built; §11.11 decides how to handle those that do not. | The self-healing property (§11.11) stops being enough, and D3's option 4 or a rebuild is needed. |
| **AA6** | **One events store may back several floor map documents, with different expiry settings.** | §5.1's whole argument weakens; a filtered checkpoint would become possible, though still fragile. |
| **AA7** | **Retention ≥ display expiry** is enforceable where expiry is set. | The map silently under-reports (§5.1). |
| **AA8** | **The client tolerates ~230 ms per tick.** It already drops ticks while a read is in flight, so latency degrades smoothness rather than correctness. | The tick budget becomes a hard constraint and B's boundary interval has to shrink. |

### 11.10 Decisions to be taken

Ordered by how much else depends on them.

| | Decision | Why it matters | Notes |
|---|---|---|---|
| ~~**D1**~~ | ~~Which store backs block A~~ | | **DECIDED: Plan B, not MySQL.** And Plan B rather than a bespoke LMDB store — see §11.12 for why, and for how D4's concern is met without owning the storage engine |
| ~~**D2**~~ | ~~Boundary interval for checkpoints~~ | | **DECIDED: hourly.** 24 builds a day; worst-case fold `interval + grace` ≈ 15 600 events (§11.3). Recent times are the ordinary case, not an edge case — see §11.12.1 |
| ~~**D3**~~ | ~~Late-event policy~~ | | **DECIDED: grace period, option 1 — see §11.11.** Option 4 (fold from an older checkpoint) is the escape hatch and can be added later without changing anything built for option 1 |
| **D4** | **How the store is presented** | | **Decided (c), now recommended for revision to (b) — see §11.14.** (c) was chosen because (b) was believed to need changes inside `stroom-planb-impl`. It does not: `PlanBDocument` is an interface, the doc types Plan B recognises are a Guice multibinder, and `stroom-pathways-impl` already does exactly this from outside. The module question is answered by the same finding |
| ~~**D5**~~ | ~~Keep the user-authored events query?~~ | | **DECIDED: keep it** — the flexibility is worth the machinery. This fixes E as a `Searchable` taking StroomQL, and rules out the typed `FloorMapResource` endpoint |
| ~~**D6**~~ | ~~Retention period, and how AA7 is enforced~~ | | **DECIDED: default expiry and retention to 1 day.** A user who raises it too far owns the consequence. AA7 (`expiry ≤ retention`) still needs validating where expiry is set, so the failure is an error rather than silent under-reporting |
| **D7** | **How block B is stored** | The key *order* is fixed (§11.2) and the key *layout* is settled (§11.12); only the container is open | **Explained in §11.13, and it follows D4.** (c) now implies a second floor-map-created document; (b) later would allow a DBI inside the events store's own environment. The migration between them is a copy, not a rewrite |
| ~~**D8**~~ | ~~Build the count store (C), or stay with `GROUP BY`?~~ | | **DECIDED: `GROUP BY`, whichever is simpler.** Revisit only if wide-range histograms become an actual complaint |
| ~~**D9**~~ | ~~Does the Events Query tab read through E?~~ | | **DECIDED: route through E.** Not required for accessibility, but it fixes an accessibility-visible inconsistency — see §11.12.2 |

**Nothing now blocks a prototype.** D7 is the only decision still genuinely open, and §11.13 shows it
follows from D4 rather than standing alone. The one remaining loose end is the *module* question
within D4 — where block E's code lives — which is a packaging problem, not a design one.

> **A numbering note.** The retention decision above was given against "D7"; it answers **D6**.
> D7 — how block B is stored — is a different question and remains open.

### 11.11 Late events: when the checkpoint is built, and what it costs

**Decision: option 1, a grace period.** The same choice Traces made, for the same reason.

#### The problem

`checkpoint(B)` claims to be the state of every entity as at B. It is only correct if **every event
with `effective_time < B` has already arrived.** Floor map events are pipeline-ingested, so an event
arriving after its own effective time is normal rather than exceptional, and nothing about the
checkpoint's contents reveals that one was missed.

#### The framing that makes this tractable

> **The checkpoint is a performance optimisation, not a source of truth.** Correctness lives in the
> event log; the checkpoint is only a starting point for the fold.

That is what makes a cheap answer acceptable here, and it is what gives option 4 below its power.

#### The decision

Build `checkpoint(B)` at **B + grace**, not at B. This is what `TraceDb.publish`'s `publishBefore`
cut-off does — its javadoc calls the cut-off *"the operator's answer to 'how long until all of a
trace's spans have arrived'"* ([TraceDb.java:1004](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/dao/trace/TraceDb.java:1004)).

**Its important property is that it is self-healing.** An event that misses `checkpoint(B)` is still
in the log, so `checkpoint(B+1)` — built from the log — includes it:

| | |
|---|---|
| Damage window | **one boundary interval** |
| Who is affected | only entities whose latest state the late event would have changed |
| Symptom | a stale position (or a missing entity), for scrub positions in `(B, B+interval)` only |
| Afterwards | corrects itself permanently, with no intervention |

**Why that is acceptable here.** This is a monitoring view. One entity showing a stale position, for
a bounded window, at scrub positions in the past, repairing itself — that is a very different
severity from the same defect in an audit report or a billing run. Weigh it against the alternatives
below, all of which cost real machinery.

#### Consequences to design around

1. **The newest checkpoint is always one grace period old.** The fold window is therefore
   `interval + grace` (§11.3), not `interval`.
2. **Grace is a per-store setting**, because it is a property of the ingest path, not of the map.
3. Grace trades exposure against freshness: longer grace means fewer missed events but an older
   newest checkpoint and a larger fold.

#### The options not taken

| | | Why not |
|---|---|---|
| **2. Rebuild affected checkpoints** | Detect events with `effective_time` before the newest boundary and rebuild | Correct, but needs change detection and one expensive query per affected boundary. Worth revisiting only if the self-healing window proves too long |
| **3. Accept, with no grace** | Build at B | Option 1 with `grace = 0` — strictly more exposure for no saving |
| **4. Fold from an older checkpoint** | Fold from `checkpoint(B−n)` and apply `(B−n, T]` | **Kept as the escape hatch.** A *read-time* knob: because the fold reads the log, it picks up any late event in the window regardless of when it arrived. No rebuild, no extra storage, tunable per query, composes with option 1. Costs n× the fold, so it buys correctness with latency — add it if a deployment turns out to have long lateness |

### 11.12 D1 and D4: which store, and how it is presented

These arrived as two questions and are really one. D1 asked Plan B or MySQL, then Plan B or a bespoke
LMDB store. D4 asked for a `FloorMapEventStore` that cannot be misconfigured. The second is the
reason to consider the first.

#### The concern is real

A floor map today points at an ordinary Plan B document, and the user can change `stateType`, the key
and value schemas, `condense`, `retention`, snapshot settings and `maxStoreSize`. Several of those
break the map, and most break it **silently**:

| Setting | What it does to the map |
|---|---|
| `condense` | collapses runs of identical positions — the survivor can fall outside a read, so a stationary entity vanishes |
| key type ≠ the default shape | breaks the arrival-order guarantee `FloorMapEventsQueryOrder` depends on, and the reduction falls back to comparing rendered times |
| value type | ~5× on storage (§6), invisibly |
| `retention` off (the default) | the store grows until the 95% merge stop, at which point ingest silently stops being queryable (§11.11) |
| `maxStoreSize` at 10 GiB default | ~7 months, or ~4 weeks with unique values (§6) |
| `stateType` ≠ `TEMPORAL_STATE` | nothing works at all |

**None of these produce an error.** They produce a map that is subtly wrong, and the cause is a
document a user was entitled to edit.

#### But owning the storage engine is not the answer

The tempting conclusion is to own our own LMDB and expose only what suits the map. Against that:

- **Plan B gives a great deal for free** — merge, shard management, snapshots, retention,
  compaction, the document lifecycle, and a search provider. `ShardManager` alone is 600+ lines we
  would be reimplementing.
- **And the key layouts §11.2 needs are expressible in it.** This was the real question. Block A is a
  `TEMPORAL_STATE` store, which is `<entity><time>` — exactly right. Block B needs *boundary*-first
  ordering, which looks impossible for a temporal store keyed by entity — but a plain `STATE` store
  with a composite `KeyPrefix` of `"<boundary>|<entity>"` sorts lexicographically boundary-first, so
  a prefix scan on `"<boundary>|"` returns every entity at that boundary in one contiguous range.
  **That is the access pattern §11.3 is built on, and Plan B can serve it today.**

> **Decision (D1): Plan B.** Not MySQL (§7.1), and not a bespoke LMDB store — the configuration
> problem is not a storage-engine problem, and solving it that way costs far more than it saves.

#### So the answer to D4 is about presentation, not storage

What is wanted is a store that is *created and owned by the floor map*, exposing only the settings
that make sense for it — expiry/retention, size, and nothing else — with `stateType`, key schema,
value schema and `condense` fixed at values the map requires.

Three shapes, and the constraint that rules one out:

| | Shape | Verdict |
|---|---|---|
| **a** | **Hidden entirely inside `FloorMapDoc`** — no separate store document | **Ruled out by ingest.** Plan B resolves a store by *document name* (`docFinder.findByName(type, name)`), and a pipeline's `PlanBFilter` names it in the `<map>` element. A store with no document has no name to write to. It would also kill AA6 — one store backing several maps — which is a capability, not an accident |
| **b** | **A `FloorMapEventStore` document type** that is a Plan B store with locked-down settings | **The shape wanted.** The open question is whether it can be registered with Plan B's machinery without editing `stroom-planb-impl`, since `StateSearchProvider.getDataSourceType()` keys on `PlanBDoc.TYPE` |
| **c** | **A normal Plan B document, created and configured by the floor map**, with the map validating it and warning loudly when it is edited into an unusable state | **The pragmatic fallback.** No new document type, no upstream changes. It does not *prevent* misconfiguration, it detects it — which is most of the value, since today's failures are silent |

**Recommendation: start at (c), design towards (b).** (c) is achievable now, needs nothing from
upstream, and converts every silent failure in the table above into a visible one. (b) is the better
end state and (c) does not block it — the validation logic written for (c) is exactly the constraint
set (b) would enforce.

**Still to establish for (b):** whether a non-`PlanBDoc` type can participate in Plan B's doc cache,
search provider and merge processor without changes inside `stroom-planb-impl`. If it cannot, (b)
carries the same merge exposure §11.12 is trying to avoid, and (c) becomes the answer rather than the
step towards it.

#### 11.12.1 D2: what about looking at the last hour?

Hourly boundaries do not make recent times a special case — **they make them the ordinary case**,
which is the one the fold window was sized for.

A snapshot at T folds from the newest checkpoint at or before T. Because a checkpoint is built at
`boundary + grace` (§11.11), the newest one available may be the *previous* boundary's:

```
interval = 1h, grace = 15m

now = 10:30, T = 10:25   →  checkpoint(10:00) exists (built 10:15), fold 25 minutes
now = 10:10, T = 10:05   →  checkpoint(10:00) not built yet, fold from checkpoint(09:00) = 65 minutes
```

The worst case is `interval + grace` = 75 minutes ≈ 15 600 events at 300 k/day — a pruned range scan
over a single partition, and exactly the figure §11.3 quotes. **Looking at the last hour, or at
"now", costs the same as looking at any other time.** There is no cliff.

If that fold ever proves too slow, the knob is the interval, not the architecture: halving it halves
the worst-case fold and doubles the number of checkpoint builds.

#### 11.12.2 D9: routing the Events Query tab through E, and accessibility

**It is not required for accessibility — but it fixes something accessibility-visible.**

The canvas keeps a standing accessible summary, exposed as the map image's accessible name, whose
entity counts come from `lastEventObjects` — *"the event objects last handed to `setEventObjects`"*.
That field is written by whichever producer pushed last, and the Events Query tab is a second
producer: it publishes over its own time range **without** the per-entity reduction the map applies.

So while that tab is open, the accessible summary can report a count that matches neither the map's
state nor what a sighted user would count on the canvas — and a screen-reader user has no way to see
the discrepancy. Routing both producers through E makes them agree.

To be clear about the severity: the accessible summary is deliberately kept out of `redraw()` and is
refreshed only where content changes, so this is a wrong number rather than a flood of announcements.
It is a correctness bug that happens to surface in an accessibility feature, not an accessibility
barrier.

### 11.13 D7: where the checkpoint rows physically live

**This decision follows D4 rather than standing alone**, which is the main thing to understand about
it. Two things are already settled and only the third is open:

| | |
|---|---|
| Key **order** | boundary first, entity second (§11.2) — the whole point of block B |
| Key **layout** | a composite `KeyPrefix` of `"<boundary>|<entity>"` in a plain Plan B `STATE` store, so a prefix scan on `"<boundary>|"` returns every entity at that boundary (§11.12) |
| **Container** | open — this decision |

#### The options

**(i) A second Plan B document**, type `STATE`, created and owned by the floor map alongside the
events store.

- Works today with no upstream changes, and inherits merge, retention, snapshots and the search
  provider, so block E can read it like anything else.
- Costs a **second document per floor map** to create, name, hide and keep in step with the first —
  and it is subject to D4's misconfiguration problem twice over.
- **Writes to A and B are not atomic.** The checkpoint builder reads A and writes B in separate
  transactions, so a crash between them leaves B behind A. That is survivable for the same reason
  §11.11's grace period is: the next build reads the log and repairs it. Worth knowing rather than
  worth fixing.

**(ii) A second DBI inside the events store's own LMDB environment.**

- **Verified feasible.** A Plan B store type opens named databases freely —
  `env.openDbi(name, MDB_CREATE)`, with `maxDbs` a constructor parameter. `TraceDb` opens five plus
  seven secondary-index DBIs in one environment.
- One document, one retention, one lifecycle, and **nothing extra for a user to see or break** —
  which is exactly what D4 is trying to achieve.
- Writes to A and B can **share a transaction**, so B is never inconsistent with A.
- Costs a Plan B store type that knows about the extra DBI — a change inside `stroom-planb-impl`.
  That is D4 option (b) territory and carries the merge exposure (c) was chosen to avoid.

**(iii) Derived on demand and cached in memory.** No persistence: compute a checkpoint when first
needed and keep it. Nothing to manage, self-correcting by construction, and it makes block D optional.
But a cold start pays the expensive query (§7.1: ~2.4 s), nothing survives a restart, and each node
caches separately — so it amortises only within one node's uptime, which is most of what block D
exists to do.

**(iv) A reserved key space inside the events store itself.** Checkpoint rows under a reserved
prefix, in the same store. Tempting because it needs no new container at all — and **rejected**,
because it pollutes the event key space: the histogram's `GROUP BY` would count checkpoint rows as
events, `latestPerEntity` would see them, and the Events Query tab would show them to the user.

#### How it follows D4

| D4 | D7 |
|---|---|
| **(c)** — floor-map-created Plan B document *(decided)* | **(i)** — a second floor-map-created document |
| **(b)** — a `FloorMapEventStore` type *(the end state)* | **(ii)** — fold the checkpoint DBI into the store type |

**And (i) → (ii) is a migration, not a rewrite.** The key layout is identical in both; only the
container changes, so the move is a copy. Nothing written for (i) is wasted.

#### Recommendation

**Take (i), and consider (iii) as a stepping stone.** Building the fold path against an in-memory
checkpoint proves §11.3's read path and the §11.11 grace logic without committing to a persistent
container at all — and if the fold turns out to be fast enough on its own, (iii) may be sufficient for
longer than expected. Persist to (i) when restart cost or cluster behaviour makes it necessary.

### 11.14 Correction: option (b) is a supported extension point

**§11.12 recommended (c) on a false premise, and D4 was decided on that recommendation.** The premise
was that a `FloorMapEventStore` document type would need changes inside `stroom-planb-impl`. It does
not. The question *"can't the floor map open and own its own Plan B document?"* has a better answer
than (c): **it can define its own type of Plan B document**, and the machinery for that already
exists and is already used by a module outside Plan B.

#### What the tree actually provides

`PlanBDocument` is an **interface** in `stroom-core-shared`:

```java
public interface PlanBDocument extends Document {
    String getDescription();
    StateType getStateType();
    AbstractPlanBSettings getSettings();
}
```

and `AbstractPlanBDoc extends AbstractDoc implements PlanBDocument` sits beside it. Plan B's own
machinery — `PlanBDocCacheImpl`, `StateSearchProvider`, `ShardManager`, `ArchiveShardLocator` — is
written against the **interface**, not against `PlanBDoc`.

Which document types count as Plan B stores is a **Guice extension point**:

```java
Multibinder.newSetBinder(binder(), String.class, PlanBDocumentTypes.class)
```

consumed by `PlanBDocCacheImpl` as `@PlanBDocumentTypes Set<String>`.

**And there is a working precedent from outside Plan B.** `TracesDoc` lives in
`stroom-core-shared/stroom/pathways/shared/`, extends `AbstractPlanBDoc`, and `PathwaysModule` — in
`stroom-pathways-impl` — adds its type to that multibinder and registers a `DocumentActionHandler`
for it. Plan B then treats it as a store with no knowledge of Pathways.

#### What this means for D4

A `FloorMapEventStoreDoc` needs:

| | |
|---|---|
| A class extending `AbstractPlanBDoc` in `stroom-core-shared` | **Additive** — a new file, so effectively no merge exposure |
| Its type added to the `@PlanBDocumentTypes` multibinder from `FloorMapModule` | One line |
| A `DocumentActionHandler` and store, registered the same way | The Pathways shape |
| `stroom-floormap-impl` → `stroom-planb-impl` | The impl-on-impl dependency §11.12 called "against the grain" — **which `stroom-pathways-impl` already declares** |

So the impl-on-impl dependency is not a compromise invented here; it is what upstream itself does for
Pathways. **That was the objection (c) existed to avoid, and it does not hold.**

#### The revised recommendation

**Go to (b).** It delivers what D4 actually asked for — a store type that is configured *for* the
floor map and exposes only settings that suit it, rather than a general-purpose Plan B document a
user can edit into an unusable state. `stateType`, key schema, value schema and `condense` become
properties of the type rather than fields on a form.

**The validation work from (c) is not wasted and should still be done**, because a store can still be
wrong for reasons the type cannot prevent — a `maxStoreSize` too small for the retention, or an
expiry exceeding retention (AA7). It moves from "detect a document someone broke" to "validate the
settings we do expose", which is a smaller job with the same value.

#### And for D7

One `PlanBDocument` carries one `stateType` and one settings object, so **A and B remain two
documents** — but both are now floor-map-owned types, created and configured by the floor map, which
was the point. Option (ii), a checkpoint DBI inside the events store's own environment, still needs a
custom `Db` implementation and those live in `stroom-planb-impl`, so it remains the one shape that
does touch upstream code.

> **D7 is therefore unchanged: option (i), two documents** — but both of them ours by type, not just
> by convention.
