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

### Operational — the operator has no controls

There is **no configuration surface for Graph DB at all**: no `GraphDbConfig`, no `AppConfig` entries.
Every limit below is a `private static final` constant in the source with only a test-seam constructor, so
none of them can be tuned by an administrator, an environment variable, or the UI.

| Blocker | Consequence |
|---|---|
| **Store size is fixed at 10 GiB per graph** and cannot be changed | A graph that outgrows it fails with `MDB_MAP_FULL`. The only remedies are splitting data across several `GraphDb` documents or deleting data |
| **Retention is off by default** — every version is kept forever | Combined with the fixed size cap, any sustained feed will eventually fill the store. Retention must be switched on deliberately, per document |
| **Query caps abort work in flight** — 30 s traversal budget, 200,000 path-states *per anchor*, 1,000,000 accumulated rows, 50 maximum variable-length hops | A legitimate but broad query fails rather than running slowly, and nobody can raise the ceiling |
| **Queries execute synchronously on the calling thread** | This is deliberate (the engine is an in-memory call over a single LMDB read transaction with no shard fan-out), but it means a slow query occupies a request thread for up to the full 30 s budget. Concurrency behaviour under load has not been characterised |

Detail: [10-limits.md](10-limits.md), [11-operations.md](11-operations.md).

### Data safety

| Blocker | Consequence |
|---|---|
| **Bad records are skipped, not failed** — a malformed record is logged at `ERROR` and dropped, and the stream carries on | Partial data loss is quiet. A graph can look healthy while silently missing a subset of its input |
| **There is no schema validation at ingest** — the `graph-mutation:1` XSD exists only as a test resource, and the Graph Filter dispatches on lower-cased element local names | A misspelled element contributes nothing and raises nothing. Validate offline before you trust a translation |
| **`rebuild()` is the only compaction backstop, and it reprocesses source streams** | If those streams have been aged off by a retention policy, the graph cannot be rebuilt. Storage growth then has no remedy short of deleting the graph |
| **Redundant versions are never condensed**, and the property-value index does not participate in retention | Storage grows monotonically even under a retention policy |
| **The Graph Filter resolves its target graph by name** | Two graphs sharing a name is a fatal ingest error, and renaming a graph silently breaks every pipeline pointing at it |

Detail: [03-ingest.md](03-ingest.md), [02-architecture.md](02-architecture.md),
[11-operations.md](11-operations.md).

### Correctness surprises

| Blocker | Consequence |
|---|---|
| **`collect()` returns a comma-joined string, not a list** | Silently wrong for anyone assuming Cypher list semantics. You cannot index it or take its size as a list |
| **Property values are strings only** | Comparison and ordering are lexical unless values were encoded for it at ingest time. `"10" < "9"` |
| **Shortest path runs in the browser over the loaded subgraph only** | A genuinely shorter path through nodes not currently on the canvas will not be found. There is no `shortestPath()` in the query language |
| **Temporal Precision is inert** — the Settings control is editable and persisted, but no implementation code reads it | Setting it has no effect of any kind |

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

At minimum: a real configuration surface (store size, retention defaults and the traversal guardrails),
loud rather than silent ingest failures with schema validation, a compaction path that does not depend on
source streams still existing, and native typed property values. These are tracked in
[12-future-work.md](12-future-work.md).

> **Not assessed:** clustering and high-availability behaviour. This documentation was written from
> single-node operation only. Nothing here should be read as a claim in either direction about how Graph DB
> behaves in a clustered Stroom deployment.

---

## The documentation set

| File | What it covers | Audience |
|---|---|---|
| [01-introduction.md](01-introduction.md) | What Graph DB is, what problem it solves, when not to use it, glossary | Analysts, evaluators |
| [02-architecture.md](02-architecture.md) | How data is stored, the temporal model, what a graph physically is | Analysts, administrators |
| [03-ingest.md](03-ingest.md) | The `graph-mutation:1` format, the Graph Filter, loading your first graph | Pipeline authors |
| [04-event-logging-xslt.md](04-event-logging-xslt.md) | Converting Stroom event-logging XML into graph mutations, with a worked XSLT | Translation authors |
| [05-querying.md](05-querying.md) | Running queries: the Explore tab (graph) and the Data tab (table) | Analysts |
| [06-language-reference.md](06-language-reference.md) | The Cypher subset, clause by clause, and everything it rejects | Analysts |
| [07-functions.md](07-functions.md) | Every aggregate and scalar function | Analysts |
| [08-analysis-examples.md](08-analysis-examples.md) | Worked analyses over event data and the POLE dataset | Analysts |
| [09-gql-and-neo4j.md](09-gql-and-neo4j.md) | Comparison with ISO GQL and with Neo4j Cypher | Evaluators, migrators |
| [10-limits.md](10-limits.md) | Every limit, its exact value, and how to stay within it | Analysts, administrators |
| [11-operations.md](11-operations.md) | Storage, sizing, retention, rebuild, backup, permissions | Administrators |
| [12-future-work.md](12-future-work.md) | Roadmap, with difficulty and risk per item | Stakeholders |
| [13-developer-guide.md](13-developer-guide.md) | Code structure and how to extend Graph DB | Developers |
| [14-testing.md](14-testing.md) | An acceptance protocol: dataset, cases and expected results | Developers, testers |

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

## Relationship to the engineering documents

The files in `docs/` outside this directory are **engineering records**, not user documentation: design
proposals, implementation plans, review reports and test protocols written during development. They remain
accurate about intent and design rationale, but they describe work in progress and are pinned to the branch
and date they were written on. Where this set and an engineering record disagree, this set is correct.

Documents whose content has been absorbed into this set have been deleted; they remain in git history.
[13-developer-guide.md](13-developer-guide.md#design-history) explains how to retrieve a specific one.
