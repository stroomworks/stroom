# A Native Diff Operator for the Temporal Cypher Graph

**Status:** v1 (delta-table) implemented — see §13. Remainder (annotated subgraph, var-length, diff-aggregation) is design proposal.
**Audience:** Stroom engineering team, and analysts who will write the queries
**Scope:** A first-class Cypher clause that reports **what changed** in the graph between two points in time — additions, removals and property modifications — rather than the state *at* or *over* a time window. Two output modes (a delta table for analysis, an annotated subgraph for visualization), syntax, semantics, worked examples, build strategy and risk profile.
**Companion documents:**
- [`temporal-cypher-graph.md`](temporal-cypher-graph.md) — the parent design (the temporal property-graph and its `AS OF` / `AROUND` / `BETWEEN` clauses). This document extends §5.4 of that one.
- [`temporal-cypher-graph-implementation-plan.md`](temporal-cypher-graph-implementation-plan.md) — the task-by-task build plan the temporal work followed (P0.3 temporal spike, P4 window execution).

---

## 1. Executive summary

The temporal graph today answers **state** questions — *"what did the graph look like at (or across) time T?"* Three clauses cover it: `AS OF t` (a snapshot), and `AROUND t ± d` / `BETWEEN t1 AND t2` (a windowed view). None of them answers a **change** question — *"what is different between Monday and today?"*

That gap is real and it catches people out. `BETWEEN t1 AND t2` reads like a change query, but it returns the **union** of everything present at any point in the window, collapsed to one row per surviving element (see [`GraphAdjacencyDb.latestIntersectingNeighbour`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphAdjacencyDb.java)). It tells you what *existed*, never what was *added* or *removed*.

This document proposes a fourth temporal clause:

```cypher
MATCH <pattern>
DIFF FROM <t1> TO <t2>
[WHERE ...]
RETURN ...
```

`DIFF FROM t1 TO t2` compares the graph as of `t1` (the *baseline*) against the graph as of `t2` (the *comparison*) and classifies each matched element as **`ADDED`**, **`REMOVED`**, **`MODIFIED`** or **`UNCHANGED`**.

Crucially, a diff feeds **two very different consumers**, so it has **two output modes**, selected by *how you write `RETURN`*:

- **Delta table** — `RETURN changeKind, a.id, …`. Path-level set-difference; `UNCHANGED` suppressed by default. For analysis and reporting: *"list me what changed."*
- **Annotated subgraph** — `RETURN GRAPH`. The **deduplicated union of every node and edge** in the matched subgraph across *both* instants — **including `UNCHANGED`** — each row an element carrying a `changeKind` column (plus `before`/`after` values for modifications). For the graph UI to render the whole subgraph with per-element styling: unchanged elements as neutral context, added/removed/modified highlighted. Suppressing unchanged here would leave the graph **disjointed** — added and removed fragments floating with nothing to anchor them — which is exactly what this mode exists to avoid.

Both modes share one small set of RETURN additions: the `changeKind` pseudo-column and the `before(...)` / `after(...)` accessors.

**The key architectural point:** the recommended v1 is *not* a new storage or scan mechanism. It is the existing `AS OF` evaluation run twice and joined. That is what makes it tractable — it reuses the machinery the temporal work already shipped and inherits its guardrails. The genuinely new work splits in two: the **`RETURN GRAPH` element-row output** (returning whole annotated nodes/edges — a capability the engine does not have yet), and getting the **semantics** right — especially what "changed" means for a *path* in the delta table. The semantics decision, not the code, is the critical path.

Both output modes are in scope for v1; a storage-efficient single-pass variant (§7.2) is a deferred follow-on optimisation.

---

## 2. The gap today

The parent design's §5.4 defines the temporal clauses, and the code implements them exactly:

| Clause | Question it answers | How it resolves |
|---|---|---|
| *(none)* | Latest state | Floor lookup at "now" |
| `AS OF t` | State **at** an instant | Per-edge floor lookup at `t` |
| `AROUND t ± d` | State **over** `[t−d, t+d]` | Window-intersection scan, one row per surviving element |
| `BETWEEN t1 AND t2` | State **over** `[t1, t2]` | Same window-intersection scan |

Every one is a *state* operator. Each answers "what is there?" for some choice of "when". A **diff** is categorically different: it answers "what is *not the same* between two whens?", which requires **comparing two states and reporting the delta** — additions, removals, and per-property modifications, ideally with before/after values.

You can already *simulate* a diff by hand: run the pattern `AS OF t1`, run it again `AS OF t2`, and compute the set difference yourself (in a dashboard, a downstream join, or a spreadsheet). That works, and it is exactly the mental model this proposal formalises. The point of a native operator is to (a) make the common question a one-liner, (b) define the semantics *once*, correctly, including the fiddly cases people get wrong by hand (tombstones vs. filter drop-out, path identity, keeping the subgraph connected), and (c) let the planner run the two evaluations and the join efficiently under the existing cost caps.

