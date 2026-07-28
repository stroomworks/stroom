# Operating a Graph DB

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers); several of them are
operational and this file is where they bite.
**Audience:** Stroom administrators.
**Scope:** storage, sizing, retention, recovery, permissions and the per-document settings.
**Companion documents:** [02-architecture.md](02-architecture.md) (why storage behaves this way),
[10-limits.md](10-limits.md) (the ceilings), [03-ingest.md](03-ingest.md) (what fills a graph).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

> Read the [blockers](README.md#production-readiness--known-blockers) before deploying this anywhere that
> matters. This file describes how to operate Graph DB as it is; it is not a claim that doing so is
> currently advisable.

## Where the data lives

Each `GraphDb` document owns one LMDB environment on local disk:

```
<stroom app path>/graphdb/<document uuid>/
```

Graphs are fully isolated: separate files, separate size budgets, no shared contention. Deleting the
document deletes the store.

> **Single-node.** The store is a local file tree, not a shared service, and Graph DB has no clustering of
> its own. In a multi-node Stroom this matters a great deal — see
> [Scaling and clustering](#scaling-and-clustering) below.

## Sizing — the part that will catch you out

Three facts combine badly, and an administrator needs all three:

1. **The store is capped at 10 GiB per graph and cannot be enlarged.** Graph DB does not pass a size
   override, so it always takes the default. There is no config property, environment variable or UI
   control for it ([10-limits.md](10-limits.md)).
2. **Retention is off by default**, so every version of every node and edge is kept forever.
3. **Nothing is ever overwritten.** Every update writes a new version; every delete writes a tombstone.
   Deleting data *increases* the size of the store.

So a graph fed continuously will grow until it hits a wall you cannot move.

### Estimating growth

Size tracks **change**, not entity count. A graph of 10,000 nodes updated hourly grows far faster than one
of 1,000,000 nodes loaded once.

A usable rule of thumb: multiply the number of node and edge versions your pipeline writes per day by a few
hundred bytes each — a version stores its full label and property snapshot, not a delta — then add the
property index, which is roughly proportional to the number of distinct (label, property, value) triples.
Measure the directory after a representative day's load and extrapolate; the ratio holds better than any
formula.

### When you approach the cap

In order of preference:

1. **Enable retention** (below) and let the scheduled job trim.
2. **Split across several graphs** — by time period, by data source, or by subject area. Graphs are
   independent, and a query names its own graph, so this is a modelling decision rather than a migration.
3. **Reduce what you load.** Prefer event-as-edge over event-as-node
   ([04-event-logging-xslt.md](04-event-logging-xslt.md)), emit each node once per batch rather than once
   per event, and keep property values short.
4. **Rebuild** — with the caveat below.

Monitor the directory size. Nothing warns you before `MDB_MAP_FULL`.

## Scaling and clustering

### Graph DB is single-node today

Graph DB uses Plan B's storage primitives but **none of its clustering** — no sharding, no snapshots, no
cross-node file transfer, no node-aware query routing. The mechanism, and why it is a deliberate decision
rather than an oversight, is in
[02-architecture.md](02-architecture.md#graph-db-is-single-node). Operationally, two things matter:

- **A query reads only the local node's store.** Stroom's node-resolution hook exists and is generic, but
  the only implementation answers for Plan B documents and expresses no preference for a `GraphDb`.
- **In a cluster, a graph fragments silently.** The Graph Filter writes to the local node, and stream
  processing is distributed. Each node holds only what it processed; each query returns only what is local.
  Nothing reports the shortfall.

If you run a multi-node Stroom, **pin both graph processing and graph querying to a single node**, or accept
partial answers. There is no replication either, so a graph is only as durable as the node holding it and
its backups — see [Backup and restore](#backup-and-restore).

### What you can do today

| Option | Buys you | Cost |
|---|---|---|
| **Split across several `GraphDb` documents** — by period, tenant, or subject area | Real capacity growth: each document has its own ceiling | No query spans two graphs. You partition the question by hand |
| **Enable retention and shorten the window** | Bounds growth on a continuously fed graph | Loses the history that is the feature's main draw, and does not reclaim the property index |
| **Pin graph work to one node** | Removes the fragmentation risk with no code change | Manual, easy to regress, no redundancy, and does not scale |
| **Reduce what you load** | Slows growth | Modelling effort — see [04-event-logging-xslt.md](04-event-logging-xslt.md) |

Splitting across documents is the only one that genuinely increases the data you can hold. Treat the others
as ways of living within one graph's ceiling.

### What would need building

Each of these is a code change, sized in [12-future-work.md](12-future-work.md). Listed cheapest first,
because the two cheapest are also the two most valuable:

1. **Make the store size configurable.** `GraphStores` currently passes no size override, so every graph
   silently takes the 10 GiB default. The parameter already exists — this is a small change that lifts the
   hard ceiling.
2. **Register a `GraphDb` node resolver.** The routing hook is already generic; only the resolver is
   Plan-B-specific. Teaching it about graph documents would send every graph query to one designated node,
   which fixes the silent fragmentation properly rather than by convention.
3. **Implement condense and compact.** Plan B performs both as scheduled maintenance; Graph DB does
   neither, which is why redundant versions accumulate forever. Plan B's implementations are a working
   template.
4. **Adopt Plan B's snapshot model.** Writer nodes plus read-only snapshot nodes, with file transfer
   between them. This buys read scaling and locality — but note it would **not** raise the per-graph size
   ceiling, because a snapshot is a whole copy of one store rather than a partition of it. Capacity still
   comes from splitting across documents, or from a genuine partitioning scheme that does not exist in
   either system today.

### For comparison: how a Lucene index scales

Administrators sizing a Stroom deployment will already know the index model, and the contrast explains what
Graph DB is missing and why.

| | Lucene index | Graph DB |
|---|---|---|
| **Capacity ceiling** | Sum of the volumes in its volume group — grow by adding volumes | Fixed 10 GiB per graph, not tunable |
| **Splitting a dataset** | Automatic: `partitionBy`/`partitionSize` by time, then `shardsPerPartition`, with shards rolling at `maxDocsPerShard` | Manual — separate documents, and no query spans two |
| **Placement** | Shards sit on volumes bound to named nodes, recorded in the database | Wherever the stream happened to be processed |
| **Multi-node reads** | Shards grouped per node, dispatched to each, results merged | Local node only |
| **Fragmentation risk** | None — placement is tracked, so a query knows every shard to visit | Silent and unreported |
| **Administrator controls** | Volume groups, per-volume byte limits, partition and shard settings | None |

This is why a Lucene index *requires* a volume group and a graph has no equivalent setting: the index has a
placement model to configure, and Graph DB has nowhere to put one.

Note that the index capability is not automatic either — a volume group whose volumes all sit on one node
gives a single-node index. The difference is that an administrator gets to decide, and with Graph DB there is
no decision to make.

**The underlying reason is the data model, not maturity.** An inverted index partitions cleanly: a document
belongs to exactly one shard, and a query searches each shard independently and merges. Graph traversal is
cross-cutting — an edge can span two partitions, so a multi-hop pattern may need to cross partition
boundaries mid-traversal. Sharding a graph is therefore a substantially harder problem than sharding an
index, which is why it sits at the bottom of
[12-future-work.md](12-future-work.md#scaling-and-clustering) rather than being a gap someone simply has not
closed yet.

Practical consequence when choosing between them: if the dataset needs to grow beyond what one node can
hold, use an index and accept that relationship questions are awkward. Reach for a graph when the
relationships are the point and the volume is modest.

## Retention

Configured **per document**, on the Settings tab, using the same retention control as Plan B stores.
A scheduled job named **"Graph DB Retention"** runs every ten minutes and, for each graph with retention
enabled, deletes versions older than the cutoff and then sweeps interned identifiers that no longer appear
anywhere. It always keeps at least one version per entity, so historical queries continue to resolve.

The job is marked advanced and appears in the Jobs screen like any other. It only opens a graph's store if
that graph actually has retention enabled, so leaving it on is cheap.

**What retention does not reclaim** — both matter for planning:

- **Redundant versions are never condensed.** Ten identical daily snapshots of an unchanged node stay ten
  versions. There is no merge pass.
- **The property index does not participate**, and the property-key table is never swept. Stale index
  entries are filtered at query time rather than deleted, so that portion grows monotonically regardless of
  retention.

Retention therefore slows growth; it does not bound it.

## Rebuild — and its trap

The only way to genuinely reclaim space is to drop the store and reprocess the source streams.

> **Rebuild depends on the source streams still existing.** If Stroom's own data retention has aged off the
> streams that fed a graph, the graph cannot be rebuilt. At that point the only remaining option is to
> delete the document and lose the data.

The practical consequence: **the retention policy on a graph's source feeds is part of that graph's
recovery plan.** If a graph matters, keep its source streams at least as long as you would need to
reconstruct it. Check this relationship before you need it, not after.

Rebuilding is also not instant — it reprocesses everything, so budget for a full reload.

## Backup and restore

A graph is a directory of LMDB files under the app path, so it is covered by whatever backs up Stroom's
data directories. Two cautions:

- **Copy a quiescent store.** LMDB files copied mid-write may be inconsistent. Take the backup when the
  graph is not being written to, or use a filesystem snapshot.
- **The document and its store are separate things.** The `GraphDb` document lives in Stroom's database;
  the data lives on disk under the document's UUID. A restore needs both, and they must match — a restored
  document pointing at a missing store is an empty graph, and an orphaned store directory is unreachable.

Because rebuild-from-streams is unreliable as a recovery route (above), backup is the primary mechanism for
any graph you care about.

## Permissions

A `GraphDb` document uses Stroom's standard document permissions, on the **Permissions** tab. Those
permissions govern reading the document and querying it.

The internal stores have no separate identity — no DocRefs, no permissions of their own. They are private
to the document, so document-level permission is the only control point. There is **no per-label,
per-edge-type or per-property access control**: anyone who can query a graph can query all of it.

Ingest is governed separately, by the processor filter's run-as user, like any other pipeline.

If a subset of a graph is more sensitive than the rest, the only available separation is to put it in a
different `GraphDb` document.

## Settings

The Settings tab is the first tab and exposes exactly three things.

### Data Retention

As described above. **Off by default.** The single most important setting on the tab, and the one most
likely to be overlooked.

### Node Type Mappings

Maps node labels onto Stroom domain types, so graph entities line up with the domain-type catalogue used
elsewhere in Stroom. Optional; affects presentation and integration, not storage or queries.

### Temporal Precision (present but not yet in effect)

A selector offering day, hour, minute, second, millisecond and nanosecond.

> **This setting currently does nothing.** The value is editable and is persisted with the document, but no
> implementation code reads it. Timestamps are handled at millisecond precision regardless of what you
> choose here. Setting it has no effect on ingest, storage or queries.

It is documented here only so that nobody deduces behaviour from its presence. Tracked in
[12-future-work.md](12-future-work.md).

## Monitoring

There is no dedicated instrumentation for Graph DB — no metrics, no health check, no size alarm. What you
can watch:

| What | Where | Why |
|---|---|---|
| Store directory size | The filesystem, under `<app path>/graphdb/<uuid>` | The only warning before the 10 GiB cap |
| Stream error counts on graph feeds | Stream processing | Skipped records mean silently missing data ([03-ingest.md](03-ingest.md)) |
| "Graph DB Retention" job | Jobs screen | Confirms trimming is actually running |
| Query failures | Query surfaces | Guardrail messages indicate queries that need rewriting ([10-limits.md](10-limits.md)) |

The stream error count is the one to automate if you automate anything: a partially loaded graph is
indistinguishable from a complete one by inspection.

## Capacity and concurrency

Queries run **synchronously on the calling thread**. This is deliberate — the engine is an in-memory
traversal over a single LMDB read transaction with no shard or network fan-out, so there is nothing to
dispatch asynchronously — but it means a slow query occupies a request thread for as long as it runs, up to
the 30-second budget.

Up to 1,023 concurrent readers are supported per graph; beyond that, readers wait.

Behaviour under sustained concurrent load has not been characterised. Treat the combination of synchronous
execution and a 30-second ceiling as a reason to keep queries anchored and narrow
([10-limits.md](10-limits.md)).

## An operational checklist

Before putting a graph into regular use:

- [ ] The graph's name is distinctive, and everyone knows renaming it breaks ingest
- [ ] Retention is enabled, with a duration you have justified
- [ ] The source feeds' retention is **at least** as long as the graph's, so rebuild remains possible
- [ ] The store directory is included in backups, and backups are taken from a quiescent state
- [ ] Something watches the store directory's size
- [ ] Something watches stream error counts on the graph's feeds
- [ ] Document permissions reflect the sensitivity of *everything* in the graph, not its average
- [ ] Somebody has read the [blockers](README.md#production-readiness--known-blockers)

## Next

- [10-limits.md](10-limits.md) — the values referenced here
- [02-architecture.md](02-architecture.md) — why storage grows the way it does
- [12-future-work.md](12-future-work.md) — which operational gaps are on the roadmap
