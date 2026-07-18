# Known differences from legacy StroomQL parsing

`OptimisingQueryCompiler` targets byte-exact parity with `SearchRequestFactory` (see
[query-optimiser-implementation-plan.md](query-optimiser-implementation-plan.md), Phase 1) for every query legacy
compiles *correctly*. Where legacy's behaviour is a confirmed **bug** — an accidental defect in its regex-based
`Tokeniser` or an unfinished code path, rather than a deliberate rule — the new compiler does **not** reproduce it.
Silently copying a bug forward would freeze it into the replacement permanently; the policy instead is: fix it,
prove the fix with a dedicated test, and record it here.

Every entry below has:
- **What legacy does**, with the root cause traced to a specific line.
- **What the new compiler does instead.**
- **Why this is a bug**, not a design choice (i.e. why silently doing "the same wrong thing" isn't the right bar).
- **The test that demonstrates it** — `TestLegacyBugFixes`, one method per entry, each asserting *both* directions
  (legacy still rejects; the new compiler does not) so a regression in either direction fails loudly.

This list is expected to grow. Anything added here must also be excluded from the strict byte-equality checks in
`TestQueryCompilerParity` (hand corpus) and/or `TestQueryCompilerGenerativeParity` (generated corpus) — see the
"Cross-references" note at the end.

---

## 1. `(not <term>)` — bracket immediately adjacent to a logical keyword, no space

**What legacy does**: rejects it.

```
from "index_view" where (not StreamId = 1) select StreamId
  → TokenException{tokenType=STRING, start=29, end=36, text=StreamId, message=Expected condition token}
```

Adding a single space after the bracket makes it succeed:

```
from "index_view" where ( not StreamId = 1) select StreamId
  → OK: NOT {StreamId = 1}
```

**Root cause**: `Tokeniser.tagKeyword` (`stroom-query/stroom-query-language/.../token/Tokeniser.java`) tags
`and`/`or`/`not` as keyword tokens using a regex whose "preceded by" alternation is:

```
(^\s*|[^=]\s+|\))
```

start-of-chunk, "some non-`=` character followed by whitespace", or a literal `)` — there is no alternative for a
literal `(` immediately before. Bracket-splitting (`split("\\(", ...)`) happens *after* this tagging pass, so at
the point the regex runs, `(not` is still one contiguous unsplit character run and the `(` simply isn't one of the
three accepted "preceded by" shapes. `not` falls through to an ordinary `STRING` token instead of `TokenType.NOT`,
and `SearchRequestFactory.createTerm` then tries to parse `[STRING("not"), STRING("StreamId"), ...]` as a term,
whose second token (`StreamId`) fails the "must be a condition" check.

**Confirmed as a bug, not a rule**, by direct testing against `SearchRequestFactory`:

| Query | Legacy result |
|---|---|
| `where (not StreamId = 1)` | rejected |
| `where ( not StreamId = 1)` | accepted |
| `where not StreamId = 1` (no brackets) | accepted |
| `where (and StreamId = 1)` | rejected (same regex, same cause) |
| `where (StreamId = 1 or (StreamId=2))` | accepted (`or` here is preceded by whitespace, not `(`) |

There is no reading of the design under which "a space after an open bracket changes whether `not` is recognised"
is intentional; it's an artifact of regex-driven, per-chunk tokenisation, not present in a hand-written recursive
descent or grammar-driven parser (which is exactly why `StroomQL.g4`'s `AND`/`OR`/`NOT` lexer rules recognise the
keyword unconditionally — no adjacency predicate at all, matching how any ordinary keyword would).

**What the new compiler does**: accepts it, producing the same result as if the space were there —
`ExpressionOperator{op=NOT, children=[ExpressionTerm{StreamId = 1}]}`.

**Test**: `TestLegacyBugFixes.bracketAdjacentNot_legacyRejectsButOptimisingAccepts` (and the control case
`bracketAdjacentNot_withSpaceWorksOnBothSides`, proving the two compilers agree once the legacy-only obstacle is
removed).

---

## 2. `<field> is null` / `<field> is not null`

**What legacy does**: rejects it, unconditionally, everywhere.

```
from "index_view" where StreamId is null select StreamId
  → TokenException{..., message=Incomplete term}
```

**Root cause**: the tokeniser fully supports this syntax — `Tokeniser` explicitly tags `IS_NULL`/`IS_NOT_NULL`
tokens (`split("(^|\\s)(is[\\s]+null)(\\s|$)", ...)` and the `is not null` equivalent), and `TokenType.ALL_CONDITIONS`
includes both. But `SearchRequestFactory.createTerm` starts with:

```java
} else if (tokens.size() < 3) {
    throw new TokenException(tokens.getFirst(), "Incomplete term");
```

`field is null` tokenises to exactly **2** tokens (`field`, `IS_NULL`) — there's no value token, because none is
needed. The 3-token minimum was written for `field <condition> value` shaped terms and was never special-cased for
the two conditions that don't take a value. The feature is wired up everywhere *except* the one place that would
actually construct the `ExpressionTerm` — an unfinished implementation, not a deliberate restriction.

**What the new compiler does**: accepts it, producing `ExpressionTerm{field="StreamId", condition=IS_NULL}` (no
`value`) / `condition=IS_NOT_NULL` respectively — using the very `Condition.IS_NULL`/`IS_NOT_NULL` enum constants
`ExpressionTerm` already defines for exactly this purpose (see `ExpressionTerm.append`, which already special-cases
these two conditions as taking no value).

**Test**: `TestLegacyBugFixes.isNull_legacyRejectsButOptimisingAccepts`,
`TestLegacyBugFixes.isNotNull_legacyRejectsButOptimisingAccepts`.

---

## 3. Bare `where` clause mixing index-eligible and index-ineligible terms

Unlike entries 1–2 above, this is not a Phase 1 parsing divergence — both compilers parse and accept the query
identically. It's a Phase 5 (`docs/query-optimiser-implementation-plan.md`, Task 5.3) divergence in the *compiled
`SearchRequest`*, and (by inference, traced through the code rather than proven by an end-to-end search test in
this codebase — see caveat below) in the *result rows a real search would return*.

**What legacy does**: puts the entire bare `where` predicate into `Query.expression` (scan-time), unconditionally
— `AstToSearchRequestMapper`/`SearchRequestFactory` never check a field's `queryable()`/`ConditionSet` before
placing a term there (confirmed: zero matches for either check in either class). Traced one concrete consequence
of this into `stroom-index-lucene/.../SearchExpressionQueryBuilder.getTermQuery`: a term referencing a field
**unknown to the index** compiles to `new MatchNoDocsQuery()`; ANDed into the top-level boolean query, that makes
the **entire query return zero rows**, not just fail to match on that one term. (This is the traced part; whether
Lucene behaves the same way for a field that *is* known to the index but simply isn't indexed was not confirmed
either way — the important, confirmed case is the unknown-field one.)

**What the new compiler does**: `AutoWhereFilterSplitRule` (Task 2.3) already classified such a term as
index-ineligible when compiling `explain()`'s advisory plan; Task 5.3 wires that same classification into
`create()`'s real output — the ineligible remainder moves to `TableSettings.valueFilter` (extraction-time,
post-scan), so it's evaluated correctly instead of zeroing the scan. A query that's entirely index-eligible is
untouched (the rule is a documented no-op there), and a query that already has its own explicit `filter` clause is
also untouched (same no-op guarantee, since `AutoWhereFilterSplitRule` only ever acts on a bare `where`).

