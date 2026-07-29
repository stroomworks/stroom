# How Graph DB works

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness).
**Audience:** analysts who want to know why queries behave as they do; administrators sizing a deployment.
**Scope:** the storage and temporal models, and how a query is answered. Canonical for the temporal
semantics. Physical byte layouts are out of scope — see the developer guide.
**Companion documents:** [03-ingest.md](03-ingest.md) (getting data in),
[10-limits.md](10-limits.md) (the limits these mechanics impose),
[11-operations.md](11-operations.md) (running it).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

You do not need this file to use Graph DB. You need it to understand why an unanchored query is slow, why
storage only ever grows, and what `AS OF` actually means.

## What a graph physically is

Each `GraphDb` document owns **one LMDB database environment** on local disk, at:

```
<stroom app path>/graphdb/<document uuid>/
```

LMDB is a memory-mapped embedded key/value store; Graph DB uses the same wrapper (`PlanBEnv`) as Stroom's
Plan B state stores. Two consequences follow immediately:

- **Graphs are fully isolated from each other.** One graph's load has no effect on another's queries, and
  deleting a document deletes its store outright.
- **The store is local to the node.** It is a file tree, not a shared service.

Inside that environment sit several key/value tables, none of which appear in the explorer tree or have
permissions of their own — they are private to the document:

| Store | Purpose |
|---|---|
| **Node store** | Every version of every node: its labels and properties, keyed by node and `validFrom` |
| **Out-edge store** | Every version of every edge, keyed from the source node |
| **In-edge store** | The same edges keyed from the destination node, so backwards traversal is as cheap as forwards |
| **Property index** | Maps a (label, property key, value) triple to the nodes carrying it. This is what lets a query *find* its starting nodes |
| **Interning tables** | Five small lookup tables mapping node ids, labels, edge types, property keys and long property values to compact numeric identifiers |

### Why ids are interned

Node ids, labels, edge types and property keys are each replaced on the way in by a short fixed-width
number, and the original string is stored once. A node id becomes 6 bytes; a label or edge type becomes 4.

This matters for two reasons you will actually notice. It keeps keys small, so more of the graph stays in
the page cache. And because the widths are *fixed*, keys sort predictably — which is what makes it possible
to fetch "all the `KNOWS` edges out of this node" as one contiguous range scan rather than a search.

The practical ceilings this imposes are in [10-limits.md](10-limits.md).

### How a graph spans a cluster

This is the most consequential thing on this page, so it is worth being precise about.

Graph DB borrows Plan B's **storage primitives** — the LMDB environment wrapper, the write batching, the id
interning tables and the value serialisers — and now also its **ingest shape**: build a fragment, ship it,
merge it.

**Ingest writes a fragment, not the graph.** When a stream reaches the Graph Filter, the filter opens a
private, empty graph store for that stream alone and writes the stream's mutations into it. Nothing is
written to the graph itself during processing. The filter's per-record commit-or-abort discipline still
applies, and now protects the fragment.

**On stream completion the fragment is replicated.** The fragment is zipped, hashed and sent to every node
named in `graphdb.nodeList` — over HTTP to remote nodes, directly to disk on the local one. Delivery must
succeed to *every* listed node or the stream task fails and is retried; partial delivery reported as success
would leave the cluster permanently disagreeing about the graph with nothing to indicate it.

**Each receiving node merges the fragment into its own authoritative store.** Merge is not a byte copy.
Every key in a graph store embeds **fragment-local** interned ids, so each row is decoded, its ids translated
into the target store's own id space, and re-encoded. The property-value index is rebuilt from the merged
node rows rather than copied, because its hash-tier keys carry clash-sequence suffixes that are meaningful
only in the store that assigned them. Merge is idempotent by construction — id interning is get-or-create and
no timestamp is generated during it — so a fragment delivered twice is harmless.

**Queries are routed to a node that holds graph data.** A node not named in `graphdb.nodeList` has no graph
store at all, so running a query there would answer from nothing rather than fail. Queries against a
`GraphDb` are therefore pinned to a configured node.

Because replication is **full** — every listed node receives every fragment — any one of them can complete
any traversal locally. That is the property the next section explains, and it is why replication rather than
partitioning is the right shape here.

