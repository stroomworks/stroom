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

Each `GraphDb` document owns one LMDB environment on local disk, under the root set by `graphdb.path`
(default `graphdb`, relative to the Stroom home directory unless absolute):

```
<graphdb.path>/shards/<document uuid>/     the authoritative store for one graph
<graphdb.path>/writer/                     per-stream fragments being written by ingest
<graphdb.path>/receive/                    fragments arriving from other nodes
<graphdb.path>/staging/                    received fragments awaiting merge, in arrival order
<graphdb.path>/unzip/                      scratch space for expanding received fragments
<graphdb.path>/merging/                    per-graph queues of fragments waiting to be merged
```

Graphs are fully isolated: separate files, separate size budgets, no shared contention. Deleting the
document deletes the store.

These directories are deliberately **not** shared with Plan B's, even though the layout matches. Each
feature's merge loop deletes a queued fragment whose owning document it cannot resolve, and a graph document
is not a Plan B document — so sharing directories would let each feature silently discard the other's data.

> **The store is a local file tree, not a shared service.** On a cluster, every node that should hold graph
> data must be named in `graphdb.nodeList` — see
> [Scaling and clustering](#scaling-and-clustering) below.

## Settings you must not change casually

Some settings are safe to change at any time. Three are not, and only one of them stops you.

| Setting | Changing it | Enforced? |
|---|---|---|
| **Temporal Precision** (per document) | Part of the key layout, so a store written at one precision **refuses to open** at another. You get a `Key schema mismatch` naming both | **Yes** — fails loudly |
| **`graphdb.path`** | The old stores are not moved. A graph opened under a new path is **provisioned empty**, so every graph silently appears to have no data | **No** |
| **`graphdb.nodeList`** — adding a node | The new node receives fragments from that point on and **is never backfilled**. If it sorts first in the list it becomes the query target, and answers are silently partial | **No** |
| `graphdb.nodeList` — removing a node | That node keeps a stale copy which nothing updates or deletes | No, but harmless while it is not queried |
| `graphdb.maxStoreSize` | Applies only to graphs opened after a restart; existing stores keep their original ceiling | Partially — needs a restart |
| Data Retention, Node Type Mappings, description | Safe. Shortening a retention window deletes data on the next maintenance run, which is not reversible | n/a |

> **Adding a node to `graphdb.nodeList` needs a deliberate procedure**, because there is no backfill and queries
> go to the first node in the list. To add a node safely:
>
> 1. Add it to `graphdb.nodeList` on every node, positioned **last**, so it does not become the query target.
> 2. Copy each `<graphdb.path>/shards/<uuid>` directory to the new node while nothing is writing, or reload the
>    source streams so every graph is rebuilt everywhere.
> 3. Only then move it earlier in the list if you want it serving queries.
>
> Skipping step 2 reintroduces exactly the defect the fragment-and-merge design removed: a node answering from
> data it never received, with nothing reporting the shortfall.

> **Changing `graphdb.path` needs the data moved with it.** Stop the node, move
> `<old path>/shards` to `<new path>/shards`, then start it. Nothing checks, and an empty graph looks the same as
> a graph you have not loaded yet.

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

### How graph data reaches every node

Graph DB uses Plan B's storage primitives **and** its build-then-merge ingest shape. The mechanism is in
[02-architecture.md](02-architecture.md#how-a-graph-spans-a-cluster); operationally, three things matter.

**Ingest never writes the graph directly.** The Graph Filter writes each stream's mutations into a private
fragment under `writer/`. On stream completion the fragment is zipped and sent to every node named in
`graphdb.nodeList`, then deleted locally.

**Each named node merges every fragment.** Replication is full, so each listed node holds the whole graph and
can complete any traversal locally. The `Graph DB Merge Processor` job starts the merge loops; they then run
continuously, so the schedule exists to (re)start them after a restart rather than to pace them.

**Queries are pinned to a listed node.** A node not in the list holds no graph store, so a query there would
answer from nothing rather than fail.

#### Configuring it

```yaml
graphdb:
  path: "graphdb"
  nodeList:
    - "node1a"
    - "node2a"
  maxStoreSize: 10737418240
  maxVarLengthHops: 50
  maxVarLengthPathStates: 200000
  maxTraversalDuration: "PT30S"
  maxAccumulatedRows: 1000000
  wholeGraphNodeCap: 100
```

| Setting | Meaning |
|---|---|
| `graphdb.path` | Root for all graph data on this node. Restart required — LMDB paths are resolved at startup |
| `graphdb.nodeList` | The nodes that hold graph data. **Empty means this node only** |
| `graphdb.maxStoreSize` | Maximum bytes one graph may reach. Restart required, and it applies only to graphs opened afterwards — see [10-limits.md](10-limits.md) |
| `graphdb.maxVarLengthHops` and the three `max…` limits below it | The traversal guardrails. See [10-limits.md](10-limits.md#query-and-traversal-limits) for what each one stops and the message a user sees |
| `graphdb.wholeGraphNodeCap` | The only limit that truncates rather than failing, because it bounds an exploratory browse |

The defaults are exactly the values that were hard-coded before these settings existed, so upgrading changes
nothing until you change something.

> **An empty `nodeList` on a cluster is silently wrong.** It means "this node only", so each node again
> accumulates only the fragments it processed and every query returns a partial answer. It is correct only on
> a single-node deployment. Set it explicitly on a cluster.

A node named in the list but **not enabled** is treated as an error, not a target to skip: the sending task
fails and the stream is retried. Skipping it would leave that node's graph permanently short while it carried
on answering queries.

#### What to watch

| Symptom | Meaning |
|---|---|
| `graphdb` merge-failure metric above zero | At least one fragment could not be merged. The fragment directory under `merging/` is **retained** deliberately so it can be merged once the cause is fixed. Check the ERROR log |
| Fragments accumulating under `staging/` or `merging/` | The merge loops are not running. Check the `Graph DB Merge Processor` job is enabled |
| A stream task failing with a send error | A target node was unreachable, not enabled, or running a build without the `/graphFileTransfer/v1/sendPart` endpoint. The stream will be reprocessed |

During a rolling upgrade an older node has no such endpoint, so sends to it fail with a 404 and the stream
task fails loudly. That is the intended behaviour — the alternative is losing the fragment quietly.

Note there is still **no snapshot mechanism** and **no partitioning** of one graph across nodes. Replication
is what makes a graph correct on a cluster; it does not raise the per-graph size ceiling, and capacity still
comes from splitting across documents.

### What you can do about capacity

| Option | Buys you | Cost |
|---|---|---|
| **Split across several `GraphDb` documents** — by period, tenant, or subject area | Real capacity growth: each document has its own ceiling | No query spans two graphs. You partition the question by hand |
| **Enable retention and shorten the window** | Bounds growth on a continuously fed graph | Loses the history that is the feature's main draw, and does not reclaim the property index |
| **Reduce what you load** | Slows growth | Modelling effort — see [04-event-logging-xslt.md](04-event-logging-xslt.md) |

Splitting across documents is the only one that genuinely increases the data you can hold. Treat the others
as ways of living within one graph's ceiling.

### What would still need building

Each of these is a code change, sized in [12-future-work.md](12-future-work.md). Cheapest first:

1. **Make the store size configurable.** `GraphStores` currently passes no size override, so every graph
   silently takes the 10 GiB default. The parameter already exists — a small change that lifts the hard
   ceiling.
2. **Implement condense and compact.** Plan B performs both as scheduled maintenance; Graph DB does
   neither, which is why redundant versions accumulate forever. Plan B's implementations are a working
   template, and this is independent of the clustering work.
3. **Snapshot nodes.** Today a node either holds graph data or is routed away from. Snapshots would let a
   node serve graph queries from a read-only whole copy it pulled from a holder, buying read scaling and
   locality. It would **not** raise the per-graph size ceiling, because a snapshot is a whole copy rather
   than a partition — and for a graph that whole-copy property is the feature, not the shortcoming.

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
[12-future-work.md](12-future-work.md#capacity-and-scale) rather than being a gap someone simply has not
closed yet.

Practical consequence when choosing between them: if the dataset needs to grow beyond what one node can
hold, use an index and accept that relationship questions are awkward. Reach for a graph when the
relationships are the point and the volume is modest.

### How other graph databases handle this

Useful context, because it shows that Graph DB's constraints are not unusual and that the roadmap's ordering
matches what mature products actually do. *This subsection is a survey of published architectures, not
something verified against the code like the rest of this set — treat product specifics as indicative and
check current documentation before relying on them.*

There are broadly four answers in the market, and every one of them trades something away.

**1. Scale up, replicate whole copies.** One node accepts writes; complete replicas serve reads. Neo4j's
cluster architecture works this way: primaries form a Raft group with a **single leader** applying all
writes, and read replicas add read capacity without participating in consensus. Write throughput scales
*vertically*.

**2. Separate storage from compute.** Amazon Neptune keeps a single writer instance plus up to 15 read
replicas over one shared storage volume, so storage grows independently of the compute tier and no manual
sharding is needed. Write throughput is still bound to that one writer.

**3. Partition over a distributed store.** JanusGraph stores adjacency lists in Cassandra, HBase or
Bigtable, so capacity scales horizontally with the backend. The cost is explicit: an edge whose endpoints
land on different machines is a **cut edge**, and traversing it requires machine-to-machine communication.
JanusGraph's own documentation is blunt that random partitioning becomes *less* efficient as the cluster
grows, because of the cross-instance communication needed to answer a query — which is why it offers
explicit partitioning so that frequently co-traversed vertices can be co-located.

**4. Build distributed traversal properly.** TigerGraph distributes both storage and computation across an
MPP cluster so heavy traversals execute in parallel; ArangoDB's clustered mode supports distributed writes.
This is the only approach that scales writes *and* capacity, and it is also by far the most engineering to
build.

### What this tells us

| Approach | Capacity | Write scaling | Traversal cost | Correctness burden |
|---|---|---|---|---|
| Scale up + whole replicas | One node's disk | Vertical only | Local, fast | Low — every copy is complete |
| Storage/compute split | Large, managed | Single writer | Local to compute tier | Low |
| Partition over distributed store | Horizontal | Horizontal | **Pays for cut edges** | Moderate — locality must be engineered |
| Distributed native traversal | Horizontal | Horizontal | Parallel, engineered | High — the hard problem, solved deliberately |

Three things worth drawing out:

**Nobody gets graph partitioning for free.** Every product either avoids partitioning (replicate whole
copies), pushes it into a distributed storage layer and accepts cut-edge latency, or invests heavily in
distributed traversal. That the industry term *cut edge* exists at all tells you how central the problem is.

**Single-writer is the common case, not an aberration.** Neo4j and Neptune — two of the most widely deployed
graph databases — both funnel writes through one node. Graph DB's roadmap item 1
([12-future-work.md](12-future-work.md#correctness-across-a-cluster)) puts it in the same category as the
mainstream answer rather than at some unusual disadvantage.

**Whole-copy replication first, partitioning much later, is the well-trodden path.** It is what Neo4j did for
years before Fabric, and Fabric's sharding still requires queries to be written with the shard layout in
mind. So Graph DB's ordering — correct single authoritative store, then whole-copy read replicas, and
partitioning as a distant Hard/High item — is the conventional progression, not a shortcut.

Where Graph DB genuinely differs today is that it has **none** of the four, and that its per-graph ceiling is
a fixed constant rather than a property of the hardware. Both are fixable; neither is exotic.

Sources for this subsection, surveyed 2026-07-28 — check current documentation before relying on any
product specific:

- Neo4j clustering and scaling —
  <https://neo4j.com/docs/operations-manual/current/clustering/introduction/> and
  <https://neo4j.com/docs/operations-manual/current/scalability/scaling-with-neo4j/>
- JanusGraph graph partitioning and cut edges —
  <https://docs.janusgraph.org/advanced-topics/partitioning/> and
  <https://docs.janusgraph.org/advanced-topics/data-model/>
- Amazon Neptune architecture — <https://aws.amazon.com/neptune/>

## Retention and maintenance

A single scheduled job, **"Graph DB Maintenance"**, runs every ten minutes and does three things:

| Step | Applies to | What it does |
|---|---|---|
| Reclaim | graphs whose document has been deleted | Removes the store. Catches a delete that happened while this node was down, which no entity event reached |
| Retention | graphs with retention enabled | Deletes versions older than the cutoff, then rebuilds the property index and sweeps interned identifiers no surviving anchor uses. Always keeps at least one version per entity, so historical queries still resolve |
| Condense | **every** graph | Collapses runs of consecutive identical versions |

> **Do not think of this as only the retention job.** It was called that when retention was all it did.
> Condensing applies to every graph including those with retention switched off, so disabling this job stops
> storage being reclaimed on graphs that have no retention policy at all.

The job is marked advanced and appears in the Jobs screen like any other. It opens every graph's store, because
condensing applies to all of them.

Retention itself is configured **per document**, on the Settings tab, using the same control as Plan B stores.

**What is still not reclaimed** — matters for planning:

- **Redundant versions are now condensed.** Runs of consecutive identical node and edge versions are collapsed
  to the earliest of each run, so ten identical daily snapshots of an unchanged node become one version. This
  runs on every maintenance cycle for **every** graph, whether or not retention is enabled, because it changes no
  answer at any instant: a point-in-time lookup is a floor scan, so it lands on the surviving earliest version,
  which holds the same value as the ones removed.
- **Deleted space is not returned to the filesystem.** LMDB reuses freed pages for new writes, so a store that
  has condensed or aged data does not shrink on disk - it stops growing. Reclaiming the file itself needs an
  in-place compaction pass, which is still to be built.

The property-**value** lookup, for values too long to store inline, is now swept as well: the rebuild reports
which entries the surviving anchors reference and the rest are removed.
- **The property-value lookup is not swept.** Property values longer than the inline tier are interned into a
  lookup table, and entries there are never removed - one per distinct long value ever seen.

The **property index itself now does participate**: when the sweep deletes any version, it clears and re-derives
the index from the versions that survived, so the anchors those superseded versions left behind are reclaimed. That
was the largest of the three leaks, because it accumulated one anchor per distinct value each node had ever held.
The property-key table is swept at the same time.

> **The rebuild is a full pass over the surviving versions**, so it only runs when the sweep actually deleted
> something. On a large graph with a short retention window this makes the retention job noticeably more expensive
> than before - it was previously a partial scan. If the job starts overrunning its ten-minute schedule, lengthen
> the schedule rather than disabling retention.

Between retention and condensing, every table is now bounded. What is not bounded is the size of the file on
disk, which stops growing but does not shrink.

## Rebuild — and its trap

The only way to genuinely reclaim space is to drop the store and reprocess the source streams.

> **Rebuild depends on the source streams still existing.** If Stroom's own data retention has aged off the
> streams that fed a graph, the graph cannot be rebuilt. At that point the only remaining option is to
> delete the document and lose the data.

The practical consequence: **the retention policy on a graph's source feeds is part of that graph's
recovery plan.** If a graph matters, keep its source streams at least as long as you would need to
reconstruct it. Check this relationship before you need it, not after.

Rebuilding is also not instant — it reprocesses everything, so budget for a full reload.

### This is an accepted limitation, not a pending fix

Reprocessing is the only rebuild path, and that is a deliberate position rather than an oversight. Graph data
is treated as **reproducible from its sources**: there is no migration tooling, and a store written by a build
with a different on-disk format refuses to open rather than being converted. The store's format stamp exists
precisely so that such a mismatch surfaces as a clear failure at open time and forces a deliberate rebuild,
instead of a build silently reading old bytes under new assumptions.

So the sequence you must be able to perform is: delete the store, reprocess the streams. Everything follows
from whether those streams still exist.

| If | Then |
|---|---|
| Source streams are retained at least as long as the graph | You can always rebuild. This is the supported configuration |
| Source streams are aged off sooner than the graph | The graph is **unrecoverable** once they go — treat it as the primary copy and back it up accordingly ([Backup and restore](#backup-and-restore)) |

An in-place compaction path that does not depend on source streams is tracked in
[12-future-work.md](12-future-work.md) and would remove this coupling. Until it exists, source-stream retention
is a graph's recovery plan and should be written down as such.

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

### Temporal Precision

How much of each key's space the `validFrom` timestamp occupies. Every node and edge version key ends with one,
and a graph is mostly keys, so this is the largest single lever on a graph's on-disk size.

| Precision | Key bytes for time | Saving vs millisecond | Latest representable |
|---|---|---|---|
| Millisecond (default) | 6 | — | ≈ year 10920 |
| Second | 4 | 2 bytes per version | ≈ year 2106 |
| Minute | 4 | 2 bytes per version | ≈ year 10136 |
| Hour | 3 | 3 bytes per version | ≈ year 3882 |
| Day | 2 | 4 bytes per version | ≈ year 2149 |

Choose the coarsest precision your questions tolerate. If you only ever ask "what did this look like on that
day", `Day` costs a third of the key space `Millisecond` does — and a `validFrom` is truncated to the chosen
unit, so two events in the same second become the same version at `Second` precision or coarser.

> **Precision is fixed when the graph is created.** It is part of the key layout, so a store records it and
> **refuses to open** under a different one — you get a `Key schema mismatch` error naming both precisions rather
> than silently misreading every key. Changing it means creating a new graph and reloading.

> **Nanosecond is rejected.** The `graph-mutation:1` vocabulary allows only three fractional digits in a
> timestamp, so nanoseconds cannot be ingested; permitting it would spend 8 bytes per key — the widest option —
> storing guaranteed zeros. Selecting it fails at open with an explanation.

Note the non-monotonic "latest representable" column: `Second` reaches only 2106 because its encoding counts
seconds from the year 2000 in four bytes, whereas `Minute` counts minutes from 1970. Both are Plan B encodings
that Graph DB reuses unchanged. None of these ceilings is likely to matter, but a `validFrom` beyond one would be
rejected rather than silently wrapped.

## Monitoring

There is no dedicated instrumentation for Graph DB — no metrics, no health check, no size alarm. What you
can watch:

| What | Where | Why |
|---|---|---|
| Store directory size | The filesystem, under `<app path>/graphdb/<uuid>` | The only warning before the 10 GiB cap |
| Stream error counts on graph feeds | Stream processing | Skipped records mean silently missing data ([03-ingest.md](03-ingest.md)) |
| "Graph DB Maintenance" job | Jobs screen | Confirms reclamation, retention and condensing are running |
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
