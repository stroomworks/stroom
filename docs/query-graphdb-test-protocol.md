# Test protocol: the new query system + the Stroom Graph DB

**Status:** Test protocol
**Audience:** the implementing/testing agent and reviewers
**Scope:** An end-to-end protocol that exercises **(1)** the new query system (the StroomQL grammar-driven optimiser: joins, enrichment, where-splitting, known-difference fixes), **(2)** the full shipped Graph DB Cypher surface (anchors, hops, variable-length, `WHERE`, `ORDER BY`/`DISTINCT`/`LIMIT`, aggregates, temporal `AS OF`/`AROUND`/`BETWEEN`, and the already-done **diff** delta table), and **(3)** every feature added by the [feature-completion plan](graphdb-features-implementation-plan.md) — `from "GraphDb"` routing (A), the settings tab (B), the Cypher-as-a-join-side (C), and `RETURN GRAPH` + Cytoscape (D).
**Companion:** [`graphdb-features-implementation-plan.md`](graphdb-features-implementation-plan.md) (this is its Phase Z gate); [`graphdb-index.html`](graphdb/archive/graphdb-index.html).

---

## 1. How to run

- **Environment:** a running Stroom with the query optimiser mode flag `stroom.query.optimiser.mode = ON` (see [`query-optimiser-user-guide.md`](query-optimiser-user-guide.md)). Run the same suite once with `SHADOW` to confirm no legacy/optimiser divergence beyond the documented [known differences](query-optimiser-known-differences.md).
- **Driver:** the tests can be driven through the Stroom MCP tools (`createDocument`, `uploadToFeed`, `csvQuery`, `validateQuery`, `fetchDashboard`/`updateDashboard`) or the UI. Cypher tests that predate Workstream A must run from the Graph DB **Data** tab (which sets `ownerDocRef`); after A, they may run from any surface.
- **Pass rule:** a test passes if the actual result equals the **Expected** rows (order-insensitive unless the query has `ORDER BY`) and the **Pass** condition holds. Record any deviation with justification.
- **Idempotency:** load the dataset once (§2). Graph ingest is a materialised projection — re-running ingest rebuilds it.

---

## 2. Test data — the "CorpNet" dataset

One coherent dataset feeds every test. Times are UTC. Reference instants:

| Symbol | Instant | Meaning |
|---|---|---|
| `T0` | `2026-01-01T00:00:00Z` | initial state |
| `T1` | `2026-05-01T00:00:00Z` | fraud rule attached |
| `T2` | `2026-06-01T00:00:00Z` | account ownership transfer + a property change |
| `WIN0`/`WIN1` | `2026-04-01` / `2026-07-01` | the diff window bounds |

### 2.1 Graph: `CorpGraph` (a `GraphDb` document)

**Nodes** (label {identifying property} — other properties):

| Node | Label | Key | Other props |
|---|---|---|---|
| U1 | `User` | `id=U1` | `name=alice`, `department` (versioned, see below) |
| U2 | `User` | `id=U2` | `name=bob`, `department=Finance` |
| U3 | `User` | `id=U3` | `name=carol`, `department=Support` |
| A100 | `Account` | `number=A100` | `tier=gold` |
| A200 | `Account` | `number=A200` | `tier=silver` |
| A300 | `Account` | `number=A300` | `tier=silver` |
| H1..H4 | `Host` | `ip=10.0.0.1 … 10.0.0.4` | `zone` per host |
| G-admin | `Group` | `name=admins` | |
| G-users | `Group` | `name=users` | |
| R-vel | `Rule` | `name=velocity` | |

**Versioned node property** (drives `MODIFIED` / node temporal):
- `U1.department`: `Sales` from `T0`; `Marketing` from `T2`.

**Edges** (`type`, `validFrom`):

