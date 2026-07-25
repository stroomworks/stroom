# Stroom graph query engine vs. the GQL mandatory feature set

**Date:** 2026-07-25 · **Branch:** `sw-query-optimiser-graph-backend` · **Commit:** `6a2fd0092a`
**Standard compared against:** ISO/IEC 39075:2024 (GQL — Graph Query Language)

---

## Read this first: what is (and isn't) being compared

Stroom's graph backend implements a **read-only subset of openCypher**. GQL
(ISO/IEC 39075:2024) is a **different language** — a full, read-write ISO
standard with its own syntax, catalog model, session model and transaction
model. The two share a common ancestry (both use ASCII-art `(node)-[edge]->()`
patterns, and GQL borrowed heavily from Cypher), but **Stroom does not parse GQL
syntax and makes no claim of GQL conformance.**

So this document is **not** a conformance audit. A conformance audit would score
zero on syntax grounds alone. Instead it is a **capability comparison**: for each
area the GQL standard marks *mandatory*, can a Stroom user express the equivalent
query intent today? That is the question a human actually cares about when asking
"how far are we from a standard graph query language?"

Two structural facts dominate everything below:

1. **Stroom's engine is read-only.** It matches patterns and projects results.
   GQL is a full CRUD language (`INSERT`, `SET`, `REMOVE`, `DELETE`) plus catalog
   and schema management. Those whole categories are **out of scope by design**,
   not partially-implemented gaps.
2. **Stroom is an embedded query overlay,** not a database server. GQL's
   mandatory *session* and *transaction* control statements (`START
   TRANSACTION`, `COMMIT`, `ROLLBACK`, session parameters) have no surface in a
   query-only engine — queries run inside a read transaction managed by the host.

With those framed, the interesting comparison is the **reading/querying core**,
where Stroom is genuinely strong.

---

## Executive summary

