# SQL Temporal Store: a search with no time term materialises the whole store in heap

**Component:** `stroom-sqlstore` — `UpdatableTemporalStoreDaoImpl.search` and `.fetchAll`
**Severity:** medium, and **latent rather than live**: the volumes the designed usage puts through
this path are small, and nothing caps it if that stops being true. No wrong answers; it is a
resource ceiling with no ceiling.
**Status:** open.

---

## The defect

`UpdatableTemporalStoreDaoImpl.search` (line 472) takes a `Consumer<TemporalEntry>`, which implies
streaming. Both of its branches end in `.fetch()` (lines 511 and 533), so jOOQ materialises the
**entire** result as a `Result` in heap before the consumer sees its first row. The `Consumer`
signature is decoration.

The branch that matters is the one taken when the criteria carry **no time term** — the `else` at
line 519. It filters on `doc_uuid` plus whatever the caller asked for and returns *every version of
every key*.

**Nothing bounds it.** There is no `LIMIT` in the DAO. The generic search path's
`DataStoreSettings.maxResults` (default 1,000,000) applies to the coprocessor **output**, i.e. after
the DAO has already built the whole list. A client-side `OffsetRange` bounds what is shipped to the
browser, not what the server builds.

`find()` is fine — it applies `.limit()`/`.offset()` from the page request on both branches — and is
not part of this.

## Who takes that branch

**The Floor Map's facts read, on a timer.** It fetches the store's whole history once and derives
each timeline position's snapshot client-side, so that playback costs no queries; the read passes
`timeRange = null` deliberately, which is what puts it on this branch. It re-runs every 60 seconds
while the Map is visible, and immediately whenever the Map becomes visible.

It also reads the `longtext` value column, necessarily. The value column is read only when a
coprocessor asks for it —

```java
final boolean includeValue = fieldIndexWants(
        coprocessors, UpdatableTemporalStore.VALUE_FIELD.getFldName());
```

— and the facts query is generated from the document's value schema, wrapping every mapped path in
`jq(Value, …)` or `xpath(Value, …)`. So `Value` is in the field index and every row's full payload is
read. That is not avoidable for this caller: the fact's position, type and image all live inside the
value.

Beyond that: **any StroomQL query against a SQL Temporal Store with no time term**, whether written
in the query bar or embedded in a document.

## Why this is latent rather than live

A floor map's facts are the static furniture of a floor plan — desks, areas, backgrounds. They are
expected to change rarely: in the deployment this was sized against, a couple of rows a week. So
full history is roughly *keys plus a trickle*, hundreds of rows, and materialising it costs nothing
worth measuring. The Floor Map warns once if it sees more than 20,000 rows, on the grounds that such
a store is being used as something other than a facts store.

So the problem is not what happens today. It is that:

- the DAO offers **no cap at all**, so the failure mode when volume grows is an OOM rather than a
  truncated result and a warning;
- a SQL Temporal Store is a general-purpose document, and nothing stops one holding high-volume
  temporal data. A user query with no time term against such a store loads all of it;
- the guard that exists is the *caller's*, in the client, above a server that has none.

**Note this is not about the timeline density histogram.** That runs the Floor Map's events query,
and the events store is restricted to Plan B — which streams, iterating an LMDB cursor and applying
a row predicate, so it never materialises the result set. The histogram only reaches a SQL Temporal
Store in the fallback where a document has no events query at all, which the creation dialog always
writes.

## `fetchAll` is a different, milder problem

`fetchAll` (line 243) joins against a `MAX(effective_time) GROUP BY key` subquery, so it returns
**one row per key** rather than every version. Its row count is bounded by key cardinality, not by
history.

It is still uncapped and unpaginated, and it ships the full value of every key. Its one caller feeds
the Floor Map editor's fact list in "show all" mode and genuinely needs those values.

**`fetchLazy()` cannot help `fetchAll`.** Its return type is `List<TemporalEntry>` from the DAO,
through `UpdatableTemporalStore`, and out of `SqlTemporalStoreResource` as a REST response body.
Streaming into a list you then return whole achieves nothing. Its fix is a cap, pagination, or a
narrower projection — an API change, not a fetch-mode change.

## Suggested fix

**Cap the fetch and report the truncation.** The DAO should refuse to build an unbounded list:
apply a configurable `LIMIT`, and give the caller a way to know it bound so it can say so rather
than quietly showing a subset. `TableResult.getTotalResults()` is already populated independently of
the rows returned, so the generic search path can detect it; the direct `fetchAll` path would need
its own signal.

That converts the failure mode from an OOM that takes the node down into a partial answer that names
itself — which is the whole of what is wrong here.

### What not to reach for first

**`fetchLazy()`/`fetchStream()` on `search`.** It looks like the obvious fix and it trades one
failure mode for a worse one. The consumer is `coprocessors.accept(arr)`, which feeds the LMDB data
store's **write queue**, and that queue applies backpressure when full. Lazy fetching holds an open
cursor, and therefore a pooled DB connection, for as long as consumption takes — so a slow or
blocked writer would pin a connection rather than merely inflating heap. Connection-pool exhaustion
under load is harder to diagnose and affects the whole instance, where an OOM on one search is at
least attributable.

If it is done anyway: the cursor must be closed on the exception path, and the interaction with task
termination needs checking, since a terminated task must not leak it.

## Verification

`TestUpdatableTemporalStoreDaoImplDB`, with a row count comfortably above any cap:

- `search()` with no time term returns at most the cap, and reports that it bound.
- The connection is released afterwards, on both the normal and the exception path.
- `search()` with `includeValue = false` returns null values and, critically, does **not** read the
  `longtext` — assert on the generated SQL, not just the result, or that guard can regress
  invisibly.
- `fetchAll()` returns exactly one row per key, the latest.
- `find()` still honours `limit`/`offset` on both branches.
