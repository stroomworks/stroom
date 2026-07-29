# Stroom Graph DB — documentation index

**Status:** Evaluation / proof of concept — **not production ready**. See [Production readiness](#production-readiness) below.
**Audience:** everyone.
**Scope:** the index for the Graph DB user documentation set. Canonical for the reading paths and the
production-readiness assessment; every other fact lives in one of the files listed below.
**Companion documents:** all of `docs/graphdb/`. This set is self-contained; earlier design and
implementation records live only in git history.

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`; readiness assessed 2026-07-29.*

---

Stroom's **Graph DB** is a document type (`GraphDb`) that stores data as a property graph — nodes, edges,
labels and properties — and queries it with a subset of Cypher. Every node and edge version carries a
`validFrom` timestamp, so the same query can be run against the graph as it exists now, as it existed at
some past instant, or as a diff between two instants. Data arrives through an ordinary Stroom pipeline via
the **Graph Filter** element, and results can be read as a table or drawn as an interactive graph.

---

## Production readiness

**Graph DB is not ready for production use.** It is suitable for evaluation, proof-of-concept work and
non-critical analysis on data you can afford to reload.

**The reason has changed, and the change matters.** It used to be a list of defects: a cluster gave wrong
answers, storage grew without bound, two configuration edits broke things silently, and three property types
did not exist. Every one of those is now built and tested — they are listed under
[Resolved issues](#resolved-issues). What stands in the way today is not a known defect but a **verification
gap**: almost nothing has been exercised outside a single JVM, and the code is very new.

Read this section before anything else in this set:

1. [Why the answer is still no](#why-the-answer-is-still-no) — what has not been verified.
2. [What would make it ready](#what-would-make-it-ready) — the specific steps, mostly environment work.
3. [Before you deploy](#before-you-deploy) — obligations that will cost you data or correctness if missed.
4. [Open issues](#open-issues) — what is still wrong or missing, and what it costs you.
5. [Accepted limitations](#accepted-limitations) — deliberate choices that will not change.

### Why the answer is still no

*Assessed 2026-07-29 against branch `sw-query-optimiser`.*

| Gap | Why it matters |
|---|---|
| **Nothing has run on a real cluster** | Every cluster claim rests on a simulated two-node cluster in one JVM (`TestGraphTwoNodeCluster`). It caught real bugs, but it has no HTTP hop, no node enablement, no real processing-task distribution and no killed node. The manual cases C1–C10 in [14-testing.md](14-testing.md) have never been executed. **This is the largest single gap** |
| **The acceptance protocol is unexecuted** | [14-testing.md](14-testing.md) says so in its own banner: the expectations are derived by construction rather than observed. Derived expectations are exactly the kind that can be self-consistently wrong |
| **The worked analysis examples are stale** | [08-analysis-examples.md](08-analysis-examples.md) was verified before the store format moved to version 2. Every store must be rebuilt and those results re-observed before the published examples can be trusted |
| **Concurrency is uncharacterised** | A query holds a request thread for up to the full traversal budget, and nightly compaction takes an exclusive per-graph lock. Nobody has measured how long compacting a multi-gigabyte store takes — and that number decides whether nightly is frequent enough or already too disruptive |
| **Replica divergence on conflicting writes is untested** | Two streams asserting different payloads for the same `(node, validFrom)` resolve last-merge-wins, and merge order differs per node, so two replicas can legitimately disagree. Inherited from Plan B and documented as a data-authoring constraint, but nobody has checked whether it arises in practice or how it would be noticed |
| **The code is new and has not had independent review** | The implementation and its tests were written in one sustained push over roughly two weeks, with the query path touched within days of this assessment. Deliberately breaking the implementation to confirm each test fails ("sabotage validation") was used throughout and raises confidence that the tests bite — but it is not a substitute for a second pair of eyes on `GraphStores.merge`, the anchor encoding and the store-lending refactor |

### What would make it ready

In order. Steps 1–4 are environment work rather than code, which is the honest summary of where this stands.

1. **Run C1–C10 on a genuine two-or-more-node cluster**, including the killed-node and disabled-node cases
   ([14-testing.md](14-testing.md#cluster-correctness-cases)).
2. **Reload a real dataset and re-verify** the worked examples in [08-analysis-examples.md](08-analysis-examples.md)
   against the version 2 store format.
3. **Measure compaction wall-clock** on a realistically sized store, and confirm it fits the nightly window
   without disrupting queries.
4. **Characterise concurrent query load.** The traversal budget multiplied by the request-thread pool is the
   number that matters.
5. **Independent review** of merge, the anchor encoding and the store-lending refactor.
6. **Shadow-run under production conditions**, watching `mergeFailures`, `missingStoreQueries` and the Graph
   Filter's ingest error count.

#### What it is ready for now

Evaluation, proof-of-concept work and non-critical analysis on reloadable data — and, newly, **a serious
cluster soak test**. That last one was pointless until recently, because the answers were wrong for
structural reasons; it would now tell you something.

Note also that several constraints are settled design positions rather than maturity gaps — the query
language is a narrow read-only subset, one graph can never exceed one node's disk, and equality on computed
decimals is exact. See [Accepted limitations](#accepted-limitations). Choose a deployment that suits them
rather than waiting for them to change.

### Before you deploy

| Obligation | Why |
|---|---|
| **On a cluster, set `graphdb.nodeList`** to the nodes that should hold graph data | Left empty it means "this node only" — correct on one node, wrong on a cluster, where each node would again accumulate only the streams it processed. A node named but not enabled is a hard error, not a skipped target ([02-architecture.md](02-architecture.md#how-a-graph-spans-a-cluster)) |
| **Choose Temporal Precision when you create a graph** | It is part of the key layout and cannot be changed afterwards — a store refuses to open under a different precision. It is also the largest single lever on a graph's size ([11-operations.md](11-operations.md#temporal-precision)) |
| **Set `graphdb.maxStoreSize` before you need it** | LMDB fixes an environment's size at creation, so a later increase applies only to graphs opened afterwards. An existing store must be rebuilt to grow past its original ceiling |
| **Turn retention on** if the graph is continuously fed | It is off by default, so every version is kept forever |
| **Keep the source streams** for as long as you keep the graph | A graph is rebuilt by reprocessing them. There is no other recovery path ([11-operations.md](11-operations.md#rebuild--and-its-trap)) |
| **Size the volume for two copies** of your largest graph | Nightly compaction rewrites a store beside itself before swapping it in |
| **Follow the procedures for changing `graphdb.nodeList` or `graphdb.path`** | Both are reported if you get them wrong, but neither is prevented ([11-operations.md](11-operations.md#settings-you-must-not-change-casually)) |
| **Only if you need to join a graph to other Stroom data:** set `stroom.query.optimiser.mode` to `ON` | Joins exist only in the optimising compiler, and the default is `OFF`, so a query containing one fails to parse. Ordinary graph queries need nothing — they do not go through that compiler ([05-querying.md](05-querying.md#does-graph-db-need-the-query-optimiser)) |

### Open issues

#### Operational

| Issue | Consequence |
|---|---|
| **A node holding only *part* of a graph cannot be detected** | A node holding *nothing* is reported, and both risky settings now warn. But a partially-populated node answers confidently and wrongly, and nothing can tell. This is why the add-a-node and change-path procedures matter |
| **Query caps abort work in flight** — 30 s traversal budget, 200,000 path-states *per anchor*, 1,000,000 accumulated rows, 50 variable-length hops | A legitimate but broad query fails rather than running slowly. All are configurable ([10-limits.md](10-limits.md#query-and-traversal-limits)), though the better answer to a query that trips one is usually a tighter pattern |
| **Queries execute synchronously on the calling thread** | Deliberate — the engine is an in-memory call over a single LMDB read transaction with no shard fan-out — but a slow query occupies a request thread for up to the full budget, and nightly compaction excludes queries on its graph outright. What that costs under load is [not yet measured](#why-the-answer-is-still-no) |

Detail: [10-limits.md](10-limits.md), [11-operations.md](11-operations.md).

#### Data safety

| Issue | Consequence |
|---|---|
| **Bad records are skipped by default** — logged at `ERROR` and dropped, and the stream carries on | Partial data loss is quiet. Set the Graph Filter's `strict` property to fail the stream instead. It defaults to off because that is the less surprising behaviour for a feed, not because it is the safer one ([03-ingest.md](03-ingest.md)) |

#### Correctness surprises

| Issue | Consequence |
|---|---|
| **`collect()` is unavailable** | Rejected at compile time rather than returning a comma-joined string, which was silently wrong for anyone assuming Cypher list semantics. Use `RETURN DISTINCT` or aggregate with `count`. Held back deliberately: a list value type touches the sealed `Val` hierarchy shared across the product ([12a-list-value-type.md](12a-list-value-type.md)) |
| **Equality on decimals is exact** | As it is everywhere in Stroom. A value *computed* before ingest — XSLT arithmetic yielding `0.30000000000000004` — will not match a query for `0.3`, and returns no rows rather than an error. An explicit `approxEquals` is tracked in [12-future-work.md](12-future-work.md#approxequals--why-a-function-rather-than-a-looser-) |
| **Shortest path runs in the browser over the loaded subgraph only** | A genuinely shorter path through nodes not currently on the canvas will not be found. There is no `shortestPath()` in the query language |

Detail: [06-language-reference.md](06-language-reference.md), [03-ingest.md](03-ingest.md),
[07-functions.md](07-functions.md).

#### Expressiveness

The query language is a deliberately narrow read-only subset. There are **no writes** (`SET`, `CREATE`,
`DELETE`, `MERGE` are not in the grammar), no path variables or server-side path finding, no
relationship-type alternation (`[:A|B]`), and a query is limited to one `MATCH` plus at most one
`OPTIONAL MATCH` or one `WITH`. Many real-world graph queries cannot yet be expressed, and queries ported
from Neo4j will usually need rewriting. This is the largest remaining category and the one users notice most.

Detail: [06-language-reference.md](06-language-reference.md),
[09-gql-and-neo4j.md](09-gql-and-neo4j.md).

### Accepted limitations

Deliberate, and not on anyone's list to change. They are here because each will surprise someone.

| Limitation | Why it is accepted |
|---|---|
| **A graph is replicated whole, never partitioned** | A traversal crosses whatever boundary you draw, so partitioning would break correctness where it preserves it for an index. Adding nodes makes a graph more available, never larger — one graph can never exceed one node's disk |
| **No snapshots** | A node either holds graph data or is routed away from. Snapshots would buy read locality, not capacity |
| **Rebuild reprocesses source streams** | Graph data is treated as reproducible from its sources, so source-stream retention *is* a graph's recovery plan. Compaction reclaims free pages; it cannot reconstruct data |
| **Variable-length paths use node uniqueness, not relationship uniqueness** | A path may never revisit a node, so Graph DB returns **fewer paths than Cypher** and says nothing about it. `(a)-[:R*1..3]->(x)` will not return `a→b→a→c`, which Neo4j does. Node uniqueness bounds a path at N nodes; relationship uniqueness is combinatorial in a dense graph and would trip the path-state ceiling far more often. It cannot be detected at runtime, so a query ported from Neo4j must be re-checked ([06-language-reference.md](06-language-reference.md#variable-length-hops)) |
| **Pipelines resolve a graph by UUID, queries by name** | A query fails visibly the moment you run it; a pipeline is long-lived configuration that would have failed silently on the next stream |

### Where the rest is tracked

Everything above, plus the work nobody has asked for yet, is in [12-future-work.md](12-future-work.md).
[epoch0-development-plan.md](epoch0-development-plan.md) is the record of the programme that cleared the
original blockers, kept because the reasoning behind each decision constrains later changes.

---

## Resolved issues

Everything below **was** a production blocker and is not one now. It is recorded because several of the fixes
changed how Graph DB must be deployed or operated — those consequences are in
[Before you deploy](#before-you-deploy) — and because knowing what was wrong explains why some things are
shaped as they are.

| Was | Now |
|---|---|
| **Graph DB was only correct on one node.** Ingest wrote into the live local store, so each node held only the streams it happened to process and every query silently returned a partial answer | Ingest writes a self-contained **fragment**, shipped on stream completion to every node in `graphdb.nodeList` and merged into that node's store. A traversal can follow an edge from data ingested by one node into data ingested by another — the case query fan-out could never have reconstructed ([02-architecture.md](02-architecture.md#how-a-graph-spans-a-cluster)) |
| **Nothing recorded a store's on-disk format**, so a build with a changed key layout read old bytes as new ones and returned wrong answers silently | The store carries a format stamp and refuses to open, or to accept a merge, on a mismatch. There is deliberately no migration path: wipe and rebuild |
| **Adding a node to `graphdb.nodeList` left it empty, permanently** | `POST /api/graphDb/v1/<uuid>/backfill` copies a graph from a node that holds it to every configured node. Part of the documented add-a-node procedure |
| **Changing `graphdb.path` silently provisioned empty graphs** | A startup check reports at ERROR, naming both paths and the graphs left behind, and repeats until they are dealt with |
| **A query against a graph this node holds nothing for answered empty, silently** | It logs an error and increments a `missingStoreQueries` metric |
| **There was almost no configuration surface** | `graphdb` covers the data path, the node list, the store size and all five traversal guardrails. Defaults match the previously hard-coded values |
| **Temporal Precision was persisted, editable, and read by nothing** | It selects the `validFrom` encoding, from 6 bytes per key down to 2, and is enforced — a store refuses to open under a different precision |
| **Redundant versions were never condensed** — a week of unchanged data cost seven versions of everything, and retention could not touch them | Runs of consecutive identical node and edge versions collapse to the earliest of each run, unconditionally, because that changes no answer at any instant |
| **A store never shrank**, because LMDB reuses freed pages rather than returning them | A nightly `Graph DB Compaction` job rewrites each store that has free pages to reclaim. Separate from maintenance and far less frequent, because it excludes queries on a graph while it rewrites it ([11-operations.md](11-operations.md#retention-and-maintenance)) |
| **Retention skipped the property index**, which is why storage grew even with retention on | The sweep re-derives the index from the surviving versions, and reclaims the interned identifiers no surviving anchor uses |
| **Property values were always `STRING`**, and `double`/`dateTime` could not be typed at all | All five types — `string`, `long`, `double`, `boolean`, `dateTime` — are available. Numbers are indexed by value rather than rendered text, so `42`, `42.0` and `42.00` agree, as does every spelling of one instant ([03-ingest.md](03-ingest.md#property-value-types)) |
| **A misspelled element contributed nothing and raised nothing** | Unrecognised elements are reported — the quietest way to lose data there is, closed |
| **The XSD existed only as a test resource** | Shipped and retrievable from a running Stroom (`GET /api/graphDb/v1/mutationSchema`). Note there is no prebuilt content pack, and your translation must emit `xsi:schemaLocation` or `SchemaFilter` rejects the document outright ([03-ingest.md](03-ingest.md#validating-against-the-schema)) |
| **The Graph Filter resolved its target graph by name**, so a rename broke a pipeline | It resolves by UUID |

> **One correction worth keeping.** An earlier version of this page said property ordering was lexical, so that
> `"10"` sorted before `"9"`. It never was — Stroom's string comparator is numeric-first. What declaring a type
> fixes is the value's type on read-back, not its sort order.

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
