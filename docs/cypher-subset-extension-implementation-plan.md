# Implementation plan: extending Stroom's Cypher subset (Tiers 1-3)

**Companion to** [`cypher-language-feature-roadmap.md`](cypher-language-feature-roadmap.md) (the *survey* — what to
build and why, in priority order) and
[`graphdb-analytic-functions-implementation-plan.md`](graphdb-analytic-functions-implementation-plan.md) (the sibling
build plan whose Phase 1 — grouping + `count`/`sum`/`avg`/`min`/`max` — is **already merged**; this plan builds
directly on that machinery rather than re-deriving it). This document is the *build plan*: ordered, self-contained
tasks a coding agent can pick up cold, in the same shape as the analytic-functions plan.

Repo facts below were verified against the working tree on **2026-07-25** (branch `sw-query-optimiser-graph-backend`).
Every citation in this document was opened and checked line-by-line against the current source during that
verification pass — see the note at the end of §1 for the one place a citation had drifted. Code drifts further from
here: **before editing any file, re-read it and confirm the cited signature/line still holds** — line numbers are
hints, symbol names are contracts.

---

## 0. How to use this document

- **Reader**: an autonomous coding agent (Sonnet). Each task is written to be picked up cold.
- **Task shape**: every task has *Goal · Depends on · Files · Contract · Done-when · Verify*. Do them in order within
  a phase; phases are gated by a gradle test command.