> The grammar already enforces **one temporal clause per query** ([`Cypher.g4`](../stroom-query/stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/Cypher.g4), P0.2 spike). So you cannot express "compare `AS OF t1` with `AS OF t2`" as two clauses in one query today — which is precisely why `DIFF` is a *single* clause that internally references two instants.

---

## 3. What "changed" means — the semantic model

The graph is **single-axis valid time**: every node and edge version carries a `validFrom`, a version's validity is the half-open interval `[validFrom, nextValidFrom)`, and a delete writes a **tombstone** version meaning "absent from here on" ([`GraphNodeDb.getNode`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphNodeDb.java), and the window-scan Javadoc there). "State as of `t`" for any element is the floor lookup: the latest version at or before `t`, unless that version is a tombstone (then: absent).

**Classification is fundamentally per element.** Given the baseline instant `t1` and the comparison instant `t2` (`t1 < t2`), each node and each edge that the `MATCH` can bind has a state at each instant — **present** (with a set of property values) or **absent**. The four change kinds fall straight out of the 2×2:

| At `t1` (baseline) | At `t2` (comparison) | `changeKind` |
|---|---|---|
| absent | present | **`ADDED`** |
| present | absent | **`REMOVED`** |
| present | present, **properties differ** | **`MODIFIED`** |
| present | present, **properties identical** | **`UNCHANGED`** |
| absent | absent | *not a result* (never existed in scope) |

This per-element classification is the base semantics. The two output modes (§4.2) then either **roll it up to paths and hide unchanged** (delta table), or **present every element of the union with its annotation** (annotated subgraph).

Two facts the reader must keep in mind, both inherited from the single valid-time axis:

- **"Changed" means *changed in the world we modelled*, on the valid-time axis.** Because there is no separate transaction-time axis (bitemporal is deferred — parent doc §5.4), a diff cannot distinguish *"the fact became true at t"* from *"we corrected a mistake in what we'd recorded"*. Both look like a change. A genuine limitation, not a bug; call it out to users.
- **A tombstone is `REMOVED`, and re-appearance is `ADDED` again.** An element present at `t1`, deleted, then re-created before `t2` with identical properties classifies as `UNCHANGED` (both endpoints present and equal) — the churn *between* the endpoints is invisible to a two-instant diff. If mid-window churn matters, that is a *timeline* query, not a diff (see Open questions).

---

## 4. Proposed syntax

### 4.1 The clause

A fourth alternative in the existing `temporalClause` rule, sitting in the same slot (after the `MATCH` pattern, before its `WHERE`) as the other three:

```
temporalClause
    : AS OF instant=value                              # asOfClause
    | AROUND instant=value PLUSMINUS duration=value    # aroundClause
    | BETWEEN from=value AND to=value                  # betweenClause
    | DIFF FROM baseline=value TO comparison=value     # diffClause      // NEW
    ;
```

`FROM`/`TO` make the direction explicit and unambiguous: `FROM` is the **baseline** (the "before"), `TO` is the **comparison** (the "after"). Direction is what distinguishes `ADDED` from `REMOVED`, so it must be syntactically visible, never positional-by-accident. The instant values reuse the existing `value` rule — typically `datetime('...')` literals, exactly like the other temporal clauses.

`DIFF` is a new keyword; `FROM`/`TO`/`GRAPH` are new contextual keywords. As with the existing extension, anything outside the locked subset simply fails to parse.

### 4.2 Two output modes, selected by `RETURN`

A single `DIFF` clause; the shape of `RETURN` picks the consumer:

| | **Delta table** | **Annotated subgraph** |
|---|---|---|
| **How to invoke** | `RETURN changeKind, <exprs>` | `RETURN GRAPH` |
| **Consumer** | Analysis, reporting, alerting | The graph visualization UI |
| **Row shape** | One row per *matching path* | One row per *distinct element* (node or edge) |
| **Classification** | Path-level set-difference (§5.2) | Per-element (§3) |
| **`UNCHANGED`** | Suppressed by default | **Always included** (context) |
| **Deduplication** | None (path rows) | Nodes and edges deduplicated |
| **Connectivity** | Not guaranteed | Guaranteed within the matched subgraph (§5.6) |

The rest of the query — the `MATCH` pattern, `WHERE`, the two instants — is identical between modes. Only the terminal `RETURN` differs.

### 4.3 The RETURN additions (both modes)

- **`changeKind`** — a pseudo-column evaluating to `'ADDED'` | `'REMOVED'` | `'MODIFIED'` | `'UNCHANGED'`. Usable in `RETURN`, `WHERE`, `ORDER BY`. In the annotated-subgraph mode it is an emitted column on every element row; the UI keys its styling off it.
- **`before(expr)`** — the value of `expr` in the **baseline** (`t1`) snapshot, or `null` if the element is absent at `t1` (i.e. for `ADDED`).
- **`after(expr)`** — the value of `expr` in the **comparison** (`t2`) snapshot, or `null` if the element is absent at `t2` (i.e. for `REMOVED`).

