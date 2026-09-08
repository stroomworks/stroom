# SQL Temporal Store: a search with no time range loads the whole store into heap

**Component:** `stroom-sqlstore` — `UpdatableTemporalStoreDaoImpl.search` and `.fetchAll`,
`UpdatableSqlTemporalStore.search`
**Severity:** medium. Heap use and result-set size scale with the store's total history, with no cap
anywhere in the path. No wrong answers; it is a resource ceiling.
**Status:** open.

---

## The defect

`UpdatableTemporalStoreDaoImpl.search` (line 472) takes a `Consumer<TemporalEntry>`, which implies
streaming. Both of its branches end in `.fetch()` (lines 511 and 533), so jOOQ materialises the
**entire** result as a `Result` in heap before the consumer sees its first row. The `Consumer`
signature is decoration.

The branch that matters is the one taken when the criteria carry **no time term** (the `else` at
line 519). It filters on `doc_uuid` plus whatever the caller asked for and returns *every version of
every key*. There is no `LIMIT` in the DAO, and no cap in the module: the generic search path's
`DataStoreSettings.maxResults` (default 1,000,000) applies to the coprocessor **output**, i.e. after
the DAO has already built the whole list.

`find()` is fine — it applies `.limit()`/`.offset()` from the page request on both branches — and is
not part of this.

## Who takes that branch, and how often

The Floor Map's **timeline density histogram**. `HistogramQueryHelper.run` passes
`timeRange = null` deliberately, and its javadoc explains why: with a `TimeRange` present the DAO
lifts a snapshot boundary out of it and returns one row per key, which is right for a point-in-time
map and useless for a histogram that needs every entry across a window. So it asks for all history
by design, then filters to the visible range **client-side**.

That query re-runs on document open and on every timeline range change. So the server loads every
event ever recorded, into heap, in order to extract timestamps and discard the rest.

Two nuances that constrain the fixes below:

- **The client's row cap does not help.** `HistogramQueryHelper` requests `OffsetRange(0, 10000)`,
  which bounds what is shipped to the browser, not what the server builds.
- **All-history is not purely waste today.** `HistogramDataModel.parse` skips out-of-range rows
  (deliberately, rather than clamping them to the edge bins) but *also* tracks `minTime`/`maxTime`
  across **every** row, to feed the timeline's "Show All". So the extent is genuinely used.

## The payload, and why the existing guard is not enough

`UpdatableSqlTemporalStore.search` reads the `longtext` value column only when a coprocessor asks
for it:

```java
final boolean includeValue = fieldIndexWants(
        coprocessors, UpdatableTemporalStore.VALUE_FIELD.getFldName());
```

That is correct and worth keeping. It is also **insufficient for the caller that most needs it**,
because the histogram runs the Floor Map's own events query text verbatim, and the default events
query pulls the event's properties out of the JSON value:

```
jq(Value, '.location') as "Location",
jq(Value, '.locationRef') as "Location Ref",
jq(Value, '.type') as "Type",
...
```

Four references to `Value` put it in the coprocessor field index, so `includeValue` is `true` and
every row's full `longtext` is read after all. A query that avoids `Value` cannot drive a floor map
at all, because the position lives inside the value.

So the exposure is row count **and** payload, in the default configuration.

## `fetchAll` is a different, milder problem

`fetchAll` (line 243) joins against a `MAX(effective_time) GROUP BY key` subquery, so it returns
**one row per key** rather than every version. Its row count is bounded by key cardinality, not by
history.

It is still uncapped and unpaginated, and it ships the full value of every key. Its one caller feeds
the Floor Map editor's fact list in "show all" mode, and genuinely needs those values. For a facts
store — objects on a floor plan — cardinality is small and this is fine; pointed at a
high-cardinality store it will exhaust the browser or the server.

**`fetchLazy()` cannot help `fetchAll`.** Its return type is `List<TemporalEntry>` from the DAO,
through `UpdatableTemporalStore`, and out of `SqlTemporalStoreResource` as a REST response body.
Streaming into a list you then return whole achieves nothing. Its fix is a cap, pagination, or a
narrower projection — an API change, not a fetch-mode change.

## Scope

- **SQL Temporal Store only.** The Plan B store streams: its search iterates an LMDB cursor
  applying a row predicate, so it never materialises the result set. (It has an unrelated problem —
  it scans the whole store regardless of range — which is a scan-cost issue, not a heap one.)
- Any StroomQL query against a SQL Temporal Store with no time term is affected, not only the Floor
  Map. The histogram is simply the one caller that does it repeatedly and unattended.

## Suggested fixes, in order of value

### A. Stop the histogram asking for values (client-side, low risk)

Have the Floor Map derive a two-column histogram query from its events store rather than reusing the
full events query:

```
from "<events store name>"
select Key, EffectiveTime
```

`Value` then never enters the field index, `includeValue` is `false`, and the payload disappears.
The Floor Map already builds a query of exactly this shape as its fallback when no events query is
configured, so the code exists.

**This changes what the density bars mean, and that needs a decision, not a preference.** Today they
reflect the user's events query including any `where` filter; a derived query would show everything
in the store. Arguably the fix — density should describe the store — or arguably a regression —
density should match the map.

It removes the payload but **not** the row count: the server still materialises one row per version
per key.

### B. Bound the row count (server-side, the right answer)

Do not ship rows for a histogram at all — aggregate to time buckets in SQL and return counts. There
is nowhere to put that today, because the histogram goes through the generic StroomQL/coprocessor
path, so it needs a purpose-built endpoint (`/histogram` on `SqlTemporalStoreResource`, taking a
store, a range and a bucket count) and a client that calls it instead of the generic query model.

Cheaper than it sounds, for two reasons. `SqlTemporalStoreResource.getTimeRange` **already exists**,
backed by a single `SELECT MIN(effective_time), MAX(effective_time)` — so the "Show All" extent that
currently justifies fetching all history is already obtainable for free, and a bucket endpoint would
sit beside it. And the bucketing arithmetic is already written client-side in
`HistogramDataModel.parse`; moving it into SQL is a translation, not a new algorithm.

Failing that, a configurable row cap in the DAO with a reported truncation is better than the
current silent unbounded fetch.

### C. `fetchLazy()`/`fetchStream()` on `search` — do **not** reach for this first

It looks like the obvious fix and it trades one failure mode for a worse one. The consumer is
`coprocessors.accept(arr)`, which feeds the LMDB data store's **write queue**, and that queue
applies backpressure when full. Lazy fetching holds an open cursor, and therefore a pooled DB
connection, for as long as consumption takes — so a slow or blocked writer would pin a connection
rather than merely inflating heap. Connection-pool exhaustion under load is harder to diagnose and
affects the whole instance, where an OOM on one search is at least attributable.

If it is done anyway: the cursor must be closed on the exception path, and the interaction with task
termination needs checking, since a terminated task must not leak it.

## Verification

`TestUpdatableTemporalStoreDaoImplDB`, with a row count comfortably above any fetch size:

- `search()` with no time term sees every row, and the connection is released afterwards.
- `search()` with `includeValue = false` returns null values and, critically, does **not** read the
  `longtext` — assert on the generated SQL, not just the result, or that guard can regress
  invisibly.
- `fetchAll()` returns exactly one row per key, the latest.
- `find()` still honours `limit`/`offset` on both branches.

For fix A, a client test that the generated histogram query text contains no reference to `Value`
for a document whose events query does.
