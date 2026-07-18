# Plan: Grammar-driven parser + cost-based query optimiser for Stroom

## Context

Stroom's StroomQL is compiled today by a **hand-coded** pipeline:
`Tokeniser` (a sequence of regex passes, `stroom-query-language/.../token/Tokeniser.java`) →
`StructureBuilder` (nesting, `.../token/StructureBuilder.java`) →
`SearchRequestFactory` (~1,490 lines, `stroom-query-common/.../language/SearchRequestFactory.java`) →
`SearchRequest`. There is **no formal grammar**, error reporting is regex-driven, and the language is hard to evolve.

Crucially, there is also **no query optimiser**. Execution is driven directly by walking the `SearchRequest`:
it is routed to exactly one backend by DocRef *type* (`SearchProviderRegistryImpl`), and the user must **manually**
decide which predicates are index-eligible (`where`) vs applied post-hoc in memory (`filter`). The engine never
chooses an access path, never prunes by index time-partition, and cannot tell the user how long a query will take
before they run it.

This project replaces the parser with an **ANTLR4 grammar** and inserts a **rule-rewrite + cost-based optimiser**
that automates predicate placement / index selection and produces a **pre-run time estimate**, derived from
metadata Stroom already tracks. Delivery is **incremental behind a feature flag**, coexisting with the current
engine until parity is proven.

Direction confirmed with the user: (1) ANTLR4 grammar; (2) rule rewrites then cost-based plan choice;
(3) estimates derived from existing metadata; (4) incremental rollout behind a flag.

## Goals

- Replace the regex tokeniser/structure-builder with a maintainable **ANTLR4 grammar + typed AST**, with precise
  syntax errors (line/column, expected tokens).
- Introduce a **logical plan IR** and **rule-based rewrites** that automatically place predicates (auto `where`/`filter`
  split), prune, fold constants, and extract time ranges for partition pruning.
- Add a **cost-based physical planner** that enumerates access paths per datasource and picks the cheapest.
- Support **joins across datasources** — equi-joins (INNER and LEFT OUTER) that let one query combine, e.g., an
  event index with a PlanB/State store or reference data, or two indexes on a shared key. The optimiser chooses the
  join order, the join algorithm, and which predicates push to each side.
- Produce a **cost + duration estimate** and an **EXPLAIN** plan surfaced in the query editor *before* the query runs.
- Reuse the existing execution machinery (`SearchProvider`, `ResultStore`, coprocessors, extraction pipelines,
  `FederatedSearchExecutor`) — the optimiser emits into it, it does not replace it.

## Non-goals (this project)

- **Non-equi joins** (join predicates other than `key = key`) and **temporal stream-to-stream correlation joins**
  (windowed event correlation). Equi-joins are in scope; these richer join forms are a later extension built on the
  same operator (the windowing infrastructure already exists and can be reused then).
- RIGHT/FULL OUTER joins in v1 (INNER + LEFT OUTER only; a RIGHT join is expressed by swapping sides).
- Replacing the expression/function engine — `ExpressionParser` and `stroom.query.language.functions.*` stay as-is
  and are invoked for `eval`/`select`/`having` expressions.
