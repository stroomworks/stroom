# 4. Behaviour changes

**Status:** Experimental, off by default. See [README.md](README.md#production-readiness).
**Audience:** analysts and administrators.
**Scope:** every way the optimiser's compiled output deliberately differs from the legacy compiler's, and how each
one shows up. Canonical for the divergence list. If a `SHADOW`-mode log line does not trace to something on this
page, it is a bug.
**Companion documents:** `docs/query-optimiser-known-differences.md` (engineering record) traces each case to the
specific line of legacy code that causes it.

---

## The rule

The optimiser targets **byte-identical output** to the legacy compiler for every query legacy compiles
*correctly*. Where legacy's behaviour is a confirmed defect — an accident of its regular-expression tokeniser, or
an unfinished code path — the optimiser does **not** reproduce it. Copying a bug forward would freeze it into the
replacement permanently.

There are exactly three such cases, plus one category of cosmetic difference. All four are below.

Parity is enforced, not asserted: a differential test compiles the whole legacy corpus through both engines and
requires identical JSON, and a seeded fuzzer generates 200 random-but-valid queries per run and does the same.
Anything on this page is explicitly excluded from those checks, with the asymmetry asserted in both directions —
so a regression in *either* engine fails loudly. See [14-testing.md](14-testing.md#the-parity-suites).

---

## 1. A bracket immediately against `not`, `and` or `or`

```
from "index_view" where (not StreamId = 1) select StreamId
```

- **Legacy:** rejected — `Expected condition token`.
- **Optimiser:** accepted, producing `NOT { StreamId = 1 }`.

Adding a single space makes legacy accept it:

| Query | Legacy |
|---|---|
| `where (not StreamId = 1)` | rejected |
| `where ( not StreamId = 1)` | accepted |
| `where not StreamId = 1` | accepted |
| `where (and StreamId = 1)` | rejected — same cause |
| `where (StreamId = 1 or (StreamId=2))` | accepted — the `or` is preceded by whitespace, not `(` |

**Why it is a defect.** Legacy tags `and`/`or`/`not` as keywords with a regex whose "preceded by" alternation
accepts start-of-chunk, a non-`=` character followed by whitespace, or a literal `)` — but not a literal `(`.
Bracket-splitting happens *after* that tagging pass, so at the moment the regex runs, `(not` is still one
unsplit character run. The keyword falls through to an ordinary string token, and the term builder then fails on
what it sees. There is no reading of the design under which "a space after an open bracket changes whether `not`
is recognised" is intentional.

The grammar's `AND`/`OR`/`NOT` lexer rules recognise the keyword unconditionally, with no adjacency predicate —
exactly as any ordinary keyword would.

**How you will see it:** a query that used to be rejected now runs. Found originally by the fuzzer.

---

## 2. `is null` and `is not null`

```
from "index_view" where StreamId is null select StreamId
```

- **Legacy:** rejected — `Incomplete term`. Everywhere, unconditionally.
- **Optimiser:** accepted, producing `ExpressionTerm{field=StreamId, condition=IS_NULL}` with no value.

**Why it is a defect.** The legacy tokeniser fully supports the syntax — it explicitly tags `IS_NULL` and
`IS_NOT_NULL` tokens, and the shared condition set includes both. But the term builder starts with a "a term needs
at least three tokens" check written for `field <condition> value`, and `field is null` tokenises to exactly two.
The feature is wired up everywhere *except* the one place that would construct the term. The condition constants
it needs already exist and are already special-cased as taking no value.

**How you will see it:** a query that used to be rejected now runs.

---

## 3. A bare `where` mixing eligible and ineligible terms

This is the one that changes **results**, not just what compiles. It is the change to tell your users about.

```
from "Events" where Status = 500 and Message = 'gateway timeout' select EventTime, User, Message
```

…where `Message` is a field the index cannot evaluate — not indexed, or indexed but not supporting that
condition.

- **Legacy:** the entire predicate goes to the index. A term referencing a field the index does not know compiles
  to a "match nothing" clause; ANDed with the rest, **the whole query returns zero rows** — indistinguishable
  from "no data exists".
- **Optimiser:** the rewrite pipeline classifies `Message = '…'` as index-ineligible and routes it to
  extraction-time filtering, leaving `Status = 500` at the index. The query returns the rows it should have all
  along.

**Why it is a defect.** Nothing about StroomQL's `where`/`filter` split is documented as "non-indexed fields must
go in `filter`, or the query silently returns nothing". A user writing a perfectly reasonable predicate gets zero
rows with no error. That is a footgun, not a constraint.

**Where it does not apply.** The rule is deliberately narrow, and is a documented no-op outside it:

- A query whose terms are **all** index-eligible is untouched.
- A query that already has its own explicit `filter` clause is untouched — the rule only ever acts on a bare
  `where`.
- A predicate whose top-level operator is not `AND` is untouched. An `OR` or `NOT` cannot be partially routed
  without evaluating all its branches at the datasource.

See [05-optimisations.md](05-optimisations.md#the-wherefilter-split) for the exact eligibility test.

**A caveat worth stating.** The "legacy returns zero rows" claim is traced through the code — an index term on a
field unknown to the index compiles to a match-nothing clause, ANDed into the top-level boolean query — rather
than proven by an end-to-end search comparison in this codebase. The confirmed case is the **unknown-field** one.
Whether the same happens for a field the index knows but has not indexed was not confirmed either way.

---

## 4. Error message text

Both engines reject the same malformed queries. Where they differ is in the message and the reported position:

- Legacy embeds token offsets from its own tokenisation of the whole query. The grammar reports line and column
  from a parser that knows what it expected.
- For an expression inside `eval` or `select`, both engines hand the text to the *same* shared expression parser
  and so produce the *same* message — but the optimiser hands it an extracted substring, so the embedded offset
  is relative to that substring rather than to the whole query.
- Three incomplete-`eval` shapes (`eval` with no variable, no `=`, or no expression) are rejected *syntactically*
  by the grammar where legacy rejects them *semantically*. Arguably an earlier and clearer rejection; either way,
  still a rejection.

The parity suite requires both engines to reject the same queries, and compares message text everywhere except a
small, explicitly enumerated set of corpus entries where only the offset representation differs.

This category is cosmetic. It will show up in `SHADOW` logs only for queries that fail to compile at all.

---

## What is *not* different

Worth stating plainly, because the list above can read as larger than it is:

- **Every valid single-source query compiles to the same `SearchRequest`**, byte for byte — the same operator fold
  shape, the same column ids, the same reserved navigation columns, the same table settings.
- **Expressions in `eval` and `select` are parsed by the same parser** as before. Function semantics are
  identical.
- **Sorting, grouping, `having`, `window`, `limit` and `show`** are compiled to the same output.
- **Date and duration handling** goes through the same shared date-expression parser.
- **Permissions** are resolved identically — the same registry, the same security context.

The optimiser's *additions* — a derived time range, a `where`/`filter` split — are visible in the compiled request
and therefore in a `SHADOW` diff, but a derived time range does not change which rows match. It only narrows which
shards are searched; the original predicate is still evaluated on every row that comes back. See
[05-optimisations.md](05-optimisations.md#time-range-pruning).

---

## Reading a shadow-mode divergence

A divergence line contains the query text and both compiled forms as canonical JSON. To classify it:

| What differs in the JSON | Almost certainly |
|---|---|
| `query.timeRange` present only on the optimiser's side | Time-range pruning ([05](05-optimisations.md#time-range-pruning)) — expected, and not a difference in results |
| A term moved from `query.expression` into a `valueFilter` | Case 3 above |
| **Anything else** | **A bug.** Capture the query text and both forms |

> **Cases 1, 2 and 4 never appear as shadow divergences.** In `SHADOW`, legacy compiles *first* and its result is
> what is returned — so a query legacy rejects throws before the optimiser is ever asked to compile it. A shadow
> soak therefore tells you nothing about the two parser fixes, and nothing about error-message differences. Those
> only show up once the mode is `ON`, where the query starts working.
>
> The corollary matters more: **a query that legacy rejects and the optimiser accepts is invisible during a soak,
> and starts succeeding the moment you flip to `ON`.** That is the intent, but it is a change no amount of shadow
> evidence will have warned you about.
