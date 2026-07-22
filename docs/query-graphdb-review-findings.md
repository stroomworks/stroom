# Pre-production review — interim findings (query optimiser, joins, graph DB)

**Status:** INTERIM · candidate findings, **not yet adversarially verified**.
**Provenance:** the exhaustive review workflow hit the session token limit after its **Map** phase (3 subsystem readers) completed; the 21-cell finder fan-out, adversarial verification, and synthesis did **not** run. The findings below are the readers' flagged hotspots — high-quality and file-referenced, but each still needs a verify pass (read the cited code; confirm/reject) before it's treated as real. Several are self-flagged by the reader as "latent" or "worth confirming".
**Next step:** verify in small paced batches (see the bottom of this file), highest-severity first.

Severities below are my triage of reader impact. **Batch 1 (the HIGH findings) is now verified — see the block immediately below.**

---

## Batch 1 verification — VERIFIED against the code (5 HIGH findings)

All five confirmed by reading the cited code. Two severity adjustments noted.

1. **Reversed temporal window — CONFIRMED (adjust HIGH → MEDIUM).** `resolveTemporal` (`CypherToLogicalPlan.java:812-823`) builds `AROUND`/`BETWEEN` windows with no `from<=to` check; `TemporalContext`'s ctor (58-69) checks only null-shape; `DiffContext` (43-46) *does* guard `baseline.isBefore(comparison)`. `latestIntersecting` (`GraphNodeDb.java:197-208`) uses `!validFrom.isAfter(to) && nextValidFrom.isAfter(from)`, which for `from>to` still matches any version with `validFrom<=to`. So a swapped `BETWEEN` (or negative `AROUND` duration) silently returns a wrong version instead of empty/error. *Valid queries are unaffected* → MEDIUM. **Fix:** add a `from<=to` guard mirroring `DiffContext`.
2. **Partial graph-mutation write not rolled back — CONFIRMED (MEDIUM-HIGH).** `addEdge` (`GraphFilter.java:388-391`) does out-edge then in-edge insert; `addNode` (314-341) does node then N property-index inserts; `perRecord` (284-292) catches `RuntimeException`, **logs and continues — no txn abort**. Writes land in one shared `LmdbWriter` txn that only commits at the 10k threshold (`LmdbWriter.java:56-69`). A mid-handler failure (e.g. in-edge insert throws after out-edge succeeded) leaves the earlier write staged and later committed → a one-sided edge / partially-indexed node, breaking the dual-write invariant silently. Conditioned on a write failing mid-handler. **Fix:** abort/reset the write txn on `perRecord` catch, or stage each record's writes atomically.
3. **All ingested properties stored as `ValString` — CONFIRMED (HIGH).** `GraphFilter.toVals` (408-414) wraps every property value as `ValString`, unconditionally, despite the codec supporting typed Vals. So every type-sensitive operation on graph data (WHERE numeric/date comparisons, `ORDER BY`, `MIN/MAX/SUM/AVG`, `RETURN GRAPH` numeric JSON) runs on best-effort string coercion — e.g. `ORDER BY` on a numeric property sorts lexically (`"10" < "9"`). Broad and silent. **Fix:** preserve typed Vals at ingest (codec already supports it); at minimum document and add ORDER-BY/aggregate correctness tests. *(The exact `rowComparator` coercion is worth one confirming read, but the root cause — no type fidelity from ingest — is confirmed.)*
4. **Lookup error silently embedded as the joined value — CONFIRMED (HIGH).** `StateProviderImpl.getState` (72-75) catches every `Exception` and returns `ValErr.create(...)` instead of throwing; `JoinExecutor.broadcastLookupProbe` (254-256) computes `matched = lookedUp != null && !(lookedUp instanceof ValNull)`, so a `ValErr` counts as a **successful match** and is embedded as the row's `Value` column. This directly contradicts `JoinSearchProvider.detectPlanBLookupSide`'s Javadoc (523-529) which claims such a mismatch "would then fail … always safely captured by createResultStore's error handling" — that throw path no longer exists. Affects the documented RANGED/SESSION-store mis-detection *and* any transient lookup exception. **Fix:** treat `ValErr` as a lookup failure in `broadcastLookupProbe` (fail the search or null-pad), and/or have `StateProviderImpl` distinguish "not applicable" (→ `ValNull`) from a real error (→ rethrow).
5. **Equi-key match is `Val.toString()` equality — CONFIRMED (adjust HIGH → MEDIUM-HIGH).** `JoinExecutor.keyOf` (481-491) builds the key from `value.toString()` per component. Semantically-equal values that stringify differently across the two sides (numeric `ValLong 1` `"1"` vs `ValDouble 1.0` `"1.0"`; divergent date formats) silently fail to match; distinct values that stringify identically over-match. String keys (the common case) are fine → MEDIUM-HIGH. **Amplified by finding 3:** a graph join side emits `ValString` while an index/state side emits typed `Val`, so a numeric-keyed graph join is exactly the risk case. **Fix:** type-aware key normalisation, or validate/restrict join-key types.