**Bare property references** (`a.id`, `c.startTime`) resolve to *"the value in whichever snapshot the element is present in"* — the `t2` value for `ADDED`/`MODIFIED`/`UNCHANGED`, the `t1` value for `REMOVED`. Least-surprising default; use `before(...)`/`after(...)` for a specific side or both.

### 4.4 `RETURN GRAPH` — the annotated-subgraph form

`RETURN GRAPH` is a diff-specific terminal form that emits the matched subgraph, unioned over both instants, as **element rows** the graph UI can assemble and style. Because Stroom results are tabular ([coprocessors → result store], parent §5.3), this is a **conventional element-row schema** — no new result *style*, just a fixed column layout the graph component knows how to read:

| Column | Meaning |
|---|---|
| `elementType` | `NODE` or `EDGE` |
| `changeKind` | `ADDED` / `REMOVED` / `MODIFIED` / `UNCHANGED` |
| `id` | stable element id — node external id, or edge `src\|type\|dst` |
| `labels` | node labels, or the edge type |
| `sourceId` / `targetId` | edge endpoints (null for nodes) |
| `properties` | the surviving/after property map (JSON) |
| `beforeProperties` / `afterProperties` | populated for `MODIFIED` (and the present side of `ADDED`/`REMOVED`), so the UI can show "was X → now Y" |

