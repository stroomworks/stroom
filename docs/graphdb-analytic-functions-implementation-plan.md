# Implementation plan: analytic (aggregation) functions for Stroom Graph DB

**Companion to** [`graphdb-analytic-functions-proposal.md`](graphdb/archive/graphdb-analytic-functions-proposal.md) (the *proposal* —
which functions & why) and [`pole-on-stroom-graphdb.md`](graphdb/archive/pole-on-stroom-graphdb.md) (the *motivation* — the POLE
queries these unblock). This document is the *build plan*: ordered, self-contained tasks a coding agent can pick up
cold. It mirrors the task shape of [`temporal-cypher-graph-implementation-plan.md`](temporal-cypher-graph-implementation-plan.md),
which delivered the Cypher engine this work extends.

Repo facts below were verified against the working tree on **2026-07-20** (branch `sw-query-optimiser`). Code drifts:
**before editing any file, re-read it and confirm the cited signature/line still holds** — line numbers are hints,
names are contracts.

---

## 0. How to use this document

- **Reader**: an autonomous coding agent (Sonnet). Each task is written to be picked up cold.
- **Task shape**: every task has *Goal · Depends on · Files · Contract · Done-when · Verify*. Do them in order within
  a phase; phases are gated.
- **Golden rules for this work:**
  1. **Reuse the Cypher engine; do not fork it.** Aggregation is a post-traversal *reduce* over rows the engine
     already produces — it is added to the existing post-traversal pipeline (`GraphTraversalEngine.finalizeRows`),
     not a new traversal mode. If a task seems to need new storage, a new index, or a new access path, stop and
     reconsider — that is a sign of scope creep beyond this plan (see §6, Out of scope).
  2. **Fail loud, never wrong.** The current Cypher subset's contract (POLE report §5) is that anything unsupported
     produces a *clear compile/parse error*, never a 500 or a wrong answer. Every rejection this plan adds must keep
     that contract: a precise `CypherCompileException` with an actionable message and the offending AST position.
  3. **One layer owns aggregation.** Grouping and reduction happen **inside the engine's post-traversal step**, not
     in the coprocessor/table layer. See §2 for why, and what the considered alternative was.

### Mandatory coding standards (apply to every task)

The codebase already follows these; match it exactly. Reviewers will reject code that does not.

- **Javadoc on every new/changed public and package-private type and method**, stating in prose: **Preconditions**
  (what must hold of the arguments/state on entry), **Postconditions** (what is guaranteed of the return value/state
  on exit), and **Null status** (which parameters and the return value may be null). Follow the existing house style —
  see `CypherToLogicalPlan.compile` and `GraphTraversalEngine.execute` for the exact phrasing (`<b>Preconditions:</b>`
  / `<b>Postconditions:</b>` / `<b>Null status:</b>`).
- **Check those conditions in code, not just in prose.** Every reference parameter documented non-null gets an
  `Objects.requireNonNull(x, "x")` at method entry (matching the existing `Objects.requireNonNull(cypher, "cypher")`
  style). Documented invariants (e.g. "exactly one of star/argument") get an explicit guard that throws
  `IllegalArgumentException`/`CypherCompileException` — see `AstAggregateExpr`'s compact-constructor guard for the
  pattern to copy.
- **JSpecify for null status on all server-side code.** Import `org.jspecify.annotations.Nullable` and annotate every
  nullable parameter, field, record component, and return type — exactly as `CompiledCypherPlan` and
  `ProjectField` already do. Non-null is the unannotated default; do not annotate non-null with `@NonNull`.
- **New records over new classes** for immutable data (matching `ProjectField`, `CompiledCypherPlan`, `SortKey`); use
  `List.copyOf` in compact constructors to defensively copy list components, as the existing records do.
- **Comment the *why*, not the *what*.** Where a decision is non-obvious (a rejected alternative, a Cypher-semantics
  subtlety), leave a short note in the style already pervasive in `GraphTraversalEngine` (e.g. its "Code-review fix:"
  and "Task P…:" rationale comments).

---

## 1. Current state (verified 2026-07-20)

This is the single most important section: **most of the aggregation front-end already exists.** The proposal's four
capabilities do *not* all cost the same, because the grammar and AST were built to accept aggregation from day one.

**Already present — needs no change:**

