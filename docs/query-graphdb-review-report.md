# Pre-production review — query optimiser, joins, graph DB (final report)

**Scope:** the `sw-query-optimiser` production candidate — the StroomQL query optimiser, cross-datasource joins, and the graph DB (Cypher engine, temporal querying, diff, `from "GraphDb"` routing, Cypher-as-a-join-side, `RETURN GRAPH`).
**Method:** static review, adversarially verified. A first exhaustive-workflow attempt completed only its Map phase before a token limit; the concrete findings were then produced and **each verified by reading the cited code** across six paced batches (correctness, then MED sweep, security, performance, test-coverage, diff/RETURN GRAPH). Full per-finding write-ups + verification notes: [`query-graphdb-review-findings.md`](query-graphdb-review-findings.md).
**Dynamic testing:** not performed (the live Stroom MCP was down for most of the review) — this is a static report; a live pass over the top findings is recommended.

## Verdict: **GO WITH FIXES**

No permission bypass, no data loss on valid happy-path queries, and the core engines (diff classification, join execution, temporal `AS OF`, the permission model) are sound. Risk concentrates in **two design-level issues** — untyped graph properties (F2) and an unbounded graph-query memory footprint (F3) — plus a cluster of **silent-wrong-result** edges (F1, F5, F7) and one **data-integrity** gap (F4). Fix **F1–F6 and add their regression tests before production**; the rest are EXPLAIN-only, documented design limits, or low-likelihood edges that can follow.

17 confirmed findings (2 HIGH, 4 MED-HIGH, 4 MED, 3 LOW-MED, 4 LOW); 0 rejected. 3 strong positives.

> **Remediation update:** the must-fix set is now addressed in the working tree — **F1, F3, F4, F5, F6 fixed** (each with the regression test it lacked); **F2 deferred by decision** (documented known-limitation). F7–F17 remain open. See **Remediation status** below.

## Ranked findings

