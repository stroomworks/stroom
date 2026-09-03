# SQL Temporal Store: XSLT lookups issue one SQL query per event

**Type:** Performance
**Component:** SQL Temporal Store pipeline lookup (`stroom-sqlstore`)
**Priority:** Medium — a throughput ceiling on the ingest path, not a correctness bug
**Risk:** Medium (see *Risks* — caching temporal data is easy to get subtly wrong)
**Origin:** Floor Map pre-production code review, 21 Aug 2026. Finding **F5** in
`docs/floormap-remediation-plan.md`, where the store-resolution half was taken and the value cache
deliberately deferred.

> **Not blocked on a decision.** The remediation plan originally proposed a
> `(map, key, time-bucket)` cache, and agreeing that bucket is what stalled this. That requirement
> was an artefact of the proposal rather than of the problem — see *Why the obvious approaches do
> not work* and *Proposed approach*. This is deferred on capacity alone; it can be picked up
> without anything being settled first.

> Line references are as of commit `8ee09c4952`.

---

## Problem

`SqlStoreLookupImpl.lookup()` is reached from `ReferenceData.getValue`, the same hot path as
LMDB-backed reference data. A translation calling `lookup()` once per event over a million-event
stream calls it a million times.

It still issues **one SQL round trip per call**, and each one runs the temporal resolution query —
a `MAX(effective_time)` subquery grouped by key, joined back to the table to fetch the matching
row:

```sql
select t1.map_name, t1.key_, t1.effective_time, t1.value_
from updatable_temporal_store t1
join (select doc_uuid, key_, max(effective_time) as max_time
      from updatable_temporal_store
      where doc_uuid = ? and key_ = ? and effective_time <= ?
      group by doc_uuid, key_) sub
  on t1.doc_uuid = sub.doc_uuid and t1.key_ = sub.key_
 and t1.effective_time = sub.max_time
```

Nothing amortises this. The classic reference-data path absorbs the equivalent cost through the
off-heap store and the effective-stream cache; this path has neither.

Repeated lookups of the **same key** are the dominant pattern for the stores this feature uses — a
location store is asked "where is entity X" over and over as events for X stream past — so the hit
rate available is high and entirely unexploited.

## Already done (do **not** redo — this is the cheap tier from the same finding)

Commit `8cc85ee889` took the two safe parts:

- **Store resolution is memoised per pipeline instance.** `SqlStoreLookupImpl` holds
  `Map<String, DocRef> resolvedStores` and is `@PipelineScoped`. Resolving a name lists the
  document store and permission-checks every row; that ran once per `lookup()` and now runs once
  per pipeline instance. A million-event stream went from a million document-store listings to one.
- **The XML parser is pooled.** `SqlStoreValueProxy.resolveValue` takes a reader from
  `XMLReaderPool.getDefault()` instead of constructing a `SAXParser` per value.

Two invariants were established there that this work must preserve:

- The memoised map caches **identity, not authorisation**. `UpdatableSqlTemporalStore.find(DocRef,
  criteria)` calls `checkPermission(storeDocRef, VIEW)` on **every** lookup, deliberately, because
  a cached `DocRef` may outlive the grant that produced it. A value cache must not become a route
  around that check.
- Nothing that depends on the lookup's effective time was cached, precisely because it needs a
  temporal cache key to be correct. That is what this issue is for.

## Why the obvious approaches do not work

The value returned depends on the **effective time**. `lookup()` builds
`EffectiveTime EQUALS eventTimeMs`, which `UpdatableTemporalStoreDaoImpl.getQueryTime` lifts out as
a snapshot time, so the query means *"the latest entry at or before this event's time"*. Two events
a millisecond apart can legitimately resolve to different entries.

| Cache key | Correct? | Useful? |
|---|---|---|
| `(key)` | **No** — returns one event's answer to another event's question | — |
| `(key, exactEventTimeMs)` | Yes | **No** — hit rate approaches zero when events have distinct timestamps, which is the case that matters |
| `(key, eventTimeMs / bucket)` | **Approximately** — returns values up to one bucket stale | Yes, but the bucket has to match the data's real granularity, and guessing coarse is silently wrong |