| Edge | From → To | validFrom | Notes |
|---|---|---|---|
| OWNS | U1 → A100 | `T0` | **superseded at `T2`** (transfer) |
| OWNS | U2 → A100 | `T2` | the transfer target |
| OWNS | U2 → A200 | `T0` | stable |
| OWNS | U3 → A300 | `T0` | stable |
| MEMBER_OF | U1 → G-admin | `T0` | |
| MEMBER_OF | U2 → G-users | `T0` | |
| MEMBER_OF | U3 → G-users | `T0` | |
| CONNECTED_TO | H1 → H2 | `T0` | 3-hop chain … |
| CONNECTED_TO | H2 → H3 | `T0` | … for variable-length … |
| CONNECTED_TO | H3 → H4 | `T0` | … reachability |
| FLAGGED_BY | A100 → R-vel | `T1` | appears inside the diff window |

**Resulting diff over `[WIN0, WIN1]`** (used by B11/N4): `OWNS U1→A100` = **REMOVED**, `OWNS U2→A100` = **ADDED**, `FLAGGED_BY A100→R-vel` = **ADDED**, `U1` node = **MODIFIED** (`Sales`→`Marketing`); everything else = **UNCHANGED**.

**Load:** create the `GraphDb` doc `CorpGraph`; send the node/edge upserts (each carrying its `validFrom`) to a feed whose pipeline ends in a **`GraphFilter`** targeting `CorpGraph`. Minimal mutation-XML shape (the XSLT maps source records to this; one node + one edge shown):
```xml
<graph xmlns="graph-mutation:1">
  <node op="upsert" label="User" validFrom="2026-01-01T00:00:00.000Z">
    <key name="id">U1</key>
    <prop name="name">alice</prop>
    <prop name="department">Sales</prop>
  </node>
  <edge op="upsert" type="OWNS" validFrom="2026-01-01T00:00:00.000Z">
    <from label="User" key="id">U1</from>
    <to label="Account" key="number">A100</to>
  </edge>
</graph>
```
For **automated** tests, load the same nodes/edges directly as engine fixtures (as `TestGraphTraversalEngine` does) instead of via a pipeline.

### 2.2 Event index: `AuthEvents` (a Lucene index)

| time | user | host | account | action | amount |
|---|---|---|---|---|---|
| `2026-07-20T09:00:00Z` | U1 | 10.0.0.3 | A100 | LOGIN | 0 |
| `2026-07-20T09:05:00Z` | U2 | 10.0.0.4 | A100 | TRANSFER | 250.00 |
| `2026-07-20T09:10:00Z` | U3 | 10.0.0.9 | A300 | LOGIN | 0 |
| `2026-07-20T09:15:00Z` | U2 | 10.0.0.2 | A200 | TRANSFER | 19.99 |

Note `10.0.0.9` is deliberately **not** in the graph (tests INNER-drop vs LEFT-null and reachability exclusion).

### 2.3 Reference index: `HostInventory` (a Lucene index)

| host | zone |
|---|---|
| 10.0.0.1 | DMZ |
| 10.0.0.2 | DMZ |
| 10.0.0.3 | Core |
| 10.0.0.4 | Core |

### 2.4 State store: `UserDir` (a Plan B `State`, key = `Key`)

| Key | Value |
|---|---|
| U1 | Marketing |
| U2 | Finance |
| U3 | Support |

---

## 3. Section A — the new query system (StroomQL optimiser)

> Prerequisite: `stroom.query.optimiser.mode = ON`.

**A1 — single-source parity.**
```
from "AuthEvents" select time, user, action order by time
```
Expected: 4 rows in time order (U1/LOGIN, U2/TRANSFER, U3/LOGIN, U2/TRANSFER). Pass: same rows the legacy compiler returns (compare under `SHADOW`).

