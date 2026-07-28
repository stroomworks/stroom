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
| 1 | **A configuration surface** — a `GraphDbConfig` covering store size, retention defaults and the traversal guardrails | Today an administrator has no controls at all. Every limit is a `private static final` | Medium | Low |
| 2 | **Tunable store size** — stop passing no size override, expose it per document or globally | The fixed 10 GiB cap is the hardest ceiling in the system, and unmovable | Easy | Low |
| 3 | **Loud ingest failures** — a strict mode that fails a stream rather than skipping records, plus schema validation | Silent partial data loss is the most dangerous current behaviour | Medium | Low |
| 4 | **Version condensing and compaction** — merge redundant identical versions, and compact in place | Storage grows monotonically even with retention on. Plan B performs both as scheduled maintenance and is a working template; Graph DB does neither | Medium | Medium |
| 5 | **Retention for the property index** — include it and the property-key table in the sweep | The index grows without bound regardless of retention | Medium | Medium |
| 6 | **Compaction without source streams** — an in-place rebuild that does not depend on reprocessing | Rebuild silently stops being possible once source streams age off | Hard | High |
| 7 | **A `GraphDb` node resolver** — teach Stroom's node-resolution hook about graph documents so every graph query routes to one designated node | In a cluster a graph fragments silently: each node holds only what it processed and each query returns only that. The routing hook is already generic; only the resolver is Plan-B-specific | Medium | Low — additive, and the seam exists |
| 8 | **Ship the XSD** as an XMLSchema document so a `SchemaFilter` can validate in-pipeline | It exists only as a test resource today | Easy | Low |

## Scaling and clustering

Graph DB is single-node: it uses Plan B's storage primitives but none of its clustering
([02-architecture.md](02-architecture.md#graph-db-is-single-node)). The operational consequences and the
workarounds available today are in
[11-operations.md](11-operations.md#scaling-and-clustering); this is the work that would remove them.

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Tunable store size** (also blocker 2) | The only change that raises the per-graph ceiling. `GraphStores` passes no size override, so every graph takes the default; the parameter already exists | Easy | Low |
| **A `GraphDb` node resolver** (also blocker 7) | Fixes silent fragmentation properly rather than by operational convention | Medium | Low |
| **Condense and compact** (also blocker 4) | Reclaims space from redundant versions, using Plan B's implementations as a template | Medium | Medium |
| **Adopt Plan B's snapshot model** — writer nodes plus read-only snapshot nodes with file transfer between them | Read scaling and query locality across a cluster. All the machinery exists in Plan B to copy | Hard | Medium |
| **Genuine partitioning** — spread one logical graph across nodes by key | The only route to a graph larger than one node's disk. Neither Graph DB nor Plan B has this | Hard | High — traversal across a partition boundary is the hard part |

Two things worth being clear about when planning this:

**Snapshots are not a capacity mechanism.** Plan B's model gives read scaling and locality, but a snapshot
is a whole copy of one store rather than a partition of it. Adopting it in full would still leave the
per-graph size ceiling exactly where it is.

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
2. **The write models are opposite.** Plan B ingests by *build-then-merge*: a pipeline writes a local
   temporary LMDB fragment, which is zipped, transferred to the writer node, and folded in by
   `MergeProcessor` calling `shard.merge(path)`. The Graph Filter instead writes **directly** into the
   target environment, one record at a time.

That second point is the root cause of the fragmentation described in
[02-architecture.md](02-architecture.md#graph-db-is-single-node): Graph DB fragments in a cluster precisely
*because* its writes go straight to local storage. Plan B does not, because a fragment is a transportable
artefact that can be produced anywhere and merged centrally.

#### The obstacle that turns out not to be one

The expected blocker was UID remapping. Interning assigns UIDs from a **local sequential counter**, so two
independently-built fragments will assign the same UID to different node ids — and every Graph DB key embeds
UIDs. A byte-level merge would silently corrupt the store.

**Plan B already solves this**, and the idiom is worth knowing before anyone re-derives it. From
`SessionDb.merge`: where a key embeds a lookup, decode it to the logical key through the *source* store's
serde and re-encode through the *target* store's serde; where it does not, copy the bytes directly. `MetricDb.merge`
does the same, with a `validateSchema` guard against merging incompatible stores.

So the deep problem is a solved pattern. Applying it across ten tables is work, not research. The temporal
model merges naturally too: versions are distinct `validFrom`-suffixed keys, so a merge is a union needing no
reconciliation.

#### Recommended order

Directionally yes — but the sequencing matters more than the destination, because the valuable fixes do not
require any of it:

1. **Tunable store size and a `GraphDb` node resolver.** Neither needs `Shard`. Together they lift the hard
   ceiling and remove silent fragmentation.
2. **`condense` and `compact` on `GraphStores`**, using Plan B's implementations as a template. Note these
   are *separable* from clustering — bounded storage does not require the merge rewrite.
3. **The merge-based write path**, and only if multi-node ingest is a firm requirement. This is the expensive
   part and the only part that genuinely needs `Shard` generalised.

**Staying single-node remains a legitimate choice.** If graphs stay modest, making the constraint explicit
and supported costs far less than adopting the shard model, and steps 1 and 2 make it respectable.

The strongest argument for going further: the merge model is what makes ingest cluster-safe, and that is
awkward to retrofit. If multi-node ingest is a firm requirement rather than a possibility, doing it before
there is production data is considerably cheaper than after.


## Data model

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Typed property values** — numbers, booleans, dates natively | Everything is a string, so ordering is lexical and comparison needs conversion. The single biggest usability gap | Hard | High — changes the storage format and needs a migration |
| **A real list type** so `collect()` returns a list | It returns a comma-joined string, which silently misleads anyone from Neo4j | Medium | Medium — needs a new value type through the whole stack |
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
| **Implement Temporal Precision**, or remove the control | It is editable and persisted but read by no code — the worst of both | Medium | Low |
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

1. **Blockers 1, 2, 3, 7, 8** — configuration, tunable size, loud ingest failures, the node resolver, ship
   the XSD. Mostly easy or medium at low risk, and together they remove the whole class of "the operator has
   no controls, and the system does not tell you when an answer is incomplete" problems. Blockers 3 and 7
   belong together: one is silent data loss on the way in, the other is silent data omission on the way out.
2. **Documentation tests** — cheap, and they stop the rest of this set drifting.
3. **Blockers 4, 5** — condensing, compaction and index retention, so storage is bounded rather than merely
   slowed.
4. **Typed property values** — the biggest usability win, and best done before there is much data to
   migrate.
5. **Blocker 6** — a compaction path independent of source streams.
6. Language and analytics features, driven by what users actually ask for.
7. **Snapshot model, then partitioning** — only once single-node behaviour is solid. Partitioning is the
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