The third is what the remediation plan originally proposed, and it is why this stalled: it needs a
granularity nobody can safely commit to on behalf of every deployment.

## Proposed approach — cache the validity interval

Cache `(docUuid, key) → { value, validFrom, validTo }` and treat it as a hit when
`validFrom <= eventTime < validTo`.

This is **exact**. There is no staleness and no granularity to agree, because the interval is
derived from the data rather than imposed on it. The hit rate is high for slowly-changing data,
which is what location and reference stores are: one query per *change*, not one per *event*.

Getting the interval needs the next entry's effective time as well as the current one.
`UpdatableTemporalStore.TIME_FIELD` is already mapped in the DAO's expression mapper
(`UpdatableTemporalStoreDaoImpl:86`), so it is expressible with the existing API, but the clean
form is a purpose-built DAO method returning both bounds in one round trip:

- `validFrom` = `MAX(effective_time)` where `effective_time <= T` — the entry already being fetched
- `validTo` = `MIN(effective_time)` where `effective_time > T`, or `Long.MAX_VALUE` if none

Two scalar subqueries, roughly the cost of the query already being run, and every subsequent lookup
falling in that interval costs nothing.

**Pair it with negative caching.** An interval for "this key has no entry at all" is exact and
cheap. Translations routinely look up keys the store has never held, and those currently miss on
every single event.

**Scope and lifetime.** Per-pipeline-instance (`@PipelineScoped`), bounded in size, same as the
existing `resolvedStores` map. A long-lived shared cache would need cross-pipeline invalidation and
is not worth it.

## Acceptance criteria

- A translation doing one `lookup()` per event against a store whose value for that key does not
  change issues **one** query, not one per event.
- Two lookups at different effective times that straddle an entry boundary return **different**
  values.
- A lookup whose event time falls inside a cached interval does not issue a query.
- A key with no entries is not re-queried on every event.
- Permission is still checked on every lookup, cache hit or miss.
- A write followed by a read of the same key in the same pipeline run sees the write.

## Risks

**Correctness of the interval is the whole thing.** An interval computed with an off-by-one on the
upper bound serves a stale value at exactly one instant — the boundary — which is the hardest
possible case to notice in production and the easiest to test deliberately. The bound is
half-open (`validFrom <= T < validTo`) because the query is `effective_time <= T`; write the
boundary tests first.

**Read-after-write within a run.** A pipeline that both writes and reads the same store could serve
its own stale interval. Invalidate the key on write. This is called out in the remediation plan and
needs a test rather than an argument.

**Permission checks must not be skipped.** The current code re-checks on every `find` for a stated
reason. A cache that serves a value without re-checking would turn a memoisation into a
privilege-escalation path. Check on hit as well as miss; the check is not the cost being addressed
here.

**Memory.** Values are `longtext`. A bounded cache with an eviction policy, not an unbounded map —
`resolvedStores` gets away with being unbounded because it holds one small entry per map name.

## Verification

- `TestSqlStoreLookup` — extend for the cache behaviours above.
- `TestUpdatableTemporalStoreDaoImplDB` — a real-MySQL test for the new interval query, including
  a key whose entry is the last one (unbounded `validTo`) and a key with no entries.
- Boundary tests at `validFrom`, `validFrom - 1`, `validTo - 1` and `validTo`.
- A test asserting the query count, not just the returned values — the point of the change is the
  count, and a cache that returns correct values while still querying every time would pass every
  value-based test.

## References

- `SqlStoreLookupImpl` — `stroom-sqlstore/stroom-sqlstore-impl/src/main/java/stroom/sqlstore/impl/pipeline/SqlStoreLookupImpl.java`
- `UpdatableSqlTemporalStore.find(DocRef, ExpressionCriteria)` — the per-lookup permission check
- `UpdatableTemporalStoreDaoImpl.find` / `getQueryTime` — the snapshot query and how the time term
  is lifted out of the expression
- Finding **F5** in `docs/floormap-remediation-plan.md`
- `docs/task-floormap-incremental-canvas-render.md` — the other deferred tier from this review,
  same pattern
