# StroomQL query-optimiser — user testing protocol

## Is it testable by users?

**Yes.** The optimiser is reachable by an administrator/analyst on a running Stroom instance, with two
independent surfaces:

1. **The compile path** behind a runtime config flag (`stroom.query.optimiser.mode`) — when switched on, real
   StroomQL queries (Query docs, Dashboards) are compiled by the optimising engine instead of the legacy one, and
   `join` queries become available.
2. **The EXPLAIN REST endpoint** (`POST /api/query/v1/explainQuery`) — advisory only; returns the compiled plan
   and a cost estimate without running anything. Testable regardless of the flag.

It ships **OFF by default** (the legacy compiler serves every query), so nothing changes until a tester opts in.

**Read the "Known limitations" section before testing** — several advertised capabilities are deliberately not
yet wired, and testing them expecting full behaviour will mislead.

---

## 1. Configuration

| Property | Values | Default | Where to set |
|----------|--------|---------|--------------|
| `stroom.query.optimiser.mode` | `OFF`, `SHADOW`, `ON` | `OFF` | Admin **Properties** screen (runtime, no restart) or `config.yml` |

- **OFF** — legacy `SearchRequestFactory` compiles and serves every query; the optimiser never runs.
- **SHADOW** — legacy still compiles and serves every query (identical to OFF from the caller's view); the
  optimiser *also* compiles the same query, best-effort, purely to **log** any divergence and an
  estimated-duration line. Zero risk to served results — the ideal soak/validation mode.
- **ON** — the optimiser compiles and serves every query; legacy never runs.

`config.yml` example:

```yaml
stroom:
  query:
    optimiser:
      mode: SHADOW
```

Via the UI: log in as admin → **Properties** → filter for `stroom.query.optimiser.mode` → set the value (takes
effect immediately, cluster-wide, reversible at any time).

---

## 2. Test A — EXPLAIN endpoint (no data or flag needed)

The most self-contained test: it compiles a query and returns the plan, without executing it.

**Request** — needs a valid identity. Because this is a state-changing `POST`, a **session cookie alone is rejected with 403** by the CSRF filter (the Swagger UI "Try it out" button uses the session cookie, so it 403s). Authenticate with an **API key / Bearer token** instead (Swagger: click **Authorize** and paste the key; `curl`: `-H "Authorization: Bearer <token>"`), which is exempt from the CSRF check.

```
POST /api/query/v1/explainQuery
Content-Type: application/json
Authorization: Bearer <api-key>

from "MyIndex" where EventTime > now() - 1d select StreamId, EventTime
```
**The body is the raw StroomQL text**, sent verbatim — *not* a JSON-quoted string. Wrapping it in quotes
(`"from \"MyIndex\" ..."`) makes the parser treat the quotes as part of the query and fail with
`missing FROM`.

**Expected result** — an `ExplainPlan` JSON tree, e.g.:

```json
{ "description": "Project", "children": [
    { "description": "Filter", "children": [
        { "description": "Scan MyIndex as MyIndex (FullScan)",
          "estimatedRows": 500, "estimatedDurationMs": 5, "confidence": 0.5,
          "notes": ["..."] } ] } ] }
```

**What to verify**
- A well-formed query returns a nested plan whose leaf is a `Scan <datasource>` node.
- The scan node carries `estimatedRows`/`confidence` **only if** a cost signal was available (a real MetaService
  feed count). For index/state-backed sources the cost adapters are stubs, so `confidence` is commonly `0.0` and
  the estimate is a fallback — this is expected (see limitations).
- A **malformed** query (e.g. `select foo`) returns an HTTP **400 Bad Request** with a clear message (the syntax
  error), **not** a 500. An empty body also returns 400. (The JSON error body's `code` matches the HTTP status.)

This endpoint's behaviour does **not** depend on `stroom.query.optimiser.mode`.

---

## 3. Test B — Parity (flag ON must not change single-source results)

**Goal**: with the optimiser serving queries, an ordinary single-source query returns exactly what it did under
legacy.

**Test data**: any existing populated datasource (an Index or a Searchable) — e.g. the standard example data, or
a small feed→index you already have. No new data format is needed.

**Procedure**
1. With `mode = OFF`, open a **Query** doc (or Dashboard) against the datasource, run a representative StroomQL
   query, and record the result table.
2. Set `mode = ON` (Properties screen).
3. Re-run the **same** query.

**Expected result**: identical result rows and columns in both runs. The optimiser at parity produces a
byte-identical `SearchRequest` for single-source queries (this is enforced by the differential-parity test suite;
the documented intentional divergences are listed in [query-optimiser-known-differences.md](query-optimiser-known-differences.md)).

**One documented, intentional exception** — a bare `where` clause mixing an index-eligible term with a term on a
field unknown to the index returns **zero rows under legacy** (an ANDed `MatchNoDocsQuery`) but the
actually-matching rows under the optimiser. This is a behaviour *improvement*, recorded in the known-differences
doc; treat differing output there as correct, not a regression.

---

## 4. Test C — Shadow-mode soak (recommended pre-rollout)

**Goal**: confirm the optimiser agrees with legacy on real traffic before ever serving with it.

**Procedure**
1. Set `mode = SHADOW`.
2. Drive normal query traffic (run your usual dashboards/queries for a while).
3. Inspect the server logs for `DispatchingQueryCompiler` lines.

**Expected result**
- Divergences are logged at `INFO` (query text + both compiled forms); matches at `DEBUG`.
- Every logged divergence should trace to a **documented** case (the where/filter-split improvement above, or the
  bracket/`is null` cases in the known-differences doc). Any *undocumented* divergence is a finding to report.
- Served results are unaffected (legacy serves them) — verify user-facing behaviour is unchanged from OFF.

---

## 5. Test D — Joins (the new user-facing capability; flag ON)

`join` is new StroomQL syntax the optimiser adds. It runs only when `mode = ON`.

**Test data**: two datasources sharing a joinable key, e.g. an events index with a `UserId` column and a
users/reference datasource with an `Id` column. (Two Searchables, or two Indexes, both work — the join runs
in-memory over each side's results.)

**Query** (explicit aliases required; no `select *`):

```
from "Events" as a
join "Users" as b on a.UserId = b.Id
where a.StreamId = 1
select a.StreamId, b.Name
```

**Expected result**
- **INNER** join (above): one combined row per matching `a.UserId = b.Id` pair; rows with no match on the other
  side are dropped; the outer `where` is applied across the combined row.
- **LEFT** join (`left join "Users" as b …`): every left row is kept; unmatched left rows have the right-side
  columns as `null`.
- A **NULL** join key on either side does **not** match another NULL (SQL semantics) — such rows are dropped
  (INNER) or null-padded (LEFT), never cross-producted.
- `select *` in a join → rejected with a clear message.
- **More than one `join`** (an N-way chain) → rejected with a clear "single join" message (see limitations).

**EXPLAIN a join** via Test A:
```
"from \"Events\" as a join \"Users\" as b on a.UserId = b.Id select a.StreamId, b.Name"
```
→ a `Join (…)` node over two `Scan` children. With placeholder cost signals (the common case — see limitations),
the planner has no distinct-key counts to justify a hash build, so the node currently reads
`Join (NESTED_LOOP, build side: LEFT)` and carries the note *"distinct-key counts unknown - cardinality is the
pessimistic upper bound (full cross-product)"*. (A `BROADCAST_LOOKUP` is named instead when a side is a keyed
State/PlanB lookup.) Note this is only the *named* plan — execution always runs an in-memory hash join regardless
(see limitations).

---

## 6. Known limitations (test against these expectations, not the full vision)

These are deferred/partial — see [query-optimiser-code-review.md](query-optimiser-code-review.md) for the full
gap list.

- **Cost-chosen algorithm is not wired into execution.** Real joins always run as an in-memory `HASH_JOIN`
  materialising the right side; `EXPLAIN` may *name* a different algorithm/build side, but execution ignores it.
  (Correctness is unaffected — only performance.)
- **Enrichment joins to a State/PlanB store are not implemented.** A `join` whose side resolves to a
  `BROADCAST_LOOKUP` will fail at execution — only index/searchable ⋈ index/searchable joins execute.
- **Single join only.** Any chain of more than one `join` is rejected up front.
- **No per-side filter push-down.** Each join side is fetched in full and filtered after the join (fine for
  correctness; can be slow on large sides).
- **Cost estimates are largely placeholders.** The MetaService (feed row-count) adapter is real; the index and
  State cost adapters are NoOp stubs, so index/state scans usually show `confidence = 0.0` in EXPLAIN. Distinct-
  key-based join cardinality is only refined for a keyed State-lookup side.
- **`SKIP`, `ORDER BY`(graph only), dictionary-expansion and time-range-extraction rewrite rules** are not all
  implemented; `SKIP` in StroomQL and the two deferred rewrite rules are out of scope for this build.
- **Live cross-provider join** (index ⋈ index against a real backend) has been proven in-module but not fully
  confirmed on a live multi-provider deployment. Running this protocol's Test D live (2026-07-20, via the Stroom
  MCP server) surfaced three bugs that no in-module test caught — a Guice startup circular-dependency, a null
  `QueryKey` on a join side's synthetic sub-query, and a placeholder `TimeFilter` rejected by one row-key-factory
  shape — all now fixed (see [query-optimiser-code-review.md](query-optimiser-code-review.md) §1a). A join no
  longer fails outright, but a full correct-rows-end-to-end pass on a live deployment hasn't been re-recorded
  here — re-run Test D to close this out.

---

## 7. Reporting

For any failure, capture: the exact StroomQL, the `stroom.query.optimiser.mode` value, the result (or error +
HTTP status), and — for a parity/shadow divergence — both compiled forms from the log. A divergence that matches
a case in `query-optimiser-known-differences.md` is expected; anything else is a bug.