- **Golden rules for this work:**
  1. **Fail loud, never wrong.** This is the Cypher subset's non-negotiable contract (see `Cypher.g4`'s file header
     and `CypherToLogicalPlan`'s class Javadoc): any out-of-subset input yields a precise, positioned
     `CypherCompileException` (compile time) or `SyntaxException` (parse time) — never a 500, never a silently wrong
     answer. Every task below that rejects a shape must produce a message that names the problem and, where possible,
     the fix (`"not in PoC subset: ..."` / `"not supported in this version: ..."`, matching the house convention
     already used throughout `CypherToLogicalPlan`).
  2. **Reuse, don't fork.** Cypher is a second front-end onto the one shared `LogicalPlan` IR and the one
     `GraphTraversalEngine` — see `CypherToLogicalPlan`'s class Javadoc ("Cypher is a second front-end onto one IR,
     not a forked engine"). Several of the phases below (1-4) reuse machinery that is *already wired* (see §2's
     cross-cutting finding); resist the temptation to add a parallel code path when an existing one already does the
     job.
  3. **One layer owns each concern.** Grammar/AST is a pure syntax-to-tree transform with zero semantics. The
     compiler (`CypherToLogicalPlan`) owns validation and lowering to the shared IR — it is the *only* place that
     throws `CypherCompileException`. The engine (`GraphTraversalEngine`) owns traversal and the post-traversal
     pipeline (`finalizeRows`/`finalizeAggregatedRows`) — it does not re-validate what the compiler already checked
     (see `resolveAnchors`' anchor-predicate re-validation for the one deliberate exception, and why, in §2).

### Mandatory coding standards (apply to every task)

The codebase already follows these; match it exactly. Reviewers will reject code that does not.

- **Javadoc on every new/changed public and package-private type and method**, stating in prose: **Preconditions**
  (what must hold of the arguments/state on entry), **Postconditions** (what is guaranteed of the return value/state
  on exit), and **Null status** (which parameters and the return value may be null). Follow the existing house style
  — see `CypherToLogicalPlan.compile` and `GraphTraversalEngine.execute` for the exact phrasing (`<b>Preconditions:</b>`
  / `<b>Postconditions:</b>` / `<b>Null status:</b>`).
- **Check those conditions in code, not just in prose.** Every reference parameter documented non-null gets an
  `Objects.requireNonNull(x, "x")` at method entry (matching the existing `Objects.requireNonNull(query, "query")`
  style in `CypherToLogicalPlan.compile`). Documented invariants (e.g. "exactly one of star/argRowKey/argIsVariable")
  get an explicit guard that throws `IllegalArgumentException`/`CypherCompileException` — see `AggregateColumn`'s
  compact-constructor guard for the pattern to copy.
- **JSpecify for null status on all server-side code.** Import `org.jspecify.annotations.Nullable` and annotate every
  nullable parameter, field, record component, and return type — exactly as `CompiledCypherPlan` and `ProjectField`
  already do. Non-null is the unannotated default; do not annotate non-null with `@NonNull`.
- **New records over new classes** for immutable data (matching `AggregateColumn`, `GroupKeyColumn`,
  `CompiledCypherPlan`); use `List.copyOf` in compact constructors to defensively copy list components, as
  `CypherAggregation`'s compact constructor already does.
- **Comment the *why*, not the *what*.** Where a decision is non-obvious (a rejected alternative, a Cypher-semantics
  subtlety), leave a short note in the style already pervasive in `GraphTraversalEngine` (its "Code-review fix:" and
  "Task P…:" rationale comments) and `CypherToLogicalPlan` (its "not in PoC subset: ..." message style).

---

## 1. Current state (verified 2026-07-25)

### 1.1 Data-flow recap

```
Cypher.g4 → AstCypherBuilder (AST) → CypherToLogicalPlan.compile → CompiledCypherPlan
  → GraphSearchProvider.createResultStore → GraphTraversalEngine.execute
  → finalizeRows / finalizeAggregatedRows → List<Val[]>
  → GraphSearchProvider.assembleRow → coprocessors (generic table/CSV)
```

Three modules, one pipeline:

- **Grammar/AST** — `stroom-query/stroom-query-grammar`. Grammar of record:
  `stroom-query/stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/Cypher.g4`. AST types:
  `.../ast/cypher/*.java`. Builder: `AstCypherBuilder.java` (a hand-written typed visitor, not a generated-visitor
  subclass — see its class Javadoc). Keywords are lexer rules spelled letter-by-letter with case-insensitive
  fragments `A`..`Z` (`Cypher.g4:314-320`), declared before `NAME` (`Cypher.g4:311`) so keywords always win. `AstValue`
  (`AstValue.java:27-28`) and `AstBooleanExpr` (`AstBooleanExpr.java:24`) are **sealed** — a new leaf type means
  editing the `permits` clause and every exhaustive `switch`/`instanceof` chain over the interface.
- **Compiler/planner** — `stroom-query/stroom-query-planner`. `CypherToLogicalPlan.java` (1022 lines) is the sole
  compiler and the sole thrower of `CypherCompileException`. `stroom.query.planner.cypher` also already contains
  `CypherAggregation`, `OutputColumn` (sealed, `permits GroupKeyColumn, AggregateColumn`), `GroupKeyColumn`,
  `AggregateColumn`, `CompiledCypherPlan`, `CypherCompileException`, `DiffContext`, `TemporalContext` — these five
  are **not new**; they shipped as part of `graphdb-analytic-functions-implementation-plan.md` Phase 1, which is
  fully merged (confirmed by reading the files: `CypherToLogicalPlan.compile` already builds a `CypherAggregation` at
  line 218 and threads it into `CompiledCypherPlan`'s 6th component). `stroom.query.planner.logical` holds the shared
  IR: `Expand`, `VarLengthExpand`, `NodeScan`, `Filter`, `Join`, `JoinType`, `Direction`, `Project`, `ProjectField`,
  `Sort`, `SortKey`, `Limit`, `QualifiedField`.
- **Engine** — `stroom-graphdb/stroom-graphdb-impl`. `GraphTraversalEngine.java` (2073 lines) owns traversal
  (fixed-length chain, bounded var-length BFS, `RETURN GRAPH` element collection, `DIFF` bindings) and the
  post-traversal pipeline (`finalizeRows`, `finalizeAggregatedRows` — the latter also already shipped, Phase 1 of the
  analytic-functions plan). `GraphRowValueFunctionFactory` bridges a traversal row (`Map<String,Val>`) to the shared
  `ValueFunctionFactory`/`ExpressionPredicateFactory` machinery; `GraphCypherQueryCompiler`, `CypherCompiler`,
  `GraphSearchProvider`, `GraphElementExecutor` fill out the module.
- **Shared relational IR** — `stroom-query/stroom-query-api`'s `ExpressionTerm` (`Condition` enum at
  `ExpressionTerm.java:207` onward; the term's `value` is a single `String`, no second-field slot).
  `stroom-query-language`'s `Val` sealed interface (`Val.java:52`, `permits ValNumber, ValString, ValErr, ValNull,
  ValBoolean, ValXml`) and `Type` enum (`Type.java:22-35`, no `LIST` today). Runtime predicate dispatch:
  `stroom-query-common`'s `ExpressionPredicateFactory`.

The engine's output contract — one `Val[]` per visible `RETURN` item, in `Project.fields()` order, positionally
mapped to the `FieldIndex` by `ProjectField.name()` (`GraphSearchProvider.buildResultRequests`/`assembleRow`) — is
preserved by every phase below; none of them change *what* the provider sees, only *what rows reach it* or *how a
row's values are computed*.

### 1.2 The runtime predicate vocabulary already exists (cross-cutting finding — read before Phase 1)

This is the single most important fact for Phases 1-2: **the hard part is already built and merged, just not reachable
from Cypher.** `ExpressionTerm.Condition` already declares `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `MATCHES_REGEX`,
`IN`, `IS_NULL`, `IS_NOT_NULL` (`ExpressionTerm.java:207-247`, confirmed by direct read — every one of these seven
values is present today). `ExpressionPredicateFactory` already dispatches every one of them for the `TEXT` field type
(`STARTS_WITH`→`StringStartsWith`, `CONTAINS`→`StringContains`, `ENDS_WITH`→`StringEndsWith`,
`MATCHES_REGEX`→`StringRegex`, `IN`→`StringIn`, plus a dedicated `IS_NULL`/`IS_NOT_NULL` branch ahead of the general
dispatch). `GraphRowValueFunctionFactory.getFieldType()` returns `FieldType.TEXT` unconditionally for every graph
property (with a comment explaining this is deliberate: the comparison path tries date → numeric → text in order
regardless of declared type), so **every** graph `WHERE` term already routes through this exact dispatch today for
`=`/`<>`/`<`/`<=`/`>`/`>=`. StroomQL's own binder, `AstToSearchRequestMapper.buildTerm`
(`stroom-query-common/src/main/java/stroom/query/language/AstToSearchRequestMapper.java:504-538`), already lowers
`IN` and `IS [NOT] NULL` this exact way: an `AstInTerm` becomes `Condition.IN` with `value` = the literals joined by
`", "` (line 519); an `AstIsNullTerm` becomes `Condition.IS_NULL`/`IS_NOT_NULL` with no `value` at all (line 535-538).

**Consequence: Phases 1-2 need zero `GraphTraversalEngine` change.** They are grammar + AST + a `CypherToLogicalPlan`
dispatch extension only. The `wherePredicate`/`propertyPredicate` construction sites in the engine
(`execute` line 302-306; `executeDiffBindings` line 443-447; `executeGraphBindings` line 698-702;
`executeGraphBindingsAsOf` line 883-887; `resolveAnchors`'s own re-validation, line 1501-1503;
`matchesTargetConstraint`'s re-validation, line 1279-1281) all call
`expressionPredicateFactory.createOptional(operator, rowAccessors(), dateTimeSettings)` on whatever
`ExpressionOperator` tree the compiler handed them — they neither know nor care which `Condition` values appear in
it, so a term with `Condition.STARTS_WITH` or `Condition.IS_NULL` flows through exactly the same call as a term with
`Condition.EQUALS` today, with no plumbing change at all.

**Verified gotcha — now fixed by Phase 0 (do not skip):** `ExpressionPredicateFactory`'s `StringIn.create`
(`ExpressionPredicateFactory.java:1939-1946`) splits `term.getValue()` on a **single space** (`term.getValue().split("
")`) — but every producer of a `Condition.IN` value joins on a comma: `AstToSearchRequestMapper.buildTerm`'s
`AstInTerm` branch (line 519) joins with `", "` (comma-space), `MetaExpressionUtil` (line 113) joins with `","`, and
the sibling numeric/date paths `getTermNumbers`/`getTermDates` (lines 521-542) split on the module's own
`DELIMITER = ","` constant (line 58) and `.trim()` each element. Only `StringIn` is the outlier. So a StroomQL text
`IN ('Powell', 'Smith')` produces `"Powell, Smith"`, which `StringIn.create` splits on space into `{"Powell,",
"Smith"}` — a set with a trailing-comma value that never matches a real `surname` of `"Powell"`: **text `IN` is
silently broken today.** **Phase 0** fixes this at the source — `StringIn.create` splits on `DELIMITER` and `.trim()`s,
exactly like its numeric/date siblings — unifying all three `IN` paths on comma-delimited values and aligning with
every producer. Consequently Phase 2's Cypher `IN` lowering joins literal values with `", "` (comma-space, matching
the mapper), **not** a single space; Phase 0 is a hard prerequisite for Phase 2 and ships first (see Phase 0).

**Caveat to document, not fix:** `resolveAnchors`'s seek path (`GraphTraversalEngine.java:1464-1516`) only accelerates
an **`EQUALS`-style** anchor term via the property index (`stores.getPropertyIndex().findAnchors`, which needs an
exact value to seek by, line 1489-1496) — the *first* term of an anchor's property predicate is always treated as the
index seek key regardless of its actual condition. A non-equality anchor predicate (e.g. `MATCH (p:Person {surname:
'Pow'}) WHERE ...` is not how this arises today, since inline property-map terms are always `EQUALS`; but once Phase 1
lands, `MATCH (p:Person) WHERE p.surname STARTS WITH 'Pow'` has *no* inline property map at all, so `resolveAnchors`
throws today's existing `UnsupportedOperationException` — "an anchor MATCH requires at least one property predicate"
— exactly as it does today for any WHERE-only anchor with no inline `{...}` map. This is **not a new gap**: a plain
`WHERE`-only anchor is already unseekable in the current subset (the property map is the only seek path), so Phase 1
does not change this restriction, only documents it. No new work; note it in the Phase 1 test suite as an explicit,
already-existing limitation (see Phase 1's Contract).

### 1.3 Note on citation drift found during verification

One citation from the source investigation was off by a small margin and is corrected here: `AstMatch`'s record
declaration is at `AstMatch.java:34-44` (not `33-38` — the extra lines are the record's Javadoc). All other citations
in this plan were opened and checked directly against the working tree and matched exactly, including several
surprisingly precise ones (e.g. `Cypher.g4`'s keyword-fragment block is at exactly lines 314-320 as expected, and
`CypherToLogicalPlan`'s `toCondition`/`compileComparisonTerm`/`compilePattern`/`compileAggregateColumn` etc. all sit
at their cited line ranges to the line).

---

## 2. Design decisions

**(a) String/IN/IS-NULL predicates ride the existing `Condition` vocabulary — no new runtime.** See §1.2. The only
new code is: a grammar alternative, an AST enum value, and one `CypherToLogicalPlan.toCondition`/dispatch arm per
operator. Rejected alternative: building a graph-specific predicate type — rejected because it would duplicate
tested, already-wired machinery for zero benefit.

**(b) Field-vs-field `WHERE` (`a.x > b.y`) is a parallel graph-local predicate, not an `ExpressionTerm` extension.**
`ExpressionTerm` is `(field, Condition, value: String)` — field vs. **constant** only, with no second-field slot —
and it is shared IR consumed by the relational/StroomQL executor too. Adding a second field slot would ripple into
every `Condition` consumer across the codebase for a feature only Cypher needs. Instead, Phase 5 carries field-vs-field
comparisons as a separate list on `CompiledCypherPlan`, composed as an extra, AND-combined `Predicate<Map<String,Val>>`
inside `GraphTraversalEngine` alongside (not instead of) the existing `wherePredicate`. Considered and rejected:
extending `ExpressionTerm` itself (ripples into the relational core); building a second parallel `ExpressionOperator`
tree reusing `Condition` values with a "this value is actually a field reference" flag (rejected — every
`ExpressionPredicateFactory` consumer would need updating to know to resolve it against a second row, defeating the
whole "route through existing machinery" property of (a)).

**(c) `collect()` gates on a new `ValList` type; `count(DISTINCT x)` does not and ships first.** `count(DISTINCT x)`
is a scalar reduction (a `long`) over an existing `AggregateColumn`-shaped mechanism — it needs one new boolean field
threaded through already-existing plumbing (Phase 1 of the analytic-functions plan, now merged). `collect()` needs a
genuinely new list-valued `Val`, which ripples through a sealed interface. Splitting them (Phase 3 vs. Phase 4) means
the cheap win ships without waiting on the expensive one, exactly as the roadmap's own utility/difficulty split
implies (Tier 1 item 3 bundles them for *user-value* reasons; this plan un-bundles them for *engineering-cost*
reasons — see the analytic-functions plan's own §1 "Reconciling with the proposal's ordering" for the precedent of
doing this).

**(d) `OPTIONAL MATCH` needs a bound/unbound marker, not just null-padding.** Null-padding an unmatched hop's
*properties* already works today for free (`evaluate()` returns `ValNull.INSTANCE` for any absent
`"variable.property"` key — `GraphTraversalEngine.java:1943-1965`). The actual gap is testing **whether the pattern
variable matched at all** — Cypher's idiomatic test is `WHERE v IS NULL`, but `rowFor` never stores a bare
`"variable"` key (only `"variable.property"` keys), so `evaluate()` has no way today to answer "was `v` bound", and
throws `UnsupportedOperationException` on a bare-variable reference regardless of whether the match happened. Phase 6
must thread an explicit bound/unbound marker per optional variable through the row map (or an equivalent side
channel) so `IS NULL` (Phase 2) can distinguish "never matched" from "matched, property absent" — these are different
things in Cypher and conflating them would violate the fail-loud-never-wrong contract by half: it would fail loud
correctly, but the *later* `WHERE v IS NULL` semantics would be silently wrong if unbound and matched-with-null-props
were indistinguishable.

**(e) Multi-stage `WITH` needs a scope environment and a frontier-seeding execute entry point.** Today every name in
a query resolves against one implicit pattern scope with no tracking at all (`fieldNameOf`, `compileNodeScan`,
`defaultColumnName`, `toQualifiedField` all just pattern-match the AST shape, with no notion of "is this name in
scope right now"). `WITH`'s defining property in Cypher is that it **narrows** scope: only the names it projects are
visible after it. Landing multi-stage `WITH` without a scope check would either wrongly reject valid post-`WITH`
references to still-materialised-but-unprojected variables (over-narrow — a correctness-safe failure) or wrongly
*accept* references to variables `WITH` dropped (under-narrow — a silent-wrong-answer risk, the one thing this
codebase's contract forbids). So Phase 7 must build the scope tracking before generalising past a single pipe. On the
engine side, `execute` is single-pass and always seeds its frontier from `resolveAnchors`; multi-stage compilation
needs a second entry point that seeds the frontier from a prior stage's already-materialised row list instead.

**(f) Phase ordering rationale (why not tier number).** The roadmap's tiers are user-value-ordered; this plan is
dependency- and cost-ordered, which is *mostly* the same but not always:

1. **Phases 1-2 (string predicates, `IN`/`IS NULL`)** first: zero engine change (§1.2), cheapest, unblock the most
   real queries, and Phase 2 (`IS NULL`) is a hard *prerequisite* for Phase 6.
2. **Phase 3 (`count(DISTINCT x)`)** next: cheap, builds on already-merged `AggregateColumn` infrastructure, no
   grammar risk.
3. **Phase 4 (`collect()`)** is gated on a new `ValList` type — the one genuinely cross-cutting, ripple-risk change
   in Tier 1-3 — so it is sequenced after the cheaper Tier-1 wins even though the roadmap lists it alongside them.
4. **Phase 5 (field-vs-field `WHERE`)** — Tier 2 in the roadmap, but re-rated here as *deeper* than the roadmap's
   "Low-Medium" suggests once the `ExpressionTerm`-has-no-second-field-slot constraint is accounted for (see design
   decision (b)); still sequenced before Tier 3 because it stays contained to the filter step, touching no plan shape.
5. **Phase 6 (`OPTIONAL MATCH`)** depends on Phase 2 (`IS NULL`) — shipping it earlier would be untestable by
   Cypher's own idiom.
6. **Phase 7 (multi-stage `WITH`)** last: it is the only feature that breaks the compiler's single-implicit-scope
   assumption and interacts with every other phase's WHERE/aggregation lowering once queries have more than one
   stage — highest blast radius, so it goes last and lands single-pipe-first.

---

## Phase 0 — Fix the StroomQL `IN` delimiter (shared-code prerequisite)

**Phase goal:** text `IN` works end-to-end through the in-memory predicate path (`ExpressionPredicateFactory`), so that
both StroomQL's existing `IN (...)` and Phase 2's new Cypher `IN [...]` resolve against a comma-delimited value the
same way the numeric/date `IN` paths already do. This is a **bug fix in shared code**, not a Cypher feature — but it is
a hard prerequisite for Phase 2 (a Cypher `IN` that lowered onto the current broken `StringIn` split would be silently
wrong, violating the fail-loud-never-wrong contract), so it ships first.

**Phase gate:** `./gradlew :stroom-query:stroom-query-common:test` green.

**Why (recap of §1.2):** `getTermNumbers`/`getTermDates` split `Condition.IN` values on `DELIMITER = ","` and `.trim()`
each; every `IN` producer joins on a comma (`AstToSearchRequestMapper` `", "`, `MetaExpressionUtil` `","`). Only
`StringIn.create` splits on a single space, so text `IN` never matches when values are comma-joined.

---

### Task 0.1 — `StringIn.create`: split on `DELIMITER`, trim, matching its siblings

**Goal.** Make text `IN` parse its value list identically to the numeric/date `IN` paths.

**Depends on.** Nothing.

**Files.**
- Edit: `stroom-query/stroom-query-common/src/main/java/stroom/query/common/v2/ExpressionPredicateFactory.java`
  (`StringIn.create`, ~lines 1939-1946; `DELIMITER` constant at line 58; sibling references `getTermNumbers`/
  `getTermDates` at 521-542).
- Edit (tests): `stroom-query/stroom-query-common/src/test/.../TestExpressionPredicateFactory.java` (or the nearest
  existing predicate test — locate it; add one if none covers `Condition.IN` on a `TEXT` field).

**Contract.**
- `StringIn.create` changes `term.getValue().split(" ")` to `term.getValue().split(DELIMITER)` and `.trim()`s each
  element before collecting into the `Set<String>` (mirror `getTermNumbers`' `values[i].trim()` exactly). An empty/blank
  value still yields `matchNone()` (preserve the existing `in.length == 0` guard; note `"".split(",")` yields `[""]`, so
  also treat a single blank element as no-match — match the spirit of the existing guard, and cover it with a test).
- Do **not** change `getTermNumbers`/`getTermDates` (already correct) or `StringInDictionary` (a different,
  whitespace-tokenised dictionary lookup — leave it alone; confirm by reading it that it is genuinely a different
  concept and not a second copy of this bug).
- Trimming makes the predicate tolerant of both `","` and `", "` joins, so it aligns with *all* existing producers at
  once (`MetaExpressionUtil`'s `","` and the mapper's `", "`).

**Done-when.** A `TEXT`-field `Condition.IN` term whose value is `"Powell, Smith"` (comma-space, as the mapper
produces) matches a row whose field is exactly `"Powell"` and one that is `"Smith"`, and rejects `"Powells"`. A
comma-only join `"Powell,Smith"` also matches. Existing numeric/date `IN` tests unaffected.

**Verify.** `./gradlew :stroom-query:stroom-query-common:test`.

---

## Phase 1 — String predicates: `STARTS WITH` / `CONTAINS` / `ENDS WITH` / `=~`

**Phase goal:** `WHERE p.surname STARTS WITH 'Pow'` / `... CONTAINS 'vehicle'` / `... ENDS WITH 'son'` /
`c.description =~ '.*vehicle.*'` all parse, compile, and filter correctly end-to-end; `=~` is bounded against
catastrophic backtracking.

**Phase gate:** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test` green.

---

### Task 1.1 — Grammar: three literal string operators

**Goal.** Add `STARTS WITH`, `CONTAINS`, `ENDS WITH` as `comparisonOp` alternatives.

**Depends on.** Nothing.

**Files.**
- Edit: `stroom-query/stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/Cypher.g4`

**Contract.**
- `comparisonOp` (currently `Cypher.g4:182-184`, `EQ | NEQ | LT | LE | GT | GE`) gains three alternatives:
  `| STARTS WITH | CONTAINS | ENDS WITH`. `WITH` is already a token (`Cypher.g4:256`) — reuse it; do not add a second
  token for it. Add new keyword tokens `STARTS`, `ENDS`, `CONTAINS` in the keyword block
  (`Cypher.g4:253-284`, letter-fragment idiom, e.g. `STARTS : S T A R T S ;`), placed before `NAME` per the existing
  convention (the whole keyword block already sits above `NAME` at `Cypher.g4:311`, so appending here is correct).
- Because `comparisonPredicate` is `left=expression op=comparisonOp right=expression` (`Cypher.g4:179-181`,
  unchanged), `STARTS WITH`/`CONTAINS`/`ENDS WITH` parse with the same shape as `=`/`<`/etc. — no new predicate rule
  needed for these three.
- Update the grammar file-header comment (the "Deliberate v1 simplifications" block, `Cypher.g4:1-65`) to note the
  new operators are supported, matching the header's role as a running "what's in/out" summary (see how it already
  documents `AS OF`/`RETURN GRAPH`/`FROM`).

**Done-when.** `CypherParser` regenerates (the build does this automatically) with `StartsContext`/etc. token types
visible; `comparisonOp` accepts all six existing plus these three operator forms.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test`. Add a `TestCypherQueryParser` case per operator
(parses; AST shape correct) — see Task 1.4 for the full list.

---

### Task 1.2 — AST: `AstComparisonOp` + builder

**Goal.** Extend the AST enum and `AstCypherBuilder.buildComparisonOp`.

**Depends on.** Task 1.1.

**Files.**
- Edit: `stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/AstComparisonOp.java`
- Edit: `stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/AstCypherBuilder.java`

**Contract.**
- `AstComparisonOp` (`AstComparisonOp.java:22-29`, currently `EQ, NEQ, LT, LE, GT, GE`) += `STARTS_WITH, CONTAINS,
  ENDS_WITH`. `AstComparisonPredicate` (unchanged — already a generic `left`/`op`/`right` record) needs no edit.
- `AstCypherBuilder.buildComparisonOp` (`AstCypherBuilder.java:325-335`, a `switch` on `ctx.getStart().getType()`)
  += `case CypherParser.STARTS -> AstComparisonOp.STARTS_WITH;` (the token to switch on is `STARTS`, since the parser
  rule is `STARTS WITH` — the leading token is what `comparisonOp`'s single-token `getStart()` sees; confirm the
  generated `ComparisonOpContext`'s start token once the grammar change lands, since the alternative spans two
  tokens, unlike the existing single-token alternatives), `case CypherParser.CONTAINS -> AstComparisonOp.CONTAINS;`,
  `case CypherParser.ENDS -> AstComparisonOp.ENDS_WITH;`.

**Done-when.** `AstCypherBuilder.build` on a query using each new operator produces an `AstComparisonPredicate` whose
`op()` is the correct new enum value.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test`. Add a builder-level assertion (or reuse
`TestCypherQueryParser`'s existing style, whichever the file's own tests do for the six existing operators) for each
new operator.

---

### Task 1.3 — Compiler: `toCondition` dispatch

**Goal.** Lower the three new `AstComparisonOp` values to `Condition.STARTS_WITH`/`CONTAINS`/`ENDS_WITH`.

**Depends on.** Task 1.2.

**Files.**
- Edit: `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/CypherToLogicalPlan.java`

**Contract.**
- `toCondition` (`CypherToLogicalPlan.java:490-499`, a `switch` over `AstComparisonOp`) += three arms:
  `case STARTS_WITH -> Condition.STARTS_WITH; case CONTAINS -> Condition.CONTAINS; case ENDS_WITH ->
  Condition.ENDS_WITH;`.
- `compileComparisonTerm` (`CypherToLogicalPlan.java:462-479`) is **unchanged** — the right side is still required to
  be an `AstLiteralExpr` (line 469-473's existing rejection of a non-literal right side already covers this; a string
  operator's right side is a string literal, which is already accepted by `renderLiteralValue`,
  `CypherToLogicalPlan.java:808-818`).
- No change to `compilePropertyPredicate` (`CypherToLogicalPlan.java:402-415`) — inline `{...}` property maps stay
  equality-only by design (they compile a `propertyKeyValue`, not a `comparisonPredicate`); string operators are a
  `WHERE`-only construct.

**Done-when.** `CypherToLogicalPlan.compile` on `MATCH (p:Person {id:1}) WHERE p.surname STARTS WITH 'Pow' RETURN
p.surname` compiles to a `Filter` whose predicate contains an `ExpressionTerm` with `condition() ==
Condition.STARTS_WITH` and `value() == "Pow"`.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test`. Add `TestCypherToLogicalPlan` positive cases for all
three operators (assert the compiled `ExpressionTerm`'s `Condition`).

---

### Task 1.4 — `=~` (regex): grammar, AST, compiler, and the backtracking guard

**Goal.** Add `=~` as a fourth comparison operator, with a bound against catastrophic backtracking / oversized
input, shipped and tested separately from Tasks 1.1-1.3 (per the roadmap's own risk note — "ship the three literal
operators first and add `=~` behind that guard").

**Depends on.** Tasks 1.1-1.3 landed and green (so the guard can be tested in isolation without the other three
operators' tests as noise).

**Files.**
- Edit: `Cypher.g4` (new structural token, not a keyword — no letter-fragment spelling needed).
- Edit: `AstComparisonOp.java`, `AstCypherBuilder.java` (same shape as Task 1.2).
- Edit: `CypherToLogicalPlan.java` (`toCondition` + a new guard).

**Contract.**
- Grammar: `REGEX : '=~' ;` as a structural token near the other operator tokens (`Cypher.g4:303-308`, where
  `NEQ`/`LE`/`GE`/`LT`/`GT`/`EQ` live) — **not** in the keyword block, since `=~` is punctuation, not a word.
  `comparisonOp` += `| REGEX`.
- AST: `AstComparisonOp` += `REGEX`; `buildComparisonOp` += `case CypherParser.REGEX -> AstComparisonOp.REGEX;`.
- Compiler: `toCondition` += `case REGEX -> Condition.MATCHES_REGEX;`. **New validation, at compile time, not left to
  the runtime regex engine**: reject a regex literal whose source text exceeds a fixed length cap (recommend 200
  characters — generous for a property filter, small enough to bound `Pattern.compile`/matching cost) with
  `CypherCompileException` ("not in PoC subset: a =~ pattern longer than N characters is rejected — simplify the
  pattern"). This is a compile-time-only guard (str length), not an attempt to statically detect exponential-blowup
  patterns (ReDoS detection is a much harder, separate problem) — document this scope limitation in the Javadoc of
  wherever the check lives (a new small private method in `CypherToLogicalPlan`, called from `compileComparisonTerm`
  when `predicate.op() == AstComparisonOp.REGEX`).
- Confirm (do not change) that `ExpressionPredicateFactory`'s `StringRegex` predicate
  (`ExpressionPredicateFactory.java:468`) uses `java.util.regex.Pattern` under the hood and compiles the pattern once
  per term construction, not per row — re-read that class's `StringRegex.create` before writing the length-cap
  Javadoc, to state accurately whether per-row cost is amortised.

**Done-when.** `WHERE c.description =~ '.*vehicle.*'` compiles and filters correctly; a pattern over the length cap
is rejected at compile time with a clear message; the existing three-operator tests (Task 1.3) still pass unaffected.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test`. Add: a
`TestCypherQueryParser` parse case, a `TestCypherToLogicalPlan` positive case (correct `Condition.MATCHES_REGEX`), and
a `TestCypherToLogicalPlan` negative case for the oversized-pattern rejection (assert
`CypherCompileException` + message).

---

### Task 1.5 — Engine-level test suite for Phase 1

**Goal.** Prove all four operators filter correctly end-to-end (no engine code changed — this task is pure test
coverage, confirming §1.2's "zero engine change" claim empirically).

**Depends on.** Tasks 1.1-1.4.

**Files.**
- Edit: `stroom-graphdb/stroom-graphdb-impl/src/test/java/stroom/graphdb/impl/TestGraphTraversalEngine.java`.

**Contract — test matrix (each an isolated `@Test`):**
- `STARTS WITH` / `CONTAINS` / `ENDS WITH` each match/reject correctly over a small seeded set of string properties
  (reuse existing `seed…` fixtures where a string property already exists, e.g. surnames/descriptions).
- `=~` matches a simple pattern; case-sensitivity behaviour is whatever `StringRegex`'s existing implementation does
  — document it in the test's name/comment rather than assuming, since this plan does not change that class.
- A **non-existent property** under a string-operator `WHERE` (schemaless graph: some nodes lack the property)
  behaves consistently with the three-valued-logic decision recorded in Phase 2 (a node missing the property does not
  match `STARTS WITH`/etc. — it is neither true nor false-in-a-way-that-matters, it simply fails the predicate; do
  not assert a specific `Val` here beyond "the row is excluded", since `ExpressionPredicateFactory`'s existing
  leniency is what is being exercised, not new code).
- Regression: the anchor-seek caveat from §1.2 — a `WHERE`-only anchor (no inline property map) still throws the
  existing `UnsupportedOperationException` from `resolveAnchors` (unchanged behaviour; add one test asserting this
  stays true, so a future change to `resolveAnchors` cannot silently break it without failing this test).

**Done-when.** All green.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

---

## Phase 2 — `IN` list membership + `IS NULL` / `IS NOT NULL`

**Phase goal:** `WHERE p.surname IN ['Powell','Smith','Jones']` and `WHERE c.closedDate IS NULL` /
`IS NOT NULL` parse, compile, and filter correctly; three-valued-logic behaviour for a missing property is decided
and documented.

**Phase gate:** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test` green.

---

### Task 2.1 — Grammar: list literal + `IN`/`IS [NOT] NULL` predicates

**Goal.** Add a bracketed list literal to `value`, and two new unary/binary predicate shapes to `primary`.

**Depends on.** Nothing (independent of Phase 1).

**Files.**
- Edit: `Cypher.g4`.

**Contract.**
- `value` (`Cypher.g4:214-220`, currently `STRING | NUMBER | (TRUE|FALSE) | PARAM | functionCall`) += a new
  alternative: `| OPEN_BRACKET (value (COMMA value)*)? CLOSE_PAREN` — **correction, use `CLOSE_BRACKET`, not
  `CLOSE_PAREN`** (brackets, not parens, close a list) `# listValue`. `OPEN_BRACKET`/`CLOSE_BRACKET`/`COMMA` are
  already tokens (used today by `edgeDetail`'s `[...]` and `propertyMap`'s `{...}`, respectively) — no new tokens
  needed for the brackets themselves.
- `primary` (`Cypher.g4:175-178`, currently `OPEN_PAREN expr CLOSE_PAREN | comparisonPredicate`) has **no unary
  predicate shape today** — confirmed by direct read. Add two new alternatives:
  `| inPredicate | isNullPredicate`, with new rules:
  ```
  inPredicate     : left=expression IN right=expression ;
  isNullPredicate : left=expression IS NOT? NULL_ ;
  ```
  `right=expression` for `inPredicate` (not `right=value`) so `p.surname IN [...]` and a future parameterised list
  both parse via the same `expression → value → listValue` path — `expression`'s `value` alternative
  (`Cypher.g4:188-194`) already reaches `value`, so no change needed there.
- New tokens: `IN` (keyword, letter-fragment idiom, `Cypher.g4:253-284` block), `IS` (keyword, same block), `NULL_`
  (keyword — trailing underscore per the existing `SKIP_` convention at `Cypher.g4:265`, since `NULL` alone risks
  colliding with generated-code reserved words the way `SKIP` already does). Reuse the existing `NOT` token
  (`Cypher.g4:269`) for the `IS NOT NULL` form — do not add a second negation token.
- Update the grammar file-header comment to note `IN`/`IS NULL`/`IS NOT NULL`/list literals are now supported.

**Done-when.** `CypherParser` regenerates with `InPredicateContext`/`IsNullPredicateContext`/`ListValueContext`
visible; `p.surname IN ['Powell','Smith']` and `c.closedDate IS NULL` / `IS NOT NULL` all parse.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test`. Parser-level parse tests for: an empty list `[]`, a
single-element list, a multi-element list, `IS NULL`, `IS NOT NULL`.

---

### Task 2.2 — AST: `AstListValue`, `AstInPredicate`, `AstIsNullPredicate`

**Goal.** Model the three new shapes; extend the two sealed interfaces they join.

**Depends on.** Task 2.1.

**Files.**
- New: `stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/AstListValue.java`
- New: `stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/AstInPredicate.java`
- New: `stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/AstIsNullPredicate.java`
- Edit: `AstValue.java` (`permits` clause, line 27-28)
- Edit: `AstBooleanExpr.java` (`permits` clause, line 24)
- Edit: `AstCypherBuilder.java`

**Contract.**
```java
/** A bracketed list literal, e.g. {@code ['Powell','Smith']} - Cypher's only list-construction syntax this
 *  subset accepts (no comprehensions, no ranges). */
public record AstListValue(List<AstValue> elements, AstPosition position) implements AstValue { ... }

/** {@code left IN right} - list/set membership. {@code right} is expected to resolve to an {@link AstListValue}
 *  at compile time (see CypherToLogicalPlan); the grammar itself does not constrain right beyond "an expression",
 *  matching primary's existing comparisonPredicate shape. */
public record AstInPredicate(AstExpression left, AstExpression right, AstPosition position)
        implements AstBooleanExpr { ... }

/** {@code left IS [NOT] NULL}. {@code negated} true for IS NOT NULL. */
public record AstIsNullPredicate(AstExpression operand, boolean negated, AstPosition position)
        implements AstBooleanExpr { ... }
```
- `AstValue` (`AstValue.java:27-28`) `permits` += `AstListValue`.
- `AstBooleanExpr` (`AstBooleanExpr.java:24`) `permits` += `AstInPredicate, AstIsNullPredicate`.
- `AstCypherBuilder.buildPrimary` (`AstCypherBuilder.java:310-315`, currently `if (ctx.expr() != null) {...} return
  buildComparisonPredicate(ctx.comparisonPredicate());`) += two new `if` branches ahead of the final
  `comparisonPredicate` fallback, checking `ctx.inPredicate() != null` / `ctx.isNullPredicate() != null` (exact
  accessor names depend on the generated `PrimaryContext` — confirm after Task 2.1's grammar regenerates, since
  `primary`'s labelled alternatives may generate distinct context subclasses like `comparisonPredicate`'s current
  unlabelled form does not; if ANTLR generates unlabelled alternatives here too, match `buildPrimary`'s existing
  `ctx.expr() != null` / else-fallback idiom rather than an `instanceof` dispatch).
- New builder methods `buildInPredicate`/`buildIsNullPredicate`, following the existing `buildComparisonPredicate`
  shape (`AstCypherBuilder.java:317-323`) — build `left`/`right` (or `operand`) via `buildExpression`.
- `AstCypherBuilder.buildValue` (`AstCypherBuilder.java:390-404`) += a branch for the new `ListValueContext`,
  recursively calling `buildValue` per element.

**Done-when.** `AstCypherBuilder.build` on `WHERE p.surname IN ['Powell','Smith']` produces an `AstInPredicate` whose
`right()` is an `AstLiteralExpr(AstListValue([...]))`; `WHERE c.closedDate IS NOT NULL` produces
`AstIsNullPredicate(operand=AstPropertyAccessExpr(c,closedDate), negated=true, ...)`.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test`. Add builder-level tests for both new predicate
shapes and the list-value builder.

---

### Task 2.3 — Compiler: lower `IN`/`IS NULL` to `ExpressionTerm`, and decide null semantics

**Goal.** Lower the two new `AstBooleanExpr` shapes to `ExpressionTerm`s using the existing `Condition.IN` /
`Condition.IS_NULL` / `Condition.IS_NOT_NULL` vocabulary (§1.2), and record the three-valued-logic decision this
plan is making.

**Depends on.** Task 2.2.

**Files.**
- Edit: `CypherToLogicalPlan.java`.
- Edit (tests): `TestCypherToLogicalPlan.java`.

**Contract.**
- `compileBooleanExpr`/`compileBooleanExprAsItem` (`CypherToLogicalPlan.java:433-460`) += dispatch arms for
  `AstInPredicate` and `AstIsNullPredicate`, mirroring how `AstComparisonPredicate` is already dispatched (line
  449-450, 456-457) — both new shapes produce a single `ExpressionTerm`, exactly like a comparison predicate does, so
  they slot into the same `addTerm`/`compileBooleanExprAsItem` pattern with no new `ExpressionOperator` shape needed.
- New `compileInTerm(AstInPredicate)`, beside `compileComparisonTerm` (line 462):
  - `left` must resolve via `fieldNameOf` (`CypherToLogicalPlan.java:481-488`) to a field name — reuse it verbatim
    (same rejection message style as `compileComparisonTerm`'s line 464-468 if it does not).
  - `right` must be an `AstLiteralExpr` wrapping an `AstListValue` — reject anything else ("not in PoC subset: the
    right side of IN must be a literal list, e.g. `['a','b']`") — a computed/variable list is out of this subset.
  - Render the list: join each element's `renderLiteralValue` (line 808-818) with `", "` (comma-space), matching
    `AstToSearchRequestMapper`'s `AstInTerm` join and the comma-`DELIMITER` that `StringIn.create` parses **after
    Phase 0** (Phase 0 is a hard prerequisite — see its Task 0.1). Now that all `IN` paths agree on comma, this is the
    ordinary, non-diverging join; no special comment needed beyond a normal reference.
  - Build `ExpressionTerm.builder().field(field).condition(Condition.IN).value(<comma-space-joined>).build()`.
  - **Reject** any list element that is not a string/number/boolean literal (mirror `renderLiteralValue`'s own
    existing rejection, line 816-817). An empty list `IN []` must match **nothing**: after Phase 0, `"".split(",")`
    yields `[""]` (length 1) and Phase 0's Task 0.1 guard treats a single blank element as `matchNone()`, so rendering
    an empty `AstListValue` to an empty value string correctly matches nothing — but do not rely on that implicitly:
    special-case an empty `AstListValue` in `compileInTerm` and lower `x IN []` to a term that provably never matches
    (or assert the empty-value→matchNone behaviour with a dedicated test at both layers). Decide and document; it is a
    real edge case verified against `String.split`'s actual behaviour.
- New `compileIsNullTerm(AstIsNullPredicate)`: resolve `operand` via `fieldNameOf`; build
  `ExpressionTerm.builder().field(field).condition(negated ? Condition.IS_NOT_NULL : Condition.IS_NULL).build()` —
  **no `.value(...)` call** (mirrors `AstToSearchRequestMapper.buildTerm`'s `AstIsNullTerm` branch exactly, line
  535-538, which also omits `.value(...)`).
- **Three-valued-logic decision (record here, in the class Javadoc or a dedicated comment block near
  `compileBooleanExpr`):** this subset does **not** implement full Cypher three-valued logic (`true`/`false`/`null`
  propagating through `AND`/`OR`/`NOT`) — it keeps `ExpressionPredicateFactory`'s existing two-valued boolean
  predicate semantics (a term either matches a row or it does not; there is no `null` result that propagates
  specially through `AND`/`OR`). This is **consistent with today's existing behaviour**, since equality/comparison
  terms already behave this way (a missing property fails an `EQUALS` term today, full stop — it does not produce a
  `null` that a surrounding `NOT` would flip to `true`). Document explicitly: `WHERE c.closedDate IS NULL` on a
  row where `closedDate` is absent → **matches** (true); `IS NOT NULL` on the same row → does not match (false);
  `WHERE p.x = 'y'` on a row lacking `x` → does not match (false), and `WHERE NOT p.x = 'y'` on that same row →
  **does** match (true) — i.e. `NOT` really does invert a missing-property comparison's two-valued result, unlike
  Cypher's `null`-propagating three-valued `NOT null = null`. This is a **deliberate, documented simplification**,
  not an oversight — flag it prominently in the user-facing docs (§ Documentation, below) since it is the one place
  this subset's behaviour diverges from standard Cypher in a way a Cypher-literate user might not expect.

**Done-when.** `MATCH (p:Person {id:1}) WHERE p.surname IN ['Powell','Smith'] RETURN p.surname` compiles to a
`Condition.IN` term with value `"Powell, Smith"` (comma-space-joined, matching Phase 0's `DELIMITER`). `WHERE
c.closedDate IS NULL` compiles to a `Condition.IS_NULL` term with no value. `IN []` is handled per the explicit
empty-list decision above (test both the chosen behaviour and that it never throws at execution).

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test`. `TestCypherToLogicalPlan` cases: `IN` with 1/2/3
elements (assert exact comma-space-joined value string), `IN []`, `IS NULL`, `IS NOT NULL`, and the negative case
(`IN` with a non-literal-list right side → `CypherCompileException`).

---

### Task 2.4 — Engine-level test suite for Phase 2

**Goal.** Prove `IN`/`IS NULL`/`IS NOT NULL` filter correctly end-to-end, including the documented two-valued-logic
behaviour and the empty-list edge case (again: no engine code change — pure coverage).

**Depends on.** Tasks 2.1-2.3.

**Files.**
- Edit: `TestGraphTraversalEngine.java`.

**Contract — test matrix:**
- `IN` matches any of 2-3 listed values; does not match a value outside the list.
- `IN []` never matches any row (assert the Task 2.3 decision, whichever it was).
- A surname containing a literal space is **not** currently representable safely in an `IN` list of more than one
  element (the space-join delimiter from Task 2.3 cannot distinguish "a space inside one value" from "the separator
  between two values") — add an explicit test **documenting this known limitation** (assert current, possibly-wrong
  behaviour, with a comment pointing at this plan's §1.2 gotcha and Task 2.3, so it is a deliberate, tracked gap, not
  a silent one — the "fail loud, never wrong" contract is about *rejecting* out-of-subset input, and this is an
  in-subset value-collision edge case worth flagging for a future dedicated fix rather than pretending it does not
  exist).
- `IS NULL` matches a node lacking the property; does not match one that has it (any value, including one that is
  itself semantically "empty").
- `IS NOT NULL` is the exact converse of `IS NULL` over the same seeded set.
- `NOT (p.x = 'y')` on a row lacking `x` matches — the two-valued-logic decision from Task 2.3, made explicit as a
  regression test.

**Done-when.** All green; the space-delimiter limitation is captured as a named, documented test rather than an
unexamined gap.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

---

## Phase 3 — `count(DISTINCT x)`

**Phase goal:** `RETURN p.surname, count(DISTINCT c.type) AS distinctTypes` groups by `p.surname` and counts distinct
non-null `c.type` values per group.

**Phase gate:** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test` green.

---

### Task 3.1 — Grammar: optional `DISTINCT` in `aggregateCall`

**Goal.** Accept `count(DISTINCT expr)` alongside the existing `count(expr)`/`count(*)`.

**Depends on.** Nothing.

**Files.**
- Edit: `Cypher.g4`.

**Contract.**
- `aggregateCall` (`Cypher.g4:196-198`, currently `fn=(COUNT|SUM|AVG|MIN|MAX) OPEN_PAREN (STAR|expression)
  CLOSE_PAREN`) becomes `fn=(COUNT|SUM|AVG|MIN|MAX) OPEN_PAREN DISTINCT? (STAR|expression) CLOSE_PAREN`. `DISTINCT` is
  already a token (`Cypher.g4:260`, used today by `returnClause`'s `RETURN DISTINCT?`) — reuse it, no new token.
- No grammar-level restriction on which function `DISTINCT` may follow (`sum(DISTINCT a.p)` parses too) — the
  compiler (Task 3.2) is the layer that decides which combinations are meaningful, matching this grammar's existing
  "parse broadly, compile progressively" discipline (see the file header's note on multi-stage `WITH`).

**Done-when.** `count(DISTINCT c.type)` and `count(DISTINCT *)` both parse (the latter is nonsensical but is a
compile-time rejection, not a parse-time one — consistent with how `sum(*)` already parses today and is rejected only
at compile time, `CypherToLogicalPlan.java:658-664`).

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test`. Parse tests for `count(DISTINCT x)` and
`count(DISTINCT *)`.

---

### Task 3.2 — AST: `AstAggregateExpr.distinct`

**Goal.** Thread a `distinct` flag through the aggregate AST node.

**Depends on.** Task 3.1.

**Files.**
- Edit: `AstAggregateExpr.java`.
- Edit: `AstCypherBuilder.java`.

**Contract.**
- `AstAggregateExpr` (`AstAggregateExpr.java:33-37`, currently `(function, argument, star, position)`) += `boolean
  distinct` as a new component: `(function, argument, star, distinct, position)`. **Breaking constructor change** —
  its only call sites are `AstCypherBuilder.buildAggregateCall` (`AstCypherBuilder.java:366, 368` — both `return new
  AstAggregateExpr(...)` calls). Update the compact constructor's Javadoc/precondition comment (currently documents
  "exactly one of star or argument", unaffected by this addition — `distinct` is orthogonal, not part of that
  invariant, same relationship `AggregateColumn.distinct` will have to its own star/argRowKey/argIsVariable invariant
  in Task 3.3).
- `AstCypherBuilder.buildAggregateCall` (`AstCypherBuilder.java:356-369`) reads `ctx.DISTINCT() != null` and passes it
  to both `new AstAggregateExpr(...)` calls (line 366 and 368).

**Done-when.** `count(DISTINCT c.type)` parses to `AstAggregateExpr(COUNT, AstPropertyAccessExpr(c,type), false,
distinct=true, ...)`.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test`. Builder test asserting `distinct=true`/`false` for
both forms.

---

### Task 3.3 — Compiler: `AggregateColumn.distinct` + naming

**Goal.** Thread `distinct` through `AggregateColumn`, and render it in the default output column name so
`count(a.x)` and `count(DISTINCT a.x)` never collide as `FieldIndex`/column keys.

**Depends on.** Task 3.2.

**Files.**
- Edit: `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/AggregateColumn.java`.
- Edit: `CypherToLogicalPlan.java`.
- Edit (tests): `TestCypherToLogicalPlan.java`.

**Contract.**
- `AggregateColumn` (`AggregateColumn.java:41-45`, currently `(function, argRowKey, star, argIsVariable)`) += `boolean
  distinct`. This is **orthogonal** to the existing "exactly one of star/argRowKey/argIsVariable" invariant
  (`AggregateColumn.java:52-59`) — do not fold it into that guard's arithmetic; add a **separate**, explicit
  precondition instead: `distinct` is only meaningful for `star=false` with a non-null `argRowKey` (i.e.
  `count(DISTINCT a.p)`, not `count(DISTINCT *)` or `count(DISTINCT v)`) — reject the other two combinations at
  **compile time** (see below), so the record's own compact constructor need not re-derive that rule (leave it purely
  a data holder for the shape the compiler has already validated, consistent with the existing invariant's own
  "enforced where this is built, not here" comment style on `star`/`argIsVariable`).
- `CypherToLogicalPlan.compileAggregateColumn` (`CypherToLogicalPlan.java:655-686`) threads `aggregate.distinct()`
  through, with **new validation**:
  - `count(DISTINCT *)` → reject ("not in PoC subset: DISTINCT is not meaningful with count(*) — did you mean
    count(DISTINCT <property>)?").
  - `<fn>(DISTINCT <bare variable>)` (any function) → reject, mirroring the existing bare-variable-arg rejection at
    line 673-680 ("aggregate a property, not a whole node/edge").
  - `sum|avg|min|max(DISTINCT a.p)` → **decide**: either support it (dedupe the values before reducing — a legitimate
    Cypher shape, "sum of distinct values") or reject it as out of this PoC's scope. **Recommendation: support it**
    — it costs nothing extra once `distinct` is threaded as a generic per-aggregate flag (Task 3.4 already needs a
    dedup step for `count(DISTINCT)`; reusing it for the other four is close to free) — but this is a judgement call
    for whoever picks up this task; if deferred, reject with a clear "not in PoC subset" message instead and note the
    deferral here.
  - `count(DISTINCT a.p)` → the mainline case: `new AggregateColumn(COUNT, "a.p", false, false, distinct=true)`.
- **Naming (prevents a malformed `FieldIndex` key, same class of bug the analytic-functions plan's Task 1.1 already
  fixed for the non-distinct case):** `defaultAggregateName` (`CypherToLogicalPlan.java:582-585`) must render
  `distinct` in the name, e.g. `count(distinct a.type)` (unaliased). Without this, `count(a.type)` and
  `count(DISTINCT a.type)` in the same `RETURN` (or across different queries against the same cached `FieldIndex`)
  would collide on the same default column name. `renderExpression`'s aggregate branch (`CypherToLogicalPlan.java:
  760-765`, kept for explain/debug only per the existing comment) should also reflect `distinct` for consistency,
  though it is not read at execution.

**Done-when.** `RETURN p.surname, count(DISTINCT c.type) AS distinctTypes` compiles with an `AggregateColumn(COUNT,
"c.type", false, false, distinct=true)`. An unaliased `count(DISTINCT c.type)` gets default name
`"count(distinct c.type)"`. `count(DISTINCT *)` and `count(DISTINCT v)` are rejected with clear messages.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test`. Positive case (shape + name), two negative cases,
one regression case (`count(a.type)` next to `count(DISTINCT a.type)` in the same `RETURN` compiles with two distinct
non-colliding `ProjectField` names).

---

### Task 3.4 — Engine: distinct-set reduction

**Goal.** `reduceCount` (and, if Task 3.3 chose to support it, `reduceSum`/`reduceAvg`/`reduceMinOrMax`) dedupe by
`Val` equality before reducing when `aggregateColumn.distinct()` is true.

**Depends on.** Task 3.3.

**Files.**
- Edit: `stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java`.

**Contract.**
- `reduceCount` (`GraphTraversalEngine.java:1815-1826`, currently: `star`/`argIsVariable` → `group.size()`;
  else count non-null `argRowKey` values) += a `distinct` branch: when `aggregateColumn.distinct()`, collect the
  **non-null** `argRowKey` values into a `Set<Val>` (every `Val` implements `equals`/`hashCode` — same pattern
  `finalizeAggregatedRows`' own `groupKeyOf`/DISTINCT dedup already uses, line 1728-1733) and return
  `ValLong.create(set.size())`, instead of the plain non-null count.
- `reduceAggregate`'s dispatch (`GraphTraversalEngine.java:1799-1807`, a `switch` on `aggregateColumn.function()`) is
  **unchanged in shape** — `distinct` is read *inside* each reduce function, not dispatched on separately, since it
  is a modifier of the existing five functions, not a sixth function.
- If Task 3.3 chose to support `DISTINCT` on `sum`/`avg`/`min`/`max` too: `reduceSum`/`reduceAvg`
  (`GraphTraversalEngine.java:1830-1856`) and `reduceMinOrMax` (line 1864-...) each gain the same "collect distinct
  `Val`s first, then reduce over the deduped set" step. If deferred: no change to those three, and Task 3.3's
  compile-time rejection is what keeps them from ever seeing `distinct=true`.

**Done-when.** `count(DISTINCT c.type)` over a group with duplicate `c.type` values returns the count of *distinct*
values, not the raw row count.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

---

### Task 3.5 — Test suite for Phase 3

**Goal.** End-to-end coverage: grammar → compile → engine → provider.

**Depends on.** Tasks 3.1-3.4.

**Files.**
- Edit: `TestGraphTraversalEngine.java`, `TestCypherToLogicalPlan.java`, `TestGraphSearchProvider.java`.

**Contract — test matrix:**
- `count(DISTINCT a.p)` per group, with duplicate values in some groups.
- `count(DISTINCT a.p)` vs. plain `count(a.p)` differ when duplicates are present, agree when they are not.
- `count(DISTINCT *)`/`count(DISTINCT v)` rejected at compile time (assert message).
- Provider round-trip: an aliased `count(DISTINCT c.type) AS distinctTypes` and an unaliased `count(DISTINCT
  c.type)` both resolve correctly through `${...}` column references (mirrors the analytic-functions plan's Task
  1.4 guard for the exact same reason — an unaliased default name containing `distinct`/spaces/parens must still
  round-trip through `CypherCompiler.buildResultRequests`'s `${" + field.name() + "}"` wrapping).

**Done-when.** All green.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test`.

---

## Phase 4 — `collect()` (gated on `ValList`)

**Phase goal:** `RETURN p.surname, collect(c.type) AS crimeTypes` groups by `p.surname` and gathers each group's
`c.type` values (duplicates kept, first-appearance order) into one output cell.

**Phase gate:** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test` green.

> **Implementation note (2026-07-25): shipped with the delimiter-joined `ValString` fallback, not `ValList`.**
> Task 4.1's true `ValList` proved a disproportionate ripple - a new `Val` variant needs a new `Type` id, a
> `ValSerialiser` entry, and handling across many `Type`/`Val` consumers (search Solr/Elastic, plan-b, UI clients,
> query functions), several via non-exhaustive `instanceof` chains that would silently mishandle a new variant (a
> fail-loud violation in modules outside this plan's test scope). Per this plan's Risks table and the
> analytic-functions plan's own Phase 2 alternative, `collect()` therefore produces a **comma-joined `ValString`**
> (`GraphTraversalEngine.reduceCollect`), DISTINCT-aware, with zero serialisation ripple. A true list-valued `Val`
> (which would also enable a future `UNWIND` over a collected list) remains deferred: **Task 4.1 (`ValList`) is not
> done**; Tasks 4.2-4.5 are, against the `ValString` representation.

---

### Task 4.1 — `ValList`: the new list-valued `Val` (cross-cutting; do this first, in isolation)

**Goal.** Add a list-valued `Val` implementation, since none exists today (`Val.java:52` permits only `ValNumber,
ValString, ValErr, ValNull, ValBoolean, ValXml`).

**Depends on.** Nothing (independent of Phases 1-3; can be built and tested in complete isolation from Cypher).

**Files.**
- New: `stroom-query/stroom-query-language/src/main/java/stroom/query/language/functions/ValList.java`.
- Edit: `stroom-query/stroom-query-language/src/main/java/stroom/query/language/functions/Val.java` (`permits`
  clause).
- Edit: `stroom-query/stroom-query-language/src/main/java/stroom/query/language/functions/Type.java` (`Type.java:
  22-35` enum — add `LIST`).
- Investigate and edit as needed: `ValSerialiser` (or equivalent — search for how `Val` instances are serialised for
  the result store; not directly cited in the source investigation this plan is based on, so **locate it fresh**:
  `grep -rl "ValSerialiser\|Val.*Serial" stroom-query/stroom-query-language` before writing this task's code, and
  update this file's citation once found).
- Investigate and edit as needed: every exhaustive `switch`/`instanceof` chain over `Val`'s permitted types or over
  `Type` — `grep -rn "instanceof Val\b\|case Val\b" --include=*.java` and `grep -rn "case NULL\|case BOOLEAN\|case
  STRING ->" --include=*.java` across the repo to find every site the compiler will flag once `ValList`/`Type.LIST`
  exist; the Java compiler's own exhaustiveness checking on sealed types/enums will surface most of these
  automatically as build failures — treat every one as a required edit, not an optional one.

**Contract.**
- `ValList` implements `Val`, holding an immutable `List<Val>` (defensively `List.copyOf`'d in its constructor, per
  house style).
- `equals`/`hashCode`: structural, delegating to `List<Val>`'s own (`List` already defines both correctly for
  elements that themselves implement them — every `Val` does).
- `toString()`: comma-joined element `toString()`s (document the exact format — e.g. `"[Drugs, Burglary]"` vs.
  `"Drugs, Burglary"` — pick one and be consistent with how the table/CSV coprocessor surfaces render other composite
  values, if any precedent exists; if none, document the choice here as this plan's decision).
- `compareTo`: define a total order (e.g. lexicographic over elements, falling back to length) — needed because
  `Val` is `Comparable<Val>` and `min`/`max`/sort paths assume every `Val` supports it, even though `collect()`'s
  output is never itself the input to `min`/`max` in this plan's scope; define it defensively rather than throwing,
  to avoid an `UnsupportedOperationException` surprising an unrelated code path that happens to sort a column of
  mixed `Val` types.
- `Type.LIST` added to the enum (`Type.java:22-35`) with a `HasPrimitiveValue` id one past the last existing entry
  (`XML`'s id 13 — use 14; confirm no other code assigns fixed meaning to specific numeric ids before doing so, e.g.
  serialised storage format compatibility — if `Type`'s ids are persisted anywhere across versions, treat this as a
  **potential backward-compatibility risk** and flag it explicitly rather than assuming it is safe).
- **Serialisation**: whatever mechanism persists a `Val` into the result store (`ValSerialiser` or equivalent) must
  round-trip a `ValList` — nested list-of-`Val` serialisation, most likely length-prefixed recursive encoding of each
  element. This is the single riskiest sub-task in this entire plan (per the analytic-functions plan's own §5 Risks
  table, "`ValList` ripple through the `Val` sealed interface is large (Med, Phase 2 only)") — budget real
  investigation time here, and if the ripple proves disproportionate, **fall back to the analytic-functions plan's
  own documented alternative**: a delimiter-joined `ValString` (lossy, no real list semantics, but zero new type).
  Record whichever choice is made, and why, in this document.

**Done-when.** `ValList` round-trips through whatever serialisation the result store uses; `equals`/`hashCode`/
`compareTo`/`toString` are unit-tested directly (no Cypher involved yet); the build compiles clean with `Type.LIST`
and `ValList` present (i.e. every previously-exhaustive `switch`/`instanceof` over `Val`/`Type` has been updated).

**Verify.** `./gradlew :stroom-query:stroom-query-language:test` (confirm this is the correct module/gradle path for
`stroom-query-language`'s own tests — `include 'stroom-query:stroom-query-language'` is confirmed present in
`settings.gradle`) plus a full-repo compile (`./gradlew compileJava compileTestJava`) to catch every non-exhaustive
`switch` the sealed-type change surfaces, across all modules, not just this one.

---

### Task 4.2 — Grammar + AST: `COLLECT`

**Goal.** Add `collect(...)` as a sixth aggregate function.

**Depends on.** Nothing (independent of Task 4.1 — can be built in parallel; only the *reduce* step, Task 4.4,
depends on `ValList` existing).

**Files.**
- Edit: `Cypher.g4`.
- Edit: `stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/AstAggregateFunction.java`.
- Edit: `AstCypherBuilder.java`.

**Contract.**
- New keyword token `COLLECT` (letter-fragment idiom, keyword block).
- `aggregateCall` (`Cypher.g4:196-198`, after Task 3.1's `DISTINCT?` insertion) becomes `fn=(COUNT|SUM|AVG|MIN|MAX
  |COLLECT) OPEN_PAREN DISTINCT? (STAR|expression) CLOSE_PAREN`.
- `AstAggregateFunction` (`AstAggregateFunction.java:22-28`, currently `COUNT, SUM, AVG, MIN, MAX`) += `COLLECT`.
- `AstCypherBuilder.buildAggregateCall`'s `switch` (`AstCypherBuilder.java:357-364`) += `case CypherParser.COLLECT ->
  AstAggregateFunction.COLLECT;`.
- Update the grammar file-header comment's aggregate-function list.

**Done-when.** `collect(c.type)` and `collect(DISTINCT c.type)` both parse to an `AstAggregateExpr(COLLECT, ...,
distinct=...)`.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test`. Parse + builder tests.

---

### Task 4.3 — Compiler: `collect()` argument validation

**Goal.** Lower `collect(a.p)` to an `AggregateColumn(COLLECT, "a.p", ...)`; reject `collect(*)` and
`collect(variable)`.

**Depends on.** Task 4.2 (does not need Task 4.1 to compile — the `ValList` type is only needed at execution).

**Files.**
- Edit: `CypherToLogicalPlan.java`.
- Edit (tests): `TestCypherToLogicalPlan.java`.

**Contract.**
- `compileAggregateColumn` (`CypherToLogicalPlan.java:655-686`) += `COLLECT` to the same validation shape the other
  four functions already get:
  - `collect(*)` → reject, mirroring line 658-664's existing star-rejection message ("not in PoC subset:
    collect(*) is not supported — only count(*) is meaningful over a whole row").
  - `collect(<bare variable>)` → reject, mirroring line 673-680's existing whole-element rejection ("a whole matched
    node/edge has no single value representation yet — aggregate one of its properties instead"). This is the same
    rationale as the other four functions' bare-variable rejection, **not a new restriction invented for
    `collect`** — `collect(v)` would need `ValList` of *nodes*, not properties, which this subset has no
    representation for regardless of `ValList` existing.
  - `collect(a.p)` → `AggregateColumn(COLLECT, "a.p", false, false, distinct=<from Task 3.1's DISTINCT? if present>)`.

**Done-when.** `collect(c.type)` compiles; `collect(*)`/`collect(c)` are rejected with clear messages.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test`.

---

### Task 4.4 — Engine: `collect` reduction into `ValList`

**Goal.** `reduceAggregate`'s dispatch gains a `COLLECT` branch building a `ValList` per group.

**Depends on.** Tasks 4.1, 4.3.

**Files.**
- Edit: `GraphTraversalEngine.java`.

**Contract.**
- `reduceAggregate` (`GraphTraversalEngine.java:1799-1807`) += `case COLLECT -> reduceCollect(aggregateColumn,
  group);`.
- New `reduceCollect`: gather each group's row's non-null `argRowKey` values, in **first-appearance order within the
  group** (Cypher's `collect` preserves order and keeps duplicates — **do not** dedupe unless `distinct` is set, in
  which case dedupe while preserving first-appearance order, e.g. via a `LinkedHashSet<Val>` before wrapping in
  `ValList`). Skip absent/`ValNull` values (matching `reduceSum`/`reduceAvg`'s existing null-skipping convention,
  `isPresent`/`numericValue` helpers already used by `reduceCount`/`reduceSum`). An empty group (or a group where
  every value is null/absent) → `ValList` wrapping an empty list (**not** `ValNull.INSTANCE` — Cypher's `collect`
  over nothing is an empty list, not null, unlike `avg`).

**Done-when.** `collect(c.type)` per group returns a `ValList` of that group's `c.type` values, duplicates kept,
order preserved; an empty group yields an empty `ValList`, not null.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

---

### Task 4.5 — Test suite for Phase 4

**Goal.** End-to-end coverage including the output-rendering question §Step 4 raised (how a list renders in the
table/CSV surfaces).

**Depends on.** Tasks 4.1-4.4.

**Files.**
- Edit: `TestGraphTraversalEngine.java`, `TestCypherToLogicalPlan.java`, `TestGraphSearchProvider.java`.

**Contract — test matrix:**
- `collect(c.type)` per group, with and without duplicates, with and without `DISTINCT`.
- `collect(*)`/`collect(v)` rejected at compile time.
- Empty group → empty `ValList`, not null.
- Provider round-trip: assert how a `ValList` cell actually renders in `readTableRows`/CSV output (this is a real
  question this task must answer empirically, not assume — run the test, inspect the actual rendered cell, and
  document the observed format here once known, since no precedent existed before this phase).
- `RETURN GRAPH` mode is unaffected: `GraphElementExecutor`'s fixed 6/7-column element-row schema
  (`CypherToLogicalPlan.ELEMENT_ROW_COLUMNS`, `CypherToLogicalPlan.java:153-154`) does not go through
  `finalizeAggregatedRows` at all (`compileReturnGraph` never builds a `CypherAggregation` — line 295's construction
  passes `null` for it) — add a regression test confirming a `RETURN GRAPH` query combined with an unrelated
  `collect()` elsewhere is simply not a valid combination (RETURN GRAPH has no RETURN items to aggregate) rather than
  silently interacting.

**Done-when.** All green; the list-rendering format is documented from observed behaviour, not assumed.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test`.

---

## Phase 5 — Field-vs-field `WHERE` (`a.x > b.y`)

**Phase goal:** `MATCH (a:Account)-[:TRANSFER]->(b:Account) WHERE a.balance > b.balance RETURN a, b` compiles and
filters correctly, comparing two matched elements' properties.

**Phase gate:** `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test` green.

**No grammar change** — `comparisonPredicate`'s `left`/`right` are already both `expression`, and a `propertyAccess`
is already a valid `expression` on either side (`Cypher.g4:188-194`); the grammar already parses `a.x > b.y` today —
confirmed by `TestCypherToLogicalPlan.comparingTwoFieldReferences_throwsNotInPoCSubset`
(`TestCypherToLogicalPlan.java:576-584`), which parses such a query successfully and only fails at **compile** time.
This phase is compiler + engine only.

> **Implementation note (2026-07-25): done, per design decision (b).** `FieldComparison` (planner.cypher) carries
> field-vs-field comparisons on `CompiledCypherPlan`; `GraphTraversalEngine` AND-combines them as an extra
> `Predicate<Map<String,Val>>` (a 7-arg `execute` overload, threaded from `GraphSearchProvider`). **v1 scope:**
> field-vs-field is only lifted out when it is a *top-level conjunct* of the `WHERE` (a direct operand of the root
> `AND`, or the whole `WHERE`); nested inside `OR`/`NOT`/parentheses it reaches `compileComparisonTerm` and is
> rejected. Only the six relational operators are allowed (string operators rejected); field-vs-field combined with
> `DIFF`/`RETURN GRAPH` is rejected (keeps the single ordinary-`execute` composition site the only one needed). The
> replaced test is now `comparingTwoFieldReferences_compilesToAFieldComparison`.

---

### Task 5.1 — Compiler: classify literal-vs-field vs. field-vs-field, carry the latter separately

**Goal.** `compileComparisonTerm` currently throws whenever the right side is not an `AstLiteralExpr`
(`CypherToLogicalPlan.java:469-473`). Change it to classify both sides and route field-vs-field comparisons to a new,
separate carrier on `CompiledCypherPlan`, per design decision (b) — **not** into the `ExpressionOperator` tree.

**Depends on.** Nothing (independent of Phases 1-4).

**Files.**
- New (or edit `CypherToLogicalPlan.java` directly, whichever is cleaner given only ~3 fields):
  `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/FieldComparison.java`
- Edit: `CompiledCypherPlan.java`.
- Edit: `CypherToLogicalPlan.java`.
- Edit (tests): `TestCypherToLogicalPlan.java` (this will need to **flip** the existing
  `comparingTwoFieldReferences_throwsNotInPoCSubset` test — it currently asserts rejection; after this phase it must
  assert successful compilation with the comparison carried on the new field. Do not delete the test's spirit —
  repurpose it to assert the new positive behaviour, and add a fresh negative test for whatever *is* still rejected,
  if anything, in this shape).

**Contract.**
```java
/** One field-vs-field WHERE comparison ({@code a.x > b.y}) - carried separately from the shared
 *  {@link stroom.query.api.ExpressionOperator} tree because that shared IR's {@link stroom.query.api.ExpressionTerm}
 *  has no second-field slot (see cypher-subset-extension-implementation-plan.md §2(b) for the rejected
 *  ExpressionTerm-extension alternative). Composed with the ordinary WHERE predicate by GraphTraversalEngine at
 *  execution, AND-combined.
 *
 * @param leftRowKey  never null; "variable.property".
 * @param op          never null.
 * @param rightRowKey never null; "variable.property".
 */
public record FieldComparison(String leftRowKey, AstComparisonOp op, String rightRowKey) { ... }
```
- `CompiledCypherPlan` (`CompiledCypherPlan.java:54-60`) += a new component, `List<FieldComparison>
  fieldComparisons` (never null; `List.of()` when there are none — **not** nullable, unlike `aggregation`/
  `diffContext`, since "no field comparisons" is the overwhelmingly common case and an empty list is a cleaner
  no-op than a null check at every consumption site). Update its Javadoc and **both** construction sites (line
  237-238 and line 295 — note there are now two, not the one the analytic-functions plan described when it was
  written, since `RETURN GRAPH` support landed since; `compileReturnGraph`'s construction, line 295, passes
  `List.of()` since `RETURN GRAPH`'s `WHERE` is pattern-only, same restriction it already applies to
  `changeKind`/`before`/`after`).
- `compileComparisonTerm` (`CypherToLogicalPlan.java:462-479`): classify `predicate.left()`/`predicate.right()` via
  `fieldNameOf` each. Four cases:
  1. `left` not a field (fails `fieldNameOf`) → today's existing rejection (line 464-468), unchanged.
  2. `left` a field, `right` an `AstLiteralExpr` → today's existing path, unchanged (returns an `ExpressionTerm`).
  3. `left` a field, `right` also a field (`fieldNameOf(right)` succeeds too) → **new**: this is a field-vs-field
     comparison. This method can no longer return a single `ExpressionTerm` for this case — restructure so the
     caller (`compileBooleanExpr`/`compileBooleanExprAsItem`, `CypherToLogicalPlan.java:433-460`) can distinguish
     "ordinary term" from "field comparison, accumulate separately, contribute no term to the boolean tree here".
     **Design question to resolve carefully**: a field-vs-field comparison inside a compound `WHERE ... OR ...`
     cannot simply be pulled out to a top-level AND-list — `a.x > 1 OR (b.y < c.z)` cannot be decomposed that way
     without changing the query's meaning. **Scope this phase to field-vs-field comparisons that appear only at the
     top level of an implicit-AND `WHERE` chain** (i.e. every top-level `AND`ed comparison, not nested inside an
     `OR`/`NOT`) and **reject** (with a clear "not in PoC subset" message) a field-vs-field comparison found inside an
     `OR` or a `NOT` — this is a real, deliberate scope-narrowing decision this task must make explicit, not an
     oversight; document it in `CypherToLogicalPlan`'s class Javadoc alongside the other "what this class does not
     lower" notes (line 127-132).
  4. `right` a field but `left` not (fails `fieldNameOf`) → same rejection as case 1 (the grammar's `left`/`right`
     labels are arbitrary; a user writing `1 < a.x` should get the same "left side must be a property access" message
     — or, better, detect this and swap sides with the operator's converse — decide and document which).
- `toCondition`(`CypherToLogicalPlan.java:490-499`)'s enum values are reused as-is for `FieldComparison.op` (it is
  `AstComparisonOp`, not `Condition` — the field-vs-field comparator in the engine, Task 5.2, resolves `Val.compareTo`
  itself, not `ExpressionPredicateFactory`, so there is no need to convert to `Condition` here at all).

**Done-when.** `WHERE a.balance > b.balance` compiles successfully, contributing zero terms to the `Filter`'s
`ExpressionOperator` and one `FieldComparison("a.balance", GT, "b.balance")` to `CompiledCypherPlan
.fieldComparisons()`. `WHERE a.balance > b.balance OR a.x = 1` is rejected (field comparison inside an `OR`). A
`WHERE` mixing an ordinary literal comparison and a top-level-`AND`ed field comparison compiles both correctly
(the literal one as a `Filter` term, the field one on the new list).

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test`. Cases: field-vs-field at top level (positive),
inside `OR`/`NOT` (negative), mixed with literal comparisons (positive, both paths populated correctly), the flipped
`comparingTwoFieldReferences_throwsNotInPoCSubset` regression.

---

### Task 5.2 — Engine: compose the field-comparison predicate at every construction site

**Goal.** Build a `Predicate<Map<String,Val>>` from `CompiledCypherPlan.fieldComparisons()` and AND-combine it with
the existing `wherePredicate` at every place the engine builds one.

**Depends on.** Task 5.1.

**Files.**
- Edit: `GraphTraversalEngine.java`.
- Edit: `GraphSearchProvider.java` (thread `compiled.fieldComparisons()` into whichever `execute`/`executeXxx` call
  needs it — confirm exact call sites by reading `GraphSearchProvider.createResultStore`, `~line 151` onward, since
  this is the same threading pattern the analytic-functions plan's Task 1.2 already established for `aggregation`).

**Contract.**
- New method, e.g. `private static Predicate<Map<String,Val>> fieldComparisonPredicate(List<FieldComparison>
  comparisons)`: for each `FieldComparison`, resolve both row keys via `row.get(leftRowKey)`/`row.get(rightRowKey)`
  (mirroring `rowFor`'s `"variable.property"` keying, `GraphTraversalEngine.java:1537-1543`), compare via
  `Val.compareTo` (the same natural ordering `rowComparator`, line 1657-1672, and `reduceMinOrMax`, line
  1864-onward, already rely on for cross-type comparison), and evaluate the `AstComparisonOp`. **Null handling**:
  either side missing/`ValNull` → the comparison does not match (two-valued logic, consistent with Phase 2's
  documented decision) — do not throw. An empty `comparisons` list → predicate that always returns `true` (a
  no-op AND term), so every call site can unconditionally AND-combine without a null check.
- **Every** `wherePredicate`/`propertyPredicate` construction site gets this combined in, via `Predicate.and(...)`:
  `execute` (`GraphTraversalEngine.java:302-306`), `executeDiffBindings` (line 443-447), `executeGraphBindings` (line
  698-702), `executeGraphBindingsAsOf` (line 883-887). **`matchesTargetConstraint`** (line 1266-1287) is a
  **separate** concern — it validates a *single hop's target* against that hop's own inline constraint, not the
  whole row's `WHERE` clause, so field comparisons (which reference two *different* pattern variables, not one hop's
  target alone) do **not** belong there; confirm this by re-reading `matchesTargetConstraint`'s Javadoc (line
  1260-1265) before assuming otherwise — the four `wherePredicate` sites above are the correct (and only) integration
  points, since a field comparison can only be evaluated once both referenced variables' rows have merged (which is
  exactly when `wherePredicate` itself already runs, per-row, not per-hop).
- Thread `List<FieldComparison>` down from `GraphSearchProvider` into whichever `execute` overload needs it — decide
  whether this needs a **new** `execute` overload (matching the `aggregation`-overload pattern,
  `GraphTraversalEngine.java:248-294`) or can be folded into the existing 6-arg overload's signature as a 7th
  parameter; given `distinct`/`aggregation` already went the "new overload, old ones delegate with a default"
  route, **follow the same convention** for consistency (a 7-arg overload; the existing 4/5/6-arg overloads delegate
  with `fieldComparisons = List.of()`).

**Done-when.** `MATCH (a:Account)-[:TRANSFER]->(b:Account) WHERE a.balance > b.balance RETURN a.id, b.id` returns
only rows where the traversed `a`/`b` pair satisfies the comparison.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

---

### Task 5.3 — Test suite for Phase 5

**Goal.** End-to-end coverage.

**Depends on.** Tasks 5.1-5.2.

**Files.**
- Edit: `TestGraphTraversalEngine.java`, `TestCypherToLogicalPlan.java`.

**Contract — test matrix:**
- Each of `>`/`>=`/`<`/`<=`/`=`/`<>` as a field-vs-field comparison, over two hop-linked variables.
- Cross-type comparison (e.g. one side numeric, one side a date-like string) behaves per `Val.compareTo`'s existing
  natural ordering — do not invent new coercion; assert whatever the existing ordering already does.
- A missing property on either side → comparison does not match (two-valued logic).
- Mixed `WHERE` (one literal term + one top-level-`AND`ed field comparison) — both filters apply.
- Field comparison inside `OR`/`NOT` → `CypherCompileException` at compile time (this is a parse-succeeds,
  compile-fails case — confirm the message is clear about *why*, not just *that* it failed).
- `DIFF`/`RETURN GRAPH`/var-length paths combined with a field comparison — at minimum, confirm the four engine
  construction sites (Task 5.2) all honour it, one test per site/mode.

**Done-when.** All green.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test`.

---

## Phase 6 — `OPTIONAL MATCH`

**Phase goal:** `MATCH (p:Person) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) RETURN p.surname, count(c) AS
crimeCount` includes people with zero crimes (count 0), rather than excluding them (Cypher's left-outer semantics vs.
today's inner-join-only traversal).

**Phase gate:** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test` green.

**Hard dependency: Phase 2 (`IS NULL`) must be merged first.** The only idiomatic way to test "did the optional
pattern match anything" is `WHERE v IS NULL`; shipping `OPTIONAL MATCH` before `IS NULL` exists would make it
untestable by any means this subset offers, and would risk landing the wrong bound/unbound semantics undetected. Do
not attempt this phase out of order.

> **Implementation note (2026-07-25): done, with a narrowed v1 scope.** The optional match is folded onto the
> mandatory plan as an `optional` `Expand` (an `optional` boolean added to `Expand` with a back-compatible
> convenience constructor, so no rewrite-rule/test churn). The engine (`GraphTraversalEngine.expandOptionalHop`)
> emits the input row unchanged when the hop matches nothing, and sets a reserved **bound-marker** row key
> (`OptionalMatchSupport.boundKey`) on matched rows; the compiler lowers `count(<optionalVar>)` to a count over
> that marker key, so an unmatched anchor yields count 0 (not 1). **v1 restrictions (all fail-loud):** exactly one
> optional hop, extending the mandatory pattern's terminal (frontier) variable; the OPTIONAL MATCH may carry no
> WHERE or temporal clause of its own; not combined with `DIFF`/`RETURN GRAPH`. **Two deferrals vs. the original
> task:** (1) `WHERE v IS NULL` filtering on the optional variable needs a host clause (`WITH`, Phase 7), so it is
> not wired yet - the bound marker that would support it is in place; (2) the "all persons" unanchored form needs a
> label-only node scan (`MATCH (p:Person)` with no property anchor), a pre-existing gap for *every* scalar query -
> so v1 OPTIONAL MATCH requires an anchored mandatory match, like all scalar matches today.

---

### Task 6.1 — Grammar + AST: `OPTIONAL MATCH`

**Goal.** Accept an `OPTIONAL` modifier on `matchClause`; flip the existing parser test that currently expects
rejection.

**Depends on.** Nothing (grammar-only; can be built before Phase 2 lands, though the *feature* cannot be usefully
tested until Phase 2 exists — sequence the merge, not necessarily the coding, after Phase 2).

**Files.**
- Edit: `Cypher.g4`.
- Edit: `AstMatch.java` (record at `AstMatch.java:34-44` — see §1.3's corrected citation).
- Edit: `AstCypherBuilder.java` (`buildMatch`, `AstCypherBuilder.java:119-125`).
- Edit (tests): `stroom-query/stroom-query-grammar/src/test/java/stroom/query/grammar/parse/TestCypherQueryParser.java`
  (line 308 currently lists `"OPTIONAL MATCH (a:Account) RETURN a"` inside the parameterized "MUST error:
  out-of-subset" test at lines 302-319 — **remove this line from that rejection list** and add a dedicated positive
  parse test elsewhere in the same file, per its existing convention of grouping "must parse" vs. "must error"
  cases).

**Contract.**
- `matchClause` (`Cypher.g4:91-93`, currently `MATCH pattern temporalClause? whereClause?`) becomes `OPTIONAL? MATCH
  pattern temporalClause? whereClause?`. New keyword token `OPTIONAL` (letter-fragment idiom, keyword block).
- `AstMatch` (`AstMatch.java:34-38`) += `boolean optional` as a new component:
  `(pattern, temporal, where, optional, position)`. **Breaking constructor change** — single call site,
  `AstCypherBuilder.buildMatch` (`AstCypherBuilder.java:119-125`), reads `ctx.OPTIONAL() != null`.
- Update the grammar file-header comment (currently lists `OPTIONAL MATCH` under "Everything NOT listed here...is
  simply absent from the grammar", `Cypher.g4:29` — remove it from that list once this lands).

**Done-when.** `OPTIONAL MATCH (a:Account) RETURN a` parses to `AstMatch(..., optional=true, ...)`; a plain `MATCH`
still parses with `optional=false`.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test`. The flipped `TestCypherQueryParser` case plus a
positive parse-shape assertion (`optional=true`).

---

### Task 6.2 — Compiler: `optional` on `Expand`/`VarLengthExpand`

**Goal.** Compile `OPTIONAL MATCH`'s pattern to the existing `Expand`/`NodeScan` shapes, carrying an `optional` flag
through, since `MATCH` lowers via `compilePattern` with no `Join` node produced at all — there is no join-type slot to
flip.

**Depends on.** Task 6.1.

**Files.**
- Edit: `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/logical/Expand.java`.
- Edit: `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/logical/VarLengthExpand.java`.
- Edit: `CypherToLogicalPlan.java`.
- Edit (tests): `TestCypherToLogicalPlan.java`.

**Contract.**
- **First, re-confirm the single-clause guard's exact current shape** (`CypherToLogicalPlan.compile`,
  `CypherToLogicalPlan.java:172-182`) — it currently rejects `query.readingClauses().size() != 1`. An `OPTIONAL
  MATCH` is a **second** `AstMatch` reading clause (`MATCH (p:Person) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime)
  RETURN ...` is *two* reading clauses, both `AstMatch`, the second flagged `optional=true`) — so this phase
  necessarily loosens the single-clause guard **specifically for a second, optional `AstMatch`**, without yet
  opening the door to Phase 7's general multi-stage `WITH`/multiple-`MATCH` chaining. **Scope this narrowly**: accept
  exactly `[AstMatch(optional=false), AstMatch(optional=true)]` (a mandatory anchor match, then one optional match,
  each with its own pattern/temporal/where) — reject any other multi-clause shape (two non-optional `MATCH`es, an
  optional match anchoring the whole query, a `WITH` anywhere, more than two clauses) with the existing "not in PoC
  subset" message, adjusted to name what *is* now accepted. This is a real, deliberate scope line — document it
  in `CypherToLogicalPlan`'s class Javadoc.
- The optional match's pattern must **share a variable with the first match's pattern** (e.g. `p` above) — this is
  what makes it a left-outer *extension* rather than an unrelated second match. Validate this explicitly (reject if
  the optional pattern's anchor variable does not appear in the mandatory match's already-bound variables) — Cypher
  itself requires this implicitly (an `OPTIONAL MATCH` with no shared variable is a cross-product, out of scope
  here).
- `Expand` and `VarLengthExpand` each gain a `boolean optional` field, read through `compilePattern`
  (`CypherToLogicalPlan.java:334-366`) — every `Expand`/`VarLengthExpand` constructed while compiling the *optional*
  match's hops gets `optional=true`; every one from the mandatory match keeps `optional=false` (today's behaviour,
  unchanged). Note `Expand`'s own Javadoc already explains why `JoinType.LEFT` cannot model a hop (a relational
  equi-join concept, not a graph traversal one) — this `optional` field is the graph-native equivalent, not a
  reuse of `JoinType`.
- The **anchor** `NodeScan` of the optional match's pattern is itself resolved via `resolveAnchors`, same as any
  anchor — but it is resolved **per already-matched row of the mandatory match**, not independently, since the
  optional pattern's anchor variable is shared with (bound by) the prior match. This is an **engine** concern (Task
  6.3), not a compiler one — the compiler's job here is only to carry the `optional` flag and validate the
  shared-variable precondition.

**Done-when.** The scoped two-clause shape above compiles to a plan the engine (Task 6.3) can execute; every other
multi-clause shape still rejects with a clear message; `Expand`/`VarLengthExpand` correctly carry `optional` in each
case.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test`. Positive case (the exact shape above), several
negative cases (two non-optional matches, `OPTIONAL MATCH` first, no shared variable, three+ clauses).

---

### Task 6.3 — Engine: zero-neighbour synthesis + bound/unbound marker

**Goal.** When an optional hop's frontier expansion finds zero accepted neighbours for a given incoming row,
synthesize exactly one output row equal to the row-so-far (unchanged), with the optional variables marked unbound so
`IS NULL` can detect it — rather than dropping the row (today's inner-join behaviour, per
`acceptChainNeighbour`'s bare `return` on no match, `GraphTraversalEngine.java:1239-1243`).

**Depends on.** Task 6.2, and (hard dependency, see phase header) Phase 2.

**Files.**
- Edit: `GraphTraversalEngine.java`.
- Edit (tests): `TestGraphTraversalEngine.java`.

**Contract.**
- **The core semantic gap (design decision (d), restated precisely here):** property projection over an unmatched
  optional variable already works for free — `evaluate()` returns `ValNull.INSTANCE` for any `"variable.property"`
  key absent from the row (`GraphTraversalEngine.java:1950-1960`). The gap is testing **whether the variable itself
  ever bound** — `rowFor` never stores a bare `"variable"` key at all (only `"variable.property"` keys,
  `GraphTraversalEngine.java:1537-1543`), so there is currently no row-map key an `IS NULL` predicate over a bare
  optional variable could resolve against, and `evaluate()`'s bare-variable case throws
  `UnsupportedOperationException` unconditionally (line 1961-1964) regardless of whether the match happened.
- **Chosen representation**: introduce a reserved per-variable marker key, e.g. `"variable" + BOUND_MARKER_SUFFIX`
  (pick a suffix that cannot collide with a real property name — a property name is always a bare `NAME` token from
  the grammar, so a suffix containing a character `NAME` cannot produce, such as a leading `$$` matching the existing
  `nextAnonymousVariable`'s `"$$anon" + n` convention, e.g. `"$$bound:" + variable`) mapped to `ValBoolean.TRUE`/
  `FALSE` (or simply: present in the row = bound, key present but with a `false`/sentinel = unbound — pick the
  simpler of the two and document the choice). Only optional-match variables get this marker; mandatory-match
  variables are never unbound in this subset (no change to their row-building).
- `fieldNameOf`/`compileOutputColumn`/`evaluate` must recognise a bare-variable `IS NULL`/`IS NOT NULL` reference to
  an *optional* variable specially (this is where Phase 2's `AstIsNullPredicate` needs a **compiler-side** companion
  change too, revisit `compileIsNullTerm` from Task 2.3: when `operand` is a bare `AstVariableExpr` naming a known
  optional-match variable — not a property access — lower it to a term/predicate over the marker key instead of
  rejecting it as a bare-variable reference the way a mandatory-match bare-variable `IS NULL` still should be
  rejected). **This is new coupling between Phase 2 and Phase 6** — Phase 2, shipped alone, correctly rejects
  `RETURN v` / `WHERE v IS NULL` for a bare pattern variable (no representation exists yet); Phase 6 is what first
  gives a bare variable a meaningful existence check, and only for optional-match variables. Document this coupling
  explicitly in both phases' class-level comments once Phase 6 lands, so a reader of Phase 2 alone is not confused
  by why `IS NULL` on a bare variable is rejected then, but not later.
- Engine change proper: in the frontier-expansion loop for hops belonging to the *optional* match's pattern
  (distinguishing them from the mandatory match's hops via the `optional` flag from Task 6.2), track per-incoming-row
  whether **any** neighbour was accepted (reuse `acceptChainNeighbour`'s existing accept/reject logic unchanged for
  the *decision* of whether a neighbour matches — the change is what happens when the *count* of accepted neighbours
  for a row is zero after the full expansion, not the per-neighbour test itself). If zero: synthesize one row equal
  to the incoming row-so-far, with every optional-pattern variable's marker key set to "unbound" (and no
  `"variable.property"` keys added for it at all — `evaluate()`'s existing absent-key-returns-null behaviour then
  handles any property projection over it correctly, unchanged). If one or more: proceed exactly as today (no
  synthesis; each accepted neighbour becomes its own row, marker key set to "bound").
- This bookkeeping is naturally per-hop-of-the-optional-pattern, so if the optional match itself is a multi-hop
  chain, "zero neighbours found" must be evaluated **once**, at the end of the optional pattern's own expansion (not
  per intermediate hop) — an intermediate hop finding zero neighbours should propagate "the optional pattern found
  nothing", not synthesize a partial row at each hop. Get this right by treating the *whole* optional pattern's
  expansion as one sub-traversal per mandatory-match row, similar in spirit to how `expandVarLengthPath`'s BFS
  already tracks "found anything at this anchor" as one aggregate outcome, not per-depth.

**Done-when.** `MATCH (p:Person) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) RETURN p.surname, count(c) AS crimeCount`
includes a person with zero crimes at count 0 (not excluded). `RETURN p.surname WHERE c IS NULL` (bare optional
variable) correctly selects only the people with no match. `RETURN c.type` for an unmatched row projects `ValNull`
(already worked before this task; regression-test it stays true). A **mandatory**-match bare variable in `IS NULL`
still throws its existing "not yet supported" error, unchanged.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`. Exhaustive matrix: zero optional matches for some
anchors and one-or-more for others in the same query; a multi-hop optional pattern where only the second hop fails
(propagates as "the whole optional match found nothing", not a partial row); `count(c)` = 0 vs. a real value;
`WHERE c IS NULL`/`IS NOT NULL` on the optional variable itself.

---

### Task 6.4 — Test suite for Phase 6

**Goal.** End-to-end coverage across grammar, compiler, and engine, since this phase touches all three non-trivially.

**Depends on.** Tasks 6.1-6.3.

**Files.**
- Edit: `TestCypherQueryParser.java`, `TestCypherToLogicalPlan.java`, `TestGraphTraversalEngine.java`,
  `TestGraphSearchProvider.java`.

**Contract — test matrix (in addition to each task's own listed cases):**
- The worked roadmap example itself: `MATCH (p:Person) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) RETURN p.surname,
  count(c) AS crimeCount` — assert the exact row set and counts against a small seeded fixture with a mix of
  zero-crime and multi-crime people.
- Provider round-trip for the same query (through `readTableRows`).
- Every rejection from Task 6.2 (multi-clause shapes outside the narrow accepted one).

**Done-when.** All green; the worked example matches the roadmap's own worked comment
(`docs/cypher-language-feature-roadmap.md` lines 155-157) exactly.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test`.

---

## Phase 7 — Multi-stage `WITH` / multiple `MATCH`

**Phase goal:** `MATCH (p:Person)-[:PARTY_TO]->(c:Crime) WITH p, count(c) AS crimes WHERE crimes > 5 MATCH
(p)-[:LIVES_AT]->(a:Address) RETURN p.surname, crimes, a.postcode` compiles and executes, with `WITH`'s scope
narrowing enforced. **Land single-`WITH` (one pipe) first**, per the roadmap's own sequencing note; general chains of
arbitrarily many `WITH`/`MATCH` stages are a follow-on, not required by this phase's Done-when.

**Phase gate:** `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test` green.

**No grammar change.** `query : fromClause? readingClause+ returnClause EOF` (`Cypher.g4:78-80`) and
`readingClause : matchClause | withClause` (`Cypher.g4:86-89`) **already accept** arbitrarily many `MATCH`/`WITH`
stages — confirmed by direct read, and by the existing rejection being compile-time only
(`TestCypherToLogicalPlan.withClause_throwsNotInPoCSubset`, `TestCypherToLogicalPlan.java:567-574`, parses a
`WITH`-containing query successfully and only fails at `compile()`). `AstWith`/`AstReadingClause` already exist too.
This is the deepest phase in this plan because it is compiler + engine only, against a compiler that has always
assumed exactly one implicit scope.

> **Implementation note (2026-07-25): done, scoped to `WITH … HAVING` (no second `MATCH`).** v1 delivers the
> highest-value slice - **aggregate-then-filter (`HAVING`)**, impossible before - and rejects the rest fail-loud.
> - **Grammar discovery:** the grammar did *not* actually accept `WITH … WHERE …`; `withClause` had no
>   `whereClause`, so a `WITH … WHERE` failed to *parse*. Added `whereClause?` to `withClause` (Cypher's `HAVING`)
>   and a `@Nullable AstWhere where` to `AstWith`.
> - **Approach (no engine restructure of `execute`):** the `WITH` is compiled as stage one's terminal
>   projection/aggregation by synthesising an `AstReturnClause` from its items and reusing
>   `buildProjectFields`/`buildAggregation`/`compileReturn` wholesale. The `WITH`'s `WHERE` (HAVING) + the final
>   `RETURN` become a `WithStage` on `CompiledCypherPlan`; the engine runs stage one unchanged, then
>   `applySecondStage` re-keys each stage-one `Val[]` by the `WITH` column names, applies HAVING, projects the final
>   `RETURN`, and de-dups if `DISTINCT`. `GraphSearchProvider` advertises `WithStage.finalFields()` as the output
>   columns. So the plan's "frontier-seeding `execute` restructure" (Task 7.3) was **not needed** for this slice - it
>   is only needed for a *second `MATCH`* after the `WITH`, which v1 defers.
> - **Scope narrowing (the plan's core correctness concern):** `compileWithPipe` builds the scope from the `WITH`'s
>   (mandatorily aliased) column names and validates every reference in the HAVING and final `RETURN` against it -
>   a property access, a dropped variable, or an aggregate in the final `RETURN` **fails loud** rather than resolving
>   to null.
> - **v1 rejections (fail-loud):** a second `MATCH` after `WITH`; un-aliased `WITH` items; `ORDER BY`/`SKIP`/`LIMIT`
>   on the `WITH` or the final `RETURN`; an aggregate in the final `RETURN`; `WITH` combined with `DIFF`/`RETURN
>   GRAPH`. **Deferred:** the second-`MATCH` "narrow-then-expand" pipe (needs the engine to seed a stage-two frontier
>   from stage-one rows carrying node UIDs) and general N-stage chains.

---

### Task 7.1 — Compiler: scope environment

**Goal.** Introduce explicit variable-scope tracking, threaded through compilation, before generalising past the
single-reading-clause guard — per design decision (e), this must exist *before* the loop that processes multiple
reading clauses, not be retrofitted after.

**Depends on.** Nothing new (can start immediately; independent of Phases 1-6, though it will need Phase 3's
`AggregateColumn`/`CypherAggregation` machinery once single-`WITH` reaches its aggregate-then-filter use case in Task
7.2).

**Files.**
- New: `stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/CompileScope.java` (or fold into
  `CypherToLogicalPlan` as a private nested type/field if the scope is small enough to not warrant its own file —
  judgement call for whoever picks this up, given it is used only within one class).
- Edit: `CypherToLogicalPlan.java`.

**Contract.**
- A scope is, at minimum, the **set of names visible for the next stage to reference** — after a `MATCH`, every
  pattern variable it bound; after a `WITH`, exactly the projected item names (aliased or default), and nothing
  else from before (Cypher's defining `WITH`-narrowing rule).
- Every name-resolution site that currently assumes one implicit pattern scope must consult this new scope instead:
  `fieldNameOf` (`CypherToLogicalPlan.java:481-488`), `compileNodeScan`/`compilePattern`'s variable binding (line
  334-391 — a hop's target variable becomes newly in-scope from this point forward), `defaultColumnName`/
  `toQualifiedField` (line 555, 737). Each of these currently just pattern-matches the AST shape with no notion of
  "is this name actually in scope right now" — that is precisely the gap this task closes.
- **Fail loud**: a reference to a name not in the current scope (e.g. referencing a variable from before a `WITH`
  that did not re-project it) must throw `CypherCompileException` with a message naming the unresolvable variable
  and which stage narrowed it out — this is the single most important correctness property of this whole phase (see
  design decision (e) — the two failure modes, over-narrow vs. under-narrow, and why under-narrow is the one this
  plan's contract absolutely forbids).

**Done-when.** The scope model exists and is unit-testable in isolation (a scope narrows correctly across a
`MATCH`→`WITH`→`MATCH` sequence; a reference to a name outside the current scope is detected) — this task does
**not** yet need `compile()`'s main loop to use it end-to-end; that is Task 7.2.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test`. Direct unit tests of the scope type itself
(narrowing, lookup, rejection of an out-of-scope name).

---

### Task 7.2 — Compiler: replace the single-clause guard with a fold over reading clauses (single-`WITH` only)

**Goal.** `CypherToLogicalPlan.compile`'s `query.readingClauses().size() != 1` guard
(`CypherToLogicalPlan.java:172-182`) becomes a loop, scoped to exactly: one `MATCH`, one `WITH`, one final `MATCH`
(or `MATCH`, `WITH`, `RETURN` with no second `MATCH` — the simplest single-pipe shape), reusing Task 7.1's scope.

**Depends on.** Task 7.1.

**Files.**
- Edit: `CypherToLogicalPlan.java`.
- Edit (tests): `TestCypherToLogicalPlan.java` (this **replaces**
  `withClause_throwsNotInPoCSubset`, `TestCypherToLogicalPlan.java:567-574`, exactly as Phase 6 replaced the
  `OPTIONAL MATCH` rejection test — flip it to assert successful compilation for the specific shape this task
  accepts, and add a fresh negative test for any *remaining* out-of-scope multi-clause combination).

**Contract.**
- Fold each `AstMatch` onto the running `plan` exactly as `compile` already does for the single-clause case (line
  184 `compilePattern`, line 212-215 `Filter` from `WHERE`) — this part is unchanged machinery, just invoked once per
  `MATCH` in the sequence instead of exactly once.
- Fold each `AstWith` into a `Project` (reuse `buildProjectFields`/`compileReturn`,
  `CypherToLogicalPlan.java:512-547`, the same lowering `RETURN` already uses — a `WITH` is structurally a `RETURN`
  that feeds the next stage instead of terminating the query) — including its own optional `WHERE` (Cypher's
  `WITH ... WHERE ...` is exactly `HAVING`: a post-aggregation filter over the `WITH`'s **projected** names, using
  the scope from Task 7.1, not the pre-`WITH` row map). If the `WITH` stage's items include an aggregate, build a
  `CypherAggregation` for it the same way `buildAggregation` already does for a terminal `RETURN`
  (`CypherToLogicalPlan.java:605-622`) — reuse that method directly rather than duplicating its logic; this is
  exactly the "aggregate-then-filter" `HAVING` use case the roadmap's own worked example shows.
- **Keep the "one temporal clause per query" invariant explicit** (the roadmap's own phrasing) — decide, and
  document in `CypherToLogicalPlan`'s class Javadoc, whether a temporal clause may appear on *any* `MATCH` in a
  multi-stage query or only the first/last; **recommend**: reject a temporal clause on any `MATCH` other than the
  first, with a clear message, as the simplest rule consistent with "one temporal clause, scoped to the whole query"
  — revisit if a real use case demands per-stage temporal scoping later (explicitly out of this task's Done-when).
- Everything this phase does **not** attempt (multiple `WITH`s in sequence, `WITH` combined with `OPTIONAL MATCH`
  from Phase 6, aggregation across more than one `WITH` boundary) must still throw the "not in PoC subset" rejection
  — narrow the guard precisely to the single-pipe shape, not open the floodgates.

**Done-when.** The roadmap's own worked example (this phase's header) compiles and produces a plan whose shape is
(conceptually) `Project(Filter?(Project(Filter?(Expand*(NodeScan)))))` — a `MATCH`, optionally filtered and
projected/aggregated by a `WITH` (with its own optional `HAVING`-style filter), feeding a second pattern's `Expand`
chain, feeding the final `RETURN`'s `Project`. Any other multi-clause shape still rejects with a clear, updated
message.

**Verify.** `./gradlew :stroom-query:stroom-query-planner:test`. The worked example (positive), `WITH ... WHERE ...`
alone as a `HAVING` case (positive), each of the "not yet attempted" shapes above (negative, clear messages), the
temporal-clause-placement rule (positive and negative).

---

### Task 7.3 — Engine: frontier-seeding entry point

**Goal.** `execute` is single-pass and always seeds its frontier from `resolveAnchors` at the `NodeScan` leaf
(`GraphTraversalEngine.java:342, 358`); a second `MATCH` stage after a `WITH` needs to seed its frontier from the
prior stage's already-materialised, projected/aggregated row list instead.

**Depends on.** Task 7.2.

**Files.**
- Edit: `GraphTraversalEngine.java`.

**Contract.**
- `unwrap` (`GraphTraversalEngine.java:1558-1612`) expects exactly one shape: `[Limit->][Sort->]Project[Filter?
  ->[Expand*|VarLengthExpand]->NodeScan]`. A two-stage plan (Task 7.2's fold) nests this shape *inside itself* —
  the "input" below the second stage's `Expand`/`NodeScan` chain is not a `NodeScan` at all, but the first stage's
  own `Project` (i.e. `unwrap` must be prepared to recurse, or a new sibling method must walk a nested plan and split
  it into "first-stage shape" + "second-stage shape" pieces, rather than assuming a single flat unwrap covers the
  whole tree). **Decide the exact split** (recommend: a new method, e.g. `unwrapMultiStage`, that recognises a
  `Project` sitting where `unwrap` currently requires a `NodeScan`, and recursively unwraps *that* as an independent
  `PlanShape`, yielding an ordered `List<PlanShape>` — one per stage — rather than trying to make `unwrap` itself
  handle nesting inline, to keep the existing single-stage `unwrap` untouched and reusable per-stage).
- New execution path (decouple "seed the frontier" from "expand it", per design decision (e)): execute the first
  stage's `PlanShape` exactly as `execute` does today (through `resolveAnchors` → hop expansion → whichever
  `finalizeRows`/`finalizeAggregatedRows` the first stage's own `distinct`/`aggregation` calls for, per-stage) to
  produce its materialised row list; then, for the second stage, seed the frontier **directly from those rows**
  (each output tuple becomes a `Frontier`, keyed by the `WITH` stage's projected names — reusing `rowFor`'s
  `"variable.property"` keying convention so the second stage's `Expand`/`Filter`/`Project` machinery needs no
  change to *read* the frontier, only to *how it originates*) instead of calling `resolveAnchors` a second time.
  Chain a per-stage `unwrap`/hop-expansion/`finalize`, rather than one flat `unwrap(plan)` call for the whole nested
  tree.
- This is a genuinely structural change to `execute`'s dispatch (not a new overload with a default, unlike every
  prior phase's engine changes) — budget real design time; consider whether the cleanest shape is a recursive
  `executeStage(PlanShape, seedFrontier)` helper that the top-level `execute` calls once per stage in sequence,
  feeding each stage's output as the next stage's `seedFrontier`, with the first stage's `seedFrontier` being
  `resolveAnchors`'s own result (today's behaviour, now just the base case of a general recursion instead of the
  only case).

**Done-when.** The Task 7.2 worked example executes correctly end-to-end: the first `MATCH`+`WITH` groups/filters
correctly, the second `MATCH` expands from each surviving `WITH` row, and the final `RETURN` projects correctly.

**Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

---

### Task 7.4 — Test suite for Phase 7

**Goal.** End-to-end coverage of the single-`WITH` shape, since this phase's engine change (Task 7.3) is the
riskiest structural change in this entire plan.

**Depends on.** Tasks 7.1-7.3.

**Files.**
- Edit: `TestCypherToLogicalPlan.java`, `TestGraphTraversalEngine.java`, `TestGraphSearchProvider.java`.

**Contract — test matrix:**
- The roadmap's own worked example, against a seeded fixture with people who do/don't have >5 crimes.
- `WITH` narrowing: a reference to a variable dropped by `WITH` (not re-projected) is rejected at compile time with a
  clear message naming the variable.
- `WITH ... WHERE ...` (`HAVING`) with and without an aggregate.
- The temporal-clause-placement rule from Task 7.2 (positive and negative).
- Provider round-trip for the worked example.
- Regression: every earlier phase (1-6) still works standalone (single-`MATCH` queries with string predicates,
  `IN`/`IS NULL`, `count(DISTINCT)`, `collect()`, field-vs-field `WHERE`, `OPTIONAL MATCH`) — this phase's `unwrap`
  change is the highest-risk regression surface in the whole plan, since every other phase's engine tests assume the
  single-stage `unwrap` shape unchanged.

**Done-when.** All green, including every prior phase's full test suite re-run clean.

**Verify.** `./gradlew :stroom-query:stroom-query-grammar:test :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test` (the full three-module gate, not just this phase's own files — this is the
one phase where a full re-run of every earlier phase's tests is part of this phase's own Done-when, not merely a
courtesy).

---

## Phase 8 — Scalar & string functions in `RETURN` (wire the existing expression engine)

**Phase goal:** a non-aggregated `RETURN` may apply scalar functions to matched values, e.g.
`MATCH (p:Person) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) RETURN toUpper(p.surname), coalesce(c.type, 'none')` -
compiling and evaluating the functions over each traversal row, instead of today's "bare property/variable
reference only" projection.

**Phase gate:** `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test` green.

**The key insight — this wires an engine Stroom already has; it does not reimplement Cypher's stdlib.** Three facts
make this a "connect two components" job, not a "write 40 functions" job:
1. `stroom.query.language.functions` already contains a **232-class expression-function library** (`UpperCase`,
   `LowerCase`, `Substring`, `Replace`, `StringLength`, `Concat`, `Case`, `Decode`, `Round`, `Ceiling`, `Floor`,
   `ToString`, `IndexOf`, …) plus an `ExpressionParser` (`ExpressionParser.parse(ExpressionContext, FieldIndex,
   String) -> Expression`).
2. The Cypher compiler **already renders `RETURN` items into that engine's exact syntax** - property refs as
   `${a.name}` and function calls as `name(...)` (see `CypherToLogicalPlan.renderExpression` /
   `renderValueAsExpression`; the rendered text lands in `ProjectField.rawExpression()`).
3. `GraphRowValueFunctionFactory` already bridges a traversal row (`Map<String,Val>`) to the `Val`/value-function
   world for the `WHERE` path.
The only thing missing is the wiring: `GraphTraversalEngine.evaluate(ProjectField, row)` (`~line 2114`, called per
column from `finalizeRows` `~line 1739`) currently handles a bare `${ref}` and **throws** for anything else, with a
message that names the fix exactly: *"literals, aggregates and function calls need the full ExpressionParser, not
wired to a graph traversal row."*

**Scope for this phase (fail-loud on everything outside it):**
- Functions in a **non-aggregated `RETURN`** only. Functions combined with aggregation (over a group key, or inside
  an aggregate argument) is a follow-on - reject at compile with a clear message.
- Functions in **`WHERE`** are a follow-on (the `WHERE` path is a different evaluator - `ExpressionPredicateFactory`,
  not the projection `evaluate()`); reject a function in `WHERE` for now.
- A **curated allowlist** of pure, deterministic functions only - the 232 include dashboard/annotation/link/
  current-user/I-O functions that are irrelevant or unsafe for a graph query.

> **Implementation note (2026-07-25): done, with one discovery and one scoping call.**
> - **Discovery the tasks below missed:** the grammar's `functionCall` took **`value`** arguments, not `expression`
>   - so `toUpper(a.name)` did not even *parse* (functions accepted only literals, for temporal constructors like
>   `datetime('...')`). Phase 8 therefore also changed `functionCall` args `value → expression`,
>   `AstFunctionValue.arguments` `List<AstValue> → List<AstExpression>`, and updated `resolveInstant`/
>   `resolveDuration` to unwrap the now-`AstLiteralExpr`-wrapped temporal string.
> - **Scoping call:** rather than alias Cypher names to Stroom functions with *possibly different semantics*
>   (Cypher `substring(s,start,length)` vs Stroom `substring(s,startIndex,endIndex)`; no Stroom `coalesce`),
>   functions are exposed under their **Stroom names** (exact Stroom semantics, no surprise) via a curated allowlist
>   (`CypherFunctions`), plus two safe 1:1 Cypher aliases (`toUpper`→`upperCase`, `toLower`→`lowerCase`). Cypher's
>   `coalesce` is expressed as `if(isNull(x), y, x)` (tested against an `OPTIONAL MATCH` null). A fuller
>   Cypher-name-and-semantics compatibility layer is a documented follow-on.
> - **Free wins:** functions in `WHERE` and functions-with-aggregation were **already rejected** by existing code
>   (`fieldNameOf` returns null for a function; `compileOutputColumn` requires a property for a non-aggregate group
>   item), so no new rejection code was needed for those.
> - **Engine:** `evaluate()` was replaced by a `RowProjector`/`CompiledProjectField` that compiles each column once
>   (fast path for a bare `${var.prop}`; `ExpressionParser` → `Generator` for anything else) and evaluates per row.
>   A regression was caught and fixed: the `DIFF` path's dot-less `changeKind` row key must be looked up at eval
>   time before the bare-variable rejection fires.

---

### Task 8.1 — Design spike: evaluate a `ProjectField` expression over a graph row

**Goal.** Establish the exact mechanism by which a `ProjectField.rawExpression()` (e.g. `upperCase(${a.name})`)
compiles once to an `Expression` and evaluates to a `Val` for a given traversal row - reusing the same
`ExpressionParser`/`Generator` machinery the relational (StroomQL) result path already uses, not a bespoke evaluator.

**Depends on.** Nothing (read-only investigation + a throwaway proof).

**Files (read).**
- `stroom-query/stroom-query-language/src/main/java/stroom/query/language/functions/ExpressionParser.java`
  (`parse(ExpressionContext, FieldIndex, String)`), `Expression`, `Generator`, `FieldIndex`.
- The relational consumer that compiles `ProjectField`/column expressions and feeds row values per row (find via
  `CompiledField`/`CompiledColumn`/coprocessor usage of `ExpressionParser`) - copy its pattern.
- `stroom-graphdb/.../GraphTraversalEngine.java` (`evaluate`, `finalizeRows`, `rowAccessors`),
  `GraphRowValueFunctionFactory.java`.

**Contract.** Produce a written note (or a spike test) answering: (a) how to build the `FieldIndex` for one
`ProjectField` from the `${...}` references its expression names; (b) how to supply that field's `Val` values per
row (from the `Map<String,Val>` traversal row) into the compiled `Expression`; (c) where the compiled `Expression`
is cached (compile once per `ProjectField`, evaluate per row - not per row re-parse); (d) the `ExpressionContext`/
`DateTimeSettings` the parser needs. **Done-when** the mechanism is written down and a throwaway test evaluates
`upperCase(${a.name})` to a `ValString` over a hand-built row.

**Verify.** Spike only; no production code merged from this task.

---

### Task 8.2 — Compiler: Cypher-name → Stroom-name alias layer + allowlist

**Goal.** Map Cypher function names to their Stroom equivalents and reject anything off the curated allowlist, at
compile time with a positioned error.

**Depends on.** Task 8.1.

**Files.**
- Edit: `CypherToLogicalPlan.java` (`renderValueAsExpression`/`renderExpression`'s `AstFunctionValue` branch - the
  site that renders `name(...)`).
- New: a small alias/allowlist table (e.g. `CypherFunctions` in `stroom.query.planner.cypher`).
- Edit (tests): `TestCypherToLogicalPlan.java`.

**Contract.**
- A curated map: `toUpper`→`upperCase`, `toLower`→`lowerCase`, `size`→`stringLength` (on a string), `substring`,
  `replace`, `trim`, `split`, `toString`, `coalesce`→(Stroom's null-coalescing equivalent), `CASE`→`Decode`/`Case`,
  `abs`/`round`/`floor`/`ceil` - confirm each target name against `stroom.query.language.functions` before adding it
  (names are contracts; e.g. `UpperCase`/`Substring`/`Replace`/`StringLength`/`Round`/`Ceiling`/`Floor` exist).
- Render the mapped Stroom name into the `${...}`-style expression text.
- **Reject** an unmapped/unknown function name and any name not on the allowlist with
  `"not supported in this version: function '<name>' is not available in Cypher RETURN (supported: <list>)"` and the
  call's AST position. Keep the existing temporal-literal constructors (`datetime`/`duration`) working in the
  temporal-clause context unchanged (they are not projection functions).

**Done-when.** `RETURN toUpper(a.name)` compiles to a `ProjectField` whose `rawExpression` names the Stroom function;
an unknown function fails loud. **Verify.** `./gradlew :stroom-query:stroom-query-planner:test`.

---

### Task 8.3 — Engine: evaluate a compiled expression per row

**Goal.** `GraphTraversalEngine.evaluate` compiles a non-bare-ref `ProjectField.rawExpression` via `ExpressionParser`
(once) and evaluates it per row, replacing today's `UnsupportedOperationException`. The existing bare-`${ref}` fast
path stays (no per-row parse for the common case).

**Depends on.** Tasks 8.1, 8.2.

**Files.**
- Edit: `stroom-graphdb/.../GraphTraversalEngine.java` (`evaluate`; cache the compiled `Expression` per
  `ProjectField`, e.g. built once when the `PlanShape` is unwrapped).
- Edit (tests): `TestGraphTraversalEngine.java`.

**Contract.**
- Keep the current behaviour for a bare `${var.prop}` / `${var}` reference exactly (fast path; the bare-variable
  rejection and absent-property→`ValNull` semantics are unchanged).
- For any other expression: evaluate the cached `Expression` over the row's `Val`s (per Task 8.1's mechanism). A
  reference to an absent property resolves to `ValNull` (so functions compose with `OPTIONAL MATCH` nulls -
  `coalesce(c.type,'none')` yields `'none'` for an unmatched `c`).
- Errors in evaluation surface as `ValErr` (the engine's existing error-value convention), not a thrown exception
  that aborts the whole query.

**Done-when.** The phase-goal query evaluates its functions correctly, including `coalesce` over an unmatched
`OPTIONAL MATCH` variable. **Verify.** `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

---

### Task 8.4 — Test suite for Phase 8

**Goal.** Cover the alias layer, the allowlist rejection, and end-to-end evaluation of the priority functions.

**Depends on.** Tasks 8.2, 8.3.

**Files.** Edit: `TestCypherToLogicalPlan.java`, `TestGraphTraversalEngine.java`, `TestGraphSearchProvider.java`.

**Contract — matrix:** `toUpper`/`toLower`/`substring`/`replace`/`toString` over a property; `coalesce`/`CASE` over a
present value and over an absent/`OPTIONAL MATCH`-null value; an unknown function → `CypherCompileException`; a
function in `WHERE` → rejected (follow-on); a function combined with aggregation → rejected (follow-on); provider
round-trip for one function query.

**Done-when.** All green. **Verify.** `./gradlew :stroom-query:stroom-query-planner:test
:stroom-graphdb:stroom-graphdb-impl:test`.

---

## 3. Documentation & user-facing updates

- Update [`cypher-language-feature-roadmap.md`](cypher-language-feature-roadmap.md)'s "What the subset covers today"
  section and its priority table as each phase merges — move each shipped feature from the table into the
  "baseline" prose, the same way this plan's own §1 records the analytic-functions plan's Phase 1 having already
  merged.
- Add a short subsection per phase to the Cypher user guide / help text (wherever the current subset is documented),
  each listing: the new syntax, a worked example, and its explicit rejections (regex length cap; `IN`'s
  space-delimiter value-collision limitation from Phase 2; `collect(*)`/`collect(v)` rejections; field-vs-field
  inside `OR`/`NOT` rejection; the narrow two-clause `OPTIONAL MATCH` shape; the single-`WITH`-only shape and its
  temporal-clause-placement rule).
- **Prominently document Phase 2's two-valued-logic decision** (§ Task 2.3) in the user guide, flagged as a
  deliberate divergence from standard Cypher's three-valued `null` semantics — this is the one place a
  Cypher-literate user's intuition could be wrong, so it deserves more visibility than a routine feature note.
- Add one `unreleased_changes/*.md` entry per phase, in the repo's single-line format (confirmed convention, e.g.
  `unreleased_changes/20260723_113847_916__0.md`): `* Feature : <one-line description>`, e.g. `* Feature : Add Cypher
  string predicates (STARTS WITH / CONTAINS / ENDS WITH / =~) to the Graph DB query language.` — one entry per
  phase at merge time, not one giant entry for the whole plan.
- If the repo generates Swagger/OpenAPI for the query endpoint, regenerate it (no API-shape change expected — this is
  all behaviour behind the existing Cypher text field — but confirm per-phase, as the analytic-functions plan's own
  §3 already notes).

---

## 4. Suggested commit / PR sequence

One PR per phase, in the order above (this **is** the dependency order, not merely a suggestion):

0. **Phase 0** (StroomQL `IN` delimiter fix) — shared-code bug fix; no dependencies; **merge before Phase 2** (Phase 2's
   Cypher `IN` lowering assumes the comma-delimited `StringIn` behaviour Phase 0 establishes). Smallest PR of all.
1. **Phase 1** (string predicates) — no dependencies; smallest *Cypher* PR; can land before or alongside Phase 0.
2. **Phase 2** (`IN`/`IS NULL`) — **hard dependency on Phase 0**; no dependency on Phase 1; merge before Phase 6.
3. **Phase 3** (`count(DISTINCT x)`) — no grammar/engine dependency on Phases 1-2; can also be developed in parallel.
4. **Phase 4** (`collect()`) — its Task 4.1 (`ValList`) has no Cypher dependency and can start anytime; its Tasks
   4.2-4.5 depend on Task 3.1's grammar groundwork only incidentally (the `DISTINCT?` grammar slot Phase 3 adds) —
   sequence Task 4.1 in parallel with Phases 1-3, but do not merge Phase 4's Cypher-facing tasks before Phase 3, since
   `collect(DISTINCT x)` needs Phase 3's `DISTINCT?` grammar slot.
5. **Phase 5** (field-vs-field `WHERE`) — independent of Phases 1-4; can merge anytime after Phase 1 lands (no hard
   dependency, but sequencing after the cheaper wins matches this plan's cost-ordering rationale, §2(f)).
6. **Phase 6** (`OPTIONAL MATCH`) — **hard dependency on Phase 2**; do not merge before it.
7. **Phase 7** (multi-stage `WITH`) — depends on Phase 3's `CypherAggregation`/`buildAggregation` reuse (Task 7.2);
   softly benefits from every other phase being stable first, since its regression surface (Task 7.4) covers all of
   them.
8. **Phase 8** (scalar functions in `RETURN`) — independent of Phase 7; can merge any time after Phase 1. `coalesce`/
   `CASE` are most useful once Phase 6 (`OPTIONAL MATCH`, merged) is present, since that is what produces the nulls
   they handle. Start with a design spike (Task 8.1) before touching production code.

Each phase's own commit sequence (grammar → AST → compiler → engine → tests, per its tasks) mirrors the
analytic-functions plan's own §4 convention: land the null-safe/no-op default first within a phase wherever a task
introduces a new parameter with a backward-compatible default (e.g. Phase 5's new `execute` overload, Phase 6's
`optional` flag defaulting false), so the tree stays green between tasks within a phase, not just between phases.

---

## 5. Risks & mitigations

| Risk | Likelihood | Phase | Mitigation |
|---|---|---|---|
| `ValList` ripple through the `Val` sealed interface / serialisation layer is large | Med-High | 4 | Task 4.1 is isolated and gated first; fallback is the analytic-functions plan's own documented alternative (delimiter-joined `ValString`) if the ripple proves disproportionate. |
| `=~` regex catastrophic backtracking / oversized input | Low-Med | 1 | Compile-time length cap (Task 1.4); explicitly scoped as *not* attempting static ReDoS detection. |
| Three-valued-logic correctness (a missing property's behaviour under `AND`/`OR`/`NOT`) | Med | 2, 5, 6 | Explicit, documented two-valued-logic decision in Task 2.3, reused consistently by Phases 5-6; flagged prominently in user docs as a deliberate Cypher-standard divergence. |
| Temptation to extend the shared `ExpressionTerm` for field-vs-field comparisons, rippling into the relational/StroomQL executor | Med | 5 | Design decision (b) explicitly rejects this; `FieldComparison` is a Cypher-only parallel carrier, never touching `ExpressionTerm`. |
| `IN`'s value delimiter was inconsistent across the codebase (`StringIn` split on space; every producer joins on comma) — text `IN` silently never matched | Was High, now resolved | 0 | **Phase 0** unifies all `IN` paths on comma-`DELIMITER` + trim at the source (`StringIn.create`), so Phase 2's Cypher `IN` and StroomQL's existing `IN` both work. Residual: a value containing a literal comma cannot be an `IN` element — documented as a known limitation, consistent with the numeric/date paths' identical constraint. |
| `WITH` scope-plumbing scope-creep (attempting general multi-stage chains instead of single-`WITH`) | Med | 7 | Task 7.2 explicitly scopes to exactly one `MATCH`→`WITH`→`MATCH` shape and rejects everything else; general chains are a follow-on, not this phase's Done-when. |
| Non-equality anchor predicates never accelerate via the property index (a pre-existing limitation, not new) | Low | 1 | Documented in §1.2 as a caveat, not a defect; a `WHERE`-only anchor is already unseekable today regardless of this plan. |
| `OPTIONAL MATCH`'s bound/unbound marker collides with a real property name or leaks into `RETURN`/aggregation in an untested way | Med | 6 | Marker key chosen to be unrepresentable by the grammar's `NAME` token (Task 6.3); exhaustive test matrix in Task 6.4 covering `count`, property projection, and `IS NULL` together. |
| Phase 7's `unwrap` restructuring regresses every earlier phase's single-stage engine tests | Med-High | 7 | Task 7.3 keeps the existing single-stage `unwrap` untouched and reusable per-stage (new sibling method, not an in-place rewrite); Task 7.4's Done-when explicitly requires a full re-run of every prior phase's suite, not just Phase 7's own. |
| `Type.LIST`'s enum id (14) assumes no persisted-format backward-compatibility constraint on `Type`'s existing ids | Low-Med | 4 | Flagged explicitly in Task 4.1 as needing confirmation before assuming safety; investigate before assigning the id. |

---

## 6. Out of scope (explicitly not this plan)

Per the roadmap's Tiers 4-5 — see [`cypher-language-feature-roadmap.md`](cypher-language-feature-roadmap.md) for the
utility/difficulty/risk rationale behind each:

- **Writes** (`CREATE`/`MERGE`/`SET`/`DELETE`), **`CALL`/procedures** — conflicts with the mutation-XML ingest
  pipeline's ownership of writes; deliberately out of scope, not merely deferred.
- **`UNWIND`** (list → rows) — Tier 4, opportunistic; pairs naturally with Phase 4's `collect()`/Phase 2's `IN`, but
  not part of this plan. (Scalar/string functions, formerly bundled with `UNWIND` here, are now **Phase 8** — the
  function *library* already exists in Stroom and only needs wiring.)
- **Scalar functions in `WHERE`, and functions combined with aggregation** — Phase 8 wires functions into the
  non-aggregated `RETURN` projection only; a function in a `WHERE` (a different evaluator) or over a group key / inside
  an aggregate argument is a follow-on, rejected with a clear message by Phase 8.
- **Non-allowlisted functions** — Phase 8 exposes a curated allowlist of pure, deterministic functions; the rest of
  Stroom's 232-function library (dashboard/annotation/link/current-user/I-O functions) stays unavailable in Cypher.
- **`UNION`/`UNION ALL`** — Tier 4; column-compatibility + concatenation, well-contained once Phase 7's multi-clause
  plumbing exists, but not attempted here.
- **Multi-type relationships** (`-[:A|B]->`) — Tier 4; the adjacency stores are keyed per edge type (design doc
  §5.1), so this needs physical-scan fan-out and merge, a real traversal-engine feature, deliberately deferred.
- **`shortestPath`/`allShortestPaths`** — Tier 5; a new traversal algorithm (BFS/bidirectional/Dijkstra) with its own
  cost/termination guarantees, not a reducer over existing rows; needs a dedicated feasibility spike.
- **Unbounded variable-length** (`-[:R*]->`) — Tier 5; the grammar's mandatory finite upper bound
  (`varLength : STAR min=NUMBER? DOTDOT max=NUMBER`, `Cypher.g4:143-145`) is a deliberate safety invariant, not an
  oversight — recommend never relaxing it without a hard cost-budget mechanism first.
- **`HAVING` as a distinct keyword** — this plan's Phase 7 delivers `HAVING`'s functionality via `WITH ... WHERE`
  (Cypher's own idiom), not a bespoke `HAVING` clause; no separate `HAVING` keyword is added.
- **Aggregates inside a `MATCH`'s `WHERE`** (as opposed to a `WITH`'s post-aggregation filter) — `WHERE` on a `MATCH`
  stays pre-aggregation, field-vs-literal/field-vs-field only, exactly as today; only a `WITH`'s own `WHERE` (Phase
  7) may reference an aggregate, matching Cypher's own scoping rules.
- **General (>1-pipe) multi-stage `WITH` chains** — Phase 7 explicitly lands single-`WITH` only; chains of two or more
  `WITH` stages are a follow-on plan, not this one's Done-when.
- **Map projections, list comprehensions, path variables/`RETURN path`, subqueries, `FOREACH`** — never in the locked
  v1 subset (`Cypher.g4`'s file header); no phase in this plan touches them.

---

## 7. Verification summary (module/test-class quick reference)

| Phase | Grammar test class | Compiler test class | Engine test class | Provider test class |
|---|---|---|---|---|
| 1 | `TestCypherQueryParser` | `TestCypherToLogicalPlan` | `TestGraphTraversalEngine` | — |
| 2 | `TestCypherQueryParser` | `TestCypherToLogicalPlan` | `TestGraphTraversalEngine` | — |
| 3 | `TestCypherQueryParser` | `TestCypherToLogicalPlan` | `TestGraphTraversalEngine` | `TestGraphSearchProvider` |
| 4 | `TestCypherQueryParser` (+ new `stroom-query-language` unit tests for `ValList`) | `TestCypherToLogicalPlan` | `TestGraphTraversalEngine` | `TestGraphSearchProvider` |
| 5 | — (no grammar change) | `TestCypherToLogicalPlan` | `TestGraphTraversalEngine` | — |
| 6 | `TestCypherQueryParser` | `TestCypherToLogicalPlan` | `TestGraphTraversalEngine` | `TestGraphSearchProvider` |
| 7 | — (no grammar change) | `TestCypherToLogicalPlan` | `TestGraphTraversalEngine` | `TestGraphSearchProvider` |

Module gradle paths (confirmed present in `settings.gradle`): `:stroom-query:stroom-query-grammar`,
`:stroom-query:stroom-query-planner`, `:stroom-query:stroom-query-language`, `:stroom-graphdb:stroom-graphdb-impl`.
The full-plan gate, run once after Phase 7: `./gradlew :stroom-query:stroom-query-grammar:test
:stroom-query:stroom-query-planner:test :stroom-query:stroom-query-language:test
:stroom-graphdb:stroom-graphdb-impl:test`.
