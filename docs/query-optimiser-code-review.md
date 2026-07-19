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

## 2. What remains to be implemented (vs the plan)

Cross-checked against `docs/query-optimiser-implementation-plan.md`. **Phases 0–5 are genuinely complete and
wired.** Phase 6 (joins) is the frontier. These are feature/deferral items, not code-quality defects.

| Plan task | Status | What remains |
|-----------|--------|--------------|
| **6.2** Enrichment joins (State/PlanB `BROADCAST_LOOKUP`) + domain-type source discovery | not started | `JoinExecutor` throws `UnsupportedOperationException` for `BROADCAST_LOOKUP`; no `StateFetcher` wiring; no domain-type source discovery. The biggest remaining functional gap. |
| **6.3** EXPLAIN join cardinality | placeholder | Join cardinality still estimated with hard-coded `0,0` distinct keys; nested-join case un-annotated. |
| **6.4** Domain relationships (D7) | not started | No `RelationshipType` on `DomainTypeDoc`; no relationship-mediated join routing. Marked a fast-follow in the plan. |
| **6.1** (deferred items) | partial | Execution hard-codes `HASH_JOIN` and always materialises the right side — `JoinCostModel.chooseAlgorithm` is never consulted at execution time (only in advisory EXPLAIN); no per-side filter push-down; synchronous (not async) side feed; **N-way join chains rejected** (single join only); live cross-provider `index ⋈ index` run unverified. |
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

Low-severity **branch-coverage** nits on defensive or logging paths, judged not worth the churn/brittleness for
this pass. Listed so the decision is explicit, not silent:

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

*Generated during a code-quality review pass; commit hashes are on branch `sw-query-optimiser`. Update this doc if
the deferred coverage items above are subsequently closed or the remaining plan tasks are implemented.*
