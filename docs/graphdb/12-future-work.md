# Roadmap and known gaps

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
**Audience:** stakeholders planning work; users wondering whether a gap will close.
**Scope:** everything the rest of this documentation set describes as missing, with an estimate of
difficulty and risk. Canonical for roadmap status.
**Companion documents:** every other file links here whenever it says "not supported".

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

**Nothing on this page exists.** The rest of the documentation set describes only what is built; this file
is the counterpart, and no other file may describe any of it in the present tense.

Difficulty is engineering effort. Risk is the chance of getting it subtly wrong, breaking something
existing, or being unable to change it later.

## Production blockers

The items that stand between Graph DB and a production deployment. If only one section of this page is
acted on, it should be this one.

| # | Item | Why | Difficulty | Risk |
|---|---|---|---|---|
| ~~**0**~~ | ~~**Cluster-correct ingest**~~ — **done.** Ingest writes a per-stream fragment, which is replicated to every node in `graphdb.nodeList` and merged there | Was the only blocker that made answers wrong rather than merely constrained. See [Correctness across a cluster](#correctness-across-a-cluster) | Hard | — |
| ~~1~~ | ~~**A fuller configuration surface**~~ — **done.** `GraphDbConfig` now covers the store size and all five traversal guardrails as well as `path` and `nodeList` | Retention defaults remain per-document rather than global, which is where they belong | Medium | — |
| ~~2~~ | ~~**Tunable store size**~~ — **done** via `graphdb.maxStoreSize` | Still needs a restart, and applies only to graphs opened afterwards, because LMDB fixes an environment's size at creation | Easy | — |
| 3 | **Loud ingest failures** — a strict mode that fails a stream rather than skipping records, plus schema validation | Silent partial data loss is the most dangerous current behaviour | Medium | Low |
| 4 | **Version condensing and compaction** — merge redundant identical versions, and compact in place | Storage grows monotonically even with retention on. Plan B performs both as scheduled maintenance and is a working template; Graph DB does neither | Medium | Medium |
| 5 | **Retention for the property index** — include it and the property-key table in the sweep | The index grows without bound regardless of retention | Medium | Medium |
| 6 | **Compaction without source streams** — an in-place rebuild that does not depend on reprocessing | Rebuild silently stops being possible once source streams age off | Hard | High |
| ~~7~~ | ~~**A `GraphDb` node resolver**~~ — **done.** `QueryNodeResolver` is now a multibinder behind a composite, and `GraphQueryNodeResolverImpl` pins graph queries to a configured node | The second half of cluster correctness, shipped together with blocker 0 — alone it would only have made answers consistently incomplete | Medium | — |
| 8 | **Ship the XSD** as an XMLSchema document so a `SchemaFilter` can validate in-pipeline | It exists only as a test resource today | Easy | Low |

## Correctness across a cluster

**This was the highest priority item on this page and it has been done.** The section is kept because the
reasoning it records is what constrains any future change here — in particular, why partitioning a graph across
nodes is not a cheap follow-on.

### What was wrong

Graph DB wrote to the local node and read from the local node. In a multi-node Stroom, stream processing is
distributed, so each node accumulated only the fragment built from the streams it happened to handle, and a
query returned only that fragment. No error, no warning, no partial-result indicator. There was no
configuration-level mitigation either: Stroom cannot pin a pipeline to a node.

### What correctness required, and how it was met

Two conditions, both necessary — the reasoning is in
[02-architecture.md](02-architecture.md#why-fanning-queries-out-would-not-have-fixed-it):

| | Requirement | How it was met |
|---|---|---|
| 1 | **Every mutation reaches one authoritative store** | Build-then-merge ingest: the Graph Filter writes a per-stream fragment, which is shipped to every node in `graphdb.nodeList` and merged into that node's store |
| 2 | **Every query runs against a complete copy** | `GraphQueryNodeResolverImpl` pins a graph query to a configured node; because replication is full, any of them is a complete copy |

Shipping (2) alone would not have been a fix: routing every query to one node while writes remained spread
across the cluster converts a random wrong answer into a consistent wrong answer — more debuggable, equally
incorrect. Both landed together for that reason.

### Why fan-out is not an option

Worth restating because it is the intuitive fix and it does not work. A Lucene index shards cleanly because a
document belongs to exactly one shard and no document's evaluation depends on another shard. A graph traversal
crosses boundaries by nature: an edge can span two fragments, so merging independent local traversals does not
reconstruct paths that needed both. For a graph, **whole-copy replication preserves correctness and
partitioning breaks it** — the inverse of the usual intuition.

That is why Plan B's snapshot model is the right shape here despite buying no extra capacity, and why
Lucene-style sharding is the one thing a graph cannot borrow.

### What remains, and what it cost

Not done, and deliberately: **snapshots** (a node either holds graph data or is routed away from),
**partitioning** one graph across nodes, and **load-balanced routing** — the first configured node is always
chosen, so a wrong answer is at least reproducible.

Two findings worth keeping. First, the subtle part was merging stores whose keys embed locally-assigned ids;
`SessionDb.merge` and `MetricDb.merge` showed the idiom, but graph keys have no byte-copy fast path at all, and
the property index cannot be row-copied because its hash-tier keys carry clash-sequence suffixes local to the
store that assigned them. Second, `Shard` did not need generalising: the merge engine was extracted from Plan
B's `MergeProcessor` as `PartMergeProcessor` and parameterised, leaving Plan B behind a façade.


## Capacity and scale

Distinct from [Correctness across a cluster](#correctness-across-a-cluster) above, which must come first.
This section is about how *much* a graph can hold and how fast it can be read once answers are trustworthy.
The operational workarounds available today are in
[11-operations.md](11-operations.md#scaling-and-clustering).

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Tunable store size** (also blocker 2) | The only change that raises the per-graph ceiling. `GraphStores` passes no size override, so every graph takes the default; the parameter already exists | Easy | Low |
| **Condense and compact** (also blocker 4) | Reclaims space from redundant versions, using Plan B's implementations as a template. Independent of the clustering work | Medium | Medium |
| **Snapshot fan-out** — read-only snapshot nodes on top of a correct writer | Read scaling and query locality. Presupposes correctness work item 1; a snapshot of a fragmented store is a complete copy of an incomplete graph | Hard | Medium |
| **Genuine partitioning** — spread one logical graph across nodes by key | The only route to a graph larger than one node's disk. Neither Graph DB nor Plan B has this, and it is the one approach that would reintroduce the correctness problem deliberately, requiring distributed traversal to solve properly | Hard | High |

Two things worth being clear about when planning this:

**Snapshots are not a capacity mechanism.** A snapshot is a whole copy of one store rather than a partition of
it, so adopting the model in full would leave the per-graph size ceiling exactly where it is. For a graph that
whole-copy property is a *feature* — it is what lets a replica complete a traversal locally — but it is not
capacity.

**Capacity today comes from splitting across documents.** That is a modelling decision, not a migration,
and it works now — but no query spans two graphs, so you partition the question by hand. Genuine
partitioning is the only thing that would change that, and it is the hardest item on this page.

### Architectural note: how far up the Plan B stack should Graph DB sit?

This question recurs, so the analysis is recorded here rather than re-derived. *Assessed 2026-07-28 against
branch `sw-query-optimiser`.*

**Graph DB already runs on Plan B** — at the bottom of the stack. It uses `PlanBEnv`, `LmdbWriter`,
`UidLookupDb`, `HashLookupDb`, `Db` and the serde layer, and never touches lmdbjava directly. The real
question is whether it should adopt Plan B's *upper* layers: `Shard`, `ShardManager`, merge and snapshots.

It should **not** mean becoming a Plan B state type. The eight state types (`STATE`, `TEMPORAL_STATE`,
`SESSION`, `METRIC`, …) are key/value shapes and a graph is not one of them. The document-type level is the
wrong seam; the `Shard`/merge level is the one worth considering.

#### The two structural mismatches

1. **`Shard` assumes one `Db` per document.** Its accessor is `<R> R get(Function<Db<?, ?>, R>)` — a single
   logical map. A graph is ten co-indexed tables with cross-table invariants, notably the out-edge/in-edge
   dual write. `Shard` would need generalising to a composite, or `GraphStores` implementing it as a facade
   over its tables.
2. ~~**The write models are opposite.**~~ **No longer true.** Plan B ingests by *build-then-merge*, and Graph
   DB now does the same: the Graph Filter writes a per-stream fragment which is shipped and merged. This was
   the root cause of the cluster fragmentation described in
   [02-architecture.md](02-architecture.md#how-a-graph-spans-a-cluster) — writes went straight to local
   storage, so there was no transportable artefact to merge centrally.

Only the first mismatch remains, and it turned out not to need solving either: rather than generalising
`Shard`, the merge *engine* was extracted from `MergeProcessor` as `PartMergeProcessor` and parameterised, so
Graph DB reuses the lifecycle without adopting `Shard` at all.

#### The obstacle that turns out not to be one

The expected blocker was UID remapping. Interning assigns UIDs from a **local sequential counter**, so two
independently-built fragments will assign the same UID to different node ids — and every Graph DB key embeds
UIDs. A byte-level merge would silently corrupt the store.

**Plan B already solves this**, and the idiom is worth knowing before anyone re-derives it. From
`SessionDb.merge`: where a key embeds a lookup, decode it to the logical key through the *source* store's
serde and re-encode through the *target* store's serde; where it does not, copy the bytes directly. `MetricDb.merge`
does the same, with a `validateSchema` guard against merging incompatible stores.

So the deep problem was a solved pattern. Applying it across ten tables was work, not research. The temporal
model merges naturally too: versions are distinct `validFrom`-suffixed keys, so a merge is a union needing no
reconciliation.

Two things turned out differently from that prediction, and both are worth recording:

- Graph keys have **no byte-copy fast path at all**, because every one of them embeds an interned id. Plan B's
  idiom applies, but to every row rather than to a subset.
- The property index **cannot be merged by copying rows**, even for identical values, because its hash-tier
  keys carry clash-sequence suffixes local to the store that assigned them. It is rebuilt from the merged node
  rows instead, which has the side benefit of reusing the ingest encoding path.

#### What was actually done

The merge-based write path was taken, and `Shard` was **not** generalised. Instead the merge lifecycle was
extracted out of `MergeProcessor` into a parameterised `PartMergeProcessor` that both features drive, leaving
Plan B behind a behaviour-preserving façade. Graph DB never adopts `Shard`, so the "one `Db` per document"
mismatch simply does not arise.

What remains from the original ordering:

1. **Tunable store size.** Does not need `Shard`; lifts the hardest ceiling in the system.
2. **`condense` and `compact` on `GraphStores`**, using Plan B's implementations as a template. Separable from
   clustering — bounded storage does not require anything above.
3. **Snapshots**, and only if a node that holds no graph data needs to serve graph queries locally rather than
   being routed away from. This is read scaling and locality, not correctness.


## Integration with the rest of Stroom

Graph DB is currently near-isolated: data goes in through a pipeline and comes out through a query, and it
participates in almost none of Stroom's other mechanisms. The one exception is already built — see below.

### Already available

**A Cypher sub-query can be a StroomQL join side**, so graph relationships can be combined with index or
state-store data and filtered in StroomQL's `where`. This is implemented and documented in
[05-querying.md](05-querying.md#joining-a-graph-to-other-stroom-data). It was undocumented for some time,
which is worth remembering when assessing what else might already work.

### What is missing

| Item | Why it matters | Difficulty | Risk |
|---|---|---|---|
| **Record `streamId`/`eventId` on every mutation** | The Graph Filter records no provenance, so nothing links a node or edge back to the event that created it. Useful on its own for auditing, and a **prerequisite** for the two items below | Easy | Low |
| **Extraction over graph results** | Stroom's search extraction resolves a `streamId`/`eventId` pair back to the source event and pulls fields from it via XPath. Applied to graph results this would allow a **thin graph**: store ids, labels and relationships only, and fetch bulky attributes from the streams on demand | Medium | Medium |
| **A graph lookup for pipelines** | Plan B exposes a lookup so a translation can enrich an event mid-pipeline from a state store. Graph DB has no equivalent, so "enrich this event with the groups its user belongs to" cannot be expressed even though the data is present | Medium | Medium |
| **Cypher as the driving side of a join** | Today the graph can only be the *joined* side, never the one leading the query | Medium | Medium |

### Why the thin-graph idea is interesting

It attacks the size ceiling from a different direction to everything in
[Capacity and scale](#capacity-and-scale). Rather than making a graph hold more, it makes a graph hold
*less*: topology in the store, attributes in the streams that are already retained anyway. For event-derived
graphs — where the relationships are small and the event payloads are not — that could be a far larger
saving than any amount of compaction.

Two honest limitations to design around:

**Extraction is a post-filter, not a pruning predicate.** It happens per surviving row, after the traversal.
So an extracted value could be used in a `WHERE` and would filter the answer correctly, but it could not stop
the walk exploring, and it could not help the anchor — which is where query cost actually lives
([10-limits.md](10-limits.md)). Useful for expressiveness; not a performance feature.

**It couples the graph's usefulness to stream retention.** Age the source streams off and the extracted
attributes disappear while the topology remains — the same trap as `rebuild()`
([11-operations.md](11-operations.md#rebuild--and-its-trap)). A thin graph is only as complete as the data
behind it.


## Data model

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| ~~**Typed property values**~~ — **partly done.** `long` and `boolean` are available via `<property type="…">` | Every value's type used to be `STRING` regardless of what it held | Medium | — |
| **Typed `double` and date property values** | The two types the above deliberately left out. Blocked on anchor encoding, not on effort: the equality index is keyed on a value's rendered text and a query seeks the literal's own text, so a type whose canonical rendering differs from what an author would write silently finds nothing. `42.0` renders as `42` | Medium | **Medium — needs a design decision first.** Two viable routes: (a) a canonical encoder shared by the ingest, merge and query sides, so all three agree on `42.0` ≡ `42`; (b) index both the raw and canonical forms, which removes the false negative at the cost of extra index entries — but merge only sees decoded values, so the raw form would have to be stored to survive it. Route (a) is cleaner and the reason it was not simply done now |
| **A real list type**, which would re-enable `collect()` | `collect()` is currently **rejected** rather than returning a comma-joined string. Deferred until closer to production | Medium | Medium — the edit is ~6 files and the compiler finds most of them; the risk is that 285 files can then receive a value type they have never seen. **Full analysis, including what re-enabling involves, in [12a-list-value-type.md](12a-list-value-type.md)** |
| **Typed values in `graph-mutation`** — a version 1.1 or 2.0 of the ingest vocabulary | Prerequisite for typed properties | Medium | Medium |
| **Per-property versioning** instead of whole-snapshot versions | Would cut storage growth substantially for wide, slowly-changing nodes | Hard | High |

## Query language

Grouped by how likely you are to want them.

### Small and worthwhile

| Item | Difficulty | Risk |
|---|---|---|
| `SKIP` — needs an offset on the core's `Limit` node | Easy | Low |
| `ORDER BY` an aggregate expression rather than only its alias | Easy | Low |
| `ORDER BY` / `SKIP` / `LIMIT` on a `WITH` | Medium | Low |
| Aggregation inside a `DIFF` query | Medium | Medium |
| Filtering on `changeKind` / `before()` / `after()` in a `WHERE` | Medium | Low |

### Structural

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **A second `MATCH` clause**, and multi-pattern `MATCH (a), (b)` | Common Cypher shape; several Neo4j queries need it | Medium | Medium |
| **Relationship-type alternation** `[:A\|B]` | Adjacency is keyed per type, so this needs a union of scans | Medium | Medium |
| **Pattern predicates** — `WHERE NOT (a)-[:X]->(b)` | `EXISTS { … }` covers part of the need; negation in a broader expression is common | Medium | Medium |
| **Untyped hops** `-->` | Needs an index that does not exist; arguably should stay unsupported to keep costs predictable | Hard | High |
| **Chaining a variable-length hop with other hops** | Currently a var-length hop must be the only hop | Medium | Medium |
| **Multiple `WITH` clauses** (full pipelining) | Medium | Medium |

### Analytics

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Path variables and `RETURN p`** | Prerequisite for anything path-shaped | Hard | Medium |
| **`shortestPath` / `allShortestPaths`** server-side | The Explore tab's version is browser-side over loaded nodes only, and is easy to mistake for the real thing | Hard | Medium |
| **Graph algorithms** — centrality, community detection, triangle count | Needed to answer POLE-style "who matters most" questions in one query | Hard | Medium |
| **Spatial** — `point()`, `point.distance()` | Needs a spatial type and index | Hard | Medium |
| **`labels()` / `keys()` / `properties()`** | Blocked on the list/map type above | Medium | Low |
| **`UNWIND`, list comprehensions, map projections** | Blocked on the list/map type | Medium | Medium |

### Not planned

So that nobody waits for them:

- **Writes from the query language** (`CREATE`, `SET`, `MERGE`, `DELETE`). Ingest belongs to pipelines,
  which is what keeps provenance, permissions and reprocessing consistent with the rest of Stroom. This is
  a design decision, not a gap.
- **Cross-graph queries.** One query, one graph.
- **Full ISO GQL conformance.** GQL includes sessions, transactions, catalogues and schema statements that
  do not fit a read-only embedded store. See [09-gql-and-neo4j.md](09-gql-and-neo4j.md).
- **Stored procedures / `CALL`.**

## Performance and execution

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Asynchronous query execution** | Queries occupy a request thread for up to 30 s | Medium | Medium |
| **Cost-based anchor selection** — let the planner pick the cheapest start rather than always the first pattern | Removes the main performance footgun. Would need statistics | Hard | Medium |
| **Streaming results for sorted and aggregated queries** | Those must currently buffer, which is what the million-row ceiling protects | Hard | Medium |
| **Index on edge properties** | Edge property predicates are post-expand filters only | Medium | Medium |

## User interface

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Cypher-carrying dashboards** — make Create Dashboard work by dispatching the query component through the Cypher seam | The button is disabled today because dashboards parse query text as StroomQL | Medium | Medium |
| ~~**Implement Temporal Precision**~~ — **done.** It selects the `validFrom` encoding (6 bytes down to 2), is part of the schema stamp and so immutable after provisioning, and rejects `Nanosecond` | Was editable and persisted but read by no code — the worst of both | Medium | — |
| **Warn on the silent 100-node preview cap** | Every other limit reports itself; this one truncates quietly | Easy | Low |
| **Server-side layout for large graphs** | The 2,000-element render cap is a browser constraint | Hard | Medium |
| **Saved queries / query history** on the graph tabs | Medium | Low |

## Documentation and testing

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Land the event-logging XSLT as a test** in `stroom-graphdb-impl`, with its sample corpus | It currently lives only in [04-event-logging-xslt.md](04-event-logging-xslt.md) and is unexercised by CI, so it will rot silently | Easy | Low |
| **Assert documented limits against the constants** in a test | [10-limits.md](10-limits.md) records ~15 values that a refactor can change with no doc-side signal | Easy | Low |
| **Assert documented error strings** against `CypherToLogicalPlan` | Same problem for the messages quoted in [06-language-reference.md](06-language-reference.md) | Easy | Low |

Those three are cheap and would convert a large part of this documentation set from prose into something CI
defends. They are the highest value-per-effort items on the page.

## Suggested order

If the goal is a production-capable Graph DB:

1. ~~**Blocker 0 with blocker 7**~~ — **done.** Cluster-correct ingest and the node resolver, shipped
   together, along with the store format stamp that guards every later layout change. The two halves were not
   independently useful: merge without routing leaves queries reading the wrong store, routing without merge
   makes wrong answers merely consistent. Everything below is a limitation you can document and work around;
   that one was not.
2. **Blockers 3 and 8, then 1 and 2** — loud ingest failures and the XSD first, because silent partial data
   loss on the way in is the same class of problem as the silent omission on the way out that has just been
   fixed. Then the fuller configuration surface and tunable store size: mostly easy at low risk, and together
   they remove the "the operator has no controls and no warning" problem.
3. **Documentation tests** — cheap, and they stop the rest of this set drifting.
4. **Blockers 4, 5** — condensing, compaction and index retention, so storage is bounded rather than merely
   slowed.
5. **Typed `double` and date values** — needs the anchor-encoding decision above settled first. `long` and
   `boolean` are already done.
6. **Blocker 6** — a compaction path independent of source streams.
7. **Stream provenance on mutations** (`streamId`/`eventId`) — easy, low risk, and the gate to extraction and
   the thin-graph model. Worth doing early even if the follow-on work is not scheduled, because retrofitting
   provenance onto an already-populated graph means a rebuild.
8. Language and analytics features, driven by what users actually ask for.
9. **Snapshot fan-out, then partitioning** — only once correctness is settled. Partitioning is the
   largest item here and the only route to a graph bigger than one node's disk. See
   [the architectural note](#architectural-note-how-far-up-the-plan-b-stack-should-graph-db-sit) before
   starting: the merge-based write path is the expensive prerequisite, and the one thing genuinely awkward to
   retrofit once there is production data.

## Next

- [README.md](README.md) — the blockers in their user-facing form
- [09-gql-and-neo4j.md](09-gql-and-neo4j.md) — how the gaps compare with Neo4j and GQL

### Further reading

[09-gql-and-neo4j.md](09-gql-and-neo4j.md) puts these gaps in the context of ISO GQL and Neo4j. The
earlier feature roadmap and pre-production review that produced several entries above have been retired
to git history — see [13-developer-guide.md](13-developer-guide.md#design-history). Their findings were
either fixed, captured on this page, or recorded as behavioural caveats in
[06-language-reference.md](06-language-reference.md) and [09-gql-and-neo4j.md](09-gql-and-neo4j.md).
