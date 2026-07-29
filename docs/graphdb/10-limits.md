# Limits and how to stay within them

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness).
**Audience:** analysts writing queries; administrators sizing a deployment.
**Scope:** every limit in Graph DB, its exact value, and how to work within it. **Canonical for all limit
values** — other files link here rather than restating numbers.
**Companion documents:** [02-architecture.md](02-architecture.md) (why these limits exist),
[06-language-reference.md](06-language-reference.md) (compile-time rejections, which are different),
[11-operations.md](11-operations.md) (storage and retention).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`. Each row names the source constant so a
maintainer can re-verify by grep.*

---

> **The query and traversal limits are now configurable**, under `graphdb` in the application configuration. The
> defaults below are unchanged from when they were hard-coded, so an existing deployment behaves identically
> until someone changes one. Raising a limit is a legitimate response to a legitimate query that trips it, but
> the better fix is usually a tighter pattern, a `LIMIT`, or a narrower `WHERE` — the limits exist because the
> alternative to failing is exhausting the node.

> **Temporal Precision is fixed at creation.** It determines the width of every key's `validFrom`, so it is part
> of the store's format stamp: a graph refuses to open under a different precision rather than misreading its keys.
> Choose it when you create the graph — changing it later means a new graph and a reload
> ([11-operations.md](11-operations.md#temporal-precision)).

The three per-document Settings-tab controls — Temporal Precision, Data Retention and Node Type Mappings — are
per graph rather than per deployment, and none of them affects the limits below.

## Query and traversal limits

These stop a running query. They fail loudly with an explanatory message rather than silently truncating.

| Limit | Default | Scope | What you see | Setting |
|---|---|---|---|---|
| Variable-length hop range | **50** | Per pattern | `… exceeds the maximum allowed maxHops of 50` — raised before any work is done | `graphdb.maxVarLengthHops` |
| Path states explored | **200,000** | **Per anchor node**, not per query | `variable-length traversal explored more than 200000 path-states; narrow the pattern's label/property constraints or reduce the hop range` | `graphdb.maxVarLengthPathStates` |
| Traversal wall-clock | **30 seconds** | Per query | `graph traversal exceeded the maximum allowed duration of PT30S` | `graphdb.maxTraversalDuration` |
| Rows held in memory | **1,000,000** | Per query | `graph traversal accumulated more than the maximum allowed 1000000 rows in memory; narrow the pattern's … or add a WHERE filter` | `graphdb.maxAccumulatedRows` |
| Anchor nodes from a label-only `MATCH` | **1,000,000** | Per query | `a label-only MATCH matched more than the maximum allowed 1000000 anchor nodes; add a property constraint …` | `graphdb.maxAccumulatedRows` |
| Whole-graph preview nodes | **100** | `MATCH (n) RETURN GRAPH` with no `LIMIT` | Silently capped — this is the one limit that truncates without telling you | `graphdb.wholeGraphNodeCap` |

**Per anchor, not per query** deserves emphasis. A variable-length query starting from one node gets a
200,000-state budget. The same query starting from 500 nodes runs 500 explorations, each with its own
budget — so it will not trip the path-state limit, but it will very likely hit the 30-second wall instead.

## Storage limits

| Limit | Value | Consequence | Source constant |
|---|---|---|---|
| Store size per graph | **10 GiB** | Exceeding it fails with `MDB_MAP_FULL`. **Now tunable.** LMDB fixes an environment's size when it is created, so raising this applies to graphs opened afterwards, not to ones already on disk — hence the restart requirement. It reserves address space rather than disk, so a generous value is cheap | `graphdb.maxStoreSize` |
| Labels per node version | **255** | The record is rejected and skipped at ingest | `GraphNodeDb.MAX_LABEL_COUNT` |
| Key length | **511 bytes** | Bounds how long an indexed property value can be before it is handled indirectly | `Db.MAX_KEY_LENGTH` |
| Property value indexed inline | **32 bytes** | Values up to this are fastest to seek | `GraphPropertyIndex.DIRECT_MAX_LENGTH` |
| Property value indexed by reference | **511 bytes** | Beyond 32 bytes, values are interned; beyond 511 they are hashed. Both still work, with an extra lookup | `GraphPropertyIndex.UID_LOOKUP_MAX_LENGTH` |
| Distinct nodes per graph | ~2.8 × 10¹⁴ | Not a practical concern | `GraphStores.NODE_UID_WIDTH` (6 bytes) |
| Distinct labels / edge types / property keys | ~4.3 × 10⁹ each | Not a practical concern | `GraphStores.TYPE_UID_WIDTH` (4 bytes) |
| Internal tables per graph | **32** | Affects future extension only | `GraphStores.MAX_DBS` |
| Concurrent readers per graph | **1,023** | Further readers wait | `PlanBEnv.CONCURRENT_READERS` |

