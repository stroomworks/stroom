# Testing a Graph DB

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness).
**Audience:** developers changing Graph DB; anyone validating a deployment or an upgrade.
**Scope:** a repeatable acceptance protocol — a dataset, the cases to run against it, and what each should
return. Canonical for the test dataset.
**Companion documents:** [13-developer-guide.md](13-developer-guide.md) (unit-test inventory),
[06-language-reference.md](06-language-reference.md) (the surface under test),
[03-ingest.md](03-ingest.md) (loading the dataset).

*Test inventory re-verified against the module on 2026-07-29, branch `sw-query-optimiser`.*

> **These cases have not been executed as written.** The dataset and the expected results are derived by
> construction — each expectation follows deterministically from the data below — but this protocol has not
> been run end to end in its current form. Treat a failure as worth investigating, not as proof of a
> regression.

---

## Approach

Graph DB has three surfaces that can each break independently, so a useful test pass exercises all three:

1. **Ingest** — does `graph-mutation:1` XML reach the store intact? The failure mode is *silent*: bad
   records are skipped and the stream still succeeds ([03-ingest.md](03-ingest.md)).
2. **Query** — does the language return correct results, including temporally?
3. **Rejection** — do unsupported constructs fail cleanly, with a clear message rather than a wrong answer
   or a 500?

The third matters as much as the second. A deliberately narrow language is only safe if the boundary is
enforced loudly.

## The `CorpGraph` dataset

Small enough to reason about by hand, shaped to exercise every capability: a fixed multi-hop chain, a
variable-length chain, an ownership transfer that makes temporal queries meaningful, and a versioned node
property that produces a `MODIFIED` in a diff.

| Node | Label | Key property | Other |
|---|---|---|---|
| U1 | `User` | `id=U1` | `name=alice`, `department` — **versioned** |
| U2 | `User` | `id=U2` | `name=bob`, `department=Finance` |
| U3 | `User` | `id=U3` | `name=carol`, `department=Support` |
| A100 | `Account` | `number=A100` | `tier=gold` |
| A200 | `Account` | `number=A200` | `tier=silver` |
| A300 | `Account` | `number=A300` | `tier=silver` |
| H1–H4 | `Host` | `ip=10.0.0.1` … `10.0.0.4` | `zone` |
| G-admin | `Group` | `name=admins` | |
| G-users | `Group` | `name=users` | |
| R-vel | `Rule` | `name=velocity` | |

| Edge | From → To | `validFrom` | Purpose |
|---|---|---|---|
| `OWNS` | U1 → A100 | 2026-01-01 | Superseded by the transfer below |
| `OWNS` | U2 → A100 | 2026-06-01 | The transfer target |
| `OWNS` | U2 → A200 | 2026-01-01 | Stable |
| `OWNS` | U3 → A300 | 2026-01-01 | Stable |
| `MEMBER_OF` | U1 → G-admin | 2026-01-01 | |
| `MEMBER_OF` | U2 → G-users | 2026-01-01 | |
| `MEMBER_OF` | U3 → G-users | 2026-01-01 | |
| `CONNECTED_TO` | H1 → H2 | 2026-01-01 | Chain, for fixed multi-hop … |
| `CONNECTED_TO` | H2 → H3 | 2026-01-01 | … and variable-length … |
| `CONNECTED_TO` | H3 → H4 | 2026-01-01 | … reachability |
| `FLAGGED_BY` | A100 → R-vel | 2026-05-01 | Falls inside the diff window |

Plus one versioned property: **`U1.department`** is `Sales` from 2026-01-01 and `Marketing` from
2026-06-01.

### Loading it

Note the `OWNS U1→A100` edge is **not** deleted — it is simply superseded by a later version of the same
edge triple. To make the transfer a genuine removal you would need an `<edge-delete>`; as written, both
edges remain and the account has two owners after the transfer date. Use the delete if you want to test
`REMOVED` rather than `ADDED`.