**A2 — known-difference fixes** (see [known-differences doc](query-optimiser-known-differences.md)). Run each; Pass: matches the documented corrected behaviour, not the legacy bug.
```
from "AuthEvents" where account is not null select user            -- all 4 rows
from "AuthEvents" where not (action = 'LOGIN') select user         -- bracket-adjacent not → 2 TRANSFER rows
from "AuthEvents" where amount > 100 select user                   -- bare-where mixed-eligibility → 1 row (U2/250), no zero-row footgun
```

**A3 — INNER join, index ⋈ index.**
```
from "AuthEvents" as e inner join "HostInventory" as h on e.host = h.host
select e.time, e.user, h.zone
```
Expected: 3 rows (hosts .3/.4/.2 → Core/Core/DMZ); the `10.0.0.9` event is dropped. Pass: INNER drops the unmatched row.

**A4 — LEFT join null-padding.**
```
from "AuthEvents" as e left join "HostInventory" as h on e.host = h.host
select e.user, h.zone
```
Expected: 4 rows; `h.zone` is null for the `10.0.0.9` (U3) event. Pass: all left rows kept, null pad on miss.

**A5 — enrichment fast path (State broadcast lookup).**
```
from "AuthEvents" as e inner join "UserDir" as u on e.user = u.Key
select e.user, u.Value
```
Expected: U1→Marketing, U2→Finance (×2), U3→Support. Pass: rows correct **and** the plan uses the broadcast-lookup path for the `UserDir` (`PlanB`, key `Key`) side (confirm via A7 EXPLAIN).

**A6 — `where` across joins.**
```
from "AuthEvents" as e inner join "HostInventory" as h on e.host = h.host
where h.zone = 'Core' select e.time, e.host
```
Expected: 2 rows (10.0.0.3 @09:00, 10.0.0.4 @09:05). Pass: the residual predicate is applied to the combined rows.

**A7 — EXPLAIN.**
```
explain from "AuthEvents" as e inner join "UserDir" as u on e.user = u.Key select e.user, u.Value
```
Expected: a plan naming both sides and the join, with the `UserDir` side as a broadcast/state lookup. Pass: plan renders; join + both sides visible.

---

## 4. Section B — Graph DB Cypher (shipped surface, incl. diff)

> Run from the `CorpGraph` Data tab (pre-Workstream-A) or any surface (post-A).

**B1 — anchor by label + property.** `match (u:User {id:'U1'}) return u.id` → `U1`.

**B2 — single hop.** `match (u:User {id:'U1'})-[:MEMBER_OF]->(g:Group) return g.name` → `admins`.

**B3 — multi-hop (fixed).** `match (h:Host {ip:'10.0.0.1'})-[:CONNECTED_TO]->(x:Host)-[:CONNECTED_TO]->(y:Host) return y.ip` → `10.0.0.3`.

**B4 — variable-length `*1..3`.** `match (h:Host {ip:'10.0.0.1'})-[:CONNECTED_TO*1..3]->(x:Host) return distinct x.ip order by x.ip` → `10.0.0.2`, `10.0.0.3`, `10.0.0.4`.

**B5 — `WHERE` field vs literal.** `match (u:User)-[:OWNS]->(a:Account) where u.id = 'U2' return a.number order by a.number` → `A100`, `A200` (latest state: U2 owns A100 after the transfer, and A200).

**B6 — `ORDER BY` / `DISTINCT` / `LIMIT`.** `match (u:User)-[:MEMBER_OF]->(g:Group) return distinct g.name order by g.name limit 10` → `admins`, `users`.

**B7 — aggregates.** `match (u:User)-[:MEMBER_OF]->(g:Group) return g.name, count(u) order by g.name` → `admins`:1, `users`:2. Also verify `sum/avg/min/max` compile and reduce (e.g. `return count(u)` → 3). Pass: aggregation groups by the non-aggregate `RETURN` items; `ORDER BY <alias>` works.