**Why this is a bug, not a design choice**: nothing about StroomQL's `where`/`filter` split is documented as
"non-indexed fields must go in `filter`, or the query silently returns nothing" — a user writing a perfectly
reasonable `where a = 1 and freeTextField = 'x'` today gets zero rows with no error, indistinguishable from "no
data exists", which is a footgun, not an intentional constraint.

**Test**: `TestOptimisingQueryCompilerWhereFilterSplit.mixedEligibility_movesIneligibleTermToValueFilter` (and the
no-op controls, `allTermsEligible_leavesExpressionAndValueFilterUnchanged` /
`explicitFilterClauseAlready_isUntouched`). Unlike entries 1–2, there is no companion assertion here proving what
legacy *actually* returns end-to-end (that needs a real index/search backend, out of scope for this module's unit
tests) — the "legacy returns zero rows" claim rests on the traced `MatchNoDocsQuery` code path above, not a
runtime comparison.

---

## Cross-references

- `docs/query-optimiser-implementation-plan.md`'s Open Decision D2 records this resolution for `is null`/`is not
  null`.
- `TestQueryCompilerParity` (the hand corpus, Task 1.6) does not currently contain a query matching any of the
  three shapes above — nothing to exclude there today, but if one is ever added to the corpus it must be added to
  this document instead of `KNOWN_ERROR_TEXT_DEVIATIONS` (that map is for cases where **both** sides still reject,
  just with different text; these are one-directional) or, for entry 3, instead of asserting byte-parity at all.
- `TestQueryCompilerGenerativeParity` (Task 1.7) can generate the bracket-adjacent-`not` shape (never `is null` —
  the generator doesn't produce that syntax) and special-cases it via `BRACKET_ADJACENT_LOGICAL_KEYWORD`, asserting
  the asymmetry explicitly rather than skipping the comparison. It does not generate queries against a
  `FieldInfoSource` with non-queryable fields, so entry 3 above doesn't affect it either.