> **On a cluster, set `graphdb.nodeList`.** An empty list means "this node only", which is correct for a
> single-node deployment and silently wrong for a cluster: each node would again accumulate only the
> fragments it happened to process. See
> [11-operations.md](11-operations.md#scaling-and-clustering).

Still deliberately absent: snapshots (a node either holds graph data or is routed away from), partitioning
one graph across nodes, and load-balanced routing — the first configured node is always chosen, so a wrong
answer is at least reproducible.

### Why fanning queries out would not have fixed it

Before the fragment-and-merge design above, ingest wrote straight into whichever node's store happened to
process the stream, so a cluster held one partial graph per node. The obvious repair — send the query to
every node and merge the results, as a Lucene index does — **does not work for a graph**, and understanding
why is what forces the design above rather than a cheaper one.

A Lucene document belongs to exactly one shard. A query can therefore be evaluated independently on each
shard and the results concatenated, because no document's evaluation depends on another shard's contents.

A graph traversal has no such property. Consider `(a:User)-[:ACCESSED]->(f:File)` where `a` lives in the
fragment on one node and `f` in the fragment on another. Neither node can answer: the first has the starting
node but not the target's labels and properties; the second has the target but never sees the edge that
reaches it. Merging two independent local traversals does not reconstruct the path, because the join those
traversals needed to perform spanned the boundary between them. Longer patterns make it worse — every hop is
another opportunity to cross.

So **a correct answer requires the whole graph to be resolvable in one place.** That gives exactly three
architectures:

| Approach | Correct? | Notes |
|---|---|---|
| Every mutation funnelled into one authoritative store, queried there | **Yes** | Plan B's build-then-merge ingest — **the approach Graph DB now takes** |
| One authoritative store plus **whole-copy** replicas, queried against any complete copy | **Yes** | Plan B's snapshot model. Graph DB reaches the same end by replicating every *fragment* to every node instead of copying finished stores |
| The graph partitioned across nodes, with traversal crossing partitions | Yes, but | Distributed graph traversal — the genuinely hard option, and not in scope |

Two conditions, both necessary, fall out of that, and both are now met:

1. **Every mutation must reach one authoritative store**, regardless of which node processed the stream —
   met by shipping each fragment to every configured node.
2. **Every query must run against a complete copy** of that store — met by pinning graph queries to a
   configured node.

Satisfying only the second is a trap worth naming, because it looks like a fix. Routing all queries to one
node while ingest still wrote locally would have made answers *consistently* incomplete instead of randomly
incomplete — more reproducible, and no more correct.

### An inversion worth noting

For most stores, sharding is the sophisticated answer and whole-copy replication the crude one. For a graph
it is the other way round. **Whole-copy replication preserves correctness precisely because each copy can
complete any traversal locally; partitioning breaks it.**

This is why Plan B's snapshot model — a whole copy of one store rather than a slice of it — is the *right*
shape for a graph even though it buys no extra capacity, and why Lucene-style sharding, which scales so well
for an index, is the one thing a graph cannot borrow.

### For comparison, how Plan B does scale

Useful context, because it is the template any future work would follow. Plan B is configured with a list
of **writer nodes**. A node named in that list holds the authoritative store and accepts pipeline writes;
any node *not* in the list becomes a **snapshot node**, pulling a point-in-time copy from a writer and
querying that local copy. Queries either run against the local snapshot or are forwarded to the first
writer node.

Note what that is not: no hash partitioning of one store across nodes, and no replication for redundancy.
It is one authoritative writer plus read-only snapshot fan-out — which is a read-scaling and locality
mechanism, not a capacity one. Even fully adopted, it would not raise the per-graph size ceiling.

## The temporal model

This is the part worth reading carefully, because it drives most of Graph DB's distinctive behaviour.

### Everything is a version; nothing is overwritten

Every key in the node and edge stores ends with a **`validFrom`** timestamp. Loading a node id that already
exists does not update a row — it writes **a new version** alongside the old ones.

A version's value is a **complete snapshot** of that entity: all its labels, all its properties. There is no
per-property delta. If a node has ten properties and one changes, the new version restates all ten.

### There is no end date

A version is valid from its `validFrom` until the next version of the same entity, and the newest version
is valid indefinitely. Nothing stores a `validTo`, and nothing is rewritten when a later version arrives.

This is why ingest can be done out of order and in parallel: writing a version dated last Tuesday does not
require finding and amending the version dated last Monday.

### Deletion is a tombstone

Deleting a node or edge writes a version marked *absent* from that instant onward. It does not remove
anything.

The consequence is important and occasionally surprising: **a deleted entity is still visible to queries
asking about earlier instants.** That is the intended behaviour — history is preserved — but it means
deletion never reclaims space. It costs space.

### How an instant is resolved

For a point-in-time query (`AS OF`, or no temporal clause at all, which means "now"):

> Seek to the position for this entity at the requested instant, then **step backwards** to the nearest
> version at or before it.

If there is no such version, the entity did not exist yet and does not match. If the version found is a
tombstone, the entity has been deleted by that instant and does not match. Otherwise that version's labels
and properties are what the query sees.

This is a single backwards step in a sorted structure, so it is cheap — a point-in-time query against a
long history costs about the same as one against a short history.

For a **range** query (`BETWEEN`, `AROUND`), the question is different: *was this entity present at any
point within the window?* That cannot be answered by one seek, so it scans the versions falling in the
range. Range queries therefore cost more on entities with a lot of history, and cannot exit early.

### What this buys you

- `AS OF` gives you the graph as it genuinely was, including entities since deleted.
- `DIFF FROM … TO …` evaluates the same pattern at two instants and classifies each element as `ADDED`,
  `REMOVED`, `MODIFIED` or `UNCHANGED`.
- Re-ingesting a corrected historical record is a normal write, not a migration.

### What it costs you

- **Storage only grows.** Updates add versions, deletes add versions. See
  [Retention](#retention-and-reclaiming-space) below.
- **Range queries scale with version count**, not entity count.
- **You must supply `validFrom` yourself** on every mutation — it is never defaulted from event time or
  receipt time. Getting it wrong puts data at the wrong point in history, where it is easy not to notice.

## How a query is answered

Understanding this makes the difference between a query that returns instantly and one that hits the 30-second
budget.

### 1. Find the starting nodes (the anchor)

The first node pattern in your `MATCH` is the **anchor**. Everything else is reached by walking outward from
it, so how the anchor is found dominates the cost. There are three access paths:

| Anchor pattern | How it is resolved | Cost |
|---|---|---|
| `(p:Person {nhs_no: 'NHS001'})` — label **and** property | **Property-index seek**: jumps straight to the matching nodes | Fast, and roughly constant regardless of graph size |
| `(p:Person)` — label only | **Label scan**: walks every node in the graph, keeping those carrying the label | Proportional to the whole graph |
| `(n)` — neither | **Rejected**, except for the whole-graph preview form `MATCH (n) RETURN GRAPH` | — |

The ordering here is the single most useful performance fact about Graph DB: **a property predicate on the
anchor turns a full scan into a seek.** [10-limits.md](10-limits.md) covers the practical consequences.

Note that labels and properties on *later* nodes in the pattern are filters applied after arriving there —
they are never used as alternative starting points. The engine folds the pattern strictly left to right,
anchor first, in the order you wrote it. It does not reorder your pattern to find a cheaper start.

### 2. Walk the pattern

Each hop looks up the adjacency store for the relevant direction — the out-edge store for `-[:R]->`, the
in-edge store for `<-[:R]-` — restricted to the one edge type named in the pattern. Because keys are ordered
by (node, edge type, …), the edges of one type leaving one node are contiguous, so a hop is a range scan
rather than a search.

An edge pattern must name exactly one type. There is no "any edge type" index, so an untyped hop has no
access path and is rejected.

A variable-length hop (`*1..3`) is a breadth-first exploration repeated per starting node, which is why its
budget is counted **per anchor** rather than per query: a broad anchor multiplies the work.

### 3. Filter, project and return

`WHERE` predicates are applied as the walk proceeds. Then results are projected into the columns you asked
for, de-duplicated if you wrote `DISTINCT`, sorted, aggregated and limited.

Two shapes matter for memory. If your query can stream — no `ORDER BY`, no `DISTINCT`, no aggregation, and a
`LIMIT` — the engine stops as soon as it has enough rows. Anything requiring the whole result set before it
can answer (a sort, a count, a de-duplication) must hold rows in memory, which is what the accumulated-row
ceiling protects.

## Retention and reclaiming space

Because nothing is ever overwritten, a graph fed continuously grows without bound unless you intervene.

**Retention is configured per document and is off by default.** When enabled with a duration, a scheduled
job (running every ten minutes) deletes versions older than the cutoff from the node and edge stores,
always keeping at least one version per entity so that "as it was then" queries still resolve, and then
sweeps interned ids that no longer appear anywhere.

Two things retention does **not** do, both of which matter:

- **It does not condense redundant versions.** Ten identical daily snapshots of an unchanged node remain
  ten versions. There is no merge or compaction pass.
- **It does not clean the property index or the property-key table.** Stale index entries are filtered out
  at query time rather than removed, so that portion of the store grows monotonically regardless.

The only full reclaim is **rebuild** — dropping the store and reprocessing the source streams. This carries
a trap worth stating plainly: **it depends on those source streams still existing.** If Stroom's own data
retention has aged them off, the graph cannot be rebuilt, and the only remaining option is to delete the
document. Consider that relationship when setting retention on the feeds that supply a graph.

Details and the operational procedure are in [11-operations.md](11-operations.md).

## Consequences worth remembering

| Because… | …this follows |
|---|---|
| Every write is a new version | Storage grows with *change*, not with entity count. A small graph updated often can be larger than a big graph loaded once |
| Deletes are tombstones | Deleting data never frees space, and deleted data stays visible to historical queries |
| `validFrom` is caller-supplied | The quality of your temporal queries is exactly the quality of the timestamps your XSLT emits |
| Anchors resolve by property index | A property predicate on the first node pattern is the difference between a seek and a full scan |
| Adjacency is keyed per edge type | Every hop must name its edge type; there is no wildcard traversal |
| The store is one LMDB env per document | Graphs are isolated, sized independently, and capped independently |
| Ranges intersect windows | `BETWEEN`/`AROUND` cost more than `AS OF`, on entities with long histories |

## Next

- [03-ingest.md](03-ingest.md) — getting data in
- [06-language-reference.md](06-language-reference.md) — the query language
- [10-limits.md](10-limits.md) — the limits these mechanics impose, and how to work within them

### Further reading

[13-developer-guide.md](13-developer-guide.md) covers the physical key layouts and the class structure.
The original architecture proposal — including why this was built on Plan B's LMDB layer — has been
retired; it remains in the repository's git history.