The UI reads `elementType` to sort rows into nodes and edges, lays out the graph using `sourceId`/`targetId`, and styles each element by `changeKind` — e.g. `UNCHANGED` neutral/grey, `ADDED` green, `REMOVED` red (kept visible, perhaps dashed), `MODIFIED` amber with a before/after tooltip. Rendering itself is UI work (the parent doc's P6.3 stretch); `RETURN GRAPH` is the query-side contract that feeds it.

> **Optional property selection.** `RETURN GRAPH { a.id, u.department }` could restrict `properties`/`before`/`after` to named fields to shrink payloads on wide graphs. Additive; a v1.1 refinement, not required.

---

## 5. Semantics in detail

### 5.1 Element identity is by graph UID, not by projected value

To classify an element as `ADDED` vs `REMOVED` vs `MODIFIED`, the operator must decide *"is this the same thing at t1 and t2?"* Identity is the **interned graph UID** (node external-id UID; edge `(src, type, dst)`), **never** the projected scalar columns. If identity were by projected value, a node whose `id` you return would look like a different node the instant any property changed — which is exactly the `MODIFIED` case we want to *detect*, not hide. Interning already gives every node and edge a stable compact UID ([`UidLookupDb`](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/dao/UidLookupDb.java)), so identity is free and exact. This same UID is the dedup key for `RETURN GRAPH`.

### 5.2 Delta-table mode: path-level set-difference

The per-element classification (§3) is the base. In **delta-table** mode it is rolled up to *paths*, because the analyst asked "which of my matches changed?" A path's identity is the **ordered tuple of every element UID it binds** (`d`, `c`, `a` for `(d)-[c]->(a)`). Evaluate the pattern `AS OF t1` → tuple set `M1`; `AS OF t2` → `M2`; then `M2 \ M1` = `ADDED`, `M1 \ M2` = `REMOVED`, and `M1 ∩ M2` with any bound element's properties differing = `MODIFIED`, else `UNCHANGED` (suppressed by default).

The consequence to document: if a device's connection *moves* from `acct-17` to `acct-91`, that is **not** one `MODIFIED` row — the path topology changed, so it is `acct-17` **`REMOVED`** + `acct-91` **`ADDED`**. `MODIFIED` is reserved for *"same topology, different properties on a bound element"*. This is the honest, least-ambiguous rule, and it mirrors the parent design's decision to reject per-path `AS OF` as ambiguous in favour of a crisp per-element rule (§5.4 there).

> **The annotated-subgraph mode does *not* do this roll-up.** It classifies and emits each node and edge individually (§5.6), which is both what the UI needs and semantically *simpler* — the path-identity subtleties above do not arise, because there are no path rows.

### 5.3 `MODIFIED` detection scope

"Properties differ" compares the **full property set** of each element (node and edge props), by value, between the two snapshots — not merely the fields named in `RETURN`. A diff that reported "unchanged" because you happened not to project the field that changed would be a silent correctness trap. `before()`/`after()` (and `beforeProperties`/`afterProperties` in `RETURN GRAPH`) then surface exactly which values moved. (A future `MODIFIED ON (u.department)` narrowing is deliberately out of v1 scope.)

### 5.4 Interaction with `WHERE` — the subtle one

`WHERE` is evaluated **independently against each snapshot** (part of "evaluate the pattern as of `t`"). So an element can leave the result set between `t1` and `t2` for **two different reasons**:

1. it was **deleted** (tombstoned), or
2. it **still exists but its properties changed so it no longer satisfies `WHERE`**.

Both surface as **`REMOVED`** — because from the *filtered query's* point of view the row is genuinely gone. Defensible and consistent, but a documented sharp edge: with a filter present, `REMOVED` means *"no longer a match"*, not always *"deleted from the graph"*. The symmetric `ADDED` case (a property changed *into* satisfying `WHERE`) applies too. Where users need "deleted, specifically", they diff the unfiltered population.

### 5.5 Preconditions and errors

- `t1 < t2` is required; equal or reversed instants are a compile-time error.
- `DIFF` is mutually exclusive with the other temporal clauses (grammar-enforced).
- `changeKind` / `before` / `after` / `RETURN GRAPH` outside a `DIFF` query are a compile-time error.

### 5.6 Annotated-subgraph mode: the full connected union

This is the mode that feeds the visualization, and its rules are what keep the rendered graph coherent:

- **Union of both instants.** An element is returned iff it is present at `t1` **or** `t2` within the matched subgraph. So `REMOVED` (t1-only) and `ADDED` (t2-only) elements are *both* present in the output, alongside everything that persisted.
- **`UNCHANGED` is included, deliberately.** Unchanged nodes and edges are the scaffolding that keeps the picture connected and gives the user somewhere to anchor the highlighted changes. Dropping them — the delta-table default — would scatter added/removed fragments across an empty canvas. This is the core of the feedback that prompted this mode.
- **Per-element classification.** Each node and edge carries its *own* `changeKind` (§3), not a path roll-up. A `MODIFIED` node with entirely `UNCHANGED` edges still appears, amber, wired into its unchanged neighbourhood.
- **Deduplicated.** Each distinct node UID and edge UID appears **once**, regardless of how many matching paths traverse it — the graph the UI draws, not a path list.
- **Connectivity guarantee (within the matched subgraph).** Because the union includes every node present at either instant, the endpoints of any returned edge are themselves returned — a `REMOVED` edge is never dangling; its endpoints appear with their own `changeKind` (often `UNCHANGED`, sometimes also `REMOVED`). A node that appears *only* because a removed edge touched it is still emitted, so the "before" topology is fully reconstructable. The one honest caveat: connectivity is guaranteed over the **subgraph the pattern matched**, not the entire database — a `MATCH` that only binds two hops out cannot show a third-hop neighbour it never traversed.

---

## 6. Worked examples

Illustrative data; the returned tables show the *shape* of results.

### 6.1 Delta table — which accounts did a device start or stop talking to?

```cypher
MATCH (d:Device {id:'d-42'})-[c:CONNECTED_TO]->(a:Account)
DIFF FROM datetime('2026-07-01T00:00:00Z')
       TO datetime('2026-07-08T00:00:00Z')
RETURN changeKind, a.id, c.startTime
ORDER BY changeKind, a.id
```

| changeKind | a.id    | c.startTime          |
|------------|---------|----------------------|
| ADDED      | acct-91 | 2026-07-05T14:02:11Z |
| ADDED      | acct-93 | 2026-07-06T08:20:00Z |
| REMOVED    | acct-17 | 2026-06-28T09:10:00Z |

`UNCHANGED` connections suppressed. For the `REMOVED` row, `c.startTime` is drawn from the baseline snapshot (the only one where it exists).

### 6.2 Delta table — property change with explicit before/after

```cypher
MATCH (u:User {id:'u-7'})
DIFF FROM datetime('2026-07-01T00:00:00Z')
       TO datetime('2026-07-08T00:00:00Z')
WHERE changeKind = 'MODIFIED'
RETURN u.id,
       before(u.department) AS wasDept, after(u.department) AS nowDept,
       before(u.riskScore)  AS wasRisk, after(u.riskScore)  AS nowRisk
```

| u.id | wasDept | nowDept   | wasRisk | nowRisk |
|------|---------|-----------|---------|---------|
| u-7  | Sales   | Marketing | 12      | 47      |

Same node UID at both instants, two properties moved → `MODIFIED`.

### 6.3 Annotated subgraph — the full picture for the UI

```cypher
MATCH (d:Device {id:'d-42'})-[c:CONNECTED_TO]->(a:Account)<-[:OWNS]-(o:Owner)
DIFF FROM datetime('2026-07-01T09:00:00Z')
       TO datetime('2026-07-08T09:00:00Z')
RETURN GRAPH
```

| elementType | changeKind | id                        | labels        | sourceId | targetId | properties            |
|-------------|------------|---------------------------|---------------|----------|----------|-----------------------|
| NODE        | UNCHANGED  | d-42                      | Device        |          |          | {name:'laptop-42'}    |
| NODE        | UNCHANGED  | acct-17                   | Account       |          |          | {tier:'gold'}         |
| NODE        | ADDED      | acct-91                   | Account       |          |          | {tier:'silver'}       |
| NODE        | MODIFIED   | own-3                     | Owner         |          |          | {name:'Dana Lin'}     |
| EDGE        | REMOVED    | d-42\|CONNECTED_TO\|acct-17 | CONNECTED_TO  | d-42     | acct-17  | {startTime:'…06-28…'} |
| EDGE        | ADDED      | d-42\|CONNECTED_TO\|acct-91 | CONNECTED_TO  | d-42     | acct-91  | {startTime:'…07-05…'} |
| EDGE        | UNCHANGED  | own-3\|OWNS\|acct-91        | OWNS          | own-3    | acct-91  | {since:'2025-01-…'}   |

Note what makes this renderable as a *connected* graph: `d-42` and `acct-17` are `UNCHANGED` but still emitted, so the `REMOVED` edge to `acct-17` has both endpoints and can be drawn (e.g. dashed red) instead of floating. The UI styles by `changeKind`; a `MODIFIED` node like `own-3` would carry `beforeProperties`/`afterProperties` (elided above) for its tooltip.

### 6.4 Annotated subgraph — whole-population churn

```cypher
MATCH (h:Host)-[l:TALKS_TO]->(h2:Host)
DIFF FROM datetime('2026-06-01T00:00:00Z')
       TO datetime('2026-07-01T00:00:00Z')
RETURN GRAPH
```

Returns the entire host-to-host communication subgraph over the month, every node and link annotated — the natural input to a "what changed on the network this month?" visualization, with the stable core shown as context and the month's additions, drop-offs and modified links highlighted.

### 6.5 (v1.1 stretch) Counting the churn

```cypher
MATCH (h:Host)
DIFF FROM datetime('2026-06-01T00:00:00Z')
       TO datetime('2026-07-01T00:00:00Z')
RETURN changeKind, count(*) AS n
```

| changeKind | n   |
|------------|-----|
| ADDED      | 214 |
| REMOVED    | 63  |
| MODIFIED   | 588 |

Aggregation over a diff depends on the aggregation/RETURN-expression path the current engine does **not** yet fully wire (only bare `${var.prop}` references are resolved — [`GraphTraversalEngine.evaluate`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java)). Flagged as **v1.1**.

---

## 7. How it's built

### 7.1 Strategy A — plan-level double evaluation + anti-join *(recommended v1)*

The diff is the manual workaround promoted to an operator:

1. **Compile once.** `MATCH … WHERE … RETURN …` compiles as usual; the `DIFF` clause resolves to a two-instant temporal context (an extension of [`TemporalContext`](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/TemporalContext.java), which already carries `AS_OF`/`AROUND`/`BETWEEN`).
2. **Evaluate twice.** Run the compiled traversal `AS OF t1` and `AS OF t2` — the *exact* existing `asOfAccess` path, unchanged. Each side emits rows tagged with their bound element-UID tuple.
3. **Classify.** A new **`DiffOperator`** full-outer-merges the two tagged streams keyed on element UID, emits `changeKind`, and (on the intersection) compares property sets to split `MODIFIED` from `UNCHANGED`.
4. **Shape the output.**
   - *Delta table:* project `changeKind` / `before` / `after` / bare refs over the retained snapshot rows; suppress `UNCHANGED`.
   - *Annotated subgraph (`RETURN GRAPH`):* instead of path rows, drain the classifier into a **distinct-node set and distinct-edge set** (dedup by UID, §5.6), emit one element row each with the §4.4 schema, keep `UNCHANGED`, and guarantee endpoint inclusion. This element-row emitter is the main net-new output component.

**Why this is the right v1:** almost everything up to step 4 is reuse — the scan, floor lookup, traversal and guardrails (`MAX_VAR_LENGTH_PATH_STATES`, `MAX_TRAVERSAL_DURATION` — [`GraphTraversalEngine`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java)) all apply per side automatically. Cost is **~2× a state query plus a join**, and both result sets are materialised — acceptable for v1, bounded by the same caps.

### 7.2 Strategy B — single-pass version-run diff scan *(deferred optimisation)*

The window scan already **buffers each element's whole version run** to compute intervals ([`GraphAdjacencyDb.expandOutWindow`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphAdjacencyDb.java), [`GraphNodeDb.getNodeWindow`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphNodeDb.java)). A diff scan reuses that run to do **two floor lookups (t1 and t2) in one pass** and classify inline — no second traversal, no materialising both sides, and true element-level before/after straight from the run. Strictly more efficient, and it maps *especially* well onto the per-element annotated-subgraph mode (which is already element-centric). It does not naturally express path-level set-difference, so introduce it later as the execution path for the subgraph mode and single-element/1-hop diffs, with Strategy A remaining the general path.

Recommendation: **ship A; adopt B under the hood for the element/subgraph cases once semantics are settled and measured.**

---

## 8. What's reused vs. built new

**Reused (the bulk):** the entire `AS OF` evaluation path (anchor scan, `expand`, floor lookup); interning for element identity and dedup; the traversal cost caps and deadline; coprocessors / result store / dashboards / REST / permissions downstream; the temporal-clause grammar slot and `TemporalContext` shape.

**Built new (focused surface):**
1. The `DIFF FROM … TO …` grammar alternative + AST + a two-instant temporal context.
2. A `DiffOperator` (tagged full-outer merge on element UID + property-set comparison + `changeKind`).
3. RETURN pseudo-columns `changeKind` / `before` / `after`, wired into projection, `WHERE`, `ORDER BY`.
4. **`RETURN GRAPH` element-row output** — whole node/edge serialization (labels + property maps as returnable values, which the engine cannot do today), union assembly, dedup, connectivity, before/after per element. The heaviest new piece, and the enabler for the graph UI.
5. The semantics spec — worked examples reviewed and frozen before coding.
6. *(Deferred)* the Strategy B single-pass version-run diff scan.

---

## 9. Risk profile

The engineering is moderate; the **semantics and the new output shape carry the risk**. Ranked most-severe first.

| Risk | Severity | Why it matters | Mitigation |
|---|---|---|---|
| **`RETURN GRAPH` needs whole-element return, which the engine lacks** — today it resolves only bare `${var.prop}`; returning a whole annotated node/edge (labels + property map) is net-new ([`GraphTraversalEngine.evaluate`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java) throws on bare `RETURN n`) | **High** | It is the feature the visualization depends on; if it slips, the graph mode is undeliverable. | Land whole-element serialization as an explicit, testable sub-deliverable; it is independently useful (plain `RETURN n` benefits too). Keep the element-row schema conventional/tabular so no new result *style* is needed. |
| **Path-diff semantics (delta table) are ambiguous** — what "same path" means; topology change → REMOVED+ADDED vs MODIFIED (§5.2) | **High** | If the rule surprises analysts, every delta result is quietly mistrusted. *Note: the annotated-subgraph mode sidesteps this — it is per-element, no path rows.* | A **semantics spike** with worked examples reviewed by a domain owner **before engine code** (the parent's P0.3 pattern). Freeze §5.2/§5.3 with tests. |
| **`WHERE` drop-out conflated with deletion** (§5.4) — `REMOVED` can mean "deleted" *or* "no longer matches the filter" | **High** | Users read `REMOVED` as "deleted" and are wrong when a filter is present; an audit conclusion could hinge on it. | Document precisely; consider a distinct kind (e.g. `LEFT_MATCH`) or a flag. Decide in the spike. |
| **Result size / render load in subgraph mode** — the union of a busy subgraph over a month can be very large; unchanged context *adds* to it | **Medium** | A `RETURN GRAPH` over a hub-heavy region could return an unrenderable graph and a heavy payload. | Per-side caps + a subgraph-specific element cap; `log`/surface truncation rather than silently capping; optional property selection (§4.4) to shrink payloads; guidance to anchor diffs on a bounded pattern. |
| **Single valid-time axis** — cannot distinguish real-world change from data correction; mid-window churn invisible (§3) | **Medium** | Diffs may be read as an audit trail they are not. | State the limitation prominently; point users at a *timeline* query for churn. Bitemporal stays deferred. |
| **Double evaluation cost & memory** (Strategy A runs the traversal twice, materialises both sides) | **Medium** | A broad diff is ~2× a heavy query plus a join. | Reuse existing per-side caps; move element/subgraph diffs onto Strategy B once available; document that diffs are heavier than snapshots. |
| **Property-equality definition for `MODIFIED`** (value equality, type coercion, multi-valued ordering) | **Medium** | False/missed `MODIFIED`s erode trust and mis-colour the graph. | Define canonical value equality in the spike; reuse ingest/interning canonicalisation; test reordered/retyped props. |
| **Combinatorial blow-up on variable-length path diffs** | **Medium** | Diffing `-[:T*1..3]->` doubles an already-exponential traversal. | Inherit `MAX_VAR_LENGTH_PATH_STATES`/deadline per side; consider restricting v1 `DIFF` to fixed-length patterns. |
| **`t1 == t2` / reversed / huge ranges** | Low | Trivial mistakes returning empty or enormous results. | Compile-time `t1 < t2`; reuse the wall-clock deadline. |
| **Grammar / keyword addition** (`DIFF`/`FROM`/`TO`/`GRAPH`) | Low | Additive; omission-is-rejection still holds. | One `temporalClause` alternative + a `RETURN GRAPH` form + AST; standard grammar tests. |
| **EXPLAIN of a two-evaluation plan** | Low | Harder to read than a scan. | Render the two sub-evaluations + the diff join in `EXPLAIN` from the start. |

**Overall:** a **moderate engineering, moderate-to-high semantic risk** feature. The `AS OF` reuse removes most execution danger. The two genuinely load-bearing pieces are (1) whole-element return for `RETURN GRAPH` — a real new capability, scoped as its own deliverable — and (2) getting the *meaning* right and communicating the sharp edges (`WHERE` drop-out, valid-time-only, path identity), addressed by front-loading a semantics spike. Cheap insurance against an expensive misunderstanding.

---

## 10. Non-goals (v1)

- **Not bitemporal.** Diff is on valid time only (§3).
- **Not a change *timeline*.** `DIFF` compares two instants; it does not enumerate intermediate versions (see Open questions).
- **Not aggregation over diffs** in v1 (§6.5) — deferred to v1.1 with the expression path.
- **Not the graph *rendering* itself** — `RETURN GRAPH` defines the query-side element-row contract; force-directed/temporal styling is UI work (parent P6.3).
- **Not multi-`MATCH` / `WITH`-stage diffs** — the compiler still lowers a single reading clause (parent grammar note).
- **Not cross-graph diff** (two different `GraphDbDoc`s) — a separate feature.

---

## 11. Open questions

- **`REMOVED` disambiguation.** Should a filter-driven drop-out be a distinct kind from a true deletion (§5.4)? Resolve in the semantics spike — it materially changes how results are read *and coloured*.
- **Subgraph size ceiling.** What is a renderable/returnable element count for `RETURN GRAPH`, and how do we degrade past it — truncate-with-warning, sample, or require a tighter pattern? A call for the semantics spike, informed by the UI's rendering limits.
- **Two tables vs one superset schema.** Emit `RETURN GRAPH` as one element-row table (nodes+edges, nulls for inapplicable columns, as in §6.3) or two coprocessor result sets (a node table + an edge table)? The latter is tidier for the UI but touches the single-`ResultRequest` shape the Cypher compiler builds today ([`CypherCompiler`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/CypherCompiler.java)). Decide with the UI owner.
- **Change timeline operator.** Demand for `CHANGES BETWEEN t1 AND t2` emitting *every* version transition per element (a true changelog), not just the two-instant delta? Larger feature, naturally built on Strategy B. Park until diff ships.
- **`MODIFIED ON (…props…)`** and **`RETURN GRAPH { …props… }`** property selection (§5.3, §4.4) — additive refinements; not v1.
- **Var-length in v1?** Restrict `DIFF` to fixed-length patterns to bound cost, or allow bounded var-length from the start? A cost/scope call for the spike.
- **Direction default.** `FROM`/`TO` vs also accepting `DIFF BETWEEN t1 AND t2` as a synonym — leaning against, since `BETWEEN` already means the *window union* and overloading it reintroduces the very confusion this feature removes.

---

## 12. v1 (delta-table) — frozen decisions & scope (2026-07-21)

The semantics spike this document called for is resolved here for the **delta-table** build. These decisions are
**firm for v1**; the annotated-subgraph rules (§4.4, §5.6) are *not* frozen and are deferred with the mode.

### 12.1 v1 scope
**Delta-table mode only.** `RETURN GRAPH` (annotated subgraph), variable-length `DIFF` patterns, and aggregation
over a diff (§6.5) are **deferred to v1.1**. v1 supports **fixed-length** `MATCH` patterns only; a var-length
pattern under `DIFF` is a compile-time error.

### 12.2 Frozen semantics (confirming §3–§5)
- **Per-element classification** is the 2×2 of §3. **Path identity** for the delta table is the **ordered tuple
  of every bound element UID the pattern binds, including edges** (§5.1–§5.2); a topology change is
  `REMOVED`+`ADDED`, never `MODIFIED` (§5.2).
- **`MODIFIED` = the element's full property set differs** between the two snapshots (§5.3), not just projected
  fields.
- **Single `REMOVED` kind (resolves the §11 "REMOVED disambiguation" open question).** With a `WHERE` filter,
  both a true deletion and a filter drop-out (properties changed so the element no longer matches) classify as
  `REMOVED`; symmetrically for `ADDED`. Documented sharp edge: *with a filter present, `REMOVED` means "no longer
  a match", not always "deleted from the graph"* (§5.4). No `LEFT_MATCH` kind in v1. Users needing "deleted
  specifically" diff the unfiltered population.
- **Bare property references** resolve to the present snapshot — `t2` for `ADDED`/`MODIFIED`/`UNCHANGED`, `t1` for
  `REMOVED` (§4.3); `before(...)`/`after(...)` pin a side. `UNCHANGED` suppressed by default.
- **`t1 < t2`** required (compile-time error otherwise); `changeKind`/`before`/`after` outside a `DIFF` query are
  a compile-time error.

### 12.3 Canonical property equality (for `MODIFIED`)
Each element's properties are a `Map<String, Val>` decoded by the shared `ValSerdeUtil` codec
([`GraphPropsCodec`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphPropsCodec.java)).
Two elements are **equal** iff their property maps have the **same key set** and, for every key, **value-equal**
`Val`s. Value equality is by the type-tagged canonical value (same codec both sides — a reordered map or
insertion-order difference does not matter, keys are compared as a set; a type change, e.g. `"12"` vs `12`, is a
difference). A missing key on one side ≠ a present key on the other. This is stricter than "the fields you
projected", by design (§5.3) — `before()`/`after()` then surface which values moved.

### 12.4 Codebase corrections folded into the build plan
Verification against the code refined three points this document under-states:
1. **Edge-variable binding is a prerequisite even for the delta table.** The engine today binds only target-node
   properties and discards edge data ([`GraphTraversalEngine.acceptChainNeighbour`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java)),
   and the logical `Expand` has no relationship-variable field — so example 6.1's `c.startTime` and an edge's
   participation in the path tuple are net-new. Built first.
2. **Two-instant context via a separate `DiffContext` on `CompiledCypherPlan`, not by extending
   `TemporalContext`.** The diff runs the **unchanged** `asOf` engine path twice (`TemporalContext.asOf(t1)` and
   `asOf(t2)`) and merges — no new `TemporalContext` mode, no churn to its exhaustive switches.
3. Minor: "one temporal clause per query" is the compiler's single-`MATCH` rule, not the grammar; `DIFF`/`FROM`/
   `TO` are **hard** keywords; the engine already does aggregation (§6.5's rationale is stale — diff-aggregation
   still deferred for scoping).

### 12.5 Frozen output oracles
Worked examples **6.1** (edge add/remove delta) and **6.2** (node `MODIFIED` with `before`/`after`) are the
**expected-row oracles** for the v1 end-to-end tests: their result tables are the exact rows those queries must
produce over an equivalent fixture.

---

## 13. v1 (delta-table) — as built (2026-07-21)

The delta-table mode of §12 is implemented, tested and green. What shipped:

### 13.1 Grammar & AST
- A 4th temporal clause `DIFF FROM <baseline> TO <comparison>` (`diffClause` in
  [`Cypher.g4`](../stroom-query/stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/Cypher.g4)),
  and `before(prop)` / `after(prop)` expression accessors (`diffAccessor`). `DIFF`/`FROM`/`TO`/`BEFORE`/`AFTER`
  are hard keywords.
- AST: `AstDiff` (a 4th `AstTemporal`), `AstDiffAccessorExpr` + `AstDiffSide` (an `AstExpression`), built by
  `AstCypherBuilder`.

### 13.2 Edge binding (the §12.4.1 prerequisite)
- Logical [`Expand`](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/logical/Expand.java)
  carries a relationship-variable field; `GraphTraversalEngine` binds the traversed edge's properties to it (so
  `c.startTime`-style projections resolve for **all** Cypher, not just diff) and tracks each edge's
  `(src, edgeType, dst)` identity.

### 13.3 Planner
- `DiffContext{baseline, comparison}` on `CompiledCypherPlan` (separate from `TemporalContext`, per §12.4.2),
  with `baseline < comparison` enforced at compile time.
- `changeKind` renders to `${changeKind}`; `before(a.p)`/`after(a.p)` render to `${before.a.p}`/`${after.a.p}`
  (`CypherToLogicalPlan.diffAccessorRowKey` owns the key convention).
- Compile-time rejections: var-length under `DIFF`; `changeKind`/`before`/`after` outside `DIFF`; **v1 also
  rejects them in a `DIFF` `WHERE`** (post-classification filtering is deferred — §12.1) and rejects an aggregate
  in a `DIFF` `RETURN` (diff-aggregation deferred).

### 13.4 Execution (Strategy A, §7.1)
- `GraphTraversalEngine.executeDiffBindings` runs the fixed-length pattern at one instant, returning each path as
  a `DiffMatch{identity, flatRow}` (identity = ordered `ElementId` tuple of anchor node + per-hop edge + target
  node; edge orientation resolved per direction).
- `DiffOperator.classify` full-outer-merges the two instants' matches by identity into `ClassifiedMatch`es
  (`ADDED`/`REMOVED`/`MODIFIED`/`UNCHANGED`); `MODIFIED` is `Map<String,Val>.equals` over the flat rows, which is
  exactly §12.3's canonical equality (`Val` compares by concrete type + value).
- `DiffExecutor` suppresses `UNCHANGED`, builds one delta-table row per change (present-snapshot values +
  `changeKind` + `before.`/`after.` values), and projects it through the ordinary `RETURN`/`ORDER BY`/`DISTINCT`/
  `LIMIT` pipeline (`GraphTraversalEngine.projectDiffRows`). `GraphSearchProvider` branches to it when the
  compiled plan carries a `DiffContext`; output flows to coprocessors identically to a normal query.

### 13.5 Tests
`TestDiffOperator` (full 2×2 + topology-move + edge `MODIFIED` + present-snapshot resolution),
`TestGraphTraversalEngine` (edge binding; `executeDiffBindings` classification; `DiffExecutor` projection with
before/after and `UNCHANGED` suppression), `TestCypherToLogicalPlan` (projection rendering + the rejections), and
`TestGraphSearchProvider` (a `DIFF` query end-to-end through the real compiler-derived columns and
coprocessor/result-store path).

### 13.6 Deferred (unchanged from §12.1 / §10)
`RETURN GRAPH` annotated subgraph, variable-length `DIFF`, diff-aggregation, `WHERE`-filtering on
`changeKind`/`before`/`after`, Strategy B single-pass scan.

---

*§1–§11 are the original design proposal; §12 froze the v1 decisions; §13 records what was built.*