| ID | Sev | Area | Finding | Key location | Fix |
|----|-----|------|---------|--------------|-----|
| **F1** | HIGH | join/security | A failed enrichment lookup returns `ValErr`, which the probe treats as a **successful match** — embedding the error as the joined value; also downgrades a permission-deny to soft junk and enables state-store enumeration | `JoinExecutor.java:256`, `StateProviderImpl.java:72-75` | `ValErr` never matches (fail or null-pad); don't swallow `PermissionException` |
| **F2** | HIGH | graphdb | Every ingested property is stored as `ValString` — `WHERE`/`ORDER BY`/aggregates use string coercion (numeric `ORDER BY` sorts lexically), and `RETURN GRAPH` renders numbers as quoted strings | `GraphFilter.java:408-414`; `GraphElementExecutor.java:210-220` | Preserve typed `Val`s at ingest (codec already supports it) |
| **F3** | MED-HIGH | graphdb/scale | Graph engine has **no memory ceiling** for direct queries: `rowCap`=`LIMIT` and is disabled by `ORDER BY`/`DISTINCT`; `ORDER BY … LIMIT n` has no bounded top-N; only a 30 s deadline backstops. Broad/sorted queries can OOM | `GraphTraversalEngine.java:203,258-263`; `GraphSearchProvider.java:182` | Bounded top-N heap; engine row/byte ceiling (spill or fail-loud) |
| **F4** | MED-HIGH | graphdb | A mid-handler failure in `GraphFilter` isn't rolled back — a one-sided edge or partially-indexed node is staged and later committed | `GraphFilter.java:388-391,314-341`; `perRecord:284-292` | Abort/reset the write txn on a per-record failure |
| **F5** | MED-HIGH | join | Equi-key match is `Val.toString()` equality → cross-type keys (`1` vs `1.0`, date formats) silently mis/over-match; amplified by F2 (graph side is all `ValString`) | `JoinExecutor.java:481-491` | Type-aware key normalisation, or validate join-key types |
| **F6** | MED-HIGH | join | `LmdbJoinBuildStore.prefixKey` has no key-size bound (unlike `dbKey`) → `BufferOverflowException` crash on a long probe key with no match, on a spilling join | `LmdbJoinBuildStore.java:246-252` vs `231-238` | Bound `prefixKey`; treat over-length as a guaranteed miss |
| **F7** | MED | graphdb | Reversed `AROUND`/`BETWEEN` window (no `from<=to` guard, unlike `DIFF`) silently matches a wrong version instead of empty/error | `CypherToLogicalPlan.java:812-823`; `GraphNodeDb.java:197-208` | Add a `from<=to` guard mirroring `DiffContext` |
| **F8** | MED | graphdb | Variable-length is **node-simple**, not relationship-unique — silently under-matches vs standard Cypher | `GraphTraversalEngine.java:864-931` | Document the divergence; decide if relationship-uniqueness is wanted |
| **F9** | MED | graphdb | **No multigraph / edge identity** — edges keyed by `(src,type,dst)`, so distinct same-type edges between a pair collapse to versions of one edge | `ElementId.java:34-37`; adjacency store | Document as a v1 constraint, or add an edge-instance discriminator |
| **F10** | MED | optimiser | `EXPLAIN` is wrong for filtered/LEFT joins (`PushFiltersBelowJoinsRule` ignores `joinType`; `LogicalPlanExplainer` drops `Filter(Scan)` cost). **Execution is unaffected** | `PushFiltersBelowJoinsRule.java:87-139`; `LogicalPlanExplainer.java:126-172` | Join-type-aware rule; propagate the inner cost |
| **F11** | LOW-MED | graphdb | Self-loop double-counts under an undirected hop (non-`DISTINCT`) | `GraphTraversalEngine.java:986-997` | De-dup the BOTH branch / coalesce self-loops |
| **F12** | LOW-MED | join | `SearchProviderRegistry` silently overwrites on a duplicate datasource type | `SearchProviderRegistryImpl.java:56-71` | Fail-fast on duplicate key |
| **F13** | LOW-MED | graphdb | `DIFF` `MODIFIED` basis differs: `RETURN GRAPH` compares the whole property map, the scalar delta-table the bound row | `GraphElementExecutor` / `DiffExecutor` | Document both bases; confirm intent |
| **F14** | LOW | optimiser | `Binder` reinterprets a literal dotted field name as `alias.field` when it collides with a join alias | `Binder.java:512-531` | Quoting/escape, or document |
| **F15** | LOW | graphdb | UID counter isn't rolled back on a width-overflow encode (trigger effectively unreachable — 4.29e9 distinct type names) | `UidLookupDb.java:147-155` | Roll back `maxId` on failure |
| **F16** | LOW | join | Enrichment lookup cache is a hardcoded 1000 entries → thrash on a high-cardinality probe | `StateProviderImpl` | Make configurable |
| **F17** | LOW | security | Execution-time permission errors disclose the docref (name+UUID), unlike the resolver's generic "not found" | `GraphDbDocCacheImpl`/`getGraphDbDoc` | Align messaging |

## Must-fix-before-production (and why)

- **F1** — silently returns wrong results *and* turns an authorization deny into soft junk + an enumeration side-channel. One-line matcher fix, high impact.
- **F2** — affects **every** graph query with a numeric/date property (lexical sorts, coerced aggregates, string-typed viz values). The most pervasive correctness issue; the codec already supports the fix.
- **F3** — a broad or sorted graph query can OOM the node; the common `ORDER BY … LIMIT` is exactly the unbounded case.
- **F4** — silent data corruption (one-sided edges) on a mid-write failure.
- **F5** — silent join mis-matches, made likely by F2.
- **F6** — a routine "no match" can crash a spilling join.

Each also needs the **regression test it currently lacks** (see below) — every one of these lives in an untested corner (Batch 5).

## Remediation status (F1–F6)

All six must-fix findings addressed in the working tree (pending commit), each with the regression test it previously lacked; per-module Checkstyle + tests pass, and a combined build across all affected modules confirms they integrate.