```xml
<graph xmlns="graph-mutation:1" version="1.0">
  <node id="U1" validFrom="2026-01-01T00:00:00.000Z">
    <label>User</label>
    <property name="id">U1</property>
    <property name="name">alice</property>
    <property name="department">Sales</property>
  </node>
  <node id="U1" validFrom="2026-06-01T00:00:00.000Z">
    <label>User</label>
    <property name="id">U1</property>
    <property name="name">alice</property>
    <property name="department">Marketing</property>
  </node>
  <node id="A100" validFrom="2026-01-01T00:00:00.000Z">
    <label>Account</label>
    <property name="number">A100</property>
    <property name="tier">gold</property>
  </node>
  <node id="H1" validFrom="2026-01-01T00:00:00.000Z">
    <label>Host</label>
    <property name="ip">10.0.0.1</property>
  </node>
  <edge type="OWNS" validFrom="2026-01-01T00:00:00.000Z">
    <src>U1</src><dst>A100</dst>
  </edge>
  <edge type="OWNS" validFrom="2026-06-01T00:00:00.000Z">
    <src>U2</src><dst>A100</dst>
  </edge>
  <edge type="CONNECTED_TO" validFrom="2026-01-01T00:00:00.000Z">
    <src>H1</src><dst>H2</dst>
  </edge>
</graph>
```

Abridged — the remaining nodes and edges follow the same shape. Note that the second `U1` element is a new
**version**, not an update: it restates every property, because a version replaces rather than merges.

Load it through a feed whose pipeline ends in a Graph Filter targeting `CorpGraph`. For automated tests,
load the same nodes and edges directly as engine fixtures, as `TestGraphTraversalEngine` does — much faster
and it removes the pipeline from the equation.

### Confirming the load

Success is invisible: a Graph Filter produces no output stream. So check positively rather than assuming:

- No **Error** stream for the feed, **and** a zero error count on the processed stream.
- `MATCH (n) RETURN GRAPH` returns nodes.
- `MATCH (u:User) RETURN count(u) AS n` returns **3**.

If the graph is empty and there is no error stream, suspect element names the filter did not recognise —
it dispatches on lower-cased local names and validates nothing.

## Query cases

Run from the `CorpGraph` **Data** tab, or from any surface with a leading `from "CorpGraph"` clause.

| # | Capability | Query | Expect |
|---|---|---|---|
| B1 | Anchor: label + property | `MATCH (u:User {id:'U1'}) RETURN u.id` | `U1` |
| B2 | Single hop | `MATCH (u:User {id:'U1'})-[:MEMBER_OF]->(g:Group) RETURN g.name` | `admins` |
| B3 | Fixed multi-hop | `MATCH (h:Host {ip:'10.0.0.1'})-[:CONNECTED_TO]->(x:Host)-[:CONNECTED_TO]->(y:Host) RETURN y.ip` | `10.0.0.3` |
| B4 | Variable-length | `MATCH (h:Host {ip:'10.0.0.1'})-[:CONNECTED_TO*1..3]->(x:Host) RETURN DISTINCT x.ip ORDER BY x.ip` | `10.0.0.2`, `10.0.0.3`, `10.0.0.4` |
| B5 | Incoming direction | `MATCH (g:Group {name:'users'})<-[:MEMBER_OF]-(u:User) RETURN u.id ORDER BY u.id` | `U2`, `U3` |
| B6 | `WHERE` | `MATCH (u:User)-[:OWNS]->(a:Account) WHERE u.id = 'U2' RETURN a.number ORDER BY a.number` | `A100`, `A200` |
| B7 | `DISTINCT` + `ORDER BY` + `LIMIT` | `MATCH (u:User)-[:MEMBER_OF]->(g:Group) RETURN DISTINCT g.name ORDER BY g.name LIMIT 10` | `admins`, `users` |
| B8 | Aggregation | `MATCH (u:User)-[:MEMBER_OF]->(g:Group) RETURN g.name AS grp, count(u) AS n ORDER BY grp` | `admins`:1, `users`:2 |
| B9 | Label-only anchor | `MATCH (u:User) RETURN count(u) AS n` | `3` |
| B10 | `RETURN DISTINCT` in place of `collect()` | `MATCH (u:User)-[:MEMBER_OF]->(g:Group) RETURN DISTINCT g.name AS grp, u.id AS uid ORDER BY grp, uid` | one row per membership: `admins`/`U1`, `users`/`U2`, `users`/`U3` |
| B11 | `WITH` | `MATCH (u:User)-[:OWNS]->(a:Account) WITH u.id AS uid RETURN uid` | one row per ownership |
| B12 | Missing property | `MATCH (g:Group {name:'admins'}) RETURN g.name, g.nosuch` | `admins`, *(empty)* |
| B13 | `RETURN GRAPH` | `MATCH (u:User {id:'U1'})-[:MEMBER_OF]->(g:Group) RETURN GRAPH` | 2 node rows + 1 edge row, six columns |