- Changing result storage, coprocessors, or the cluster search protocol.
- Building a statistics-collection job or query-telemetry store (noted as future accuracy upgrades, not v1).
- **Requiring domain types** — the semantic-type integration ([Domain types](#domain-types-semantic-layer)) is
  strictly advisory. A query whose fields carry no domain type plans **identically**; nothing in the engine may
  depend on a domain type being present.

## Target compilation pipeline

```
StroomQL text
  → ANTLR Lexer + Parser (StroomQL.g4)            [Phase 1]
  → ParseTree → typed AST (visitor)               [Phase 1]
  → Bind & validate (resolve DocRef + fields,     [Phase 2]
      type-check, check conditions vs ConditionSet)
  → Logical plan IR (Scan/Filter/Eval/Join/        [Phase 2]
      Aggregate/Having/Window/Sort/Limit)
  → Rule rewrites (predicate pushdown = auto       [Phase 2]
      where/filter split, push filters below joins,
      join reordering, prune, const-fold,
      time-range extraction, dictionary expansion)
  → Physical planning + cost-based choice:         [Phase 3/6]
      access path per source (index-scan w/
      partition pruning vs full-scan vs state-
      lookup) AND join algorithm (broadcast-lookup
      vs hash-join vs nested-loop)  ── CostModel
  → CostEstimate + EXPLAIN (surface pre-run)        [Phase 4]
  → Executor: realise plan as SearchRequest(s) to   [Phase 5]
      existing SearchProvider / FederatedSearchExecutor
```

## New modules & the integration seam

- **`stroom-query/stroom-query-grammar`** (new) — the `StroomQL.g4` grammar (Gradle `antlr` plugin, server-side only;
  the GWT client parses nothing — it calls REST), generated parser, and the typed AST + AST-builder visitor.
- **`stroom-query/stroom-query-planner`** (new) — logical IR, binder, rewrite rules, physical planner, `CostModel`,
  `CostEstimate`, `ExplainPlan`. Depends on `stroom-query-api` (for `QueryField`/`ConditionSet`/`IndexField`) and on
  the metadata services for costing (see Phase 3).
- **`QueryCompiler` facade** (new, in `stroom-query-common`) — one interface, two implementations:
  - `LegacyQueryCompiler` → wraps the existing `SearchRequestFactory`.
  - `OptimisingQueryCompiler` → grammar + planner, emitting a `SearchRequest` **plus** a `CostEstimate`/`ExplainPlan`.
- **Feature flag** — a new property (e.g. `stroom.query.optimiser.enabled`, default `false`) on the query/search
  config, following the existing `*Config` AbstractConfig pattern (e.g. alongside
  `stroom-query-common/.../v2/SearchResultStoreConfig.java`).

**The switch point is one method.** Both validation and execution funnel through
`searchRequestFactory.create(query, sampleRequest, expressionContext)`:
- `stroom-query-impl/.../QueryServiceImpl.java:605` (`mapRequest`, used by both `search()` and `validateQuery()` at `:214`)
- `stroom-query-impl/.../QueryStoreImpl.java`

Replace that direct call with `queryCompiler.compile(...)`; the flag selects the implementation. This keeps the
blast radius tiny and guarantees coexistence.

## Reuse (do not rebuild)

- **Capability metadata already exists**: `QueryField.getConditionSet()` / `ConditionSet` (`stroom-query-api/.../datasource/`)
  declare which `Condition`s each field/backend supports — the optimiser uses this both to validate and to decide
  push-down eligibility. `QueryField.queryable()` tells indexed vs non-indexed.
- **Field discovery**: `DataSourceProviderRegistry.getFieldInfo(FindFieldCriteria)` and `getTimeField(docRef)`
  (`stroom-query-common/.../v2/DataSourceProviderRegistry.java`) for the bind phase.
- **Routing / execution**: `SearchProviderRegistry` + `SearchProvider.createResultStore(SearchRequest)`; keep
  `FederatedSearchExecutor`, `ResultStore`, coprocessors, and `stroom-search-extraction`.
- **Cost signals** (Phase 3): `IndexShard` fields `documentCount`, `fileSize`, `commitDocumentCountPs()`,
  `partitionFromTime`/`partitionToTime` (`stroom-core-shared/.../index/shared/IndexShard.java`) via
  `IndexShardService`/`TimePartitionFactory`; `MetaService.find`/`getMaxId` for stream counts by feed/type/time.
- **Expressions**: `ExpressionParser` + functions for `eval`/`select`/`having`.
- **Semantic (domain) types already exist**: a `domainType` string is carried on `QueryField`/`IndexField`/`Column`
  (`stroom-query-api/.../datasource/QueryField.java`, `.../Column.java`), populated from the index field by
  `LuceneSearchProvider`, with a catalogue document (`DomainTypeDoc`) and a wildcard matcher `DomainType.canAccept`
  (`stroom-core-shared/.../domaintype/shared/DomainType.java`). Dashboards already declare the types they handle
  (`DashboardDoc.domainTypes`) and resolve them server-side via `DashboardStoreImpl.findByType`. The optimiser
  reuses this semantic layer for join reasoning — see [Domain types](#domain-types-semantic-layer).

## Domain types (semantic layer)

Stroom already carries a **semantic type** on fields and columns: `domainType`, a `class.attribute` string such as
`Host.ipaddress` or `Asset.location`, with single-segment `*` wildcards and a matcher `DomainType.canAccept`
(`stroom-core-shared/.../domaintype/shared/DomainType.java`). It sits *above* the physical `FieldType` and the
capability `ConditionSet`: it says what a value **means**, not how it is stored or which conditions push down. Today
it is **inert for execution** — the only behavioural consumer is the client "Jump to…" navigation and its server
matcher `DashboardStoreImpl.findByType`; `canAccept` is otherwise exercised only by a unit test. Nothing feeds a
domain type into a predicate, access path, or cost. This optimiser would be its first execution-side consumer.

Domain types add **nothing** to single-source planning (Phases 1–5), which is driven entirely by `FieldType` +
`ConditionSet` + `queryable()` + shard metadata as above. Their value is concentrated in the capabilities this
project newly adds — **joins, cross-source enrichment, and costing** — where "what does this column mean" is real
signal. Consistent with the non-goal above, every use below is advisory and must degrade gracefully.

- **Semantic join-key validation & discovery — Phase 2 bind, Phase 6 execution.** When binding
  `join B on a.k = b.k`, check the two keys' domain types with `a.canAccept(b) || b.canAccept(a)` (either side may
  hold the wildcard). Reject/warn on semantically incompatible joins (e.g. `Asset.id = Host.ipaddress`, both strings
  physically) at plan time rather than at run time; and, where keys line up by meaning across differently-named
  columns (`src_ip: Host.ipaddress` ⋈ `ipAddress: *.ipaddress`), offer/confirm the equi-key. This is "match by
  meaning, not a brittle key" applied to joins.
- **Enrichment-source routing — Phase 3 interface, Phase 6 execution.** Generalise the dashboard "handled types"
  registry (`DashboardDoc.domainTypes` → `findByType`) into a planner-side index of *which datasources can be looked
  up / joined by a given domain type*. State/PlanB/reference stores declare the types they answer for; the
  broadcast-lookup join then **discovers** candidate `GetState`/`StateProvider` sources for a typed probe key
  instead of the author hard-wiring them.
- **Cross-source value canonicalisation — Phase 2 rewrite.** Cross-source equi-joins need value-domain agreement
  (`192.168.0.1` vs `3232235521`; MACs with/without colons). Where a domain type owns a canonical form, a rewrite
  inserts a normalising `eval` on both join sides. This is a correctness enabler for heterogeneous joins, not merely
  an optimisation.
- **Cardinality/selectivity refinement — Phase 3 cost model, gated on stats.** A per-domain-type distinct-value /
  selectivity estimate sharpens the join-cardinality formula (`distinct(key)`) and the equality-vs-range heuristic.
  This needs a statistics source; stats collection is a post-v1 non-goal, so it rides the later
  telemetry-calibration path and is **not** a v1 item.
- **EXPLAIN annotation — Phase 4.** Surface the semantic key and any inserted normalisation ("joined on
  `Host.ipaddress` ≈ `*.ipaddress`") so a user can see why a cross-source join was permitted.

The downstream **temporal Cypher graph** is a further consumer: `domainType` is a ready-made node/property type
system for `Cypher.g4` — another reason to thread the semantic type through the `Join` IR from Phase 2.

**Scope for v1**: the two cheap, mechanism-reusing wins — semantic **join validation** (Phase 2) and
**enrichment-source routing / key compatibility** (Phase 6, interface defined in Phase 3). Canonicalisation follows
as cross-source joins mature; selectivity-from-stats waits for telemetry. Matching stays deliberately blunt (two
segments, one `*`, no subtyping) — strong enough to *validate* a join, so auto-inference always **confirms** rather
than silently rewrites.

## Phased delivery

### Phase 0 — Scaffolding
Create the two new modules and the `QueryCompiler` facade + config flag (default off). `LegacyQueryCompiler`
delegates to `SearchRequestFactory`; wire `QueryServiceImpl`/`QueryStoreImpl` to the facade. **No behaviour change.**

### Phase 1 — Grammar + parser at parity
- Author `StroomQL.g4` covering every current construct: `from`, `where`, `filter`, `eval`, `having`, `group by`,
  `sort by [asc|desc]`, `window <field> by <dur> [advance <dur>]`, `select … [as …]`, `limit`, `show`, logical
  `and/or/not`, brackets, `in`/`in dictionary`, `between … and …`, `is [not] null`, all comparison operators,
  arithmetic, `${param}`, function calls, single/double-quoted strings, ISO date-times, durations, and `//` + `/* */`
  comments. Keyword list mirrors `TokenType` (`stroom-query-api/.../token/TokenType.java`). A verified EBNF snapshot
  of these constructs is provided as a starting checklist in
  [Appendix A](#appendix-a-stroomql-grammar-reference-phase-1-seed).
- **Reserve the join syntax in the grammar now** (parsed, but rejected with a clear "not yet enabled" message until
  Phase 6): `from <SOURCE> [as <alias>]` and
  `[left] join <SOURCE> [as <alias>] on <alias.field> = <alias.field> [and …]`, plus `alias.field` qualified
  references throughout. Adding new keyword tokens (`JOIN`, `ON`, `LEFT`, and optional `INNER`) is a grammar edit
  only — no downstream engine changes in this phase.
- Visitor builds a typed AST; `OptimisingQueryCompiler` initially maps AST → the **same `SearchRequest`** the legacy
  path produces (no optimisation yet).
- **Parity gate**: golden test runs legacy vs new over the existing StroomQL corpus (`TestStructureBuilder`,
  `SearchRequestFactory` tests) and asserts identical `SearchRequest`; plus new error-reporting tests.

### Phase 2 — Logical plan + rule rewrites
- Define the logical IR operators (Scan, Filter, Eval/Project, **Join**, Aggregate, Having, Window, Sort, Limit).
  Include the `Join` operator in the IR from the start (multi-input node with join type + equi-key list) so later
  rules and costing are shape-complete, even though single-source is what executes until Phase 6.
- **Bind/validate** against `getFieldInfo` + `ConditionSet` (fail fast with good messages when a field/condition is
  unsupported, replacing ad-hoc checks in `SearchRequestFactory`). Resolve `alias.field` qualified references to the
  correct `Scan` input, and validate that join equi-keys are domain-type-compatible
  (see [Domain types](#domain-types-semantic-layer)).
- **Rewrite rules**: predicate pushdown that **auto-derives the `where`/`filter` split** (index-eligible terms →
  datasource; the rest → post-filter), **push single-source filters below joins** (so each side scans as little as
  possible), constant folding, redundant-term pruning, dictionary expansion, and **time-range extraction** (isolate
  the time predicate for later partition pruning). Existing explicit `where`/`filter` queries still compile
  identically; the optimiser only *improves* placement when it can prove equivalence.

### Phase 3 — Cost model + physical planning
- `CostModel` derives estimates from existing metadata only:
  - **Streams/meta**: `MetaService` count by feed/type + time range (with `getMaxId` for id-range selectivity).
  - **Lucene**: sum `IndexShard.documentCount` over shards whose `[partitionFromTime, partitionToTime]` overlaps the
    query time range (partition pruning), scaled by predicate selectivity heuristics keyed off `FieldType`/`ConditionSet`
    (equality « range « unindexed-scan); convert to time via `commitDocumentCountPs()` throughput and `fileSize`.
  - **PlanB/State**: key/row-count from the store's info; point-lookup vs scan.
  - **SQL-stats / Searchable / Solr / Elastic**: use provider-exposed counts where available, else typed fallbacks.
  - **Extraction**: `estimatedRows × per-event extraction-pipeline constant` when an extraction pipeline is used.
  - Divide scan work by **node parallelism** (cluster size) to model federated wall-clock.
- Enumerate access paths per datasource, pick min estimated cost. Emit chosen physical plan +
  `CostEstimate{rows, bytes, durationMs, confidence, notes}`. Calibration constants live in config.
- **Join costing (interface defined here, exercised in Phase 6)**: estimate join output cardinality from the two
  side estimates and an equi-key selectivity heuristic (`|A ⋈ B| ≈ |A|·|B| / max(distinct(A.key), distinct(B.key))`,
  distinct-key counts approximated from field type + side row estimates). The planner then costs each join
  algorithm — **broadcast-lookup** (tiny build side, e.g. a State/PlanB store: build-side rows × probe-side rows
  lookup cost), **hash-join** (materialise + probe both sides), **nested-loop** (fallback) — and picks the cheapest,
  choosing the smaller estimated side as the build side.

### Phase 4 — EXPLAIN + pre-run estimate in the UI
- Add a `QueryResource` `explain`/`estimate` endpoint (server-side, no grammar keyword needed) returning the
  `ExplainPlan` + `CostEstimate`; reuse the bind/rewrite/cost path from Phases 2–3.
- Surface in the query editor before **Run**: show estimated duration + a collapsible plan tree, and warn when the
  estimate exceeds a configurable threshold. Client hooks live near the existing Ace completion providers
  (`stroom-core-client/.../query/client/presenter/`), which already call REST rather than parsing locally.

### Phase 5 — Plan-driven execution (single datasource) + rollout
- Executor realises the chosen single-source physical plan as `SearchRequest`(s) into the existing providers
  (initially the plan simply parameterises the `SearchRequest`: computed where/filter split + shard/time-partition
  pruning hints), reusing `FederatedSearchExecutor`, `ResultStore`, coprocessors, extraction.
- Add a lightweight actual-vs-estimated logging hook (foundation for the future telemetry-calibrated cost model).
- Enable the flag per environment after a soak for single-source queries.

### Phase 6 — Joins (multi-source execution)
- Enable the reserved join grammar and lower `Join` logical nodes to a **physical join executor** that:
  1. runs each side as its own sub-query through the existing `SearchProvider`/`FederatedSearchExecutor`, producing
     per-side result streams (reuse `ResultStore`/coprocessors — no new storage layer);
  2. combines them with the cost-chosen algorithm — **broadcast-lookup**, **hash-join**, or **nested-loop** —
     honouring INNER vs LEFT OUTER semantics.
- **Reuse existing lookup machinery for enrichment joins**: joins to a State/PlanB or reference-data store can lower
  to the existing state-fetch functions (`GetState`, `StateProvider`, `StateFetcher` in
  `stroom-query-language/.../functions/`) for the broadcast-lookup path, rather than a new lookup mechanism.
  Candidate enrichment sources are discovered by **domain type** — the planner-side analogue of
  `DashboardStoreImpl.findByType` (see [Domain types](#domain-types-semantic-layer)).
- Extend `EXPLAIN` + `CostEstimate` to show join order, algorithm, and per-side estimates.
- Roll out joins behind the same flag after single-source soak; once parity + accuracy hold, deprecate
  `SearchRequestFactory`.

## Test-driven development & dual-run parity

The project is structured so the **legacy engine is a live oracle for the new one**. Both implementations sit behind
the single `QueryCompiler` facade and both emit a `SearchRequest`, so any StroomQL string can be compiled by *both*
and the outputs compared — in a unit test, a generative harness, or production. That equivalence check is the
backbone of delivery, and the work is done **test-first**: each phase lands its failing tests before its
implementation, and no phase merges until its parity gate is green.

**TDD per phase — write the test first, then make it pass:**
- *Grammar (Phase 1)* — for every construct in `TokenType`, a lexer/parser test asserting the expected token stream
  and parse tree; plus negative tests asserting the precise error position (line/column, expected tokens) *before*
  the rule exists.
- *AST builder (Phase 1)* — table-driven `text → expected AST` cases.
- *Binder (Phase 2)* — `(query, field metadata) → resolved plan | expected error`, covering unsupported
  field/condition and (new) semantically-incompatible join-key cases.
- *Rewrite rules (Phase 2)* — each rule as a pure `input logical plan → expected rewritten plan` function, tested in
  isolation and in combination; explicit-`where`/`filter` inputs asserted **unchanged**, auto-split inputs asserted
  equivalent.
- *Cost model (Phase 3)* — synthetic `IndexShard`/meta fixtures asserting **monotonicity** properties (tighter time
  range ⇒ fewer shards ⇒ lower cost; equality < range < unindexed scan) rather than exact magic numbers, so the
  tests survive calibration-constant changes.
- *Join planning/execution (Phase 6)* — cardinality and algorithm-selection tests (tiny build side ⇒
  broadcast-lookup; two large sides ⇒ hash-join) and INNER/LEFT-OUTER correctness over two sources.

**Three layers of equivalence, tightening over time:**
1. **Golden differential tests (hard merge gate, from Phase 1).** Run the existing StroomQL corpus
   (`TestStructureBuilder`, `SearchRequestFactory` tests) through *both* compilers and assert an **identical
   `SearchRequest`**. Because term ordering and boolean nesting can differ harmlessly, compare on a **canonical
   normal form** (sorted/flattened `ExpressionOperator`, normalised whitespace/params) — the canonicaliser is itself
   unit-tested. A new construct is not "done" until it is in this corpus.
2. **Property-based / generative testing.** A small StroomQL generator emits random-but-valid queries over
   representative datasources; each is compiled by both engines and diffed against the same normal form, catching the
   long tail the hand-written corpus misses. Every failing seed becomes a permanent regression case.
3. **Shadow (compare-only) dual-run in production.** Add a third flag mode beside on/off — **shadow**: the legacy
   compiler still produces the executed `SearchRequest`, but the optimiser compiles the same query in parallel and
   its output is diffed and logged (divergences recorded, never served). This exercises the new path on real traffic
   at **zero user risk** and turns real queries into a divergence report that feeds back into layer 1. An environment
   flips from shadow to on only after a clean soak.

**Two altitudes of "same".** Because the optimiser *improves* placement (auto `where`/`filter` split, pruning), a
byte-identical `SearchRequest` is the right assertion **only where it is asked not to reorder** (parity mode). Where
it does optimise, the assertion moves up a level — **identical results** (and cost no worse) for the same inputs. So
parity is checked as *same plan* for the un-optimised path and *same answer* for the optimised path. Joins have no
legacy counterpart, so they are verified against **hand-computed expected result sets** and the invariant that
**join order/algorithm choice never changes the result**.

The concrete test inventory per area is in **Verification** below; this section is the method that produces it.

## Verification

- **Grammar/parity**: golden tests comparing legacy vs ANTLR `SearchRequest` over the full existing corpus; negative
  tests asserting precise syntax-error positions.
- **Planner**: rule-rewrite unit tests (input logical plan → expected rewritten plan), incl. that explicit `where`/`filter`
  queries are preserved and auto-split queries are provably equivalent.
- **Cost model**: unit tests with synthetic `IndexShard`/meta metadata asserting monotonic, sane estimates (tighter
  time range ⇒ fewer shards ⇒ lower cost; equality ⇒ lower than range ⇒ lower than unindexed scan); join-cardinality
  and algorithm-selection tests (tiny build side ⇒ broadcast-lookup chosen; two large sides ⇒ hash-join).
- **Joins**: correctness tests for INNER and LEFT OUTER equi-joins across two sources (index⋈state, index⋈index),
  including filter-pushdown-below-join equivalence and join-reordering results being identical regardless of order.
- **End-to-end** (flag on, dev instance): run representative StroomQL via the Stroom MCP tools — `validateQuery`,
  the new `explain`/`estimate` endpoint, and `csvQuery` — including at least one cross-source join query, and through
  a dashboard; confirm identical results to the legacy engine (for single-source) and that estimated durations track
  measured durations within a target error band on a benchmark set.
- **Regression**: full `stroom-query`/`stroom-search` test suites green with the flag both off and on.

## Risks / mitigations

- *Grammar drift from legacy behaviour* → the Phase-1 parity gate over the existing corpus is a hard merge gate.
- *Inaccurate estimates from coarse metadata* → ship with confidence bands + notes, keep calibration constants in
  config, and add the telemetry-calibration path later without reworking the interface.
- *Rollout risk* → feature flag + one-method switch seam means instant revert to the legacy compiler; a
  **shadow (compare-only) mode** exercises the optimiser on live traffic before any environment serves its output
  (see [Test-driven development & dual-run parity](#test-driven-development--dual-run-parity)).
- *ANTLR codegen in the build* → contained to the new server-side `stroom-query-grammar` module; nothing in GWT.
- *Joins add memory/latency risk (materialising sides)* → cost model prefers broadcast-lookup for small build sides
  and pushes filters below joins to shrink inputs; joins ship last (Phase 6) behind the same flag, after single-source
  is proven; non-equi/temporal joins stay out of scope for v1.

## Downstream consumer — the temporal Cypher graph

A second project, the **temporal Cypher graph** (`docs/temporal-cypher-graph.md`), is designed to build directly on
this query core rather than ship its own parser/planner. This is **not a requirement on this plan's scope** — just a
flag that a second consumer exists, which is a further reason to keep the `Join` IR **shape-complete early** (Phase 2).

What the graph **consumes from this core**:
- the **grammar module** (`stroom-query-grammar`) — a `Cypher.g4` grammar sits alongside `StroomQL.g4` and compiles to
  the **same logical IR**;
- the **`Join` logical operator** (Phase 2) and the **join algorithms** (Phase 6) — a graph hop `MATCH (a)-[r]->(b)`
  lowers to a join of an edge/adjacency relation to a node relation, run as the **broadcast-lookup / index-nested-loop**
  join with an adjacency prefix-scan as the access path (i.e. graph "traversal" *is* a join);
- the **rewrite rules + cost model** and the existing execution machinery (providers, `ResultStore`, coprocessors);
- the **semantic (domain-type) layer** ([§ Domain types](#domain-types-semantic-layer)) — the graph reuses `domainType` + `canAccept` and the enrichment-source-routing index for **entity resolution** (same domain type ⇒ same node across sources) and catalogue-driven node mapping (`class`→label, `attribute`→key property); see the graph's [domain-type integration](temporal-cypher-graph.md#56-domain-type-integration-semantic-layer).

What the graph **adds on top** (out of scope here, listed for context):
- a **variable-length-path / fixpoint** operator (bounded transitive closure) — beyond the equi-join set;
- an **as-of temporal join** — reuses the enrichment-lookup path (`GetState`/`StateProvider`) and is **distinct from**
  the windowed stream-to-stream correlation joins this plan defers;
- a **Plan B graph datasource** (node/edge/adjacency relations with cost signals).

## Companion deliverable

A self-contained, team-facing HTML version of this plan (with diagrams: current vs target pipeline, the join plan,
the cost model, and the phase roadmap) will be produced at `docs/query-optimiser-plan.html` for circulation. It is a
rendering of this plan — no external assets, inline SVG diagrams so it opens directly in any browser.

## Appendix A: StroomQL grammar reference (Phase 1 seed)

Phase 1 authors `StroomQL.g4` "covering every current construct". A **verified EBNF snapshot** of exactly those
constructs already exists (in the `stroom` skill, `references/api-query.md`), reproduced here so this plan is
self-contained. It was verified on 2026-07-11 against the parser source (`Tokeniser.java`, `TokenType.java`,
`SearchRequestFactory.java`) **and** live `validateQuery` (Stroom 7.x). Treat it as a **starting checklist and
translation source, not the specification**: `TokenType`/`Tokeniser`/`SearchRequestFactory` remain the source of
truth, and the Phase-1 parity gate is what actually proves the ANTLR grammar matches. EBNF → ANTLR4 is a mechanical
translation (lexer/parser split; ANTLR4 handles the arithmetic left-recursion natively).

```ebnf
query     = from , [ where ] , { eval | window } , [ filter ] ,
            { ("sort" , ["by"] , sortlist) | ("group" , ["by"] , fieldlist) } ,
            [ having ] , [ limit ] , [ select ] , [ show ] ;

from      = "from" , name ;                          (* index / view / data-source name *)
where     = "where"  , expr ;
filter    = "filter" , expr ;
having    = "having" , expr ;
eval      = "eval" , name , "=" , fexpr ;
window    = "window" , field , "by" , duration , [ "advance" , duration ] ;
sortlist  = sortitem , { "," , sortitem } ;
sortitem  = field , [ "asc" | "desc" ] ;             (* default asc *)
fieldlist = field , { "," , field } ;
limit     = "limit" , number , { "," , number } ;    (* one cap per grouping level *)
select    = "select" , selitem , { "," , selitem } ;
selitem   = ( field | "*" | fexpr ) , [ "as" , name ] ;
show      = "show" , "as" , name ;                   (* visualisation *)

(* boolean expression — precedence: not > and > or *)
expr      = orexpr ;
orexpr    = andexpr , { "or" , andexpr } ;
andexpr   = notexpr , { "and" , notexpr } ;
notexpr   = "not" , notexpr | primary ;
primary   = "(" , expr , ")" | term ;
term      = field , cond , value
          | field , "between" , value , "and" , value
          | field , "in" , "(" , value , { "," , value } , ")"
          | field , "in" , "dictionary" , name ;
cond      = "=" | "!=" | ">" | ">=" | "<" | "<=" ;

(* eval / computed-column expression — infix arithmetic, comparisons + function calls *)
fexpr     = cmpexpr ;
cmpexpr   = addexpr , { cond , addexpr } ;           (* comparison operators are valid here too *)
addexpr   = mulexpr , { ( "+" | "-" ) , mulexpr } ;
mulexpr   = powexpr , { ( "*" | "/" | "%" ) , powexpr } ;
powexpr   = unary  , { "^" , unary } ;
unary     = [ "+" | "-" ] , operand ;
operand   = call | "(" , fexpr , ")" | field | literal ;
call      = fname , "(" , [ fexpr , { "," , fexpr } ] , ")" ;   (* no space before "(" *)
fname     = letter , { letter } ;      (* count, sum, min, max, any, concat, substringBefore, *)
                                       (* lowerCase, upperCase, stringLength, now, formatDate, if, … *)

(* tokens *)
field     = name ;                     (* bareword = field/identifier ref; or quoted *)
value     = string | number | duration | datetime | param | call ;
name      = bareword | string ;
string    = "'" , { char } , "'" | '"' , { char } , '"' ;   (* both quote styles are identical *)
bareword  = bchar , { bchar } ;        (* NOT whitespace, quote, ( ) , or ^ / * % + - ! < > = *)
param     = "${" , { char } , "}" ;
number    = digit , { digit } , [ "." , digit , { digit } ] , [ ("e"|"E") , "-" , digit , { digit } ] ;
duration  = digit , { digit } , ( "ns" | "ms" | "s" | "m" | "h" | "d" | "w" | "M" | "y" ) ;
datetime  = "YYYY-MM-DDThh:mm:ss.sss" , [ "Z" ] ;
comment   = ( "//" , { char } , newline ) | ( "/*" , { char } , "*/" ) ;
```

**Semantic rules the grammar alone can't enforce** (these become bind-phase validation, and parity landmines for the
golden corpus):
- Clause order is enforced; `from` is required and first; `select` is required only when `show` is present.
  `eval`/`window` may interleave (and `eval` repeats); `sort by`/`group by` may interleave (and `group by` repeats).
  **`limit` precedes `select`.**
- Only these conditions exist: `=` `!=` `>` `>=` `<` `<=` `between…and` `in (…)` `in dictionary '<name>'`;
  `contains`/`like`/`matches` are rejected.
- Keywords, function names and duration units are case-insensitive — so `M`/`m` (month/minute) collapse at lex time.
- `select *` expands to all fields; with no `group by`, hidden `StreamId`/`EventId`/annotation-id columns are
  auto-appended.
- Function calls need `(` immediately after the name; in `eval`/`select` a bareword or `${name}` is a **field
  reference** and only quoted text is a string literal. The two quote styles are identical.
- (The snapshot references an undefined `literal` production in `operand`; resolve it to `value` when translating.)

**Deltas to reconcile in Phase 1** — where this snapshot and the plan/legacy engine disagree; each is a decision the
grammar author must record:
1. **Join syntax is absent** from the snapshot by design — the plan *adds* `[left] join … on …` and `alias.field`
   references (reserved in Phase 1, enabled in Phase 6). New grammar, no legacy counterpart to match.
2. **`is [not] null`** is listed among Phase-1 constructs, but in this Stroom version the legacy parser *tokenises
   then rejects* it (*"Incomplete term"*). Decide: replicate the legacy failure for a clean parity gate, or implement
   it properly as an intentional improvement kept outside the parity corpus.
3. **`M`/`m` lex collapse** (above): preserve for byte-parity, or disambiguate as an improvement.