**B8 — temporal `AS OF`.**
```
match (u:User)-[:OWNS]->(a:Account {number:'A100'}) as of datetime('2026-02-01T00:00:00Z') return u.id   -- → U1 (before transfer)
match (u:User)-[:OWNS]->(a:Account {number:'A100'}) as of datetime('2026-07-01T00:00:00Z') return u.id   -- → U2 (after transfer)
```

**B9 — temporal `AROUND ± d`.** `match (u:User)-[o:OWNS]->(a:Account {number:'A100'}) around datetime('2026-06-01T00:00:00Z') ± duration('P7D') return u.id` → both `U1` and `U2` edges whose validity intersects the ±7-day window around the transfer.

**B10 — temporal `BETWEEN`.** `match (a:Account {number:'A100'})-[:FLAGGED_BY]->(r:Rule) between datetime('2026-04-01T00:00:00Z') and datetime('2026-07-01T00:00:00Z') return r.name` → `velocity` (edge valid since `T1`, intersecting the window).

**B11 — DIFF (delta table).**
```
diff from datetime('2026-04-01T00:00:00Z') to datetime('2026-07-01T00:00:00Z')
match (u:User)-[o:OWNS]->(a:Account {number:'A100'}) return changeKind, u.id
```
Expected: `REMOVED, U1` and `ADDED, U2`. Then the property-change form:
```
diff from datetime('2026-04-01T00:00:00Z') to datetime('2026-07-01T00:00:00Z')
match (u:User {id:'U1'}) return changeKind, before(u.department), after(u.department)
```
Expected: `MODIFIED, Sales, Marketing`. Pass: all four `changeKind`s (`ADDED`/`REMOVED`/`MODIFIED`/`UNCHANGED`) are observable across the dataset; `before()`/`after()` read the two snapshots.

**B12 — unsupported constructs fail cleanly.** Each of the following must produce a **clear compile error with a position**, never a 500 or a wrong answer:
```
match (p:User)-[:OWNS]->(a:Account) return p.name, collect(a.number)   -- collect() not in the subset
match (n) return *                                                     -- RETURN * not supported
match (u:User {id:'U1'}) return u                                       -- whole-node return not supported
match (u:User {id:'U1'}) match (g:Group) return u.id, g.name            -- multiple MATCH not supported
```

---

## 5. Section N — new features (Workstreams A–D)

**N‑1 — `from "GraphDb"` routing (A).** From a generic surface (`mcp__stroom__csvQuery` or a Query doc), run:
```
from "CorpGraph" match (u:User {id:'U1'})-[:MEMBER_OF]->(g:Group) return u.id, g.name
```
Expected: `U1, admins`. Pass: the query routes to the Cypher engine **without** `ownerDocRef` being a GraphDb.

**N‑2 — settings tab (B).** In the `CorpGraph` editor's Settings tab: set retention (enabled, 2 years), `temporalPrecision = Second`, add/confirm a node mapping; Save; close; reopen. Pass: all three persist; the Documentation tab's `description` is unchanged; a precision change is flagged as rebuild-implying.

**N‑3 — Cypher sub-query as a StroomQL join side (C).**

*N‑3a — INNER (reachability semi-join):*
```
from "AuthEvents" as e
inner join ( from "CorpGraph" match (seed:Host {ip:'10.0.0.1'})-[:CONNECTED_TO*1..3]->(h:Host) return distinct h.ip as ip ) as r
  on e.host = r.ip
select e.time, e.host order by e.time
```
Expected: 3 rows — `09:00/10.0.0.3`, `09:05/10.0.0.4`, `09:15/10.0.0.2`; the `10.0.0.9` event is excluded. Pass: the graph side runs a var-length traversal; INNER filters events to reachable hosts.

*N‑3b — LEFT (enrichment):*
```
from "AuthEvents" as e
left join ( from "CorpGraph" match (u:User)-[:MEMBER_OF]->(g:Group) return u.id as userId, g.name as grp ) as m
  on e.user = m.userId
select e.user, m.grp
```
Expected: U1→admins, U2→users (×2), U3→users; all 4 events kept. Pass: `grp` binds from the sub-query's `RETURN … AS`; LEFT keeps all left rows.