### Temporal cases

The transfer date (2026-06-01) is what makes these meaningful.

| # | Capability | Query | Expect |
|---|---|---|---|
| T1 | `AS OF` before | `MATCH (u:User)-[:OWNS]->(a:Account {number:'A100'}) AS OF datetime('2026-02-01T00:00:00Z') RETURN u.id` | `U1` only |
| T2 | `AS OF` after | same, `AS OF datetime('2026-07-01T00:00:00Z')` | `U1` **and** `U2` — the first edge was superseded, not deleted |
| T3 | `BETWEEN` | `MATCH (a:Account {number:'A100'})-[:FLAGGED_BY]->(r:Rule) BETWEEN datetime('2026-04-01T00:00:00Z') AND datetime('2026-07-01T00:00:00Z') RETURN r.name` | `velocity` |
| T4 | `AROUND` | `MATCH (u:User)-[:OWNS]->(a:Account {number:'A100'}) AROUND datetime('2026-06-01T00:00:00Z') +/- duration('P7D') RETURN u.id` | both `U1` and `U2` |
| T5 | Versioned property | `MATCH (u:User {id:'U1'}) AS OF datetime('2026-02-01T00:00:00Z') RETURN u.department` | `Sales` |
| T6 | `DIFF` on a property | `MATCH (u:User {id:'U1'}) DIFF FROM datetime('2026-04-01T00:00:00Z') TO datetime('2026-07-01T00:00:00Z') RETURN changeKind, before(u.department), after(u.department)` | `MODIFIED`, `Sales`, `Marketing` |
| T7 | `DIFF` on structure | `MATCH (u:User)-[:OWNS]->(a:Account {number:'A100'}) DIFF FROM datetime('2026-04-01T00:00:00Z') TO datetime('2026-07-01T00:00:00Z') RETURN changeKind, u.id` | `ADDED`, `U2` |

Note the clause order: the temporal clause follows the **pattern**, before any `WHERE`. Writing
`DIFF FROM … MATCH …` is a syntax error.

Pass condition for the diff cases: all four `changeKind` values should be observable somewhere across the
dataset, and `before()`/`after()` should read the two snapshots independently.

## Rejection cases

Each must produce a **clear compile error naming the construct** — never a 500, never a wrong answer.