| Finding | Status | What changed |
|---|---|---|
| **F1** (+SEC-1) | ✅ Fixed | `JoinExecutor.broadcastLookupProbe` throws `BroadcastLookupFailedException` on a `ValErr` (surfaced via `ResultStore.addError`) instead of treating it as a match; `StateProviderImpl` no longer swallows exceptions — real errors (incl. permission-deny) propagate, confirmed absence → `ValNull`. |
| **F2** | ⏸ Deferred (by decision) | Graph properties stay `ValString`; the string-coercion limitation (lexical `ORDER BY`, coerced aggregates, string-rendered `RETURN GRAPH` numerics) is a documented v1 known-limitation. F5's residual is coupled to this. |
| **F3** | ✅ Fixed | `GraphTraversalEngine` hard `MAX_ACCUMULATED_ROWS` ceiling (1,000,000, tunable) fails loud across every query shape; bounded top-N heap for `ORDER BY … LIMIT`. `ORDER BY+LIMIT+DISTINCT` stays ceiling-guarded (documented follow-up). |
| **F4** | ✅ Fixed | New additive `LmdbWriter.abort()` (other Plan B users unaffected); `GraphFilter.perRecord` commits-on-success / aborts-on-failure per record → each record atomic. Trades the 10k-change batching for correctness (accepted). *Reachability note: the edge dual-write can't partially fail via XML (identical bounds), but `addNode`'s node + property-index writes can — the fix covers both.* |
| **F5** | ✅ Fixed | `JoinExecutor.keyOf` canonicalises numeric-typed keys so `5`/`5.0`/`"5"` match; integer values kept exact (no `double` round-trip); string/date/null unchanged; purely additive. Divergent-date residual documented. |
| **F6** | ✅ Fixed | `LmdbJoinBuildStore.prefixKey` returns a guaranteed miss for an over-length probe key instead of throwing `BufferOverflowException`. |

**F7–F17 remain open** (EXPLAIN-only, design limitations, low-likelihood edges) — see the ranked table.

## Coverage matrix

| Dimension | Optimiser | Joins | Graph DB |
|---|---|---|---|
| Correctness / semantics | ✅ reviewed | ✅ | ✅ |
| Concurrency / resources | ◐ partial (spill/close reviewed; threading not) | ✅ spill/cleanup | ◐ (store cache threading not deep) |
| Performance / scale | ✅ | ✅ (bounded) | ✅ (F3) |
| Security / permissions | ✅ | ✅ | ✅ (no bypass) |
| Error handling | ✅ | ✅ (F1) | ✅ (F4) |
| Reuse / maintainability | ◐ | ◐ (dup assembleRow noted) | ◐ |
| Test coverage | ✅ mapped | ✅ mapped | ✅ mapped |
| Diff / RETURN GRAPH | — | — | ✅ |

**Not covered (flagged, not done):** LMDB cursor micro-efficiency; index-side cardinality realism; cross-shard scale (known v1 limit); field/value-level security in join *output*; the ingest pipeline's permission context; MCP/`/csv/search` auth surface; adversarial-input fuzzing of the query-text extractor / Workstream-C paren-balancer; live dynamic testing.

## What's sound (positives)

- **Permission model holds** — join, enrichment, and `from "GraphDb"` paths all enforce the querying user's `USE` permission at execution via permission-checked doc caches; elevation patterns (`asProcessingUser` for the mechanical load, `useAsRead` for scope) are used correctly. No data-access bypass.
- **Join executor is well-bounded** — INNER builds the smaller side, LEFT never swaps, the build side spills to LMDB past heap thresholds and is row-capped, the probe streams, output is capped. No OOM gap in the join itself.
- **Diff classification core is sound** — identity by interned UID (a modified element stays the *same* element), topology moves surface as REMOVED+ADDED, deterministic ordering, correct `before()`/`after()` nullability, `UNCHANGED` context retained for `RETURN GRAPH`.
- **Feature test coverage is strong** — ~731 tests; temporal `AS OF`, diff, `RETURN GRAPH`, the spill path, LEFT execution, var-length-over-cycles, and aggregation are all genuinely exercised.

## Test-hardening (highest value first)

Add a **negative/edge suite** — most entries double as the regression test for a fix above: `ValErr`/lookup-error handling (F1); numeric `ORDER BY`/aggregate on graph properties (F2); a memory-ceiling assertion once F3 lands; `GraphFilter` partial-write rollback (F4); cross-type equi-key (F5); oversized-probe-key `prefixKey` (F6); reversed temporal window (F7); self-loop (F11); registry duplicate-key (F12); `PushFiltersBelowJoinsRule` with `LEFT` (F10). Also extend `TestQueryCompilerGenerativeParity` to the join + graph surfaces for SHADOW-parity fuzzing.

---

*17 confirmed findings, adversarially verified against the code; ranked by impact. Static review only — recommend a live pass over F1–F6 once the Stroom MCP is available. Detail and per-finding reproduction in [`query-graphdb-review-findings.md`](query-graphdb-review-findings.md).*
