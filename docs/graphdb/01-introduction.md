# Introduction to Stroom Graph DB

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
**Audience:** analysts and evaluators. No prior graph-database experience assumed.
**Scope:** what Graph DB is, what it is good and bad at, and the vocabulary used throughout this set.
Canonical for the concept glossary.
**Companion documents:** [02-architecture.md](02-architecture.md) for how it works internally,
[03-ingest.md](03-ingest.md) for loading data, [06-language-reference.md](06-language-reference.md) for
querying.

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

## What it is

A **Graph DB** is a Stroom document type that stores data as a **property graph** and queries it with a
subset of **Cypher**, the query language used by Neo4j and standardised (in part) as ISO GQL.

A property graph has two kinds of thing in it:

- **Nodes** — the entities. A person, a device, a file, a crime. Each node has an **id**, zero or more
  **labels** classifying it (`Person`, `Officer`), and any number of **properties** (`surname: 'Powell'`).
- **Edges** — the relationships between them. Each edge has a **type** (`PARTY_TO`, `KNOWS`), a direction,
  a source node, a destination node, and optionally its own properties.

That is the whole data model. There is no schema to declare up front: labels, edge types and property keys
come into existence as you load data that uses them.

What makes Stroom's implementation distinctive is that **every node and edge version carries a `validFrom`
timestamp**. The graph is not a snapshot of "now" that you overwrite — it is the accumulation of every
version of every entity, and a query chooses which instant to look at.

## The problem it solves

Stroom is already good at questions of the form *"find the records matching these criteria"*. A Lucene
index answers those quickly, and StroomQL can join across sources.

It is not good at questions of the form *"what is connected to this, and what is connected to that"*.
Consider:

> Anne is not involved in any crime. But how many people does she know who are?

In a relational or index-based model this is a self-join whose depth you must know in advance, written out
by hand, once per hop. Two hops is awkward. Three is unpleasant. A variable number of hops — "anyone
reachable within three relationships" — is not really expressible at all.

In a graph model it is one line:

```cypher
MATCH (p:Person {surname: 'Freeman'})-[:KNOWS*1..2]->(f:Person) RETURN f.surname
```

The traversal is the query. That is the entire reason graph databases exist, and it is the main reason to
reach for this feature.

## Why this is an improvement

Four things, stated concretely rather than as claims.

**1. Relationship traversal is a first-class operation.** Multi-hop patterns, both directions, and bounded
variable-length hops are written directly rather than assembled from joins. See
[06-language-reference.md](06-language-reference.md).

**2. Time is built into the storage, not bolted on.** Every key in the store ends with a `validFrom`
timestamp, and deletion is recorded as a tombstone version rather than a physical removal. Consequences:

- The same query can be run `AS OF` any past instant and returns the graph as it was then.
- No separate history table, no "effective from / effective to" columns to maintain, no as-at join.
- Two instants can be compared directly with `DIFF`, which classifies every element as added, removed,
  modified or unchanged.

Most graph databases have no equivalent — history is something you model yourself, usually badly. The
detail is in [02-architecture.md](02-architecture.md).

**3. One result, two views.** A query's rows can be read as a table on the **Data** tab, or drawn as an
interactive graph on the **Explore** tab. `RETURN GRAPH` produces a node/edge element table that the
Cytoscape view renders directly.

**4. It is native to Stroom.** Data arrives through an ordinary feed and pipeline via the **Graph Filter**
element — the same ingest machinery as everything else. Graph documents live in the explorer tree with the
usual document permissions, and queries run from the document's own tabs, a Query document, a dashboard, or
the API.

## When not to use it

Being honest about this saves more time than any feature list.

**Do not use Graph DB if:**

