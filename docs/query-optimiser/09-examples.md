# 9. Worked examples

**Status:** Experimental. **The results below are derived by construction, not observed on a running instance.**
They follow from the behaviour documented in [05-optimisations.md](05-optimisations.md) and
[06-joins.md](06-joins.md), and are written so you can reproduce them — but treat them as expectations to check
against, not as recorded output.
**Audience:** analysts.
**Scope:** a small end-to-end setup, then one example per capability.
**Companion documents:** [14-testing.md](14-testing.md) turns these into an acceptance protocol.

---

## The setup

The Stroom building blocks assumed here: a **Feed** (a named channel data is sent to), the **stream/meta store**
(which records each batch received), an **Index** document (which defines searchable fields), an **indexing
pipeline** (which parses raw data and writes index documents), and an **extraction pipeline** (which, at search
time, pulls the full event back for columns not stored in the index).

The steps below are conceptual — consult the main Stroom documentation for the exact UI mechanics of creating each
document. What matters here is the shape of the data and the behaviour you should observe.

### A feed and some data

Create a feed `WEB_ACCESS` and send it some CSV web-access events:

```
time,user,streamId,eventId,status,message
2024-01-01T09:15:00.000Z,alice,1001,1,200,GET /home
2024-01-01T09:16:30.000Z,bob,1001,2,404,GET /missing
2024-02-01T10:00:00.000Z,alice,1002,1,500,POST /order failed to reach payment gateway
```

Each send becomes a stream recorded in the meta store against `WEB_ACCESS`, tagged with a create time.

### An index

Create an Index document `Events`:

| Field | Type | Indexed / queryable | Notes |
|---|---|---|---|
| `EventTime` | Date | yes | the index's **time field** — this is what drives shard partitioning, and what time-range pruning needs |
| `StreamId` | Id | yes | |
| `EventId` | Id | yes | |
| `User` | Text | yes | tag its `domainType` as `User.id` — see [07-domain-types.md](07-domain-types.md) |
| `Status` | Integer | yes | |
| `Message` | Text | **no** — extraction only | large free text, pulled back at search time |

Create an indexing pipeline that parses the CSV and writes each event to `Events`, and an extraction pipeline that
re-reads the source event at search time and emits the fields — including `Message`, which is not stored in the
index.

### A second source, for the join examples

Either a second index, or a Plan B / State store, named `Users` and keyed by user id. For the enrichment example
it must be a State store, because the fast path is only taken for one.

Set `stroom.query.optimiser.mode` to `ON` before running anything that needs the optimiser.

---

## 1. Time-range pruning from `where`

```
from "Events"
where EventTime > 2024-01-15T00:00:00.000Z
select EventTime, User, Status
```

**Result:**

| EventTime | User | Status |
|---|---|---|
| 2024-02-01T10:00:00.000Z | alice | 500 |

**The same rows either way.** The difference is how much was scanned. Under legacy, with no time-range picker set,
every shard of the index is searched and the bound is applied per document. Under the optimiser, a time range of
`from = 2024-01-15T00:00:00.000Z` is derived from the predicate and the January streams' shards are skipped
entirely.

**How to see the difference.** `EXPLAIN` the query with the mode `ON` and look for `query.timeRange` — or run it
in `SHADOW` and look for a divergence whose only difference is that field.

---

## 2. The `where`/`filter` fix

`Message` is extraction-only, so the index cannot evaluate a predicate on it:

```
from "Events"
where Status = 500 and Message = 'POST /order failed to reach payment gateway'
select EventTime, User, Message
```

**Under legacy:** the whole predicate goes to the index, the `Message` term compiles to a match-nothing clause,
and ANDed with `Status = 500` the query returns **zero rows**.

**Under the optimiser:**

| EventTime | User | Message |
|---|---|---|
| 2024-02-01T10:00:00.000Z | alice | POST /order failed to reach payment gateway |

`Status = 500` stays at the index; `Message = '…'` is routed to extraction-time filtering.

