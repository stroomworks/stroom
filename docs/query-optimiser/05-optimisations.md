# 5. Optimisations

**Status:** Experimental, off by default. See [README.md](README.md#production-readiness).
**Audience:** analysts.
**Scope:** every transformation the optimiser applies to a query, what triggers each one, and when it deliberately
does nothing. Canonical for the rewrite-rule list and the eligibility test.
**Companion documents:** [06-joins.md](06-joins.md) for the join-specific reductions,
[04-behaviour-changes.md](04-behaviour-changes.md) for the one that changes results.

---

## The rewrite pipeline

Four rules run over the bound logical plan, in a fixed order chosen so that each has the previous one's output to
work with:

```
  bound plan
      │
      ▼  ConstantFoldingRule          structural cleanup — simplify the predicate tree
      ▼  RedundantTermPruningRule     drop duplicate conjuncts
      ▼  AutoWhereFilterSplitRule     partition where by index-eligibility
      ▼  PushFiltersBelowJoinsRule    relocate a single-side predicate below a join
  rewritten plan
```

The pipeline is not configurable. Two further rules were designed and are not built — dictionary expansion, which
needs a dictionary-lookup port, and time-range extraction as a rule, which needs a slot on the scan node.
Time-range extraction is instead done directly by the compiler, described below.

### Constant folding

StroomQL has no boolean literals, so there are no `true`/`false` constants to fold. What this rule folds is
**structural** redundancy in the predicate tree the binder built:

- `NOT(NOT(x))` → `x`
- `AND(x)` → `x`, `OR(x)` → `x` — a single-child operator wrapping

Both are provably equivalent rewrites regardless of what any term tests, which is why they need no field metadata
at all. The legacy compiler does nothing like this.

### Redundant term pruning

Drops a term subsumed by an identical one in the same `AND` conjunction — `x = 1 AND x = 1` becomes `x = 1`.

Scoped deliberately. The rule flattens through nested `AND` nodes only (matching the binder's own left-nested
pairwise fold) and de-duplicates the resulting flat list of direct term children. It does **not** look inside an
`OR` or `NOT` sub-tree for a duplicate of a sibling outside it: `(x = 1 OR y = 2) AND x = 1` must not become
`(x = 1 OR y = 2)`, which would change which rows match whenever `y = 2` holds and `x = 1` does not. Each sub-tree
is still pruned independently for duplicates *within* itself.

### The `where`/`filter` split

This is the rule that fixes the silent-zero-rows footgun described in
[04-behaviour-changes.md](04-behaviour-changes.md#3-a-bare-where-mixing-eligible-and-ineligible-terms).

**What it does.** For a query that relied solely on `where` — no explicit `filter` clause — it splits the
top-level `AND` conjuncts by index-eligibility. Eligible terms stay in the scan-time expression; ineligible ones
become an extraction-time value filter.

**The eligibility test.** A term is index-eligible when *all* of the following hold:

1. Its field resolves to a known field on the datasource being scanned.
2. That field is marked queryable.
3. That field declares a condition set.
4. That condition set supports the term's condition.

Anything the rule cannot resolve with confidence — an unknown field, a missing condition set, a nested operator
rather than a bare term — is treated as **not eligible**. The asymmetry is deliberate: a wrong "leave it in
`where`" changes nothing, while a wrong "push it" can send an unsupported condition to a datasource that
silently matches nothing.

**When it is a no-op**, guaranteed:

- The query already has an explicit `filter` clause. The rule only ever acts on a bare `where`.
- Every term is eligible.
- The `where` predicate's top-level operator is not `AND`.

**What the compiled request looks like afterwards.** The eligible remainder stays in `query.expression`; the
ineligible remainder is set as `valueFilter` on every table settings block that does not already have one. If
*every* term turns out ineligible, the expression becomes empty and the whole predicate moves to the value filter.

### Pushing filters below a join

For a filter directly above a join, whose predicate references only one side's fields, this rule relocates the
predicate below the join onto that side.

Two things to know:

- The `where` and `filter` slots are considered independently — one may push while the other does not, or they may
  push to different sides. If both push, the enclosing filter node disappears entirely.
- A predicate is pushed only when **every** field reference in it is `alias.field`-qualified and every alias
  belongs to the same side. An unqualified reference, or references spanning both sides, leaves the predicate
  exactly where it was.
- For a `LEFT` join, a predicate referencing only the right (null-supplying) side is **never** pushed. Removing
  candidate right rows before the join changes which left rows survive as unmatched — it would silently turn a
  `LEFT` join into an `INNER` one.

**This rule only affects `EXPLAIN`.** Real join execution uses a separate, per-conjunct splitter with the same
join-type awareness but stricter rules — see [06-joins.md](06-joins.md#push-down). The two are deliberately
independent: this rule classifies a whole predicate slot at once and was written for cost estimation, where
getting it slightly wrong costs an inaccurate estimate rather than wrong rows.

---

## Time-range pruning

**What it does.** If a query bounds the datasource's time field in its `where` clause, but no explicit time range
was set on the request, the optimiser derives a time range from the predicate and sets it. Shard selection reads
the time range — never the expression — so without this a time-bounded query filters correctly but scans every
shard doing it.

```
from "Events"
where EventTime > 2024-01-15T00:00:00.000Z
select EventTime, User, Status
```

- **Legacy:** the predicate is in the query expression, but with no time range set, *every* shard is searched and
  the bound is applied per document.
- **Optimiser:** derives `from = 2024-01-15T00:00:00.000Z`, and shards whose partition window ends before that are
  skipped entirely.

**Same rows either way — because the hint only ever widens.** The expression is left untouched, so the bound is
still evaluated on every row that is read; the time range is a pruning hint. But a hint is only harmless if it is
**at least as wide** as the user's bound: the range's upper end is applied at search time as a strict `<` on the
partition time field, so an inclusive user bound (`<=`, or the upper end of `between`) is emitted as
`bound + 1 ms`. Time values are whole milliseconds, so that is the exact exclusive equivalent, not an
approximation. Widening is the only acceptable direction — a too-wide range just reads extra rows the retained
`where` filters out; a too-narrow range silently drops rows the `where` matches (the row at exactly a `<=` bound,
before Task 8.3). The lower end is asymmetric on purpose: it is applied as `>=`, so both `>` and `>=` map to the
same millisecond — exact for `>=`, one millisecond *wider* than `>`, and safe either way because the retained
`where` still excludes the boundary row. This is why a derived time range shows up as a `SHADOW` divergence
without being a difference in results.

**What is recognised.** A term on the datasource's declared **time field**, with a condition of `>`, `>=`, `<`,
`<=` or `between`. Values go through the same shared date-expression parser as everywhere else, so relative
expressions like `now() - 1d` work.

**Multiple bounds intersect.** Several lower bounds take the latest; several upper bounds take the earliest.

**When nothing is derived:**

- An explicit time range is already set — that always wins, and is never overridden.
- The datasource declares no time field.
- The plan shape is not a filter directly above a scan. A deeper or more complex shape simply yields no bound,
  never a wrong one.
- The value will not parse. An unparseable value is treated as an ordinary predicate rather than failing the
  query.
- The term (or the whole predicate) is disabled. A disabled item is ignored at evaluation, so deriving a bound
  from it could only *narrow* the scan below what the evaluated predicate matches — the one direction a pruning
  hint must never take.

**In a join**, a time bound that was pushed onto one side is promoted into *that side's* time range, so the side's
own scan prunes shards exactly as a single-source query would.

---

## What is not optimised

Being explicit, because the word "optimiser" invites assumptions:

| Not done | Note |
|---|---|
| **Join order selection** | There is only ever one join, of two sources. Nothing to reorder |
| **Cost-driven execution** | The cost model chooses an algorithm for `EXPLAIN` only. Execution decides structurally ([06-joins.md](06-joins.md#execution-two-strategies)) |
| **Predicate rewriting for the datasource** | Conditions are passed through as written; nothing is transformed into a cheaper equivalent |
| **Dictionary expansion** | Designed, not built — needs a dictionary-lookup port |
| **Semi-join reduction** | A big-⋈-big join correlated only by its key still scans both sides in full ([12-future-work.md](12-future-work.md#semi-join-reduction)) |
| **Cluster-parallel execution of a join** | Joins run on one node ([06-joins.md](06-joins.md#what-a-join-does-not-do-yet)) |
| **Anything at all if binding fails** | For a single-source query, plan enhancement is fail-open: a bind or rewrite failure returns the unenhanced, legacy-identical request and logs at `DEBUG` |

That last row is worth internalising. The binder validates more strictly than the compiler that produces the
served request — it rejects unknown and ambiguous field references, which the mapper passes through as text. A
single-source query that fails to bind still runs; it just silently gets none of the enhancements on this page.
A **join** query that fails to bind is rejected outright, because there is no prior behaviour to fall back to.