*N‑3c — temporal `AS OF` inside the side:*
```
from "AuthEvents" as e
inner join ( from "CorpGraph" match (u:User)-[:OWNS]->(a:Account {number:'A100'}) as of datetime('2026-02-01T00:00:00Z') return u.id as owner ) as o
  on e.user = o.owner
select e.user
```
Expected: only the `U1` event rows (U1 was the owner at 2026-02-01). Pass: the graph side is evaluated at the given instant.

**N‑4 — `RETURN GRAPH` element output (D2/D3).**
```
from "CorpGraph" match (u:User {id:'U2'})-[:OWNS]->(a:Account) return graph
```
Expected: an element-row table — nodes `U2`, `A100`, `A200` and edges `U2→A100`, `U2→A200`, each row carrying `kind`, `id`, `labels`, (`source`/`target` for edges) and property columns; nodes de-duplicated. Then the diff form:
```
diff from datetime('2026-04-01T00:00:00Z') to datetime('2026-07-01T00:00:00Z')
match (u:User)-[:OWNS]->(a:Account) return graph
```
Expected: element rows including a `changeKind` column, with `UNCHANGED` elements present as context (e.g. `U2→A200`) alongside the `REMOVED`/`ADDED` OWNS edges. Pass: single table, one row per distinct element, `changeKind` populated in the diff form.

**N‑5 — Cytoscape on the Data tab (D4).** Run either N‑4 query on the `CorpGraph` Data tab and switch to the **Graph** view. Pass: nodes/edges render (positions from a client layout); the Table view still works; in the diff form, elements are styled by `changeKind` (added/removed/modified/unchanged visually distinct); tapping a node reveals its properties.

**N‑6 — Cytoscape dashboard visualisation (D5).** Build a dashboard: a Query component with `from "CorpGraph" … return graph` → a Table → a **Cytoscape Visualisation** component; map the element-row columns to `id`/`source`/`target`/`label` in the vis settings. Pass: the graph renders in the dashboard pane; a node cap control truncates large results with a visible signal; tapping a node drives dashboard selection (`stroom.select`). (Depends on N‑1 routing.)

---

## 6. Coverage / traceability

| Feature | Tests |
|---|---|
| Optimiser parity / known-difference fixes | A1, A2 |
| Two-source joins (INNER/LEFT, index⋈index) | A3, A4 |
| Enrichment fast path (State/PlanB broadcast lookup) | A5 |
| `where` across joins; EXPLAIN | A6, A7 |
| Cypher anchors / fixed hops / var-length | B1, B2, B3, B4 |
| Cypher `WHERE` / `ORDER BY` / `DISTINCT` / `LIMIT` | B5, B6 |
| Cypher aggregates | B7 |
| Temporal `AS OF` / `AROUND` / `BETWEEN` | B8, B9, B10 |
| Diff delta table (done) + `before()`/`after()` | B11 |
| Unsupported-construct error handling | B12 |
| **A —** `from "GraphDb"` routing | N‑1 |
| **B —** settings tab | N‑2 |
| **C —** Cypher-as-a-join-side (INNER/LEFT/temporal, schema-from-`RETURN`) | N‑3a/b/c |
| **D —** `RETURN GRAPH` element output | N‑4 |
| **D —** Cytoscape Data-tab view | N‑5 |
| **D —** Cytoscape dashboard visualisation | N‑6 |

**Gate:** every test passes (or a deviation is recorded with justification), under `mode = ON`, and no regression appears under `SHADOW` beyond the documented known differences.

---

*This protocol is the Phase Z gate of the [feature-completion plan](graphdb-features-implementation-plan.md). Load the CorpNet dataset once; run Sections A, B and N. Sections A and B validate the pre-existing surfaces the new work builds on; Section N validates the four new workstreams.*