This is the one behaviour change your users will notice. See
[04-behaviour-changes.md](04-behaviour-changes.md#3-a-bare-where-mixing-eligible-and-ineligible-terms).

---

## 3. A legacy parser defect

```
from "Events" where (not Status = 200) select EventTime, User, Status
```

**Under legacy:** rejected — `Expected condition token`. Adding a space after the bracket makes it work.

**Under the optimiser:**

| EventTime | User | Status |
|---|---|---|
| 2024-01-01T09:16:30.000Z | bob | 404 |
| 2024-02-01T10:00:00.000Z | alice | 500 |

Same for `where StreamId is null` and `where Message is not null`, both of which legacy rejects outright.

---

## 4. The full clause set

Everything StroomQL already had, compiled by the new engine:

```
from "Events"
where EventTime between 2024-01-01T00:00:00.000Z and 2024-03-01T00:00:00.000Z
eval statusClass = if(Status >= 500, 'error', 'ok')
group by User, statusClass
select User, statusClass, count() as hits
having hits > 0
sort by hits desc
limit 100
```

| User | statusClass | hits |
|---|---|---|
| alice | ok | 1 |
| alice | error | 1 |
| bob | ok | 1 |

`between` also contributes a derived time range covering both bounds. `window` and `show` are supported by the
compiler too — though `show` cannot be used in a join query or in a query you want to `EXPLAIN`
([10-limits.md](10-limits.md#language-limits)).

---

## 5. A two-source join

```
from "Events" as a
join "Users" as b on a.User = b.Id
select a.EventTime, a.Status, b.Name
```

**Result** — `INNER` is the default, so only users present in both sides appear:

| a.EventTime | a.Status | b.Name |
|---|---|---|
| 2024-01-01T09:15:00.000Z | 200 | Alice Smith |
| 2024-02-01T10:00:00.000Z | 500 | Alice Smith |

**What happened underneath:**

1. Each side was compiled into its own single-source sub-query, selecting only the columns needed — `User`,
   `EventTime` and `Status` from `Events`; `Id` and `Name` from `Users` — rather than `select *`.
2. Both sides ran to completion through their own providers.
3. The smaller side was read into a lookup structure and the larger streamed through it.
4. Each combined row was fed into the outer result pipeline, which applied `select` exactly as for a single-source
   query.

Switch to `left join` and unmatched left rows survive with `b.Name` null:

| a.EventTime | a.Status | b.Name |
|---|---|---|
| 2024-01-01T09:15:00.000Z | 200 | Alice Smith |
| 2024-01-01T09:16:30.000Z | 404 | *(null)* |
| 2024-02-01T10:00:00.000Z | 500 | Alice Smith |

---

## 6. A join with a `where` clause

```
from "Events" as a
join "Users" as b on a.User = b.Id
where a.Status >= 500
select a.EventTime, a.Status, b.Name
```

| a.EventTime | a.Status | b.Name |
|---|---|---|
| 2024-02-01T10:00:00.000Z | 500 | Alice Smith |

`a.Status >= 500` references only the left side and `Status` is index-eligible, so it is **pushed into the left
side's own sub-query** — that side filters before the join runs rather than after. The comparison is numeric, not
textual.

Change it to something the optimiser cannot push — a predicate spanning both sides, a nested `OR`, or a field the
index cannot evaluate — and it becomes the residual, evaluated on each combined row. Same answer, more work.

For a `left join`, a predicate on `b` is **never** pushed, whatever its eligibility. It always ends up in the
residual, because pre-filtering the null-supplying side would silently turn the query into an inner join.

---

## 7. An enrichment join

With `Users` as a Plan B / State store keyed by user id:

```
from "Events" as a
join "Users" as b on a.User = b.Key
select a.EventTime, a.Status, b.Value
```

| a.EventTime | a.Status | b.Value |
|---|---|---|
| 2024-01-01T09:15:00.000Z | 200 | Alice Smith |
| 2024-02-01T10:00:00.000Z | 500 | Alice Smith |

**Note the column names.** The lookup side contributes exactly two synthetic columns, `Key` and `Value` — not the
store's own field names. `select b.Name` would find nothing.

The store is **never scanned**. Each event row does one point lookup. This holds however large the store is, and
however large the event stream is — the probe side streams and is not row-capped.

`b.Key` in the `on` clause is what triggers the fast path: the side's datasource type must be `PlanB` and the
equi-key field on that side must be exactly `Key`, with exactly one equi-key in the join.

---

## 8. A Cypher sub-query as a join side

```
from "AuthEvents" as e
inner join ( from "CorpGraph" match (u:User)-[:MEMBER_OF]->(g:Group)
             return u.id as userId, g.name as groupName ) as ident
  on e.user = ident.userId
select e.time, e.user, ident.groupName
```

| e.time | e.user | ident.groupName |
|---|---|---|
| 2024-01-01T09:15:00.000Z | alice | Payments |
| 2024-01-01T09:15:00.000Z | alice | Engineering |

The graph side's `RETURN ... AS` list is its schema — `userId` and `groupName` — and that is what the join key and
the outer `select` bind against. Every returned item **must** carry an `AS` alias; a bare `return u.id` is
rejected.

Nothing is pushed into the graph side. A predicate on `ident` always ends up in the residual, so narrow the
traversal in the Cypher itself if it is large.

---

## 9. Reading an `EXPLAIN`

```
POST /api/query/v1/explainQuery
Authorization: Bearer <api-key>

from "Events" where EventTime > now() - 1d select EventTime, User
```

```json
{ "description": "Project", "children": [
    { "description": "Filter", "children": [
        { "description": "Scan Events as Events (FullScan)",
          "estimatedRows": 0, "estimatedDurationMs": 0, "confidence": 0.0,
          "notes": ["no cost signal available for 'Events'"] } ] } ] }
```

`confidence: 0.0` and that note are the **expected** result for an index-backed source today — the index cost
adapter is a stub. Read the tree for its shape, not its numbers. See
[08-explain-and-cost.md](08-explain-and-cost.md#how-good-are-the-numbers).

Explaining the join from example 5 gives a `Join (…)` node over two scan children. With no cost signals the
planner has no distinct-key counts, so the cardinality is the pessimistic cross-product and the node carries the
note saying so — and the algorithm it names is **not** what executes.

---

## Things to try that should fail

Useful for confirming the rejections are working:

| Query | Expected |
|---|---|
| `from "Events" as a join "Users" as b on a.User = b.Id select *` | rejected — list fields explicitly |
| `from "Events" as a join "Users" as b on a.User = b.Id join "X" as c on b.Id = c.Id select a.User` | rejected — single join only |
| `from "Events" as a join "Users" as b on User = b.Id select a.User` | rejected — qualify with a source alias |
| `from "Events" as a right join "Users" as b on a.User = b.Id select a.User` | syntax error |
| `from "Events" as a join "Users" as b on a.Nonexistent = b.Id select a.User` | rejected — unknown field on `a` |
| A join whose keys carry incompatible domain types | rejected — see [07-domain-types.md](07-domain-types.md) |