**Batch 1 result:** 5/5 confirmed, 0 rejected.

## Batch 2 verification — VERIFIED against the code (9 MED findings)

All nine confirmed; several right-sized (the readers slightly over-rated a few narrow ones). Grouped by real impact.

**Data-correctness / robustness**
- **`prefixKey` overflow crash — CONFIRMED (MED-HIGH).** `LmdbJoinBuildStore.dbKey` (231-238) bounds-checks the 511-byte key and throws a friendly error; `prefixKey` (246-252, the probe read path) does **not** — `keyBuffer.put(encodeKey(key))` throws an unchecked `BufferOverflowException` for an over-length probe key. Asymmetric: the build side validates, the probe side doesn't, so a long probe equi-key that simply has no build-side match crashes the (spilling) join instead of returning "no match". **Fix:** mirror the bound in `prefixKey` and treat over-length as a guaranteed miss (it can never equal a stored key).
- **Var-length cycle guard is node-simple, not relationship-unique — CONFIRMED (MED, divergence).** `expandVarLength`/`PathState.visited` (862-931) is a `Set<Long>` of node UIDs; the Javadoc calls this deliberate but never acknowledges it diverges from Cypher (which forbids reusing a *relationship*, not revisiting a *node*). Legitimate paths that revisit a node via a distinct edge are silently excluded → under-matching vs standard Cypher. **Fix:** document the divergence (like the known-differences doc) and decide whether relationship-uniqueness is wanted.
- **Self-loop double-count — CONFIRMED (LOW-MED).** `collectNeighbours` BOTH branch (986-997) calls `expandOut` then `expandIn`; a self-loop edge (`addEdge` doesn't forbid `src==dst`) is emitted twice, and the plain scalar path doesn't de-dup without `RETURN DISTINCT`. Narrow: needs a self-loop + undirected `-[:T]-` + non-DISTINCT. **Fix:** de-dup the BOTH branch (or reject/coalesce self-loops).
- **UID counter not rolled back — CONFIRMED but LOW (was MED-HIGH).** `UidLookupDb.put` (147-155) does `++maxId` before the width-encode and only `writeMaxId` on success, so a failed encode advances in-memory `maxId` past the ceiling permanently (until restart). **Trigger is effectively unreachable** — it needs a namespace to exhaust its fixed width (4-byte type-UID ≈ 4.29e9 distinct labels/edge-types/property-keys; 6-byte node-UID ≈ 2.8e14). Real latent bug, trivial fix (roll back on failure), not a production risk.

**Scalability**
- **Unbounded graph-side materialisation + fragile cap — CONFIRMED (MED).** `GraphSearchProvider` (182) materialises the whole traversal as one `List<Val[]>` before feeding coprocessors — a **direct** graph query (Data tab) has no row guardrail at all. The only cap, `JoinSearchProvider.checkGraphSideRowCap` (684-694), applies solely when the graph is a *join side* and is keyed on the string literal `"GraphDb"` duplicated from `GraphDbDoc.TYPE` with no compile link (686) — a rename silently disables it. **Fix:** a shared/asserted type constant; consider an engine-level result cap for direct graph queries.

**EXPLAIN-only (diagnostic; execution/results unaffected)**
- **`PushFiltersBelowJoinsRule` ignores `joinType` — CONFIRMED (MED, EXPLAIN-only).** `rewriteFilter`/`classify` (87-139) push a right-side predicate below a Join with no `joinType` check. Real execution is safe — `compileSide` (292-313) ignores the rewrite's embedded Filter and recomputes via the join-type-aware `JoinPredicateSplitter` (whose Javadoc 40-46 explicitly refuses to reuse this rule). But `explain()` (653-662) feeds the *rewritten* plan to the explainer, so EXPLAIN cost/cardinality for a LEFT join with a right-side predicate is wrong. **Fix:** make the rule join-type aware, or explain from the same plan execution uses.
- **`LogicalPlanExplainer` drops cost for `Filter(Scan)` — CONFIRMED (MED, EXPLAIN-only).** `wrap` (126-130) hardcodes `costedAccessPath=null` and `explainFilter` (147-155) routes the Filter-over-Scan case through it, discarding the cost `costScan` just computed; so `explainJoin` (162-172) treats every `Join(Filter(Scan), …)` — the shape produced for essentially every filtered join — as an un-annotated "nested join". **Fix:** propagate the inner node's `costedAccessPath` through the Filter-over-Scan branch. *(Together with the finding above, EXPLAIN is unreliable for filtered joins.)*

**Robustness / edge**
- **Registry has no duplicate-key detection — CONFIRMED (LOW-MED).** `SearchProviderRegistryImpl` (56-71) `put()`s every provider then every Searchable with no clash check; two sharing a `getDataSourceType()` silently overwrite (Searchable wins, added second). Needs a config collision to trigger. **Fix:** fail-fast on duplicate key.
- **`Binder` alias.field has no escape hatch — CONFIRMED (LOW).** `resolveField` (512-531) reinterprets a dotted bareword as `alias.field` whenever the pre-dot substring matches an in-scope alias — a field literally named `a.b` in a query with a join alias `a` mis-binds or errors. Narrow (only multi-source queries; dotted field names are rare). **Fix:** a quoting/escape mechanism, or document the limitation.

**Also closes finding #3 (ORDER BY):** `rowComparator` (1216-1231) orders by `Val` natural order; since ingest stores everything as `ValString`, a numeric property **does** sort lexically (`"10" < "9"`). Confirms #3's ORDER-BY consequence.

**Batch 2 result:** 9/9 confirmed, 0 rejected (4 severity-adjusted down, 0 up).

## Batch 3 — security / permissions (new review, not reader-sourced)

Focus: can a join, enrichment lookup, or `from "GraphDb"` path read a datasource the querying user isn't permitted to? Verified against the code.

**Reassuring headline — no data-access bypass found.** Every path enforces the current user's `USE` permission at execution through the *same* permission-checking doc caches a standalone query uses, and the elevation patterns are correct (identity is never swapped in a way that skips the gate):
- `GraphDbDocCacheImpl.get` (116) and `PlanBDocCacheImpl.get` (98) both throw `PermissionException` unless `securityContext.hasDocumentPermission(docRef, USE)` for the **real** user; each loads the doc under `asProcessingUser` *only* for the mechanical read, after the user's `USE` check.
- Graph execution — standalone, **graph-join-side**, and **`from "GraphDb"`** alike — funnels through `GraphSearchProvider.createResultStore` → `getGraphDbDoc` (107-115: `useAsReadResult { graphDbDocCache.get(name) }`), so the `USE` gate always fires. `useAsRead` elevates scope, not identity, so the `USE` check still evaluates the querying user.
- The enrichment/state lookup (`StateProviderImpl.getState` → `PlanBDocCacheImpl.get`) is `USE`-gated the same way.
- `DataSourceResolver.resolveDataSourceRef` (42-61) resolves `from "X"` via a **permission-filtered** registry lookup — an unauthorised name surfaces as *"not found. You may not have permission to use it."*, not a positive disclosure.

**SEC-1 — permission-deny on an enrichment (State) join side is silently downgraded to embedded error text — CONFIRMED (MED).** `StateProviderImpl.getState` (72-75) catches the `PermissionException` from `PlanBDocCacheImpl.get` and returns `ValErr(e.getMessage())`; `broadcastLookupProbe` then treats that `ValErr` as a *successful match* (finding #4). So a user lacking `USE` on the state store doesn't get a failed query — they get result **rows whose `Value` column contains "You are not authorised to read &lt;docRef&gt;"**. Consequences: (a) a hard authorization deny becomes a soft "success" with junk data; (b) the docref identity + internal error text is disclosed *in results*; (c) it enables **state-store existence enumeration** (distinct `ValErr` messages for "no permission" vs "no such store"). No actual store *data* leaks (the lookup failed before reading values). This is the security dimension of finding #4 and raises its priority. **Fix (same root as #4):** `ValErr` must never count as a match; and/or `getState` should not swallow `PermissionException` — propagate it so the query fails cleanly with an authorization error.

**SEC-2 — execution-time permission errors disclose the docref — CONFIRMED (LOW).** On the graph path an unauthorised `from "GraphDb X"` / graph-join side fails at `getGraphDbDoc` with `PermissionException("You are not authorised to read <docRef>")` (name + UUID), confirming the doc exists and is a GraphDb — inconsistent with the resolver's generic "not found" for name resolution. Low (Stroom surfaces docrefs in permission errors elsewhere), but worth aligning.

**Coverage note (honest):** this batch targeted cross-datasource permission bypass + elevation patterns. **Not** covered here: field/value-level security in join *output*, the ingest pipeline's permission context, the MCP/`/csv/search` auth surface, and adversarial-input fuzzing of `LeadingDataSourceExtractor` / the Workstream-C paren-balancer (a malformed leading-`from` safely falls through to StroomQL; no concrete issue seen, but not fuzzed). Flag these for a follow-up slice if wanted.

**Batch 3 result:** 1 MED + 1 LOW confirmed; **no permission bypass** (positive result). SEC-1 reinforces HIGH finding #4.

## Batch 4 — performance / scale (new review, not reader-sourced)

Focus: memory/time bounds on the graph engine and the join executor. Verified against the code.

**PERF-1 — the graph engine has no memory guardrail for direct queries — CONFIRMED (MED-HIGH).** `GraphTraversalEngine.execute` accumulates the whole result into an in-memory `List<Map<String,Val>>` (263). The only accumulation bound is `rowCap` (258-260) = the compiled Cypher `LIMIT`, and it is **`Long.MAX_VALUE` when there is no `LIMIT` *or* when `ORDER BY`/`DISTINCT` is present** (203: the cap is deliberately disabled so every row can be seen for sort/dedup). The sole backstops are a 30-second **time** deadline (`MAX_TRAVERSAL_DURATION`) and, for variable-length only, `MAX_VAR_LENGTH_PATH_STATES=200_000`. There is no memory ceiling, no spill, and no `maxSideRows`-equivalent for a *direct* (Data-tab / dashboard) query. A broad `MATCH` over a large graph — or any `ORDER BY`/aggregate/`RETURN GRAPH`/`DIFF` (which all materialise the full set, and DIFF twice) — can accumulate millions of rows in ~30 s and OOM the node.
  - **Sharp edge:** `ORDER BY … LIMIT n` does **not** use a bounded top-N heap — it materialises all rows, sorts in memory (`rowComparator`), then takes `n`. So the common interactive "sorted top-N" is exactly the unbounded case. **Fix:** a bounded priority queue for `ORDER BY`+`LIMIT`; an engine-level row/byte ceiling independent of `LIMIT` (mirroring the join's `maxSideRows`/spill); ideally spill or fail-loud past it rather than OOM. (Sharpens Batch-2 MED-4; the join graph-side cap only fires *after* this materialisation.)

**Positive — the StroomQL join executor is well-bounded — CONFIRMED (reassurance).** `joinAndFeedViaStreamingHashJoin` (356-411): for INNER it builds the **smaller** side by `DataStore.getSize()` and streams the larger; LEFT never swaps (keeps probe=left so unmatched rows emit inline); the build side realises into a `BuildSideLookup` that **spills to LMDB** past `maxHeapBuildRows`/`maxHeapBuildBytes`, is capped at `maxSideRows`, and the probe streams with output capped at `maxOutputRows`. No OOM gap in the join executor itself — the memory risk it inherits is only via a *graph* side (PERF-1), which it can only cap post-materialisation.

**PERF-2 — enrichment lookup cache is a fixed 1000 entries — CONFIRMED (LOW).** `StateProviderImpl` uses a Caffeine cache `maximumSize(1000)`, hardcoded. A broadcast-lookup (enrichment) join whose probe side has >1000 distinct keys thrashes the cache → a real Plan B point-lookup per row (N lookups, the expected worst case for a point-lookup join, but with no headroom tuning). Not configurable. **Fix:** make the size configurable, or document the cardinality assumption.

**Coverage note:** covered graph-traversal memory/time bounds, join build/spill/probe selection, and the enrichment cache. Not covered here: LMDB read/cursor efficiency micro-analysis, index-side cardinality realism, and cross-shard scale (single-shard is an already-documented v1 limit).

**Batch 4 result:** 1 MED-HIGH + 1 LOW confirmed, plus 1 strong positive (join executor bounded). PERF-1 sharpens Batch-2 MED-4.

## Batch 5 — test-coverage (new review)

Suite size on the review surface: **~731 `@Test`s** across grammar (71), planner (175), common (285), graphdb-impl (159), searchable-impl (41).

**Positive — the core is well-covered (verified, correcting my own first-pass false alarms):** temporal `AS OF` (`TestGraphTraversalEngine.singleHopMatchReturn_…_bothLatestAndAsOf`), the diff operator (`TestDiffOperator` + `executeDiffBindings_classifies…`, `diffExecutor_projectsChangeKindAndBeforeAfter_suppressingUnchanged`), `RETURN GRAPH` (`returnGraph_yieldsOneRowPerDistinctNodeAndEdge…`, `…whereClause…`, `diffReturnGraph_classifiesEachElementIndependently…`), the **spill path** (dedicated `TestSpillingBuildSideLookup`, `TestLmdbJoinBuildStore`), LEFT-join *execution* (8–9 files), var-length over cycles, and aggregation are all genuinely exercised. Feature/happy-path coverage is solid.

**The gap is negative/edge/failure coverage — and it maps almost 1:1 onto the confirmed findings.** Grep of all test sources found *no* test touching these (that's largely *why* the bugs survived):

| Missing test (grep: 0 hits) | Guards finding | Priority |
|---|---|---|
| lookup error / `ValErr` handling in a broadcast-lookup join | #4 + SEC-1 (HIGH) | **HIGH** |
| oversized probe key → `prefixKey`/`MAX_KEY` boundary (though `TestLmdbJoinBuildStore` exists, it doesn't assert the key-size limit) | Batch-2 `prefixKey` crash (MED-HIGH) | **HIGH** |
| partial-write / rollback on a mid-handler `GraphFilter` failure | #2 (MED-HIGH) | **HIGH** |
| reversed `AROUND`/`BETWEEN` window (`from>to`) — `AS OF` is tested, the window guard is not | #1 (MED) | HIGH |
| cross-type equi-key (`ValLong` vs `ValDouble`/`ValString` as one logical key) — `keyOf` tests use `ValLong`/`ValString` only | #5 (MED-HIGH) | HIGH |
| `ValString`-typed numeric `ORDER BY`/aggregate (lexical-sort) | #3 (HIGH) | HIGH |
| self-loop under an undirected hop (double-count) | #2 self-loop (LOW-MED) | MED |
| `detectPlanBLookupSide` both-sides-qualify tie-break | reader (LOW) | MED |
| `SearchProviderRegistry` duplicate datasource-type | Batch-2 (LOW-MED) | MED |
| `Binder` literal dotted field colliding with an alias | Batch-2 (LOW) | LOW |
| `PushFiltersBelowJoinsRule` with `JoinType.LEFT` (its own test builds only INNER, though LEFT *execution* is covered elsewhere) | MED-6 (EXPLAIN) | MED |
| concurrency on `GraphStoreManager`/`GraphDbDocCache` | invariants (Batch-2) | MED |
| memory ceiling on a broad/sorted graph query | PERF-1 | (add with the fix) |

**Takeaway:** coverage is feature-broad but failure-shallow. The single most valuable test-hardening investment is a **negative/edge suite** for the corners above — most double as the regression tests for the fixes. Note also there is **no SHADOW-parity fuzz/generative test** targeting the join + graph surfaces specifically (there is `TestQueryCompilerGenerativeParity` for StroomQL — extending it to joins would catch optimiser/legacy divergences).

**Batch 5 result:** no *new* defects (test gaps aren't runtime bugs); a prioritised "tests to add" list, tightly aligned to Batches 1–4. Corrected 4 first-pass false "untested" alarms (temporal/RETURN GRAPH/spill/LEFT are covered).

## Batch 6 — diff / RETURN GRAPH correctness (new review)

**Positive — the classification core is sound.** `DiffOperator` (a pure, unit-tested full-outer merge) keys identity on **interned UIDs, never projected values** (`ElementId`), so a property change keeps an element the *same* element (→ `MODIFIED`, not hidden); a topology move correctly surfaces as `REMOVED`+`ADDED`; ordering is deterministic (t2 order, then t1-only `REMOVED`); `before()`/`after()` nullability is handled (ADDED→null before, REMOVED→null after) and a `REMOVED` row renders from the baseline snapshot. `RETURN GRAPH` dedups by element identity and includes `UNCHANGED` context.

**RG-1 — no multigraph / edge identity — CONFIRMED (MED, design limitation).** An edge's identity is `(srcUid, edgeTypeUid, dstUid)` (`ElementId.Edge`; the RETURN GRAPH id is literally `src|type|dst`, `GraphElementExecutor:174-177`), and the adjacency store keys on that triple + `validFrom`. So two *distinct* same-type edges between the same pair cannot coexist — they are treated as temporal **versions of one edge**; the later overwrites/supersedes. For an event/relationship graph (e.g. multiple calls/transactions between the same two entities — the POLE use case) this silently collapses distinct edges. Neo4j users expect a multigraph with per-relationship identity. **Fix:** document prominently as a v1 constraint, or add an edge-instance discriminator.

**DIFF-1 — `MODIFIED` comparison basis differs between the two output modes — CONFIRMED (LOW-MED).** `DIFF … RETURN GRAPH` classifies `MODIFIED`/`UNCHANGED` over the **whole element property map** (`GraphElementExecutor.toDiffMatches` passes `detail.properties()`), whereas the scalar delta-table classifies over the bound/projected row (`DiffExecutor` → `engine.executeDiffBindings`' `flatRow`). So the *same* element under the same `DIFF` window can classify differently depending on whether you add `RETURN GRAPH` — and the scalar table can read `UNCHANGED` for a change in a property it didn't materialise. **Fix:** document the two comparison bases explicitly; confirm the scalar scope is intended.

**RG-2 — numeric/boolean properties render as quoted JSON strings — CONFIRMED (LOW; = finding #3's RETURN-GRAPH face).** `renderJsonValue` (`GraphElementExecutor:210-220`) has correct `BOOLEAN`/`LONG`/`DOUBLE` branches, but since ingest stores every property as `ValString` (finding #3) `value.type()` is always `STRING` → the numeric branches are dead → a numeric property emits `"123"` not `123`. A Cytoscape client can't size/scale/threshold by a numeric property without re-coercing. Root cause and fix are finding #3 (preserve types at ingest).

**Connectivity note (LOW):** `renderEndpoint` emits an edge's `source`/`target` by UID regardless of whether those nodes are themselves rows in the union — fine for normal `(a)-[r]->(b)` patterns (endpoints are bound), but a pattern that binds an edge without both endpoints could yield a dangling edge reference for the renderer.

**Batch 6 result:** 1 MED + 2 LOW confirmed, plus a strong positive (diff classification core is sound). RG-2 = finding #3's rendering face.

**REVIEW COMPLETE (Batches 1–6).** See [`query-graphdb-review-report.md`](query-graphdb-review-report.md) for the compiled, ranked report.

---

## Graph DB

- **[HIGH · correctness] Reversed temporal window matches the wrong version.** `AROUND`/`BETWEEN` never guard `from <= to` (`CypherToLogicalPlan.resolveTemporal` ~812-823; `TemporalContext` ctor 58-69), unlike `DiffContext` (40-46) which does. The shared intersection test `validFrom<=to && nextValidFrom>from` (`GraphNodeDb`/`GraphAdjacencyDb`/`GraphInEdgeDb` ~197-225) assumes `from<=to`; a swapped `BETWEEN` or a negative `AROUND` duration silently matches an unrelated version rather than returning empty.
- **[HIGH · data integrity] Partial graph-mutation write is silently committed.** `GraphFilter.perRecord` (pipeline/`GraphFilter.java` 284-292) isolates a failing record, but `addEdge`/`addNode` do multiple writes (out-edge then in-edge 389-390; node then N property indexes 314-341) in one long-lived `LmdbWriter` that only commits at a 10k threshold. If a later write throws after an earlier succeeds, the partial write isn't rolled back — it's later committed, producing a one-sided edge or partially-indexed node with no record of the inconsistency.
- **[HIGH · correctness] All ingested properties stored as `ValString`.** `GraphFilter.toVals` (408-414) wraps every property as `ValString` despite typed-Val support in the codec. So WHERE (`GraphRowValueFunctionFactory` 59-97), ORDER BY (`rowComparator`), MIN/MAX/SUM/AVG, and `RETURN GRAPH` numeric/boolean JSON rendering all run on best-effort string coercion. Leading-zero ids, locale-formatted numbers, or ISO strings that also parse as numbers silently take wrong comparison/sort/aggregate semantics.
- **[MED-HIGH · availability] UID counter not rolled back on width overflow.** `UidLookupDb.put` (dao 136-160) does `++maxId` before validating the value fits the configured fixed width; on the width-ceiling throw, `maxId` isn't rolled back, so **every** subsequent intern of a new value in that namespace fails permanently until process restart. Graph namespaces use fixed 4-byte type-UID width (~4.29e9 ceiling).
- **[MED · correctness] Self-loop / undirected hop double-counts.** `collectNeighbours` BOTH branch calls `expandOut` then `expandIn` unconditionally (986-997); a self-loop edge under `-[:T]-` is discovered twice and the plain scalar path has no de-dup unless `RETURN DISTINCT` — so a non-DISTINCT query over a self-loop returns the match twice. (DIFF/RETURN GRAPH paths absorb it via map-keyed accumulation.)
- **[MED · correctness] Var-length cycle guard is node-visited, not relationship-visited.** `expandVarLength`/`PathState.visited` (864-971) blocks revisiting a node, but Cypher forbids reusing a *relationship*; legitimate paths through cyclic/diamond subgraphs are silently excluded (under-matching vs standard Cypher).

## Joins

- **[HIGH · correctness] Lookup error silently embedded as the joined value.** `StateProviderImpl` (planb pipeline 60-76) returns a `ValErr` on any exception instead of rethrowing; `JoinExecutor.broadcastLookupProbe`'s match test (`lookedUp != null && !(lookedUp instanceof ValNull)`, ~256) treats `ValErr` as a *successful* match, so a failed lookup (e.g. the documented RANGED/SESSION-store gap) is embedded as the row's Value column rather than failing or null-padding. Contradicts `JoinSearchProvider`'s own Javadoc.
- **[HIGH · correctness] Equi-key match is `Val.toString()` equality.** `JoinExecutor.keyOf` (481-491): numeric `1` vs `1.0`, or format-divergent dates, silently fail to match; distinct values that stringify identically silently over-match. Only `ValLong`/`ValString` are tested. Especially risky for joins across a graph side and an index/state side with different value types.
- **[MED · guardrail] Graph-side row cap keyed on a duplicated string literal.** `JoinSearchProvider.checkGraphSideRowCap` (137, 684-694) matches `"GraphDb"` copied from `GraphDbDoc.TYPE` with no compile link; a rename in the graphdb module silently disables the cap — and `GraphSearchProvider` materialises its whole traversal as an uncapped `List<Val[]>` (182).
- **[MED · robustness] SearchProvider registry has no duplicate-key detection.** `SearchProviderRegistryImpl` (56-71) unconditionally `put()`s every provider then every Searchable; two sharing a `getDataSourceType()` (or a clash with the `"StroomQLJoin"` sentinel) silently overwrite, Searchable winning.
- **[LOW-MED · latent] Graph-join schema derived twice from the same Cypher text** (bind-time, discarded; compile-time, used) with nothing pinning that both passes yield the same column set — a future non-deterministic naming change would fail late at execution (`JoinSearchProvider.positionOf` 729-737) not at bind. (`OptimisingQueryCompiler` 336-379 vs `Binder` 323-348.)
- **[LOW-MED · coverage] `LmdbJoinBuildStore` per-key insertion-order** relies on a monotonic sequence suffix + big-endian `putLong` sorting under LMDB memcmp; neither is directly asserted (144-150, 230-244).
- **[LOW · latent] `StateFetcherImpl`** (22-31): a future second `StateProvider` that throws aborts the whole row lookup with no fallback and no way to distinguish "not applicable" from "broken".
- **[LOW · coverage] `detectPlanBLookupSide` "both sides qualify → left wins"** (534-546) has no test; a reorder of the two eligibility checks would silently flip which side is streamed.

## Optimiser core

- **[MED · correctness(EXPLAIN)] `PushFiltersBelowJoinsRule` ignores `joinType`.** classify()/rewriteFilter() (~87-139) pushes a right-side-only predicate below a Join for LEFT exactly as if INNER. Real execution is safe today (`compileSide` discards the rule's Filter and recomputes via `JoinPredicateSplitter`), **but `EXPLAIN` feeds the un-corrected rewritten plan to `LogicalPlanExplainer`** — so cost/cardinality for a LEFT join with a right-side predicate can be silently wrong. Also a live trap for any future consumer of the embedded Filter. No LEFT-join coverage in the rule's tests.
- **[MED-HIGH · robustness/crash] `LmdbJoinBuildStore.prefixKey` no size bound.** `dbKey` (231-244) checks the 511-byte `MAX_KEY_SIZE` and throws a clear error; `prefixKey` (246-252, used by probe reads) does not — a long probe-side equi-key that was never on the build side throws an unchecked `BufferOverflowException` (confusing crash) instead of a clean "no match".
- **[MED · correctness(EXPLAIN)] `LogicalPlanExplainer` drops cost for `Filter(Scan)`.** `wrap()` (~126-130) hardcodes `costedAccessPath=null` and `explainFilter` routes through it even in the Filter-over-Scan branch where a real cost was just computed; so `explainJoin` treats any `Join(Filter(Scan), …)` — the shape produced for essentially every filtered join — as an un-annotated "nested join". Likely a one-line fix.
- **[MED · correctness] `Binder` alias.field disambiguation has no escape hatch.** resolveField (512-531): a field literally named `a.b` when a join alias `a` is in scope is always reinterpreted as `alias.field` — spurious `Unknown field` error, or (worse) silently binds to the wrong source if `a` also exposes `b`. Inherent to the single-BAREWORD-token grammar choice; no way to force a literal.
- **[LOW-MED · maintainability] GraphJoinSource push-down exclusion enforced in 4 unlinked places** (`PlanRewriteUtil.collectScans`, `AutoWhereFilterSplitRule`, `PushFiltersBelowJoinsRule`, the `GRAPH_SIDE_PUSH_DOWN_SENTINEL` dance) — a future change to `collectScans` could silently re-enable predicate push-down into a Cypher body nothing can execute against.
- **[LOW · maintainability] `toPushedFilter`** (458-460) builds a synthetic `Filter(scan, …)` using the Scan as its own input; must never reach the shared tree-walkers that assume a Filter's input is its real child. No type-level guard.
- **[LOW · docs] Stale `Binder` class Javadoc** (100-108) says "not yet wired into any executor" — but `createJoin` now wires a bound/rewritten Join into real execution; misleads a reviewer about the join path.

---

## Invariants captured (use as a regression / verification checklist)

The readers also documented the safety/correctness properties this code must uphold — a ready-made checklist for verification and for tests. Highest-value ones:

- **Joins:** SQL `NULL != NULL` on every join path yet still null-padded for LEFT; LEFT preserves each left row once; caps checked *per row* not after accumulation; combined-row shape always `[left…, right/Key,Value…]` regardless of physical build/probe side; a LEFT join must **never** pre-filter its right side; `BuildSideLookup` two-phase (all `put` before any `get`), `close()` idempotent & non-throwing; every ResultStore/spill dir closed exactly once even on throw; `JoinSpec` always ≥1 equi-key.
- **Optimiser:** every `RewriteRule` result-preserving; `CostModel` never negative/throwing, cardinality saturates not overflow-wraps; `applyPlanEnhancements` fail-open (any exception → original legacy request unmodified); Binder fold reproduces legacy left-associative AND/OR shape for JSON parity.
- **Graph DB:** edge dual-write to adjacency + in-edge stores kept consistent only by GraphFilter's paired calls (no cross-DAO transaction); fixed-width UID ceilings never exceeded; retention keeps the latest-at-or-before cutoff + all newer versions per entity; property-index anchors are append-only so callers must re-validate against the query's temporal context; the three copies of the compiled-plan-unwrap shape must stay in lockstep; `CompiledCypherPlan` mutual-exclusivity (temporal vs diff, var-length rejected under DIFF/RETURN GRAPH) enforced only at compile time; `ElementId` equality is by interned UID (safe only because UIDs are never reused).

(The full 34-invariant list is in the workflow journal: `…/subagents/workflows/wf_c917a247-cf0/journal.jsonl`.)

---

## Coverage & how to finish (token-paced)

**What the Map pass covered:** each subsystem reader surfaced 6–8 hotspots spanning correctness, resources, and maintainability — good breadth, but **not** the full 7-dimension sweep, and **nothing is adversarially verified**. Under-covered: dedicated performance-scale, security-permissions, and test-coverage sweeps; the diff operator and `RETURN GRAPH` element paths beyond what's above.

**Plan to complete without blowing the token budget** — small batches, one per turn, verifying against the real code rather than re-running a large fan-out:
1. **Verify the HIGH findings first** (graph temporal-window, partial-write, ValString typing; join ValErr-as-match, toString equi-key) — read the cited code, confirm or reject each. ~5 findings/batch.
2. **Verify the MED findings** in the next batches.
3. **Fill the coverage gaps** (performance-scale, security-permissions, test-coverage; diff/RETURN GRAPH correctness) — one subsystem/dimension at a time.

Verification can be done inline (reading the cited files directly, no subagent fan-out) to keep spend low and steady.
