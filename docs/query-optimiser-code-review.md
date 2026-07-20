# StroomQL query-optimiser — code review (2026-07-19)

A full code-quality review of the StroomQL query optimiser (the ANTLR grammar → typed AST → logical-IR binder →
rewrite rules → cost model → join execution → `QueryCompiler` facade → wiring), on branch `sw-query-optimiser`.

**Scope**: the query-optimiser code only. The temporal-Cypher-graph feature (`Cypher*` / `graphdb` / `graph*`) was
reviewed separately and is out of scope here except where the optimiser shares a type with it.

**Method**: a 7-subsystem sweep (grammar/AST, planner logical+bind, rewrite rules, cost model, join execution,
compiler facade, api/wiring) covering the four requested dimensions — code smells, error handling, javadoc, test
coverage — with each finding adversarially verified, plus a completeness/coverage critic and a plan-vs-code gap
analysis. 61 candidate findings were judged; 36 survived verification. The substantive findings were then
re-verified by hand against the source before fixing.

**Requested bar**: "code should fail with a meaningful error message if anything unexpected happens" and "tests
cover all code" — the error-handling and test-coverage findings below are organised against those two bars.

---

## 1. Fixed

All fixes landed across four commits on `sw-query-optimiser`, each verified with `compileJava` + full module
`test` + `checkstyleMain`/`checkstyleTest`.

### Commit `c076cd73ff` — join execution: resource leaks + SQL null-key semantics

| Sev | Finding | Location |
|-----|---------|----------|
| high | `realiseSide(left)` / `realiseSide(right)` ran outside the try/finally, so the **left side's `ResultStore` leaked** (LMDB temp dir / memory) whenever the right side's realisation threw. Now: `realiseSide` self-cleans on failure, and `createResultStore` destroys the left side if the right realisation throws. | `JoinSearchProvider.java` |
| med | `realiseSide` leaked its own `ResultStore` if `awaitCompletion`/`getData`/`fetch` threw (the store was only destroyable after the fully-successful return). Now wrapped in destroy-on-failure. | `JoinSearchProvider.java` |
| med | **Null equi-keys collided**: `ValNull.toString()` returns `null`, so `keyOf` built a `[null]` tuple and every null-keyed left row matched every null-keyed right row, fabricating a spurious cross-product. Now `keyOf` returns `null` for any SQL-null key component and both algorithms skip null keys (SQL `NULL != NULL`); a null-keyed left row is still null-padded for a LEFT join. | `JoinExecutor.java` |

Tests: null-key non-match (INNER + LEFT, both algorithms), composite multi-position equi-key, LEFT join
end-to-end through `JoinSearchProvider`, the two `IllegalStateException` branches (unregistered side datasource,
equi-key field not found), and left-store-destroyed-when-right-realisation-fails.

### Commit `fc89dab73e` — cost model: overflow + NaN guards

| Sev | Finding | Location |
|-----|---------|----------|
| low | `estimateCardinality` computed `leftRows * rightRows` as a `long` before dividing, overflowing to a **negative** cardinality for large inputs (worst in the unknown-distinct-keys fallback, which multiplies the full cross-product). Now a saturating multiply (`Math.multiplyExact`, clamp to `Long.MAX_VALUE`) keeps the estimate a non-negative pessimistic upper bound. | `JoinCostModel.java` |
| low | `CostEstimate`'s confidence guard `< 0 \|\| > 1` did not reject `NaN` (both comparisons are false for NaN), so a NaN confidence was stored and later corrupted `chooseAlgorithm`'s `confidence() == 0.0` test. Now rejects `Double.isNaN`. | `CostEstimate.java` |

Tests: overflow-saturates; `CostEstimate` rejects negative/out-of-range/NaN; `RowCountSignal` and
`IndexCostSignal` precondition guards; index-scan bytes unscaled + scaled by selectivity; state-lookup placeholder
duration.

### Commit `0679a3a5a1` — binder/grammar: meaningful errors + correctness + coverage

