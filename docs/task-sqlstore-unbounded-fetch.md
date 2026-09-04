# SQL Temporal Store: unbounded result sets materialised in memory

**Component:** `stroom-sqlstore` — `UpdatableTemporalStoreDaoImpl`, `UpdatableSqlTemporalStore`;
`stroom-core-client` — `HistogramQueryHelper`, `FloorMapMapPresenter`, `FloorMapEditorPresenter`
**Origin:** F8 in `docs/floormap-remediation-plan.md`. Partly addressed by `9400f3359c`, which
stopped the search reading the `longtext` value column when no coprocessor wants it.
**Status:** open. Scoping this write-up changed the diagnosis — see *Correction* below.
**Severity:** medium. Heap and result-set size scale with total store history, with no cap
anywhere in the path.

---

## Correction to the earlier assessment

The remediation plan records F8 as "partly done — `9400f3359c` drops the `longtext` read", and I
repeated that as "the remaining exposure is row count, not payload".

**That is wrong for the default configuration.** `9400f3359c` reads the value column only when a
coprocessor asks for it, and the floor map timeline histogram runs **the user's own events query
text verbatim** (`FloorMapMapPresenter.buildEventsHistogramQuery`). The default events query is
[`FloorMapEventsQuery.defaultQuery()`](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapEventsQuery.java#L102):

```
from param('EventStore')
select EffectiveTime as "Effective Time",
  Key as "Entity ID",
  jq(Value, '.location') as "Location ID",
  jq(Value, '.type') as "Type",
  jq(Value, '.status') as "Status",
  jq(Value, '.message') as "Message"
```

Four `jq(Value, …)` calls put `Value` in the coprocessor field index, so
`UpdatableSqlTemporalStore.fieldIndexWants(coprocessors, "Value")` returns `true` and every row's
full `longtext` is read after all. The mitigation applies only to a query that never mentions
`Value` — and such a query cannot drive a floor map, because the location lives inside the value.

This is not inference: the map demonstrably resolves locations from `jq(Value, '.location')`, and
the only channel by which a value reaches the expression evaluator is the field-index-ordered
`Val[]` array. If `Value` were absent from the field index, locations would be null.

---

## Two problems, wrongly grouped

F8 named `search` and `fetchAll` together. They have different causes, different magnitudes, and
different fixes. `find()` is fine — it applies `.limit()`/`.offset()` from the page request on both
branches, and is not part of this.

### 1. `search()` with no time range — the real one

`UpdatableTemporalStoreDaoImpl.search()` (line 472) takes a `Consumer<TemporalEntry>`, which
implies streaming. Both branches end in `.fetch()` (lines 511 and 533), so jOOQ materialises the
entire `Result` in heap before the consumer sees row one. The `Consumer` signature is decoration.

The null-`queryTime` branch (the `else` at line 519) has **no time predicate at all** — it filters on
`doc_uuid` plus the caller's criteria and returns *every version of every key*. There is no `LIMIT`
in the DAO, and no cap in the sqlstore module: the generic search path's
`DataStoreSettings.maxResults` (default 1,000,000) applies to the coprocessor output, i.e. **after**
the DAO has already built the whole list.

**The floor map histogram takes that branch deliberately.**
`HistogramQueryHelper.run()` passes `timeRange = null` with a javadoc explaining why: a present
`TimeRange` makes `getQueryTime` lift a snapshot boundary and return one row per key, which is
right for the map and useless for a density histogram. So the histogram asks for all history by
design — and then filters to the visible range **client-side** in `HistogramDataModel`.

So on document open and on every timeline range change (`FloorMapMapPresenter.runHistogramQuery`,
called from three sites including `updateTimelineRange`), the server loads every event ever
recorded, values included, into heap — in order to extract timestamps and throw the rest away. The
client's `getRequestedRange` of 10,000 bounds only what is shipped to the browser, not what the
server builds.

One nuance that constrains the fixes below: `HistogramDataModel.parse` skips out-of-range rows
(deliberately, rather than clamping them to the edge bins), but it *also* tracks `minTime`/`maxTime`
across **every** row to feed the timeline's "Show All". So all-history is not purely waste today —
the extent is genuinely used. See fix B for why that costs nothing to preserve.

### 2. `fetchAll()` — milder than F8 implies, and not fixable by streaming

`fetchAll()` (line 243) joins against a `MAX(effective_time) GROUP BY key` subquery, so it returns
**one row per key**, not every version. Row count is bounded by key cardinality rather than by
history. F8's framing overstated this.

It is still uncapped and unpaginated, and it ships the full value of every key. The one caller is
`FloorMapEditorPresenter.fetchAllKeysForFactList()` (line 865), feeding the Fact List in "Show all"
mode — and that genuinely needs the values, since `FactObject.fromEntry(entry, schema)` parses them
against the value schema. For a facts store (locations on a floor plan) the cardinality is small
and this is fine; pointed at a high-cardinality store it will exhaust the browser or the server.

**`fetchLazy()` cannot help here.** The return type is `List<TemporalEntry>` from the DAO through
`UpdatableTemporalStore` and out of `SqlTemporalStoreResource` as a REST response body. Streaming
into a list you then return whole achieves nothing. The fix is a cap, pagination, or a keys-and-
locations projection — an API change, not a fetch-mode change.

---

## Scope

- **SQL Temporal Store only.** Plan B streams: `PlanBSearchHelper` iterates an LMDB cursor applying
  a row predicate, so it never materialises the result set. It has its own unrelated problem — it
  scans the whole store regardless of range (standing `TODO` at line 61) — which is a scan-cost
  issue, not a heap issue.
- Any StroomQL query against a SQL Temporal Store with no time term is affected, not just the floor
  map. The floor map histogram is simply the one caller that does it on a timer.

---

## Suggested fixes, in order of value

### A. Stop the histogram asking for values (client-side, low risk)

Have `buildEventsHistogramQuery` derive a two-column query from `FloorMapDoc.getEventsStoreRef()`
instead of reusing `getEventsQuery()`:

```
from "<events store name>"
select Key, EffectiveTime
```

`Value` then never enters the field index, `includeValue` is `false`, and the payload disappears.
This is the shape `buildEventsHistogramQuery` **already** builds as its fallback when no events
query is configured, so the code exists.

Three things to settle first:

- **It changes what the density bars mean.** Today they reflect the user's events query including
  any `where` filter; a derived query would show all events in the store. Arguably the fix (density
  should describe the store) or arguably a regression (density should match the map). Needs a
  decision, not a preference.
- **`select EffectiveTime` alone is not an option.** Observed against this instance: a
  single-column `select` throws `ArrayIndexOutOfBoundsException`. Two columns are needed, hence
  `Key, EffectiveTime`.
- **SQL-side bucketing via `group by` is not an option either.** Observed: `group by` over a large
  result set throws `Index 0 out of bounds`. Both of these are worth raising separately — they
  block the tidier fixes here.

This removes the payload but **not** the row count: the server still materialises one row per
version per key.

### B. Bound the row count (server-side, needs a decision)

The honest fix for the histogram is to not ship rows at all — aggregate to time buckets in SQL and
return counts. There is nowhere to put that today: the histogram goes through the generic
StroomQL/coprocessor path, so it would need a purpose-built endpoint
(`/histogram` on `SqlTemporalStoreResource`, taking a store, a range and a bucket count) and a
client that calls it instead of `QueryModel`. Larger than A, and the right answer.

Two things make this cheaper than it sounds. `SqlTemporalStoreResource.getTimeRange` **already
exists** as an endpoint (`getSqlTemporalStoreTimeRange`), backed by a single
`SELECT MIN(effective_time), MAX(effective_time)` — so the "Show All" extent that currently
justifies fetching all history is already obtainable for free, and a bucket endpoint would sit
directly beside it. And the bucketing arithmetic is already written client-side in
`HistogramDataModel.parse`; moving it into SQL is a translation, not a new algorithm.

Failing that, a configurable row cap in the DAO with a reported truncation is better than the
current silent unbounded fetch.

### C. `fetchLazy()`/`fetchStream()` on `search()` — do **not** do this first

It looks like the obvious fix and it trades one failure mode for a worse one. The consumer is
`coprocessors.accept(arr)`, which feeds the LMDB data store's **write queue**, and that queue
applies backpressure when full. Lazy fetching holds an open cursor, and therefore a pooled DB
connection, for as long as consumption takes — so a slow or blocked LMDB writer would pin a
connection instead of merely inflating heap. Connection-pool exhaustion under load is harder to
diagnose and affects the whole instance, where an OOM on one search is at least attributable.

If it is done anyway: the cursor must be closed on the exception path, and the interaction with
`taskContextFactory`'s termination needs checking, since a terminated task must not leak the
cursor.

---

## Verification

`TestUpdatableTemporalStoreDaoImplDB` with a row count comfortably above any fetch size:

- `search()` with a null time range sees every row, and the connection is released afterwards.
- `search()` with `includeValue = false` returns null values and, critically, does **not** read the
  `longtext` — assert on the generated SQL, not just the result, or `9400f3359c`'s guard can regress
  invisibly.
- `fetchAll()` returns exactly one row per key, the latest.
- `find()` still honours `limit`/`offset` on both branches.

For fix A, a client test that the histogram query text contains no reference to `Value` for a
document whose events query does.

## Not in scope

The `9400f3359c` `includeValue` guard is correct and should stay — it is just insufficient on its
own, because the caller that most needs it asks for the value for unrelated reasons.
