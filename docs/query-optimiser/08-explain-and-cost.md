# 8. EXPLAIN and the cost model

**Status:** Experimental. The plan tree is useful today; the numbers are not yet. See
[How good are the numbers](#how-good-are-the-numbers).
**Audience:** analysts and evaluators.
**Scope:** the `explainQuery` endpoint, the plan tree it returns, the cost model behind it, and the query editor's
pre-run warning. Canonical for the cost model's constants and confidence semantics.
**Companion documents:** [05-optimisations.md](05-optimisations.md) for what the plan reflects,
[12-future-work.md](12-future-work.md) for what would make the numbers real.

---

## The endpoint

```
POST /api/query/v1/explainQuery
Content-Type: application/json
Authorization: Bearer <api-key>

from "Events" where EventTime > now() - 1d select StreamId, EventTime
```

**The body is the raw StroomQL text, sent verbatim** — not a JSON-quoted string. Wrapping it in quotes makes the
parser treat the quotes as part of the query and fail with a missing-`from` error.

Because this is a state-changing `POST`, a session cookie alone is rejected with **403** by the CSRF filter — so
the Swagger "Try it out" button fails. Authenticate with an API key or bearer token, which is exempt.

Nothing is executed. A malformed query returns **400** with the syntax error, not 500; so does an empty body.

### It depends on the mode flag

`EXPLAIN` is served by whichever compiler the dispatcher is currently using, and `SHADOW` counts as `OFF` here —
there is nothing to shadow-diff for an already-advisory call.

| Mode | What you get |
|---|---|
| `OFF`, `SHADOW` | A single node: `Scan <datasource> (legacy engine - no cost estimate available)`. No children, no numbers |
| `ON` | The full plan tree described below |

An earlier engineering note stated that this endpoint's behaviour does not depend on the mode. That was wrong.

---

## The plan tree

Each node has a description, optional children, and — on nodes that carry one — an estimated row count, an
estimated duration in milliseconds, a confidence between 0 and 1, and free-text notes.

```json
{ "description": "Project", "children": [
    { "description": "Filter", "children": [
        { "description": "Scan Events as Events (FullScan)",
          "estimatedRows": 500,
          "estimatedDurationMs": 5,
          "confidence": 0.5,
          "notes": ["full scan; no per-row throughput signal for the stream store - using a placeholder 1000.0 rows/ms"] } ] } ] }
```

The tree reflects the plan **after** binding and rewriting, so it shows you what the optimiser actually decided:

| Node | Reads as |
|---|---|
| `Scan <source> as <alias> (<AccessPath>)` | A leaf. The access path is `FullScan`, `IndexScan` or `StateLookup`. Carries the cost estimate |
| `Filter` | A predicate. A filter directly above a scan propagates the scan's cost estimate upward, so a parent join still sees a costed side |
| `Join (<algorithm>, build side: <side>)` | Carries a cardinality estimate and a note about where it came from |
| `Join` with a "nested join" note | At least one side is not a direct scan, so there is no compile-time estimate to combine |
| `Project`, `Group by …`, `Having`, `Window by …`, `Sort`, `Limit …` | Wrappers, no estimate of their own |
| `GraphJoinSource <alias> (Cypher sub-query)` | A graph join side. No compile-time estimate — the graph engine's statistics are not wired into the cost model, so a join with one always takes the "nested join" branch |

The explainer can also render graph traversal nodes (`NodeScan`, `Expand`, `VarLengthExpand`), but this endpoint
parses its input as StroomQL, so those appear only for a Cypher plan reaching the explainer by another route —
not from anything you can post here.

### What the plan does not tell you

- **The named join algorithm is not what runs.** Execution chooses structurally, from the shape of the query —
  see [06-joins.md](06-joins.md#execution-two-strategies). The cost model is consulted only here. Correctness is
  unaffected; only the name is advisory.
- **The named build side is likewise advisory.** Execution picks the smaller side for an `INNER` join and always
  the right side for a `LEFT` join.
- **Nothing is executed**, so nothing here reflects real cardinality, real shard counts, or real time.

---

## The cost model

### How a scan is costed

Three ports are tried in order, and the first that answers wins:

1. **Meta-store row count** — for a datasource whose name is a known **feed**, the meta service's selection
   summary gives an item count, narrowed by the time range if one was derived. Yields a `FullScan`.
2. **Index shard statistics** — document count, byte size and commit throughput over the pruned partition range.
   Yields an `IndexScan`.
3. **State-store key count** — yields a `StateLookup`, with a fixed 1 ms duration.

If none answers, the result is a `FullScan` with zero rows, zero duration, **confidence 0.0**, and the note
`no cost signal available for '<name>'`.

Row counts are scaled by a selectivity multiplier per applicable predicate term:

| Category | Multiplier | Conditions |
|---|---|---|
| Equality | **0.01** | `=`, case-sensitive `=`, `is null`, `is not null`, doc-ref and user-ref matches |
| Range | **0.1** | `between`, `>`, `>=`, `<`, `<=`, `in`, `in dictionary`, `in folder`, `starts with`, `ends with` |
| Unindexed | **1.0** | everything else — `!=` excludes one value from a potentially huge domain |

Multipliers compose multiplicatively across terms. Duration is rows divided by throughput; where no throughput
signal exists, a placeholder **1,000 rows/ms** is used, and the note says so.

### How confidence is computed

Confidence is a plain product of "did we have to guess?" factors:

- No predicate terms to apply selectivity to → 1.0; any terms → **0.5**, because the selectivity constants are
  guesses.
- A real throughput signal → 1.0; the placeholder → **0.5**.
- No cost signal at all → **0.0**.

So the best you will see for a filtered index scan with real statistics is 0.5, and the common case today is 0.0.

### How a join is costed

`|A ⋈ B| ≈ |A| × |B| / max(distinct(A.key), distinct(B.key))`

Distinct-key counts are supplied by the caller, not estimated. Today the only side that can supply one is a
`StateLookup`, whose key is unique by construction, so its row count *is* its distinct-key count. That makes the
common enrichment join estimate approximately the probe side's row count rather than a full cross-product.

With no distinct-key count on either side, the formula degrades to the full cross-product — deliberately
pessimistic, never optimistic — and the node says so:

> distinct-key counts unknown - cardinality is the pessimistic upper bound (full cross-product)

The multiplication saturates at the maximum long value rather than overflowing to a negative number.

Algorithm selection, for the plan tree only: `BROADCAST_LOOKUP` when either side is a state lookup (preferring the
right); otherwise `HASH_JOIN` with the smaller side as the build side; `NESTED_LOOP` only when neither side has a
usable estimate to compare.

### Cluster parallelism is not modelled

Every duration assumes a single node. There is no port for cluster size, and inventing one with no live signal
behind it would produce a number that looks calibrated and is not.

---

## How good are the numbers

Not good yet, and the model is honest about it rather than quietly plausible.

| Input | State |
|---|---|
| Meta-store row counts | **Real** — but only for a datasource whose name matches a **feed** name |
| Index shard statistics | **Stub.** Always answers "no signal" |
| State-store key counts | **Stub.** Always answers "no signal" |
| Selectivity constants | Documented guesses. Nothing has ever tuned them against a real outcome |
| Throughput constant | A documented guess |
| Actual-vs-estimated feedback | Does not exist. `SHADOW` logs the estimate; nothing records what the query really took |

The practical effect: for a query against an Index or a state store — which is most queries — all three ports
decline, and you get a `FullScan` node with zero rows, zero duration, confidence 0.0 and the "no cost signal"
note. The plan *shape* is still accurate and worth reading; the numbers are placeholders.

The two stubs are structural rather than an oversight. A real adapter needs to live inside the index and Plan B
modules, because putting it in the query modules would close a dependency cycle. See
[12-future-work.md](12-future-work.md#real-cost-adapters).

**Use `EXPLAIN` today to answer "what did the optimiser decide?", not "how long will this take?"**

---

## The pre-run warning

The query editor calls `explainQuery` when you run a query, and pops a warning if the estimated duration exceeds
**10 seconds**:

> This query is estimated to take about 12s. You may want to narrow it before running.

Three things to know:

- **It is advisory and non-blocking.** The search is started *first*; the estimate is a separate call whose
  failure or slowness cannot affect it. Despite the wording, the query is already running by the time the warning
  appears.
- **It requires `mode: ON`.** In `OFF` and `SHADOW`, `EXPLAIN` comes from the legacy compiler with no estimated
  duration at all, so nothing can fire.
- **In practice it almost never fires.** With both cost adapters stubbed, the estimated duration is `0` for most
  datasources, which is not greater than 10 seconds. The feature is wired end to end and effectively inert until
  the real adapters land.

The threshold is a compiled-in constant, not a configuration property. Making it configurable, and rendering the
full plan tree in the editor rather than only a warning, are both tracked in
[12-future-work.md](12-future-work.md#what-the-ui-still-needs).