| Query | Expected rejection |
|---|---|
| `MATCH (n) RETURN *` | `RETURN *` unsupported |
| `MATCH (u:User {id:'U1'}) RETURN u` | Bare pattern variable |
| `MATCH (u:User {id:'U1'}) MATCH (g:Group) RETURN u.id, g.name` | Only a single `MATCH` |
| `MATCH (u:User)-[:OWNS]->(a) RETURN a.number SKIP 1 LIMIT 1` | `SKIP` not compiled |
| `MATCH (u:User)-[:OWNS\|MEMBER_OF]->(x) RETURN u.id` | Type alternation — token error at `\|` |
| `MATCH (u:User)-[:OWNS*]->(a) RETURN a.number` | Unbounded `*` — parse error |
| `MATCH (u:User {id:'U1'}) SET u.name = 'x'` | `SET` is not a keyword |
| `MATCH (u:User {id:'U1'}) RETURN labels(u)` | Returns a list |
| `MATCH (u:User)-[:OWNS]->(a) RETURN u.id, count(a) AS n ORDER BY count(a) DESC` | `ORDER BY` must name a property or alias |
| `MATCH (n)-[:OWNS]->(a) RETURN a.number` | Anchor needs a label |

> **`collect()` is a rejection case again.** It briefly executed, returning a comma-joined string; it is now
> rejected at compile time because that was a wrong answer rather than a partial one
> ([12a-list-value-type.md](12a-list-value-type.md)). Assert the failure, not a result:
>
> | Query | Expected |
> |---|---|
> | `MATCH (u:User)-[:MEMBER_OF]->(g:Group) RETURN g.name, collect(u.id)` | Compile error mentioning `no list value type` |
>
> If it succeeds, that is the regression.

## Guardrail cases

These need a graph larger than `CorpGraph`, and they are worth running because the failure mode should be a
clear message rather than a hang. See [10-limits.md](10-limits.md) for the values.

| Trigger | Expect |
|---|---|
| `-[:R*1..60]->` | Rejected before any traversal: hop range exceeds 50 |
| A label-only anchor over a very large label | Either results, or a clear "add a property constraint" message |
| A broad variable-length hop into a dense region | Path-state or 30-second message, not a hang |
| `MATCH (n) RETURN GRAPH` with no `LIMIT` | Exactly 100 nodes, **silently** — the one cap that does not announce itself |

## Cluster correctness cases

> **C1–C4 and C6 are now covered automatically** by `TestGraphTwoNodeCluster`, which runs two independent nodes
> in one JVM — separate paths, store managers, merge processors and receive directories — and replicates every
> fragment between them through the real `receiveRemotePart` entry point, so the staging store's hash check is
> exercised rather than stubbed past. Only the Jersey hop is absent.
>
> That test was validated by breaking it on purpose: with replication reduced to the producing node, the
> cross-fragment traversal and convergence cases both fail. A cluster test that passes either way is worthless, so
> check that property still holds if you change it.
>
> The manual cases below remain worth running before a release, because they cover what the simulation cannot:
> real HTTP, real node enablement, real processing-task distribution, and a genuinely killed node.

These are the acceptance cases for the fragment-and-merge ingest path. Run on a cluster of at least two
processing nodes, with the same graph fed by a single feed, and **with `graphdb.nodeList` naming the nodes
that should hold graph data** — an empty list means "this node only" and the cases below will fail as they did
before the work landed.

Allow a merge cycle to complete before asserting; the `Graph DB Merge Processor` job starts the loops and they
run continuously thereafter.

| # | Case | Expected |
|---|---|---|
| C1 | Load a dataset large enough to be split across several streams, so more than one node processes some of it. Then `MATCH (u:User) RETURN count(u)` | The full count, from **every** node the query is sent to — not a per-node fragment |
| C2 | Run the same query repeatedly against different cluster nodes | Identical results every time. Varying results mean fragments have not all merged, or `nodeList` is incomplete |
| C3 | Take a pattern whose two endpoints were ingested by *different* nodes and traverse it | The path is found. This is the case fan-out could never satisfy and merge-based ingest does |
| C4 | Compare a cluster load against the same data loaded on a single node | Equivalent query results. Note **logical**, not byte, equivalence: interned id assignment order differs, so the stores are not identical on disk |
| C5 | Kill a node mid-load, then query | No missing data once the surviving fragments have merged. A stream whose fragment was not fully shipped is reprocessed |
| C6 | Reprocess a stream that has already been merged | No duplicate or altered data — merge is idempotent |
| C7 | Name a disabled node in `nodeList`, then process a stream | The stream task **fails** with a send error. It must not succeed having skipped that node |
| C8 | Delete a graph while one of its fragments is still queued | The fragment is discarded, the merge-failure metric stays at zero, and the queue does not block |
| C9 | Add a third node to `nodeList` after a load, query it, then `POST /api/graphDb/v1/<uuid>/backfill` from an existing node | Partial answers before, complete answers after a merge cycle. The **before** half is the point — it confirms the case is real rather than masked by a coincidental reload |
| C10 | Run a backfill while the graph is being fed | It completes without a quiet period, and the writes that landed during it arrive by ordinary replication. Nothing is lost either way |