- **You need production reliability today.** See the
  [blockers](README.md#production-readiness--known-blockers). This is the overriding one.
- **Your data will exceed one node's disk in one graph.** `graphdb.maxStoreSize` raises the per-graph ceiling
  from its 10 GiB default, but it is fixed when a store is created and every node holds a whole replica, so a
  graph can never be larger than the smallest node holding it ([10-limits.md](10-limits.md)). You can split
  across several graphs, but no query spans two of them.
- **You need one graph bigger than one node's disk.** Graph DB now works correctly on a cluster — every node
  named in `graphdb.nodeList` holds a full replica and queries are routed to one of them
  ([02-architecture.md](02-architecture.md#how-a-graph-spans-a-cluster)) — but replication is not
  partitioning, so it buys correctness and not capacity. A Lucene index can shard and a graph cannot; see
  [why below](#a-note-on-scale-graph-db-versus-a-lucene-index).
- **You need the query language to write data.** It is read-only; there is no `SET`, `CREATE`, `DELETE` or
  `MERGE`. All data arrives through pipelines.
- **Your questions are lookups, not traversals.** "Find events where user = X and time in range" is a
  Lucene index question and will be faster and far more scalable there. A graph earns its cost only when you
  are following relationships.
- **You need spatial or path-finding analytics.** No `point()`, no `distance()`, no server-side
  shortest-path or centrality. The Explore tab offers some of this client-side over whatever is currently
  drawn, which is not the same thing.
- **You need numeric or date comparison without preparation.** Property values are strings; ordering is
  lexical unless you encoded values for it at ingest ([03-ingest.md](03-ingest.md)).

**Graph DB is a good fit when** the questions are about connections, the dataset is modest, the analysis is
exploratory, and the ability to ask "what did this look like last month" is valuable.

### A note on scale: Graph DB versus a Lucene index

Because both store event-derived data, it is worth being explicit about why one scales and the other does
not — the reason is the data model, not just implementation maturity.

A **Lucene index** is embarrassingly partitionable. A document belongs to exactly one shard, so an index
splits automatically by time period and shard size, shards are placed on volumes bound to named nodes, and a
query fans out to every node holding a relevant shard and merges the results. Capacity grows by adding
volumes. Nothing ever needs to cross a shard boundary.

**Graph traversal is the opposite.** It is inherently cross-cutting: an edge can span two partitions, so a
multi-hop pattern may need to follow a path out of one partition and into another, repeatedly, mid-traversal.
That is why Graph DB has no sharding, and why adding it is genuinely hard rather than merely unfinished. It
is the same reason Neo4j's own scale-out story is largely read replicas of a whole graph rather than
sharding one.

So Graph DB's fixed size ceiling is a proof-of-concept shortcut and fixable
([12-future-work.md](12-future-work.md)), and clustered correctness is already done — but **even a mature
implementation would not give Lucene-style linear scale-out**, because replicating a whole graph is what keeps
traversals correct and that buys no capacity. If a dataset genuinely needs multi-terabyte scale, an index is the right
tool and a graph is not, however interesting the relationships in it are.

## Where it fits in Stroom

```
        source data
             │
             ▼
        ┌─────────┐      an ordinary Stroom feed
        │  Feed   │
        └────┬────┘
             │
             ▼
   ┌──────────────────────┐
   │       Pipeline       │   XSLT transforms your data into
   │   … → XSLT → Graph   │   graph-mutation:1 XML, which the
   │            Filter    │   Graph Filter writes into the graph
   └──────────┬───────────┘
              │
              ▼
      ┌────────────────┐
      │  GraphDb doc   │   one LMDB store per document
      └───┬────────┬───┘
          │        │
     Explore     Data        Cypher in, graph or table out
     (graph)    (table)
```

Three pieces have to exist and agree:

1. A **GraphDb document** — the store itself. Its **name must be unique**, because the Graph Filter finds
   it by name.
2. A **feed** to receive raw data.
3. A **pipeline** ending in a **Graph Filter** whose `graphDb` property points at the document.

[03-ingest.md](03-ingest.md) walks through building all three.

## Glossary

Terms are used consistently throughout this documentation set with these meanings.

| Term | Meaning |
|---|---|
| **Node** | An entity in the graph. Has an external **id**, zero or more labels, and properties |
| **Edge** | A directed relationship between two nodes. Has a **type**, a source, a destination, and optionally properties |
| **Label** | A classification on a node, e.g. `Person`. A node may carry several. Roughly "what kind of thing this is" |
| **Edge type** | The equivalent for edges, e.g. `KNOWS`. An edge has exactly **one** type |
| **Property** | A named value on a node or edge. **String-valued only** in the current version |
| **`validFrom`** | The instant from which a version of a node or edge is effective. Required on every mutation; never defaulted |
| **Version** | One `validFrom`-stamped snapshot of a node or edge. Loading the same id again creates a new version rather than overwriting |
| **Tombstone** | The version written by a delete. Marks the entity absent from that instant onward; earlier versions remain visible to earlier queries |
| **Anchor** | The node pattern a query starts from. Its labels and property predicates decide how efficiently the query can find its starting points — see [10-limits.md](10-limits.md) |
| **Hop** | One step across an edge in a pattern. `(a)-[:R]->(b)` is one hop |
| **Variable-length hop** | A hop repeated a bounded number of times, written `*min..max`. The maximum is mandatory |
| **Point-in-time query** | A query evaluated at one instant, with `AS OF`. With no temporal clause, that instant is "now" |
| **`DIFF`** | A query comparing two instants, classifying each element as `ADDED`, `REMOVED`, `MODIFIED` or `UNCHANGED` |
| **Element table** | The fixed six-column result shape produced by `RETURN GRAPH`: `kind`, `id`, `labels`, `source`, `target`, `properties`. What the graph view renders |
| **Graph Filter** | The pipeline element that writes `graph-mutation:1` XML into a graph |
| **`graph-mutation:1`** | The XML vocabulary describing node and edge changes. The only way data enters a graph |

## Next

- To load data: [03-ingest.md](03-ingest.md)
- To understand the storage and temporal model: [02-architecture.md](02-architecture.md)
- To write queries: [06-language-reference.md](06-language-reference.md)

### Further reading

[13-developer-guide.md](13-developer-guide.md) covers the code structure and the design decisions behind
it. The original design proposals and implementation plans have been retired; they remain in the
repository's git history.