- **Grammar & lexer** parse the full aggregate call form. `Cypher.g4` (lines 161-163) has
  `aggregateCall : fn=(COUNT | SUM | AVG | MIN | MAX) OPEN_PAREN (STAR | expression) CLOSE_PAREN ;` and the five
  keyword tokens. `count(*)`, `count(a.id)`, `sum(a.balance)`, etc. all parse today.
- **AST** models them: `AstAggregateExpr(function, @Nullable argument, star, position)` and enum
  `AstAggregateFunction { COUNT, SUM, AVG, MIN, MAX }`. `AstCypherBuilder.buildAggregateCall` (≈line 337) already
  produces them.
- **Compile-to-plan lowering** exists: `CypherToLogicalPlan.renderExpression` (≈line 430-436) already lowers an
  aggregate to `ProjectField` text — `count()` for the star form, `sum(${a.balance})` for the argument form. Two
  existing tests assert this: `TestCypherToLogicalPlan.countStar_compilesToFunctionCallProjectField` and
  `.sumOfProperty_compilesToFunctionCallOverFieldReference` (≈lines 193-206).

**The two real gaps (what this plan fills):**

1. **No GROUP-BY inference at compile.** `CypherToLogicalPlan`'s class Javadoc (≈lines 98-101) states plainly:
   *"GROUP-BY inference for a mixed aggregate/plain `RETURN` is not yet implemented … such a query still compiles, but
   does not yet group distinct combinations of the non-aggregate columns."* The compiler renders the aggregate text
   but derives no grouping and carries no aggregation instructions to the executor.
