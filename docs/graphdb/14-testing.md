# Testing a Graph DB

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
**Audience:** developers changing Graph DB; anyone validating a deployment or an upgrade.
**Scope:** a repeatable acceptance protocol — a dataset, the cases to run against it, and what each should
return. Canonical for the test dataset.
**Companion documents:** [13-developer-guide.md](13-developer-guide.md) (unit-test inventory),
[06-language-reference.md](06-language-reference.md) (the surface under test),
[03-ingest.md](03-ingest.md) (loading the dataset).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

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
| B10 | `collect()` | `MATCH (u:User)-[:MEMBER_OF]->(g:Group) RETURN g.name AS grp, collect(u.id) AS ids ORDER BY grp` | `users` → the **string** `U2, U3` |
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

> **`collect()` is no longer a rejection case.** Earlier protocols listed it as unsupported; it now
> executes, returning a comma-joined string ([07-functions.md](07-functions.md)). If it fails, that is a
> regression.

## Guardrail cases

These need a graph larger than `CorpGraph`, and they are worth running because the failure mode should be a
clear message rather than a hang. See [10-limits.md](10-limits.md) for the values.

| Trigger | Expect |
|---|---|
| `-[:R*1..60]->` | Rejected before any traversal: hop range exceeds 50 |
| A label-only anchor over a very large label | Either results, or a clear "add a property constraint" message |
| A broad variable-length hop into a dense region | Path-state or 30-second message, not a hang |
| `MATCH (n) RETURN GRAPH` with no `LIMIT` | Exactly 100 nodes, **silently** — the one cap that does not announce itself |

## Automated tests

| Test | Covers |
|---|---|
| `TestGraphFilter` | Ingest parsing and per-record error handling |
| `TestGraphMutationSchema` | Sample documents against the XSD |
| `TestGraphTraversalEngine` | Traversal, temporal semantics, guardrails |
| `TestGraphSearchProvider` | End-to-end execution, including `UNION` |
| `TestCypherQueryParser` | Grammar and AST |
| `TestGraphDbSettingsPresenter` | Settings UI |

Guardrails are tested through the engine's package-private test-seam constructor, which takes limit
overrides — never by lowering a production constant. See
[13-developer-guide.md](13-developer-guide.md#the-test-seam-constructor).

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
