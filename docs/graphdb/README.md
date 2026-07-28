# Stroom Graph DB — documentation index

**Status:** Evaluation / proof of concept — **not production ready**. See [Production readiness](#production-readiness--known-blockers) below.
**Audience:** everyone.
**Scope:** the index for the Graph DB user documentation set. Canonical for the reading paths and the
production-readiness assessment; every other fact lives in one of the files listed below.
**Companion documents:** all of `docs/graphdb/`. This set is self-contained; earlier design and
implementation records live only in git history.

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

Stroom's **Graph DB** is a document type (`GraphDb`) that stores data as a property graph — nodes, edges,
labels and properties — and queries it with a subset of Cypher. Every node and edge version carries a
`validFrom` timestamp, so the same query can be run against the graph as it exists now, as it existed at
some past instant, or as a diff between two instants. Data arrives through an ordinary Stroom pipeline via
the **Graph Filter** element, and results can be read as a table or drawn as an interactive graph.

---

## Production readiness — known blockers

**Graph DB is not ready for production use.** It is suitable for evaluation, proof-of-concept work and
non-critical analysis on data you can afford to reload. The issues below are not a wishlist of missing
niceties — they are the specific reasons a production deployment would be unwise today. Each links to the
file that covers it in detail.

Read this section before anything else in this set.

### Correctness across a cluster — resolved

**This was the set's first and worst blocker and it has been fixed.** It is described here because the
change alters how Graph DB must be deployed.

Ingest no longer writes into the live store. The Graph Filter writes each stream's mutations into a
self-contained **fragment** — a complete but private graph store — which on stream completion is shipped to
every node named in `graphdb.nodeList` and merged into that node's authoritative store. Every listed node
therefore holds the whole graph, and graph queries are routed to one of them. A traversal can now follow an
edge from data ingested by one node into data ingested by another, which is the case no amount of query
fan-out could ever have reconstructed.

**Deployment requirement:** on a cluster you **must** set `graphdb.nodeList` to the nodes that should hold
graph data. Left empty it means "this node only", which is correct on a single node and wrong on a cluster —
each node would again accumulate only the fragments it processed. A node named in the list but not enabled
is a hard error rather than a skipped target, because skipping it would leave that node's graph permanently
short while it carried on answering queries.

Two things are deliberately **not** done: one graph is not partitioned across nodes (replication is full),
and there are no snapshots, so a node that holds no graph data cannot serve graph queries at all — it is
routed away from instead.

Detail: [02-architecture.md](02-architecture.md#how-a-graph-spans-a-cluster),
[11-operations.md](11-operations.md#scaling-and-clustering).

### Operational — the operator has few controls

Graph DB now has a real configuration surface under `graphdb`: where data lives, which nodes hold it, the
maximum store size, and all five traversal guardrails. The defaults are unchanged from when they were
hard-coded, so an existing deployment behaves identically until something is changed.

| Blocker | Consequence |
|---|---|
| **Two settings can be changed in ways that silently produce wrong answers** | Adding a node to `graphdb.nodeList` does not backfill it *automatically*, and queries route to the first node in the list — so adding one at the front makes every answer partial. **Mitigated:** backfill is now a supported operation, so the add-a-node procedure has a real step rather than a hand-copy. Changing `graphdb.path` provisions empty graphs rather than failing, and is not mitigated. Neither is prevented, but a query against a graph the node holds nothing for logs an error and increments `missingStoreQueries`; a node holding only *part* of a graph still cannot be detected, which is why both have a procedure in [11-operations.md](11-operations.md#settings-you-must-not-change-casually). Temporal Precision, by contrast, *is* enforced — the store refuses to open |
| **Store size still needs a restart to change, and only for new graphs** | LMDB fixes an environment's size at creation, so raising `graphdb.maxStoreSize` applies to graphs opened afterwards. An existing store must be rebuilt to grow past its original ceiling |
| **Retention is off by default** — every version is kept forever | Combined with the fixed size cap, any sustained feed will eventually fill the store. Retention must be switched on deliberately, per document |
| **Query caps abort work in flight** — 30 s traversal budget, 200,000 path-states *per anchor*, 1,000,000 accumulated rows, 50 maximum variable-length hops | A legitimate but broad query fails rather than running slowly. **Mitigated:** every one of these is now configurable ([10-limits.md](10-limits.md#query-and-traversal-limits)), though the better answer to a query that trips one is usually a tighter pattern |
| **Queries execute synchronously on the calling thread** | This is deliberate (the engine is an in-memory call over a single LMDB read transaction with no shard fan-out), but it means a slow query occupies a request thread for up to the full 30 s budget. Concurrency behaviour under load has not been characterised |

Detail: [10-limits.md](10-limits.md), [11-operations.md](11-operations.md).

### Data safety

| Blocker | Consequence |
|---|---|
| **Bad records are skipped by default** — a malformed record is logged at `ERROR` and dropped, and the stream carries on | Partial data loss is quiet. **Mitigated:** set the Graph Filter's `strict` property to fail the stream instead. It defaults to off because that is the less surprising behaviour for a feed, not because it is the safer one |
| **`rebuild()` is the only compaction backstop, and it reprocesses source streams** | If those streams have been aged off by a retention policy, the graph cannot be rebuilt. **Accepted, not pending:** graph data is treated as reproducible from its sources, so source-stream retention is part of a graph's recovery plan ([11-operations.md](11-operations.md#rebuild--and-its-trap)) |
| ~~**Redundant versions are never condensed**~~ — **fixed.** Runs of consecutive identical node and edge versions are now collapsed to the earliest of each run, unconditionally, because doing so changes no answer at any instant | This is what bounds a graph reloaded on a schedule: a week of unchanged data used to cost seven versions of everything, and retention could not touch them. Every table is now bounded. What remains is in-place file compaction: a store stops growing but does not shrink, because LMDB reuses freed pages rather than returning them ([12-future-work.md](12-future-work.md)) |
| ~~**The Graph Filter resolves its target graph by name**~~ — **fixed.** It resolves by UUID, so a rename no longer breaks a pipeline and two graphs sharing a name no longer matter | Queries still resolve by name, deliberately: a query fails visibly at the moment you run it, whereas a pipeline is long-lived configuration that would have failed silently on the next stream |

Three data-safety gaps have been closed:

- **The store records its own on-disk format** and refuses to open — or to accept a merge — if that does not
  match what the running build expects. Previously nothing recorded the format, so a build whose key layout had
  changed would read old bytes as though they were new ones and return wrong answers silently. The remedy on a
  mismatch is to wipe and rebuild; there is deliberately no migration path, because graph data is treated as
  reproducible.
- **The `graph-mutation:1` XSD is shipped** and retrievable from a running Stroom
  (`GET /api/graphDb/v1/mutationSchema`), so a `SchemaFilter` can validate in-pipeline. It previously existed
  only as a test resource. Note there is no prebuilt content pack — the XMLSchema document is created by hand —
  and your translation must emit `xsi:schemaLocation` or `SchemaFilter` rejects the document outright
  ([03-ingest.md](03-ingest.md#validating-against-the-schema)).
- **Unrecognised elements are reported.** A misspelled element used to contribute nothing and raise nothing,
  which is the quietest way to lose data there is.

Detail: [03-ingest.md](03-ingest.md), [02-architecture.md](02-architecture.md),
[11-operations.md](11-operations.md).

### Correctness surprises

| Blocker | Consequence |
|---|---|
| **`collect()` is unavailable** | It is rejected at compile time rather than returning a comma-joined string, which was silently wrong for anyone assuming Cypher list semantics. Use `RETURN DISTINCT` or aggregate with `count`. Held back deliberately because a list value type touches the sealed `Val` hierarchy shared across the product — the full analysis is in [12a-list-value-type.md](12a-list-value-type.md) |
| **Property values are strings unless typed, and only `long` and `boolean` are available** | `double` and dates cannot be typed, because the equality anchor index is keyed on rendered text and `42.0` renders as `42` — a query for `42.0` would silently find nothing. Encode those as sortable text ([03-ingest.md](03-ingest.md#property-value-types)). *Correction: this table previously said ordering was lexical (`"10" < "9"`). It was not — Stroom's string comparator is numeric-first. What typing fixes is the value's type on read-back, not its sort order* |
| **Variable-length paths use node uniqueness, not relationship uniqueness** | A path may never revisit a node, so Graph DB returns **fewer paths than Cypher** and says nothing about it. `(a)-[:R*1..3]->(x)` will not return the route `a→b→a→c`, which Neo4j does — those are two distinct relationships, and only the node repeats. **This is a deliberate choice, not a defect:** node uniqueness bounds a path at N nodes, whereas relationship uniqueness is combinatorial in a dense graph and would trip the 200,000 path-state ceiling far more often. But it cannot be detected at runtime — you cannot know a query would have matched more — so a query ported from Neo4j must be re-checked rather than trusted ([06-language-reference.md](06-language-reference.md#variable-length-hops)) |
| **Shortest path runs in the browser over the loaded subgraph only** | A genuinely shorter path through nodes not currently on the canvas will not be found. There is no `shortestPath()` in the query language |
| ~~**Temporal Precision is inert**~~ — **fixed.** It now selects the `validFrom` encoding, from 6 bytes per key down to 2 | Choose the coarsest precision your questions tolerate; it is the largest lever on a graph's size. It is fixed at creation — a store refuses to open under a different precision rather than misreading its keys ([11-operations.md](11-operations.md#temporal-precision)) |

Detail: [06-language-reference.md](06-language-reference.md), [03-ingest.md](03-ingest.md),
[07-functions.md](07-functions.md).

### Expressiveness

The query language is a deliberately narrow read-only subset. There are **no writes** (`SET`, `CREATE`,
`DELETE`, `MERGE` are not in the grammar), no `SKIP`, no path variables or server-side path finding, no
relationship-type alternation (`[:A|B]`), and a query is limited to one `MATCH` plus at most one
`OPTIONAL MATCH` or one `WITH`. Many real-world graph queries cannot yet be expressed, and queries ported
from Neo4j will usually need rewriting.

Detail: [06-language-reference.md](06-language-reference.md),
[09-gql-and-neo4j.md](09-gql-and-neo4j.md).

### What would have to change

Cluster correctness, the store format stamp, strict ingest, the shipped schema and Temporal Precision are done. What remains, in
rough priority order: native typed `double` and date property values, a real `collect()` list, then a fuller
configuration surface (store size, retention defaults and the traversal guardrails) and a compaction path that
does not depend on source streams still existing.

The remaining items are limitations you can work around once you know about them, which is why they now come
after the two that could not be worked around at all. All of it is tracked in
[12-future-work.md](12-future-work.md), and the sequenced plan is in
[epoch0-development-plan.md](epoch0-development-plan.md).

---

## The documentation set

| File | What it covers | Audience |
|---|---|---|
| [01-introduction.md](01-introduction.md) | What Graph DB is, what problem it solves, when not to use it, glossary | Analysts, evaluators |
| [02-architecture.md](02-architecture.md) | How data is stored, the temporal model, what a graph physically is | Analysts, administrators |
| [03-ingest.md](03-ingest.md) | The `graph-mutation:1` format, the Graph Filter, loading your first graph | Pipeline authors |
| [04-event-logging-xslt.md](04-event-logging-xslt.md) | Converting Stroom event-logging XML into graph mutations, with a worked XSLT | Translation authors |
| [05-querying.md](05-querying.md) | Running queries: the Explore tab (graph), the Data tab (table), and joining a graph to other Stroom data | Analysts |
| [06-language-reference.md](06-language-reference.md) | The Cypher subset, clause by clause, and everything it rejects | Analysts |
| [07-functions.md](07-functions.md) | Every aggregate and scalar function | Analysts |
| [08-analysis-examples.md](08-analysis-examples.md) | Worked analyses over event data and the POLE dataset | Analysts |
| [09-gql-and-neo4j.md](09-gql-and-neo4j.md) | Comparison with ISO GQL and with Neo4j Cypher | Evaluators, migrators |
| [10-limits.md](10-limits.md) | Every limit, its exact value, and how to stay within it | Analysts, administrators |
| [11-operations.md](11-operations.md) | Storage, sizing, retention, rebuild, backup, permissions | Administrators |
| [12-future-work.md](12-future-work.md) | Roadmap, with difficulty and risk per item | Stakeholders |
| [12a-list-value-type.md](12a-list-value-type.md) | Why `collect()` is rejected, and what a list value type would take | Developers |
| [13-developer-guide.md](13-developer-guide.md) | Code structure and how to extend Graph DB | Developers |
| [14-testing.md](14-testing.md) | An acceptance protocol: dataset, cases and expected results | Developers, testers |
| [epoch0-development-plan.md](epoch0-development-plan.md) | **Plan, not description** — the programme to clear the blockers above | Developers, stakeholders |

## Reading paths

- **Evaluating it** — [01](01-introduction.md) → [09](09-gql-and-neo4j.md) → [12](12-future-work.md),
  plus the blockers above.
- **Analysing data** — [01](01-introduction.md) → [05](05-querying.md) → [06](06-language-reference.md)
  → [07](07-functions.md) → [08](08-analysis-examples.md).
- **Loading data** — [01](01-introduction.md) → [03](03-ingest.md) → [04](04-event-logging-xslt.md)
  → [10](10-limits.md).
- **Running it** — [01](01-introduction.md) → [02](02-architecture.md) → [11](11-operations.md)
  → [10](10-limits.md).
- **Extending it** — [02](02-architecture.md) → [13](13-developer-guide.md)
  → [06](06-language-reference.md) → [14](14-testing.md).
- **Fixing it** — the blockers above, then
  [epoch0-development-plan.md](epoch0-development-plan.md).

## Relationship to the engineering documents

The files in `docs/` outside this directory are **engineering records**, not user documentation: design
proposals, implementation plans, review reports and test protocols written during development. They remain
accurate about intent and design rationale, but they describe work in progress and are pinned to the branch
and date they were written on. Where this set and an engineering record disagree, this set is correct.

Documents whose content has been absorbed into this set have been deleted; they remain in git history.
[13-developer-guide.md](13-developer-guide.md#design-history) explains how to retrieve a specific one.