| GQL mandatory area | Stroom status |
|---|---|
| Pattern matching (`MATCH`, `OPTIONAL MATCH`) | ✅ Supported |
| Graph pattern elements (nodes, edges, direction, labels, property maps, paths) | ✅ Supported |
| Variable-length paths | ✅ Supported (bounded) |
| `WHERE` filtering | ✅ Supported (rich) |
| Result projection (`RETURN`, `DISTINCT`, aliases) | ✅ Supported |
| Result ordering & paging (`ORDER BY`, `SKIP`, `LIMIT`) | ✅ Supported |
| Comparison predicates, `IS NULL` / `IS NOT NULL` | ✅ Supported |
| `CASE` value expression | ✅ Supported (`CASE WHEN …` searched form and simple form) |
| `EXISTS` predicate / subqueries | ❌ Not supported |
| Aggregate functions (`count`, `sum`, `avg`, `min`, `max`) | ✅ Supported (+ `collect`) |
| Arithmetic value expressions | ✅ Supported (`+ - * / ^ %`) |
| Scalar / string / date functions | ✅ Supported (Stroom's 230+ function library) |
| Mandatory data types (string, bool, int, float) | ✅ Supported at the value level |
| `CURRENT_GRAPH` / graph selection | ⚠️ Single implicit graph; no in-query graph selection |
| `SELECT` statement (GQL's tabular form) | ❌ Not supported |
| Session management | ❌ Out of scope (embedded engine) |
| Transaction statements (`START TRANSACTION` / `COMMIT` / `ROLLBACK`) | ❌ Out of scope (host-managed read txn) |
| Data modification (`INSERT` / `SET` / `DELETE`) | ❌ Out of scope (read-only engine) |
| Catalog & schema (graph types, `CREATE GRAPH`, element types) | ❌ Out of scope (schemaless store) |

**Bottom line:** within the **read/query half** of GQL's mandatory surface,
Stroom covers the great majority of the capability — pattern matching, filtering,
projection, ordering/paging, aggregation, arithmetic and `CASE` are all present. The
distance to "a standard graph query language" is dominated by the **write half**
(data modification, catalog, sessions, transactions), which Stroom's architecture
deliberately does not address, plus a handful of query-side items (`EXISTS`
subqueries, `SELECT`, list/`UNWIND`).

---

## Detailed comparison by GQL mandatory subclause

GQL's mandatory features are the base language constructs the standard requires of
any conforming implementation (they carry no optional "Feature ID"). They are
organised by subclause of ISO/IEC 39075:2024. The mapping below follows the
subclause grouping summarised in the public conformance write-ups (see Sources).

### Subclause 7 — Session management · ❌ Out of scope
GQL requires session-level statements (session set/reset, session parameters).
Stroom is an embedded query engine invoked by the host application; there is no
user-facing session language. **Not applicable to a query-only overlay.**

### Subclause 8 — Transaction management (`START TRANSACTION`, `COMMIT`, `ROLLBACK`) · ❌ Out of scope
Queries execute inside a **read transaction** opened by the host (`PlanBEnv.read`
in the graph store). There is no in-query transaction control, and — being
read-only — no write transactions to commit or roll back.

### Subclause 11 — Object expressions (`CURRENT_GRAPH`) · ⚠️ Partial
Stroom queries run against a **single, implicit graph** (the selected graph
doc/store). There is no in-query notion of "the current graph" that can be
switched or referenced, because there is no multi-graph `USE` clause. The
capability is effectively "always the one graph you opened."

### Subclause 14.4 — `MATCH`, `OPTIONAL MATCH` · ✅ Supported
Both are implemented. `MATCH` supports fixed-length chains and bounded
variable-length paths; `OPTIONAL MATCH` extends a matched pattern with a single
optional hop, null-padding unmatched rows (Cypher's left-join semantics).
*Limitation:* the leading `MATCH` generally needs to be **anchored** (a node with
a property predicate or an id); a purely label-only scan such as
`MATCH (n:Person) RETURN n.id` with no anchor is not yet a general node-scan.

### Subclause 14.9 — Order and paging (`ORDER BY`, offset/limit) · ✅ Supported
`ORDER BY` (multi-key, asc/desc), `SKIP n` and `LIMIT n` are all supported on the
top-level `RETURN`.

### Subclauses 14.10 / 14.11 — Primitive result / `RETURN` · ✅ Supported
`RETURN` with scalar items, `AS` aliases, `DISTINCT`, arithmetic expressions and
function calls. (Stroom also has a non-standard `RETURN GRAPH` for whole-subgraph
preview, which is an extension, not a GQL feature.)

### Subclause 14.12 — `SELECT` statement · ❌ Not supported
GQL's SQL-flavoured tabular `SELECT` form is not implemented (Cypher-family
engines generally express the same intent through `MATCH … RETURN`). The query
*intent* is reachable; the `SELECT` *syntax* is not.

### Subclause 16 — Graph pattern elements · ✅ Supported
Node patterns, edge patterns with direction (`->`, `<-`, undirected), label
predicates, inline property maps, path patterns, element-variable references and
pattern-scoped `WHERE` are all present. **Quantified path patterns:** bounded
variable-length (`-[:R*1..3]->`) is supported; the full GQL quantified-path-
pattern grammar is not.

### Subclauses 19–20 — Predicates & value expressions · ✅ Mostly supported
- **Comparison** `= <> < > <= >=` — ✅ (including field-vs-field, e.g. `a.x > b.y`)
- **`IS NULL` / `IS NOT NULL`** — ✅
- **`IN` list membership** — ✅
- **String predicates** `STARTS WITH` / `CONTAINS` / `ENDS WITH` / `=~` — ✅ (a Cypher extension beyond bare GQL)
- **`CASE`** — ✅ both the searched form (`CASE WHEN <cond> THEN … ELSE … END`, with `AND`/`OR`/`NOT` and `IS [NOT] NULL` conditions) and the simple form (`CASE <input> WHEN <value> THEN … END`); a missing `ELSE` yields null. (Also still reachable as the `stroom.case(...)` function.) *String predicates / `IN` inside a `CASE` condition are the only rejected sub-cases.*
- **`EXISTS`** (existential subquery / pattern) — ❌ not supported
- **Aggregates** `count` / `sum` / `avg` / `min` / `max` — ✅ (plus `collect`, and `count(DISTINCT …)`)
- **Arithmetic** — ✅ `+ - * / ^ %` with correct precedence and parentheses

### Data types (mandatory minimal set: string, bool, int, float) · ✅ Supported
The engine's value model carries string, boolean, integer/long and
double/float values, which covers GQL's mandatory minimal type set at the value
level. (Stroom's type system is dynamic/schemaless rather than the declared,
catalogued types GQL assumes — see catalog below.)

### Data modification (`INSERT`, `SET`, `REMOVE`, `DELETE`) · ❌ Out of scope
The engine is **read-only**. Graph data is written through Stroom's ingest/
state-store pipeline, not through the query language.

### Catalog & schema (graph types, element types, `CREATE GRAPH`) · ❌ Out of scope
The underlying store is **schemaless** (properties are open per node/edge). GQL's
mandatory closed/open graph-type machinery, element-type names and label-set keys
have no counterpart, by design.

---

## Where the real gaps are (query-side, actionable)

Setting aside the deliberately out-of-scope write/catalog/session categories,
these are the query-side items closest to worth doing, roughly in value order:

1. **True list values + `UNWIND`.** Today `collect()` returns a delimited string,
   not a real list; `UNWIND`, `keys()`, `labels()`, `range()`, list slicing all
   wait on a first-class list value type threaded through the value model.
   *Higher effort (cross-cutting value-type change).*
2. **`EXISTS { pattern }` / existential subqueries.** Needs a sub-pattern
   evaluation hook in the WHERE path. *Medium effort.*
3. **Unanchored label-only node scan** (`MATCH (n:Person) RETURN …`). Needs a
   label-indexed node scan as a plan source. *Medium effort.*
4. **`UNION` of query results.** *Medium effort.*

*Recently closed:* the `CASE WHEN` expression (both forms) and the modulo (`%`)
operator are now implemented — they previously headed this list.

None of these block the common analytic queries the engine is built for; they are
the standards-completeness tail.

---

## Honest verdict

Stroom's graph engine is best understood as **"a capable read/query slice of a
graph query language,"** not a GQL implementation and not on a path to GQL
conformance without a fundamentally larger scope (write statements, catalog,
sessions). Judged on the fair question — *can a user express standard graph
queries?* — it covers the **majority of GQL's mandatory querying capability**:
pattern matching, optional matching, rich filtering, projection with distinct/
alias, ordering and paging, the full mandatory aggregate set, arithmetic and
`CASE`. The notable query-side omissions are `EXISTS` subqueries, real list
values/`UNWIND`, `UNION`, and the `SELECT` tabular form.

---

## Sources

- [ISO/IEC 39075:2024 — Information technology — Database languages — GQL (ISO catalogue)](https://www.iso.org/standard/76120.html)
- [ISO/IEC 39075:2024(en) — online browsing platform](https://www.iso.org/obp/ui/en/#!iso:std:76120:en)
- [GQL Conformance — Ultipa documentation (mandatory-feature-by-subclause summary)](https://www.ultipa.com/docs/gql/gql-conformance)
- [Spanner Graph and ISO standards — Google Cloud (mandatory minimal data-type set)](https://docs.cloud.google.com/spanner/docs/graph/iso-standards)
- [gqlstandards.org — GQL standard overview](https://www.gqlstandards.org/)

*Stroom-side capability claims in this document are drawn from the branch's Cypher
grammar (`Cypher.g4`), the compiler (`CypherToLogicalPlan`) and the graph engine
(`GraphTraversalEngine`) as of commit `0c44648260`; see also
`docs/cypher-language-feature-roadmap.md`.*