The store-size ceiling is the one that will actually constrain you. It is configurable but **fixed once a
store is created**, so raising it later applies only to graphs opened afterwards — set it before you need it.
It also interacts with a fact from [02-architecture.md](02-architecture.md): storage grows with every version
written, and a delete writes a tombstone rather than reclaiming space. Retention and condensing bound that
growth, and nightly compaction returns the freed pages
([11-operations.md](11-operations.md#retention-and-maintenance)).

## Interface limits

These shape what you see rather than what the engine will do.

| Limit | Value | Effect | Source constant |
|---|---|---|---|
| Elements drawn in the graph view | **2,000** | Truncates with the warning *"Rendering the first 2000 elements for performance."* | `GraphResultWidget.MAX_ELEMENT_ROWS` |
| Neighbours added by "Expand neighbours" | **50** | Silently bounded | `GraphExpandService.MAX_NEIGHBOURS` |
| Starter queries generated by Discover | `LIMIT 100` | The generated query text | `GraphDiscoveryWidget.WHOLE_GRAPH_LIMIT`, `.LABEL_LIMIT` |
| Example nodes offered by Discover | **20** | Sampled, not exhaustive | `GraphDbResourceImpl.SAMPLE_NODE_LIMIT` |
| Properties shown inline per Discover node | **3** | Display only | `GraphDiscoveryWidget.MAX_INLINE_PROPS` |
| Time-travel slider positions | **20** | Across the chosen window | `GraphTemporalWidget.STEPS` |
| Time-travel default window | **7 days** | Editable in the widget | `GraphTemporalWidget.DEFAULT_WINDOW_MILLIS` |

## Staying within them

### The one thing that matters most: anchor selection

Nearly every avoidable performance problem is an anchor problem. The first node pattern in your `MATCH`
decides how the query finds its starting points, and there are only three possibilities:

```cypher
MATCH (p:Person {nhs_no: 'NHS001'})-[:KNOWS]->(f)    -- index seek: fast, size-independent
MATCH (p:Person)-[:KNOWS]->(f)                        -- full scan, filtered by label
MATCH (n)-[:KNOWS]->(f)                               -- rejected
```

**Adding a property predicate to the anchor turns a whole-graph scan into a direct seek.** If you only have
a predicate on a later node, move the anchor:

```cypher
-- scans every Person, then filters
MATCH (p:Person)-[:PARTY_TO]->(c:Crime {type: 'Drugs'}) RETURN p.surname

-- seeks straight to the drugs crimes, then walks backwards
MATCH (c:Crime {type: 'Drugs'})<-[:PARTY_TO]-(p:Person) RETURN p.surname
```

Both return the same rows. The second starts from an indexed value; the first does not. Traversal is
equally cheap in either direction, so reversing a pattern to get a better anchor costs nothing.

**Numbers seek regardless of how they were written.** A property ingested with `type="long"` or
`type="double"` is indexed by value, so `42`, `42.0` and `42.00` all reach it, as does every spelling of a
`dateTime`. A **string** property still has to be matched on the text it was stored as — that part is a
decision made at ingest, not at query time ([03-ingest.md](03-ingest.md#property-value-types)).

### By symptom

**"Exceeded the maximum allowed duration" (30 s)**
The query is exploring too much. In order of effectiveness: add a property predicate to the anchor; reduce
a variable-length range (`*1..3` explores far more than `*1..2`); add `WHERE` filters so fewer paths
survive; narrow the pattern with labels on intermediate nodes.

**"Explored more than 200000 path-states"**
A single starting node reaches too much — usually a variable-length hop into a densely connected region.
Reduce the hop range, or constrain the target: `-[:KNOWS*1..3]->(f:Person {city: 'Manchester'})` prunes as
it goes.

**"Accumulated more than the maximum allowed 1000000 rows"**
The query cannot stream, so it is holding everything in memory. This happens when you use `ORDER BY`,
`DISTINCT` or an aggregate — all of which need the full result before they can answer. Add a `WHERE` filter
or a more selective anchor. Note that adding `LIMIT` alone does *not* help here: with a sort or an
aggregate the engine must still see every row first.

**"A label-only MATCH matched more than … anchor nodes"**
Exactly what it says: add a property constraint to the anchor.

**"Rendering the first 2000 elements"**
Not an error — the graph view protecting itself. Add a `LIMIT`, or narrow the pattern. Two thousand
elements is well past the point where a node-link diagram is readable anyway; if you genuinely want all of
it, use the Data tab.

**My graph only shows 100 nodes**
You ran `MATCH (n) RETURN GRAPH` with no `LIMIT`, which caps at 100. Add an explicit `LIMIT`, or anchor the
query on something specific.

**`MDB_MAP_FULL`, or the store has stopped accepting writes**
The graph has reached its configured maximum (10 GiB by default). Raise `graphdb.maxStoreSize` and restart —
noting that only graphs opened after the restart pick up the new size, so an existing store must be rebuilt to
grow past its original ceiling. Otherwise the options are: enable retention on the
document and wait for the scheduled job; split the data across several graphs; or rebuild after reducing
what you load. See [11-operations.md](11-operations.md) — and note the rebuild caveat, which
requires the source streams to still exist.

### Modelling for the limits

Decisions taken at ingest determine which limits you meet later.

- **Give every entity kind a label**, so an anchor can always name one.
- **Give the entities you search by a short, high-selectivity property** — an id or a code — and store it
  in the form you will match on. Values of 32 bytes or less index most efficiently.
- **Prefer edges to intermediate nodes.** Modelling an event as an edge rather than a node roughly halves
  the elements involved and shortens every pattern by a hop.
- **Do not put bulk text in properties.** Long values still index, via a hash, but they consume the store,
  and the store is capped.
- **Watch your write rate, not your entity count.** A graph of 10,000 nodes updated hourly will outgrow one
  of 1,000,000 nodes loaded once. Enable retention early on anything fed continuously.

## Compile-time rejections are different

A query that asks for something the language does not support is refused before it runs, with a message
naming the construct. Those are in
[06-language-reference.md](06-language-reference.md#what-is-not-supported). The limits on this page apply
to queries that are perfectly valid and simply ask for too much.

## Next

- [02-architecture.md](02-architecture.md) — why the storage and traversal work this way
- [06-language-reference.md](06-language-reference.md) — what the language accepts
- [11-operations.md](11-operations.md) — retention, sizing and rebuild