2. **No aggregation at execution.** `GraphTraversalEngine.evaluate` (≈lines 768-795) resolves only a
   `${variable.property}` field reference; for anything else — *"literals, aggregates and function calls"* — it throws
   `UnsupportedOperationException`. This is the exact error the POLE report saw ("aggregates … not wired to a graph
   traversal row"). The engine's post-traversal pipeline (`finalizeRows`, ≈lines 716-743) does sort → project →
   distinct → limit, with **no group/reduce step**.

**Not present at all (the expensive one):**

- **`collect()`** is absent from the grammar, the `AstAggregateFunction` enum, and the builder — a query using it fails
  at *parse* time today. There is also **no list-valued `Val`** type (`ValList` does not exist; the `Val` sealed
  interface's implementations are all scalar: `ValString`, `ValLong`, `ValDouble`, `ValInteger`, `ValBoolean`,
  `ValNull`, `ValDate`, `ValErr`). So `collect()` needs grammar + lexer + AST + a way to represent a list in one output
  cell. This is why it is a separate, later phase (§ Phase 2), not bundled with the other four.

**Reconciling with the proposal's ordering.** The proposal prioritised count (1), group-by (2), collect (3),
min/max/avg/sum (4). The *implementation* groups differently, because the code reality differs from the conceptual one:
count and min/max/avg/sum share one reduce mechanism and are all already parsed, so they ship together with the
grouping infrastructure in **Phase 1**. `collect()` is the outlier (unparsed, needs a new cell type) and is
**Phase 2**. Phase 1 alone unblocks every aggregation query the POLE report flagged (q2, q3, and the counting variants
of q4/q5/q7/q14).

### 1.1 Data-flow recap (so the design below is legible)

```
Cypher text
  └─ CypherQueryParser.parse ──────────► AstCypherQuery         (grammar; DONE)
       └─ CypherToLogicalPlan.compile ─► CompiledCypherPlan     (Phase 1 Task 1.1/1.2: + aggregation model)
            = LogicalPlan (Project(Filter?(Expand*|VarLengthExpand)?(NodeScan)))
              + temporalContext + distinct + [NEW] aggregation
  └─ GraphSearchProvider.createResultStore                      (Phase 1 Task 1.4: pass aggregation into execute)
       └─ GraphTraversalEngine.execute ─► List<Val[]> (one per RETURN item, in Project order)
            └─ traversal → rows(Map<String,Val>) → finalizeRows (Phase 1 Task 1.3: + group/reduce)
       └─ coprocessors.accept(assembleRow(...)) ─► ResultStore   (UNCHANGED — see §2)
```

The engine's output contract — **one `Val[]` per visible `RETURN` item, in `Project.fields()` order, positionally
mapped to the `FieldIndex` by `ProjectField.name()`** (`GraphSearchProvider.buildFieldMapping`/`assembleRow`,
≈lines 223-245) — is preserved. Aggregation changes *how each row is computed*, not the row shape the provider sees.

---

## 2. Design decision: aggregate inside the engine

**Chosen: the engine's post-traversal step groups and reduces the materialised rows, then emits one already-aggregated
`Val[]` per group.** The coprocessor/table layer, `GraphSearchProvider`, and `CypherCompiler.buildResultRequests` are
essentially unchanged — columns stay plain `${name}` pass-throughs, so the DataStore simply stores the rows the engine
already reduced.

**Why here and not in the coprocessor layer (the considered alternative).** Stroom's DataStore *does* natively group
and aggregate (`Count`, `Sum`, `Min`, `Max`, `Average` are `@FunctionDef(category = AGGREGATE)` functions, and columns
carry `group()` depth). Delegating to it would reuse that tested machinery. **It was rejected for this PoC because it
splits one feature across three classes with a subtle contract:** the engine would have to emit *raw* per-row
underlying values (group keys + each aggregate's argument) as invisible `eval` `ProjectField`s, `CypherCompiler` would
have to build *visible* `group()`/aggregate columns whose `${…}` field references must line up exactly with those
invisible field names in the shared `FieldIndex`, and `GraphSearchProvider` would have to map the two sets together.
That cross-class `FieldIndex` alignment is exactly the kind of thing that is easy to get subtly — and silently — wrong.

**In-engine aggregation is lower-risk and better-fit here because:**

- It is **local**: the whole feature lives in `GraphTraversalEngine.finalizeRows` plus a compile-time model, and is
  unit-testable in `TestGraphTraversalEngine` with no coprocessor plumbing.
- It is **consistent**: the engine *already* owns sort, distinct, and limit as in-engine post-processing rather than
  delegating them (`finalizeRows`, ≈lines 716-743). Adding group/reduce alongside them is the established pattern, not
  a new deviation.
- The reduce itself is **trivial**: five scalar folds over a `List<Val>`, and `Val` already gives us `Comparable<Val>`
  (for min/max) and `toDouble()`/`toLong()` (for sum/avg). ~40 lines, easy to read and to test exhaustively.
- The cross-class output contract stays **unchanged**, so blast radius is minimal.

The cost — reimplementing five simple reductions Stroom has elsewhere — is small and self-contained, and the
follow-up door to delegating to the coprocessor layer later remains open (the compile-time model built in Task 1.1 is
the same information that path would need).

---

## Phase 1 — Grouping + `count`/`sum`/`avg`/`min`/`max` (the aggregation slice)

**Phase goal:** a `RETURN` mixing aggregate and non-aggregate items groups by the non-aggregate items and reduces the
aggregates, executing correctly end-to-end through `GraphSearchProvider`; unsupported aggregate shapes are rejected at
compile time with clear messages. No grammar changes.

**Phase gate:** all Phase 1 tasks done, `./gradlew :stroom-graphdb:stroom-graphdb-impl:test
:stroom-query:stroom-query-planner:test` green, and the worked POLE queries in Task 1.5 return the documented rows.

---

### Task 1.1 — Compile-time aggregation model + GROUP-BY inference

**Goal.** Teach `CypherToLogicalPlan` to detect aggregates in the `RETURN` clause, infer the implicit group keys
(Cypher rule: every non-aggregate return item is a grouping key), validate the supported aggregate shapes, and carry a
structured aggregation description to the executor.

**Depends on.** Nothing (front-end already parses aggregates).

**Files.**
- New: `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/CypherAggregation.java`
- Edit: `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/CompiledCypherPlan.java`
- Edit: `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/CypherToLogicalPlan.java`
- Edit (tests): `stroom-query/stroom-query-planner/src/test/java/stroom/query/planner/cypher/TestCypherToLogicalPlan.java`

**Contract.**

Define the model as immutable records (JSpecify-annotated, javadoc'd per §0 standards):

```java
/** The Cypher-only aggregation description for one RETURN clause, aligned 1:1 and in order with
 *  {@code Project.fields()}. Carried on {@link CompiledCypherPlan} (like DISTINCT), not as a shared-IR node,
 *  because implicit group-by is a Cypher front-end concept the relational core's sealed IR has no node for. */
public record CypherAggregation(List<OutputColumn> columns) { ... }

public sealed interface OutputColumn permits GroupKeyColumn, AggregateColumn { String rowKey_or_name(); }

/** A non-aggregate RETURN item that is also a grouping key. {@code rowKey} is the traversal-row map key
 *  ("variable.property") whose value both groups rows and fills this output position. */
public record GroupKeyColumn(String rowKey) implements OutputColumn { ... }

/** An aggregate RETURN item. Exactly one of {@code star} / non-null {@code argRowKey} / {@code argIsVariable}
 *  describes the argument. */
public record AggregateColumn(AstAggregateFunction function,
                              @Nullable String argRowKey,   // "a.balance" for count(a.balance)/sum(a.balance); null otherwise
                              boolean star,                 // count(*)
                              boolean argIsVariable) implements OutputColumn { ... }
```

`CompiledCypherPlan` gains a **nullable** `CypherAggregation aggregation` component (null ⇒ the `RETURN` has no
aggregates ⇒ current non-aggregate execution path). Update its javadoc (Preconditions/Postconditions/Null status) and
its single construction site.

In `CypherToLogicalPlan.compile`, after building the `Project`:
- Detect aggregates: an item is aggregate iff `item.expression() instanceof AstAggregateExpr`.
- If none: `aggregation = null` (behaviour unchanged; `distinct`, `ORDER BY`, `LIMIT` all as today).
- If ≥1: build a `CypherAggregation` with one `OutputColumn` **per `RETURN` item, in order** (so the list aligns with
  `Project.fields()`):
  - Non-aggregate item ⇒ `GroupKeyColumn(rowKey)` where `rowKey` is `"variable.property"` for a property access.
  - Aggregate item ⇒ `AggregateColumn(...)`.
- **Validation (reject with `CypherCompileException` + AST position, message prefixed `"not in PoC subset: "`):**
  - `sum(*)` / `avg(*)` / `min(*)` / `max(*)` → reject: only `count(*)` is meaningful.
  - `sum`/`avg`/`min`/`max` over a bare variable (e.g. `sum(a)`) → reject: aggregate a property, e.g. `sum(a.balance)`
    (a whole node/edge has no single value — mirror the engine's existing bare-variable rationale, `evaluate`
    ≈line 786).
  - Any aggregate argument that is neither a property access nor (for `count` only) a bare variable — e.g. a literal or
    a nested aggregate → reject.
  - A **group key** (non-aggregate item) that is a bare variable (e.g. `RETURN p, count(*)`) → reject: a grouping key
    must be a property access (same "whole node has no single value" rationale). Property-access group keys only.
- **Supported argument matrix** (this is the contract the executor in Task 1.3 relies on):
  - `count(*)` → `star=true`.
  - `count(v)` (bare var) → `argIsVariable=true`; semantically equals `count(*)` because every matched row binds every
    pattern variable (no `OPTIONAL MATCH` in this subset), so `v` is never null. Note this reasoning in a comment.
  - `count(a.p)` → `argRowKey="a.p"`; counts rows whose value at `a.p` is present and non-null.
  - `sum|avg|min|max(a.p)` → `argRowKey="a.p"`.

**Default output-column naming (important — prevents a malformed `FieldIndex` key).** Today an *unaliased* aggregate's
`ProjectField.name()` comes from `defaultColumnName` → `renderExpression` → `"count(${a.balance})"` (a nested `${…}`),
which is unusable as a column/field name. Change `defaultColumnName` so an unaliased `AstAggregateExpr` yields a clean,
`${…}`-free name: `count(*)`, `count(a.balance)`, `sum(a.balance)`, `count(c)`. (Leave `renderExpression`'s
`rawExpression` output as-is; it is retained for explain/debug only and is no longer read at execution for aggregate
fields.) Aliased aggregates keep the alias as the name, unchanged.

**Null status.** `CompiledCypherPlan.aggregation()` is `@Nullable`. `AggregateColumn.argRowKey` is `@Nullable`.
`CypherAggregation.columns` is non-null, non-empty when present, and `List.copyOf`-defended.

**Done-when.**
- A `RETURN` with no aggregates compiles with `aggregation() == null` (regression: existing tests still pass).
- `MATCH (a:Account) RETURN count(*) AS total` compiles with a single `AggregateColumn(COUNT, null, star=true, false)`
  and no group keys.
- `MATCH (c:Crime)-[:INVESTIGATED_BY]->(o:Officer) RETURN o.surname, count(c) AS caseload` compiles with
  `[GroupKeyColumn("o.surname"), AggregateColumn(COUNT, null, false, argIsVariable=true)]`.
- `RETURN sum(a.balance) AS t` → `AggregateColumn(SUM, "a.balance", false, false)`.
- Each rejected shape above throws `CypherCompileException` whose message names the problem and the fix.

**Verify.** Add `TestCypherToLogicalPlan` cases for every bullet above (positive: assert the `CypherAggregation`
shape; negative: `assertThatThrownBy(...).isInstanceOf(CypherCompileException.class)` with a message assertion). Keep
the two existing aggregate tests green (update only if the *name* assertions change — they use aliases, so they should
not). Run `./gradlew :stroom-query:stroom-query-planner:test`.

---

### Task 1.2 — Thread the aggregation model to the engine call

**Goal.** Make the compiled aggregation model reach `GraphTraversalEngine.execute` without breaking existing callers.

**Depends on.** Task 1.1.

**Files.**
- Edit: `stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java`
  (new `execute` overload only; body change is Task 1.3).
- Edit: `stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphSearchProvider.java`
  (pass `compiled.aggregation()`).

**Contract.** Add one `execute` overload:

```java
public List<Val[]> execute(Txn<ByteBuffer> readTxn, LogicalPlan plan,
                           @Nullable TemporalContext temporalContext, DateTimeSettings dateTimeSettings,
                           boolean distinct, @Nullable CypherAggregation aggregation) { ... }
```

The existing 4-arg and 5-arg overloads delegate to it with `aggregation = null` (so **every existing test and the
performance/filter callers compile unchanged** — there are ~30 call sites, all in tests; do not touch them). Full
javadoc per §0: Preconditions (all non-null except `temporalContext`/`aggregation`), Postconditions (one `Val[]` per
group when `aggregation != null`, else one per surviving row — as today), Null status. `Objects.requireNonNull` the
non-null params.

`GraphSearchProvider.createResultStore` (≈line 180) passes `compiled.aggregation()` as the new argument. No other
provider change — `buildFieldMapping`/`assembleRow` are keyed by `ProjectField.name()` and are unaffected.

**Done-when.** Project builds; all existing graphdb tests still pass (the new param defaults to null everywhere they
call).

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:compileTestJava
:stroom-graphdb:stroom-graphdb-impl:test`.

---

### Task 1.3 — Group + reduce in the engine's post-traversal step

**Goal.** When `aggregation != null`, group the materialised traversal rows by the group-key values and emit one
reduced `Val[]` per group, in `Project.fields()` order; then order/limit the aggregated output.

**Depends on.** Tasks 1.1, 1.2.

**Files.**
- Edit: `stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java`.

**Contract.**

The traversal itself is unchanged — it still materialises `List<Map<String,Val>> rows` exactly as today. **Aggregation
disables the traversal-time row cap** (like `ORDER BY`/`DISTINCT` already do — see the `postProcess` flag, ≈line 226):
every matching row must be seen before grouping, so extend `postProcess` to also be true when `aggregation != null`.

Replace the terminal `finalizeRows(...)` call with a branch: `aggregation == null` → existing `finalizeRows` (no
change); else → new `finalizeAggregatedRows(rows, shape, aggregation, distinct)`.

`finalizeAggregatedRows` semantics (document each rule in javadoc + inline comments; these are the postconditions):

1. **Group.** Partition `rows` by the ordered tuple of `row.get(gk.rowKey())` for each `GroupKeyColumn gk` (in
   output-column order). Group identity uses `Val` `equals`/`hashCode` (every `Val` implements both — see the DISTINCT
   note at `finalizeRows` ≈line 736). Use a `LinkedHashMap<List<Val>, List<Map<String,Val>>>` so group order is
   first-appearance-stable (before any `ORDER BY`). An absent group-key value maps to `ValNull.INSTANCE` for grouping.
2. **Empty input.**
   - If there is **≥1 group key** and zero input rows → **zero output rows** (Cypher: no groups).
   - If there are **no group keys** (pure aggregate, e.g. `RETURN count(*)`) → **exactly one output row** over the
     empty set (Cypher: aggregates still produce one row).
3. **Reduce, per group, per output column** (build a `Val[]` of width `columns.size()` = `Project.fields().size()`):
   - `GroupKeyColumn` → the group's key `Val` for that position.
   - `AggregateColumn`:
     - **count** (`star`, or `argIsVariable`) → `ValLong.create(group.size())`.
     - **count(a.p)** → `ValLong.create(` number of rows where `row.get("a.p")` is non-null and not `ValNull` `)`.
     - **sum(a.p)** → over the group's non-null numeric values (`Val.toDouble()` non-null): `ValDouble.create(sum)`;
       empty (no numeric values) → `ValDouble.create(0.0)` (Cypher: sum of empty = 0). *Note: sums render as doubles in
       this PoC; integral-type preservation is a deliberately-deferred refinement, called out in §6.*
     - **avg(a.p)** → mean of the non-null numeric values as `ValDouble`; empty → `ValNull.INSTANCE` (Cypher: avg of
       empty = null).
     - **min(a.p)** / **max(a.p)** → the least/greatest `Val` among the group's non-null values by `Val` natural
       ordering (`Comparator.<Val>naturalOrder()`), **preserving the original `Val` type** (min of `ValLong` stays
       `ValLong`; of `ValString` stays `ValString`); empty → `ValNull.INSTANCE`.
   - **Null handling summary:** `sum`/`avg`/`min`/`max` skip absent (`row.get == null`) and `ValNull` values;
     `count(a.p)` skips them (does not count); `count(*)`/`count(v)` count every row in the group.
   - **Non-numeric under sum/avg:** skip values whose `Val.toDouble()` is null (lenient — never throw). Document this.
4. **Order.** If `shape.sortKeys()` is non-empty, sort the **output rows** (not the pre-projection maps — the existing
   `rowComparator` is for the non-aggregate path only). Resolve each `SortKey` to an output-column index by matching its
   reconstructed name (`alias == null ? field : alias + "." + field`) against each output column's name — which is the
   corresponding `ProjectField.name()`. So `ORDER BY caseload` (an aggregate alias) and `ORDER BY o.surname` (a group
   key) both resolve. **If a sort key resolves to no output column, throw `CypherCompileException`** ("ORDER BY key
   'x' is not a returned column; after aggregation only RETURN columns are in scope") — fail loud. Compare by `Val`
   natural order, `nullsLast`, honouring `descending()`, chaining keys as `rowComparator` does.
5. **Distinct.** If `distinct`, de-duplicate the output rows by value (harmless after grouping — rows are already
   distinct by key; keep for consistency with the non-aggregate path).
6. **Limit.** Apply `shape.limit()` last, after ordering (so it bounds the correct rows).

**Null status.** New private helpers take non-null args; `@Nullable` only where a `Val` may legitimately be absent.
The method returns non-null (possibly empty) `List<Val[]>`.

**Done-when.** The semantics above hold. In particular:
- `RETURN count(*)` over an empty match → one row `[0]`.
- `RETURN o.surname, count(c)` over an empty match → zero rows.
- `sum`/`avg` skip nulls; `avg` of no values → null; `min`/`max` preserve type; `sum` of ints → a double.

**Verify.** Task 1.5's `TestGraphTraversalEngine` cases exercise every rule. `./gradlew
:stroom-graphdb:stroom-graphdb-impl:test`.

---

### Task 1.4 — Confirm the full provider path (columns unchanged, unaliased names safe)

**Goal.** Prove aggregation flows correctly through `GraphSearchProvider` → coprocessors → `ResultStore`, and that an
**unaliased** aggregate's cleaned name (Task 1.1) survives the `${name}` column round-trip in `CypherCompiler`.

**Depends on.** Tasks 1.1-1.3.

**Files.**
- Verify (likely no change): `stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/CypherCompiler.java`
  — `buildResultRequests` builds `Column.expression("${" + field.name() + "}")`. For an aggregate this is now e.g.
  `${count(a.balance)}` / `${count(*)}`. Because the reference is inside `${…}`, Stroom's expression parser treats the
  interior as a *literal field name*, not a function call, so parens/`*`/dot are fine. **Confirm this with the test
  below before assuming it.**
- Edit (tests): `stroom-graphdb/stroom-graphdb-impl/src/test/java/stroom/graphdb/impl/TestGraphSearchProvider.java`.

**Contract / Done-when.** A `GraphSearchProvider`-level test seeds a small graph and asserts, via `readTableRows`, the
grouped/aggregated output for both an **aliased** (`... RETURN o.surname, count(c) AS caseload`) and an **unaliased**
(`... RETURN count(*)`) aggregate. The unaliased case is the guard: it fails iff a `${count(*)}`-style reference does
*not* resolve — in which case the fallback is to sanitise `defaultColumnName` for aggregates to an identifier-safe form
(e.g. `count_star`, `sum_a_balance`) in Task 1.1 and re-run. Note that fallback here so the next agent sees it.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test --tests '*TestGraphSearchProvider'`.

---

### Task 1.5 — Test suite for Phase 1

**Goal.** Exhaustive, readable coverage of grouping + the five reductions, at the engine level (unit) and one
end-to-end level (provider).

**Depends on.** Tasks 1.1-1.4.

**Files.**
- Edit: `stroom-graphdb/stroom-graphdb-impl/src/test/java/stroom/graphdb/impl/TestGraphTraversalEngine.java` (reuse
  its `seed…` fixtures — e.g. `seedDeviceConnectedToAccounts` has `balance` values 50 and 200, ideal for sum/avg/min/
  max).
- Edit: `stroom-query/stroom-query-planner/src/test/java/stroom/query/planner/cypher/TestCypherToLogicalPlan.java`
  (Task 1.1 negatives/positives).
- Edit: `stroom-graphdb/stroom-graphdb-impl/src/test/java/stroom/graphdb/impl/TestGraphSearchProvider.java`
  (Task 1.4).

**Contract — engine test matrix (each an isolated `@Test`, named behaviour-first per the file's convention):**
- `count(*)` per group and global (no group key → single row).
- `count(a.balance)` vs `count(*)` differ when a grouped node lacks the property (seed one node without `balance`).
- `sum(a.balance)` = 250 across the two seeded accounts; grouped sum splits correctly.
- `avg(a.balance)` = 125.0; `avg` over a group with no numeric values → null.
- `min`/`max(a.balance)` = 50 / 200, returned as the original `Val` type.
- Empty match: `count(*)` → `[0]`; `x.p, count(*)` → zero rows.
- `ORDER BY <aggregate alias> DESC LIMIT n` orders on the reduced value and caps correctly.
- `ORDER BY <non-returned field>` under aggregation → `CypherCompileException`.
- Regression: a non-aggregate `RETURN` is byte-for-byte unchanged (aggregation path not taken).

**Done-when.** All green; every §Contract rule in Tasks 1.1/1.3 has a matching assertion.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test :stroom-query:stroom-query-planner:test`.

---

## Phase 2 — `collect()` (deferred; larger surface)

**Ship Phase 1 first.** `collect()` is separated because — unlike the other four — it touches the grammar and needs a
new value representation. Attempt it only after Phase 1 is merged and green.

**Phase goal:** `RETURN p.surname, collect(c.type) AS crimeTypes` groups by `p.surname` and gathers each group's
`c.type` values into one output cell.

**Depends on.** All of Phase 1 (reuses the grouping + `CypherAggregation` machinery).

**The added surface (why this is a separate phase):**

1. **Grammar + lexer** (`Cypher.g4`): add a `COLLECT` keyword token (with case-insensitive fragments, before `NAME`),
   and add `COLLECT` to the `aggregateCall` alternative: `fn=(COUNT | SUM | AVG | MIN | MAX | COLLECT)`. Regenerate the
   ANTLR sources (the build does this; confirm `CypherParser.COLLECT` appears). Update the grammar file-header comment
   listing the supported aggregates.
2. **AST**: add `COLLECT` to `AstAggregateFunction`; extend `AstCypherBuilder.buildAggregateCall`'s `switch` with the
   `CypherParser.COLLECT` case.
3. **Value representation** — the real cost. There is **no list-valued `Val`**. Two options, decide before building:
   - **(a) A new `ValList`** implementing the `Val` sealed interface (holds `List<Val>`; defines `equals`/`hashCode`/
     `compareTo`/`toString` — e.g. `toString` = comma-joined). This is the clean, faithful representation but touches a
     `sealed` interface (every `permits` site and exhaustive `switch` over `Val` types must be updated — grep for
     `instanceof Val`/`switch` over `Type`). Non-trivial ripple; scope it carefully.
   - **(b) A delimiter-joined `ValString`** (e.g. `"Drugs, Burglary"`). Zero new type, trivial, but lossy (not a real
     list; no downstream list operations). Acceptable as a first cut for a PoC if `ValList`'s ripple proves large.
   - **Recommendation:** attempt (a); fall back to (b) if the sealed-interface ripple is disproportionate. Record the
     decision in this doc when taken.
4. **Compile** (`CypherToLogicalPlan`, Task 1.1's model): `collect(a.p)` → `AggregateColumn(COLLECT, "a.p", …)`;
   `collect(*)` → reject (not meaningful).
5. **Reduce** (`GraphTraversalEngine.finalizeAggregatedRows`, Task 1.3): `collect` → gather each group's non-null
   values at `argRowKey` into the chosen representation (order = first-appearance within the group; Cypher's `collect`
   keeps duplicates — do **not** dedupe).
6. **Tests**: grammar parse test; compile-model test; engine reduce test (`collect(c.type)` per group);
   provider round-trip test; a rejection test for `collect(*)`.

**Done-when.** The phase-goal query returns the gathered values per group; `collect(*)` is rejected; all prior tests
still pass.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test`.

---

## 3. Documentation & user-facing updates

- Update [`pole-on-stroom-graphdb.md`](graphdb/archive/pole-on-stroom-graphdb.md) §5's gap table: aggregation moves from "missing" to
  "supported" for `count`/`sum`/`avg`/`min`/`max` (+ `collect` after Phase 2), with a one-line worked example
  (`RETURN o.surname, count(c) AS caseload`).
- Add a short "Aggregation" subsection to the Cypher user guide / help text (wherever the current subset is
  documented) listing the five (six) functions, the implicit-group-by rule, and the explicit rejections
  (`sum(*)` etc., aggregating a whole node, bare-variable group keys, `ORDER BY` a non-returned column).
- If the repo generates Swagger/OpenAPI for the query endpoint, regenerate it (no API-shape change is expected — this
  is behaviour behind the existing Cypher text field — but confirm).

---

## 4. Suggested commit sequence

1. Task 1.1 (`CypherAggregation` + `CompiledCypherPlan` + compile inference/validation + naming) + its planner tests.
2. Task 1.2 (engine overload + provider wiring) — compiles, no behaviour change yet.
3. Task 1.3 (engine group/reduce) + Task 1.5 engine tests.
4. Task 1.4 (provider round-trip test; naming-fallback if needed).
5. Docs (§3).
6. Phase 2 (`collect()`) — only after 1-5 are merged.

Each commit builds and passes tests on its own (Task 1.2's null-default keeps the tree green between 1.2 and 1.3).

---

## 5. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| `${count(*)}`-style column reference does not resolve (parser treats `*`/parens specially) | Low-med | Task 1.4 tests an unaliased aggregate explicitly; documented fallback = identifier-safe default names. |
| `sum`/`avg` of integer properties render as `3.0` not `3` | Certain (by design) | Documented as a PoC simplification; integral-preservation is a noted §6 follow-up, not a correctness bug. |
| Existing `TestCypherToLogicalPlan` aggregate assertions break | Low | They use aliases, so `name` is unaffected; `rawExpression` rendering is unchanged. Re-run planner tests in Task 1.1. |
| `ValList` ripple through the `Val` sealed interface is large | Med (Phase 2 only) | Phase 2 offers the joined-string fallback (b); measure the ripple before committing to (a). |
| A group-key or aggregate arg references a property absent on some nodes | Certain (schemaless graph) | Handled by design: absent ⇒ `ValNull` for grouping / skipped for numeric reduce (matches Cypher). Explicit test in Task 1.5. |
| Large result set materialised in memory (aggregation disables the row cap) | Low | The traversal already keeps `List<Map<String,Val>>` in memory and is bounded by `MAX_TRAVERSAL_DURATION` (30s) and the var-length path-state ceiling; aggregation adds no new unbounded scan. Note it; a streaming aggregate is out of scope. |

---

## 6. Out of scope (explicitly not this plan)

- **Spatial, path-finding, `RETURN *`/whole-node/path returns, pattern-predicates (`WHERE NOT (a)-[:X]->(b)`),
  writes (`SET`)** — the other POLE gaps (report §5); each needs a new engine subsystem, tracked separately.
- **`HAVING`** (post-aggregation filtering) and **aggregates inside `WHERE`** — a later refinement; `WHERE` stays a
  pre-aggregation, field-vs-literal filter as today.
- **`DISTINCT` inside an aggregate** (`count(DISTINCT a.p)`) — not in the grammar; deferred.
- **Multiple `RETURN`/`WITH` stages and aggregation across a `WITH` boundary** — the single-reading-clause restriction
  (`CypherToLogicalPlan`, ≈line 142) stands.
- **Integral-type preservation for `sum`** — a display-fidelity refinement over the double-accumulation rule in
  Task 1.3.
- **Delegating aggregation to the coprocessor/DataStore layer** — the considered alternative (§2); the door is left
  open but it is not this plan.