| Sev | Finding | Location |
|-----|---------|----------|
| med | `AstBuilder.buildWindow` **silently discarded the `advance`/`using` keyword tokens**, so a mistyped keyword (e.g. `window T by 1h WOBBLE max`) parsed and mis-mapped instead of being rejected as legacy does. Now validated and rejected with a clear `SyntaxException` (parity + a meaningful error). | `AstBuilder.java` |
| med | `ThrowingSyntaxErrorListener` **leaked a raw ANTLR `IllegalArgumentException` ("Invalid state number")** when `e.getExpectedTokens()` itself threw (an unterminated quoted string hits this) — defeating the listener's whole purpose of always producing a clean `SyntaxException`. The expected-token computation is now guarded (empty set on failure). *(Surfaced by a test written for this review.)* | `ThrowingSyntaxErrorListener.java` |
| low | `Binder.validateCondition` reached `Scope.onlyScan()` — a raw `IllegalStateException` — for a param term field in a multi-source query. A param has no datasource field metadata, so it now binds without condition-validation (consistent with the existing param path) and never hits `onlyScan()` with 2+ sources. | `Binder.java` |
| low | A `Filter` bound from a bare `where` query reported the `from`-clause position, not the `where` position (misdirecting EXPLAIN/error locations). Now reports the `where` (or `filter`) clause it was bound from. | `Binder.java` |
| low | `AstBuilder.build` javadoc promised a non-null `ctx` but had no guard (raw NPE). Now `Objects.requireNonNull`. | `AstBuilder.java` |

Tests: window-keyword rejection + correct-keyword parse; the `getExpectedTokens`-failure path; filter clause
build; quoted limit value (the `nameToken` branch); binder unknown-field-on-alias (qualified + join), ambiguous
unqualified field, param-in-multi-source binds cleanly, where-clause `Filter` position.

### Commit `f39b251944` — explain endpoint 4xx-not-500; facade javadoc/dead-code

| Sev | Finding | Location |
|-----|---------|----------|
| low | The `explainQuery` REST endpoint let a null body (raw NPE) and a syntax error (optimiser flag on → `SyntaxException`, which had **no `ExceptionMapper`**) surface as **HTTP 500**. Now validates the query (`RestUtil.requireNonNull`), and `BasicExceptionMapper` classifies `SyntaxException` the same as the legacy `TokenException` (a client error, not a 500). | `QueryResourceImpl.java`, `BasicExceptionMapper.java` |
| low | `OptimisingQueryCompiler` constructor javadoc falsely claimed `fieldInfoSource` is "not used by `create()`" — it is (the plan-enhancement step). Corrected. | `OptimisingQueryCompiler.java` |
| low | `LogicalPlanExplainer.explainJoin` had dead `leftIsLookup`/`rightIsLookup` locals (never read — `chooseAlgorithm` makes the `StateLookup` decision internally) plus a comment misdescribing them. Removed, dropped the now-unused import. | `LogicalPlanExplainer.java` |

