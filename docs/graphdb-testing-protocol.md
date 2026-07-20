# Temporal graph DB (GraphDb) — user testing protocol

## Is it testable by users?

**Yes, end-to-end.** On a running Stroom instance that includes the `stroom-graphdb` modules, a user can:

1. **Create** a `GraphDb` document in the Explorer.
2. **Ingest** graph data by sending `graph-mutation:1` XML through a pipeline containing the **Graph Filter**
   element (targeting the GraphDb doc).
3. **Query** it with the temporal-Cypher subset from the GraphDb document's query editor.

This is a **PoC-stage** feature: the Cypher subset is deliberately narrow and several clauses are rejected at
compile time (see "Known limitations"). The test data and queries below are taken verbatim from the automated
test suite, so their expected results are exact.

---

## 1. Create a GraphDb document

Explorer → **New** → **GraphDb** (document type `GraphDb`) → name it e.g. `Test Graph`. Save.

The editor has **Data** (the query tab), **Documentation**, and **Permissions** tabs. No physical configuration
is required — a GraphDb owns and hides its internal stores (retention is the only optional policy).

---

## 2. Ingest test data

The Graph Filter consumes `graph-mutation:1` XML. Any pipeline that produces that XML and routes it into a
**Graph Filter** element works. The simplest test pipeline for hand-crafted XML:

```
Source → XML parser → Graph Filter (property "graphDb" = Test Graph)
```

Set the filter's **graphDb** pipeline property (a DocRef of type `GraphDb`) to the document from step 1, then
send the XML below to the pipeline's feed (or use the pipeline **stepping** feature to push it through without a
full feed).

### Test data — `graph-mutation:1` XML

```xml
<graph xmlns="graph-mutation:1" version="1.0">
    <node id="d-42" validFrom="2026-01-01T00:00:00.000Z">
        <label>Device</label>
        <property name="id">d-42</property>
    </node>
    <node id="account-a" validFrom="2026-01-01T00:00:00.000Z">
        <label>Account</label>
        <property name="id">account-a</property>
    </node>
    <node id="account-b" validFrom="2026-01-01T00:00:00.000Z">
        <label>Account</label>
        <property name="id">account-b</property>
    </node>
    <edge type="CONNECTED_TO" validFrom="2026-01-01T00:00:00.000Z">
        <src>d-42</src>
        <dst>account-a</dst>
    </edge>
    <edge type="CONNECTED_TO" validFrom="2026-06-01T00:00:00.000Z">
        <src>d-42</src>
        <dst>account-b</dst>
    </edge>
</graph>
```

This creates device `d-42` connected to `account-a` (from 2026-01-01) and `account-b` (from 2026-06-01) — the
second edge starts later, which is what the temporal queries below exercise.

### Schema notes (`graph-mutation:1`)
- Elements: `<node>`, `<node-delete>`, `<edge>`, `<edge-delete>` under `<graph version="1.0">`.
- `<node>`: `id` + `validFrom` attributes (both required), zero-or-more `<label>` and `<property name="…">value`.
- `<edge>`: `type` + `validFrom` attributes, `<src>`/`<dst>` node-id children, optional `<property>`s.
- `validFrom` is an ISO-8601 instant. A malformed/oversize record is logged and **skipped** (the stream is not
  aborted) — you can verify this by adding a `<node>` with 256+ `<label>`s and confirming the valid records still
  ingest and a per-record error is logged.

**Expected ingest result**: no stream errors for the XML above; the graph now holds 3 nodes and 2 edges.

---

## 3. Query with temporal Cypher

Open the GraphDb document's **Data** tab and run a query. (A GraphDb is also a queryable datasource, so a
Dashboard/Query targeting it works too.)

### Query 1 — one-hop traversal (latest state)

```cypher
MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id
```

**Expected**: two rows — `account-a` and `account-b` (both edges exist "now").

### Query 2 — `AS OF` a point before the second edge existed

```cypher
MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account)
AS OF datetime('2026-03-01T00:00:00Z')
RETURN a.id
```

**Expected**: one row — `account-a` only. (At 2026-03-01, `account-b`'s edge — validFrom 2026-06-01 — did not
yet exist.) This is the core temporal capability: the same query, evaluated at a past instant, returns the graph
as it was then.

### Query 3 — `BETWEEN` a window

```cypher
MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account)
BETWEEN datetime('2026-01-01T00:00:00Z') AND datetime('2026-02-01T00:00:00Z')
RETURN a.id
```

**Expected**: one row — `account-a` (only its edge intersects that window).

### Query 4 — `WHERE` on a property + `LIMIT`

```cypher
MATCH (a:Account {id: 'account-a'}) RETURN a.id LIMIT 20
```

**Expected**: one row — `account-a`. (Demonstrates a property-anchored scan with no hop.)

### Also worth trying
- **`AROUND`**: `… AROUND datetime('2026-01-01T12:00:00Z') +/- duration('PT24H') RETURN a.id` — neighbours whose
  edge intersects the ±window.
- **Absent property returns null** (not an error): `MATCH (d:Device {id: 'd-42'}) RETURN d.balance` → one row
  whose value is null (Device has no `balance`).

---

## 4. Known limitations (query subset)

The Cypher subset is intentionally narrow. These are compile-time rejections or unsupported shapes — expect a
clear error, not a wrong result:

- **Anchor must have a label AND a property predicate.** `MATCH (n) …` (no label / no `{prop: val}`) is
  rejected — there is no "all nodes" scan; the anchor is resolved via the property index.
- **`RETURN` must be `variable.property`** (or an aggregate/function that compiles). `RETURN d` (a bare pattern
  variable — a whole node) is rejected with a clear "bare pattern variable" message.
- **`ORDER BY` and `RETURN DISTINCT` are now supported** — the graph executor sorts and de-duplicates the
  projected rows (`ORDER BY` lowers to a `Sort` node; `DISTINCT` is carried on the compiled plan). **`SKIP` is
  still rejected** at compile time with a clear "not yet supported" error (the core's `Limit` node has no offset
  slot), deliberately, rather than silently-wrong results.
- **One `MATCH` clause only**; **one variable-length hop** (`-[:T*1..n]->`, bounded — unbounded `*` is a
  parse error) and it must be the pattern's sole hop; fixed-length multi-hop chains are supported.
- **`WHERE`** supports field-vs-literal comparisons (a comparison between two fields is rejected).
- Aggregates (`count`/`sum`/…) compile but **GROUP-BY inference is not implemented** (documented gap).
- Traversal guardrails: a variable-length hop range wider than 50, more than ~200k explored path-states per
  anchor, or a traversal exceeding ~30s is rejected/aborted with a clear limit-exceeded error.

Full architecture/scope is in [temporal-cypher-graph.md](temporal-cypher-graph.md) and
[temporal-cypher-graph-implementation-plan.md](temporal-cypher-graph-implementation-plan.md).

---

## 5. Reporting

For any failure capture: the ingest XML (or the pipeline step output), the exact Cypher, the result or error
message, and — for a temporal query — the `validFrom` values in play. An expected-to-be-rejected query
(section 4) returning a clear compile error is a *pass*; a wrong result or an opaque/500-style error is a bug.