C3 is the discriminating case. C1 and C2 can pass by accident if one node happened to process everything, so
check the processing task distribution before trusting them.

C4's wording matters: assert on query results, not on file comparison. Two stores holding the same graph will
differ byte-for-byte because ids are interned in arrival order.

### Store format stamp cases

| # | Case | Expected |
|---|---|---|
| S1 | Open a graph written by a build with a different `CURRENT_SCHEMA_VERSION` | The store **refuses to open**. It must not read old bytes as new ones |
| S2 | Merge a fragment written by a build with a different stamp | The merge throws, the fragment directory is **retained** under `merging/`, and the merge-failure metric increments |

There is no migration path by design — the remedy for S1 is to wipe and rebuild the graph.

## Automated tests

The load-bearing ones. Not exhaustive — the module has 27 test classes, and the rest cover a single DAO or
codec apiece.

| Test | Covers |
|---|---|
| `TestGraphFilter` | Ingest parsing and per-record error handling — end to end through fragment and merge, so it also exercises the write path a clustered node takes |
| `TestGraphStoresMerge` | Merge itself: cross-fragment traversal, colliding id spaces, repeated merge, all versions preserved, anchor equivalence across the tier boundary, stamp mismatch refused |
| `TestGraphMergePipeline` | The wiring on one node: fragments from separate streams reaching one store, empty streams shipping nothing, a fragment for a deleted graph being discarded |
| `TestGraphTwoNodeCluster` | Two nodes in one JVM: a traversal crossing two nodes' fragments resolving on **both**, convergence on the same graph, redelivery changing nothing, and a hash mismatch being refused |
| `TestGraphTemporalPrecision` | Every supported precision round-trips; key widths; the latest sentinel is encodable by its own serde; reopening at a different precision is refused |
| `TestGraphTraversalLimits` | Configured guardrails reach the right field, defaults match the historical constants |
| `TestGraphSchemaDb` | The format stamp: written on provision, validated on open, mismatch refused |
| `TestGraphAnchorEncoding` | That every spelling of one number, and of one instant, reaches the same anchor — and that a literal's seek encodings always include the stored value's. Under-reaching loses rows silently, so it is asserted as reachability rather than as equality |
| `TestGraphCondense` | That collapsing identical version runs changes **no answer at any instant**, asserted by querying every instant before and after — not by counting rows |
| `TestGraphCompaction` | The copy-and-swap: data preserved, nothing copied when nothing was removed, no working directories left, and that a compaction waits for an in-flight reader rather than replacing the file under it |
| `TestGraphRetentionSweep` | That the property index and its lookups are reclaimed with the versions they belonged to, and that an unchanged node keeps its anchors |
| `TestGraphRootMarker` | That a `graphdb.path` change is reported when it strands data and accepted when the data moved with it — the discriminating pair |
| `TestDocumentationQueries` | **Every Cypher query printed in this documentation set**, compiled through the real parser and planner. Found three examples that had been wrong for weeks, including one showing an aggregation the language cannot express. See [Documented queries](#documented-queries) |
| `TestDocumentationReferences` | **Every source constant, code-map class and `graphdb.*` setting** the documentation cites — that it exists, **and that the value printed beside it is still the value in the code**. See [Documented references](#documented-references) |
| `TestEventLoggingXslt` | **The documented event-logging translation**: the stylesheet in `docs/graphdb/examples/` transformed over its sample corpus, compared with the documented expected output, validated against the shipped XSD, and its snippets checked against the prose in [04-event-logging-xslt.md](04-event-logging-xslt.md). See [Documented examples](#documented-examples) |
| `TestGraphQueryNodeResolverImpl` | Query routing, including that it claims only `GraphDb` documents |
| `TestCompositeQueryNodeResolver` | That more than one feature can route, and that no resolvers means no constraint |
| `TestGraphMutationSchema` | Sample documents against the XSD |
| `TestGraphTraversalEngine` | Traversal, temporal semantics, guardrails |
| `TestGraphSearchProvider` | End-to-end execution, including `UNION` |
| `TestCypherQueryParser` | Grammar and AST |
| `TestGraphDbSettingsPresenter` | Settings UI |

Guardrails are tested through the engine's package-private test-seam constructor, which takes limit
overrides — never by lowering a production constant. See
[13-developer-guide.md](13-developer-guide.md#the-test-seam-constructor).

### Documented queries

`TestDocumentationQueries` compiles every fenced `cypher` block in `docs/graphdb/`. It is a **compile** check,
not an execution one: it proves the language still accepts what these files print, not that a query returns the
rows shown.

If you add an example, two conventions matter:

- **Separate independent statements with a blank line.** A block is split on blank lines, so two statements run
  together are compiled as one and fail.
- **Annotate an example that is meant to fail.** `-- rejected` asserts it does not compile;
  `-- rejected at runtime` asserts it *does* compile and is refused later by the engine. Both directions are
  checked, so an example labelled rejected that quietly starts working also fails the test.

Fragments — a bare `RETURN …`, or a pattern such as `(p:Person)` — are skipped, because neither compiles alone.
The test asserts a **floor** on how many statements it finds, so a moved file or a changed fence label cannot
turn it into a silent no-op.

**If this documentation moves to another repository, this test will fail** with a message saying so. That is
deliberate: repoint it or delete it, but do not let it skip.

### Documented examples

`TestEventLoggingXslt` covers the worked translation in
[04-event-logging-xslt.md](04-event-logging-xslt.md) — the only end-to-end example of getting real
`event-logging:3` data into a graph, and so the thing anyone starting out copies. It lived entirely in a
document and a directory of examples that nothing ran, which is how a stylesheet rots invisibly: it stays
plausible while it stops working, and the first person to find out is a user whose feed silently produced
nothing.

| Check | Covers |
|---|---|
| The transform | `examples/event-logging-to-graph.xslt` over `examples/sample-events.xml` reproduces `examples/expected-output.xml` |
| The vocabulary | The transform's **own** output validates against the shipped XSD — not the committed expected output, so this tracks what the stylesheet produces today |
| The prose | Every fenced `xslt` block in the document is still part of the stylesheet |

Three conventions to keep if you touch any of those files:

- **The document comparison is structural.** Saxon writes the root element's two namespace declarations in the
  opposite order to the committed file — the same document, a different writer. Namespace *declarations* are
  excluded from the comparison; namespace *URIs* are compared on every element and attribute. Do not tighten
  this into a string comparison: it will pass until the next Saxon upgrade.
- **A snippet is matched as an ordered subsequence of the stylesheet's lines**, not as a substring, because the
  document abbreviates in two legitimate ways — eliding with `…`, and omitting an intervening line that the
  passage is not about. Comments are dropped from both sides and whitespace collapsed per line.
- **The stylesheet must stay runnable with no pipeline context.** It uses standard XSLT 2.0 only. A `stroom:`
  extension function would make the example untestable, and would also stop being copyable as a starting point.

Landing this found one defect: the document printed the fall-through template as an empty
`<xsl:template …/>` where the stylesheet declares an `xsl:param`.

### Documented references

`TestDocumentationReferences` checks that what the documentation cites still exists. It is deliberately narrow,
covering only the places where a reference follows a convention:

| Check | Covers |
|---|---|
| Source constants exist | Every `Class.CONSTANT` in a table with a **Source constant** column — the values an operator looks up when sizing a deployment |
| **Source constant values** | That the number printed in the same row is the constant's actual value, read from its declaration |
| The code map | Every class named in the first cell of a **Role** table in [13-developer-guide.md](13-developer-guide.md) |
| Settings exist | Every `graphdb.*` reference anywhere, against `GraphDbConfig`'s getters |
| **Setting defaults** | That every documented default is what `new GraphDbConfig()` applies |

**A full sweep of every backticked identifier was tried and rejected.** It flagged 67 benign matches — Cypher
keywords, edge labels, dataset ids, JDK types, enum constants, file names — for three real defects. Suppressing
that needs a hand-kept stop list, and a stop list that quietly grows is the same rot the check exists to catch.
So prose references — a class named mid-sentence, or a described behaviour — remain verified by hand.

Each check asserts a floor on how much it found, for the same reason as the query test.

**The value checks read the code two different ways, on purpose.** Setting defaults come from a real
`new GraphDbConfig()`, because the config object is on the test classpath and a getter cannot misread an
initialiser. Constants are read from their **source declaration** instead, because they are not all reachable:
`Db.MAX_KEY_LENGTH` and `PlanBEnv.CONCURRENT_READERS` are in another module, several are `private`, and the
interface limits live in `stroom-core-client`, which is GWT-compiled and not on this module's test classpath at
all. Reading the source treats all of them alike, and beats widening a production constant's visibility to suit
a test.

Three conventions govern how a documented value is read, and each is one an author should keep to:

- **A value cell may carry markdown, separators and a unit** — `**1,000,000**`, `10 GiB`, `30 seconds`,
  `7 days` all compare correctly.
- **A cell beginning `~` is approximate and is not compared** — `~2.8 × 10¹⁴` is arithmetic over a byte width,
  not a stored number. Such a row is instead checked on the width its **Source constant** cell annotates, as in
  `` `GraphStores.NODE_UID_WIDTH` (6 bytes) ``.
- **Write a constant reference in full.** `` `GraphDiscoveryWidget.LABEL_LIMIT` ``, not the shorthand
  `` `.LABEL_LIMIT` `` — the shorthand matches no `Class.MEMBER` pattern, so it silently escapes both checks.
  One row was written that way and was going unchecked.

A row whose value or initialiser cannot be interpreted is counted as **unchecked**, listed in the assertion
message, and does not fail the build — a new row with an unusual value should not break CI for whoever wrote it.
The floor is what stops that hollowing the check out.

## Regression checklist

Invariants worth asserting after any change to the storage or ingest layer. These are properties the code
must uphold that are not obvious from its interface:

- **Edge dual-write stays consistent.** Every edge is written to both the out-edge and in-edge stores, and
  nothing spans a transaction across the two — consistency depends on the paired calls. A traversal that
  disagrees forwards and backwards means this broke.
- **Fixed UID widths are never exceeded**, and a width-overflow does not corrupt the interning counter.
- **Retention keeps the latest version at or before the cutoff**, plus every newer version, for each
  entity — so historical queries still resolve after a trim.
- **Property-index anchors are append-only**; stale entries are filtered at query time, not deleted.
- **A record is all-or-nothing.** A failure part-way through a node's property writes leaves nothing
  behind.

## Next

- [13-developer-guide.md](13-developer-guide.md) — the code these tests exercise
- [10-limits.md](10-limits.md) — the guardrail values
- [06-language-reference.md](06-language-reference.md) — the surface under test