Test: N-way join chain rejected cleanly (the single-join guard's error branch).

---

## 1a. Further fixes — live functional testing (2026-07-20)

The code-review pass above was static (reading + unit tests). Separately, joins were then exercised live against
a running Stroom instance (via the Stroom MCP server) for the first time — exactly the manual verification Task
6.1's gate and the testing protocol's Test D call for. That surfaced three defects that no unit test caught
(each needs the *real* Guice graph and/or the *real* LMDB-backed `DataStore`, neither of which the fake-based
`TestJoinSearchProvider`/`TestOptimisingQueryCompilerJoin` suites exercise) plus two smaller robustness gaps found
while chasing them down.

### Commit `9571063615` — fix bugs that prevent Stroom compiling, starting and running a join

| Sev | Finding | Location |
|-----|---------|----------|
| high | **Guice circular-dependency failure at startup**, as soon as a `join`-capable build registers a second `DataSourceProvider`-implementing `SearchProvider`. `DataSourceProviderRegistry`'s constructor took `Set<DataSourceProvider>` directly, forcing Guice to eagerly construct every bound `DataSourceProvider` (including `SearchProvider`s, since `SearchProvider extends DataSourceProvider`) just to build that set — and at least one member's own construction chain needs a `DataSourceProviderRegistry` back, closing a cycle. Fixed the same way the existing `SearchProviderRegistry`/`JoinSearchProvider` cycle was already avoided (see the implementation plan's Task 6.1d note): the constructor now takes a lazy `Provider<Set<DataSourceProvider>>`, and the `Map<String, DataSourceProvider>` is built on first use behind double-checked locking, not at construction time. | `DataSourceProviderRegistry.java` |
| med | `OptimisingQueryCompiler.compileJoinSide`'s synthetic per-side seed `SearchRequest` was built with a **null `QueryKey`** (`new SearchRequest(null, null, null, null, null, false, null)`). A join side's `SearchProvider` needs a real key to create/register its sub-`ResultStore` against — running a join therefore failed as soon as a side's provider tried to use it. Now seeds a fresh `new QueryKey(UUID.randomUUID().toString())`. | `OptimisingQueryCompiler.java` |
| med | `JoinSearchProvider.createResultStore`'s final `dataStore.fetch(...)` call passed a non-null placeholder `new TimeFilter(0, Long.MAX_VALUE)` to mean "no time filtering". At least one `LmdbRowKeyFactory` implementation (the ungrouped/flat key shape) treats **any** non-null `TimeFilter` as an unsupported request and throws `RuntimeException("Time filtering is not supported by this key factory")` — it doesn't matter that the range covers all representable time. Real joins over that key shape crashed on read. Fixed by passing `null` (the actual "no filter" sentinel `fetch` already understands). | `JoinSearchProvider.java` |

Also updated: `TestFieldInfoSourceAdapter` (test-only, follows the constructor signature change above).

**Not exercised by any prior unit test** because `TestJoinSearchProvider`/`TestOptimisingQueryCompilerJoin` build
their `JoinSearchProvider`/registries directly with test doubles — none goes through the real Guice injector or a
real `LmdbDataStore`. Consider a smoke-style integration test that boots the real injector and runs one join
against real `DataStore`s, specifically to catch this class of bug earlier next time.

### Commit `f23e7a21cd` — AI bugfixes via MCP server testing of query optimiser

| Sev | Finding | Location |
|-----|---------|----------|
| med | `TokenExceptionUtil.toTokenError` assumed every `TokenException` carries a source token and called `token.getChars()` unconditionally — but join-shape validation errors raised directly in `AstToSearchRequestMapper` (e.g. the N-way-join and `select *`-in-a-join rejections) throw a `TokenException` with a **null token**, so reporting one of those errors back to the caller threw a raw `NullPointerException` instead of the intended clean message. Now returns a `TokenError` with `null` line/column and just the message when the token is null. | `TokenExceptionUtil.java` |
| low | `CoprocessorsFactory.create`/`SearchResponseCreator` (`getResults`/`makeDefaultResultCreators`) iterated `searchRequest.getResultRequests()` directly, which NPEs if it's null — a real state for a compiler path that doesn't set it. Now iterate `NullSafe.list(searchRequest.getResultRequests())`. | `CoprocessorsFactory.java`, `SearchResponseCreator.java` |
| low | `QueryServiceImpl`'s dashboard-search future handler logged at `DEBUG` and returned `null` on `InterruptedException`/`ExecutionException`, silently swallowing the failure instead of surfacing it to the caller/UI. Now logs at `ERROR` and returns a `DashboardSearchResponse` carrying an `ErrorMessage(Severity.ERROR, ...)`. | `QueryServiceImpl.java` |

(The same commit also touched `CypherCompiler` to derive a Cypher query's `ResultRequest`s from its `RETURN`
clause — that's a temporal-Cypher-graph fix, out of scope for this query-optimiser doc; see
`docs/temporal-cypher-graph.md`.)

**Effect on the plan's remaining-verification item**: Task 6.1's gate (below) and the testing protocol's Test D
both flagged "a real cross-provider `index ⋈ index` run against a live backend" as the one thing not yet done.
This session did that run and found the three bugs above blocking it outright; with them fixed, a join now gets
past compile/start/execute for at least the case tested. **Not yet re-confirmed here**: a full pass proving the
*returned rows* are correct end-to-end on a live multi-provider deployment (as opposed to "it no longer throws") —
re-run the testing protocol's Test D to close that out and update this note.

---

## 2. What remains to be implemented (vs the plan)

Cross-checked against `docs/query-optimiser-implementation-plan.md`. **Phases 0–5 are genuinely complete and
wired.** Phase 6 (joins) is the frontier. These are feature/deferral items, not code-quality defects.

| Plan task | Status | What remains |
|-----------|--------|--------------|
| **6.2** Enrichment joins (State/PlanB `BROADCAST_LOOKUP`) + domain-type source discovery | not started | `JoinExecutor` throws `UnsupportedOperationException` for `BROADCAST_LOOKUP`; no `StateFetcher` wiring; no domain-type source discovery. The biggest remaining functional gap. |
| **6.3** EXPLAIN join cardinality | mostly done (2026-07-19) | A `StateLookup` side's unique-key count now feeds the cardinality (enrichment joins estimate ~= probe rows, not the full cross-product), and the nested-join case is annotated. **Still deferred**: real per-field distinct-key stats for two non-keyed sides (needs a new cost port). |
| **6.4** Domain relationships (D7) | not started | No `RelationshipType` on `DomainTypeDoc`; no relationship-mediated join routing. Marked a fast-follow in the plan. |
| **6.1** (deferred items) | partial | Execution hard-codes `HASH_JOIN` and always materialises the right side — `JoinCostModel.chooseAlgorithm` is never consulted at execution time (only in advisory EXPLAIN); no per-side filter push-down; synchronous (not async) side feed; **N-way join chains rejected** (single join only); live cross-provider `index ⋈ index` run attempted 2026-07-20 via MCP-based testing, hit and fixed three execution-blocking bugs (§1a) — full correct-rows-end-to-end confirmation still outstanding. |
| **3.1** Real `IndexShardStats` / `StateStoreStats` adapters | partial | Still NoOp stubs, so every index/state-backed `Scan` estimates `confidence = 0.0`. Deferred to the module owners. |
| **2.3** Dictionary-expansion & time-range-extraction rewrite rules | deferred | 4 of 6 designed rules implemented; these two need a dictionary-lookup port and a `Scan.timeRangePredicate` slot respectively. |
| **3.2** Node-parallelism (cluster size) cost factor | not started | `durationMs` computed as if `nodeCount = 1`; no `ClusterSizeProvider` port. |
| **5.5** Actual-vs-estimated duration correlation | deferred | Only the estimated-duration logging half exists; no completion-time hook (needs `ResultStoreManager` / `QueryServiceImpl` changes to core search infra). |
| **4.2** UI pre-run estimate | deferred | Client code implemented; full GWT/browser verification of the slow-query warning not performed; threshold is a hard-coded constant, not a `UiConfig` property. |

### Stale claims in the plan doc itself (worth correcting in `query-optimiser-implementation-plan.md`)
- The Phase 6 outcome/gate wording says joins are "driven by the cost-chosen algorithm" — not true at execution
  (`JoinSearchProvider` hard-codes `HASH_JOIN`; the cost model is only consulted by advisory EXPLAIN). Disclosed at
  the sub-task level but not the phase level.
- The Task 6.1x status claims `TestOptimisingQueryCompilerJoin` has "4 tests including a pushed-down-filter case".
  It has 3 (now 4 after this review's N-way test), and none exercises a pushed-down per-side filter — that path
  (`compileJoinSide`'s filter-applying branch) is dead from the join route, superseded by the T6.1w
  filter-post-join decision.

---

## 3. Deliberately deferred (low-ROI), pending a decision

**Update (2026-07-19): the coverage tail below was subsequently closed** (test-only) except the two brittle
logging-assertion cases — see the "Close deferred query-optimiser test-coverage gaps" commit. `ScanTimeRangeExtractor`
open-ended/malformed/unparseable branches, `MetaStatsAdapter` to-time-only, `LegacyQueryCompiler` "?" fallback,
`AutoWhereFilterSplitRule.lookupField` branches, `PlanRewriteUtil` Having/filterPredicate, and `explainJoin` now
all have tests. Still skipped as brittle: shadow-mode diff-outcome logging and `logEstimatedDuration`.

The original list (now mostly closed):

- **Shadow-mode diff-outcome logging** (`DispatchingQueryCompiler`) and **`logEstimatedDuration`** — both would
  assert log output, which is brittle; the surrounding behaviour (returns legacy, calls optimising, fail-open) is
  already tested.
- `ScanTimeRangeExtractor` open-ended-bound (`LESS_THAN`/`LESS_THAN_OR_EQUAL_TO`) + malformed-`BETWEEN` branches.
- `MetaStatsAdapter` to-time-only (`LESS_THAN`) branch.
- `LegacyQueryCompiler.explain` null-datasource (`?`) fallback.
- `PlanRewriteUtil` folding/pruning of the `Having` predicate and the `Filter.filterPredicate` slot.
- `AutoWhereFilterSplitRule.lookupField` qualified-alias-not-found + unqualified multi-scan search loop.
- `LogicalPlanExplainer.explainJoin` (costed-join and nested-join branches) — no direct test.

Two efficiency / dead-code smells documented rather than refactored (both carry more risk than value):
- `OptimisingQueryCompiler.create()` re-parses the query text a second time inside `applyPlanEnhancements`
  (a double-parse; the mapper's parse isn't currently exposed for reuse).
- `compileJoinSide`'s `Filter`-applying branch is dead from the join path (superseded by T6.1w); present but
  unreachable in production.

---

*Generated during a code-quality review pass; commit hashes are on branch `sw-query-optimiser`. §1a added
2026-07-20 after live functional testing found further bugs outside the original review's scope. Update this doc
if the deferred coverage items above are subsequently closed or the remaining plan tasks are implemented.*
