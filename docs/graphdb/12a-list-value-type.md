# A list value type, and why `collect()` is currently rejected

**Status:** analysis complete, implementation deliberately deferred until closer to production.
**Audience:** developers.
**Scope:** the one change Graph DB needs from Stroom's shared query-language core, why it was held back, and
exactly what it would involve. Canonical for that analysis; [12-future-work.md](12-future-work.md) summarises and
links here.
**Companion documents:** [07-functions.md](07-functions.md) (what `collect()` would do),
[06-language-reference.md](06-language-reference.md) (the aggregate list).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

## The current position

`collect(...)` **parses but does not compile.** A query using it fails with:

> not supported in this version: `collect(...)` is unavailable because there is no list value type yet - it would
> return a comma-joined string rather than a list, which cannot be indexed or sized. Aggregate with
> count/sum/avg/min/max, or return one row per value without aggregating

It previously returned a comma-joined `ValString` — `"theft, fraud"`. That is not a smaller version of the right
answer, it is a different answer that resembles the right one: you cannot index it, take its size, or distinguish
a two-element list from a one-element list whose single value contains a comma. **Rejecting it is the honest
position**, and consistent with how the rest of this subset behaves — unsupported shapes fail loudly rather than
returning something plausible.

The grammar token, the `AstAggregateFunction.COLLECT` constant and the AST plumbing are all **retained**. Only
`CypherToLogicalPlan` rejects it, and `GraphTraversalEngine`'s `case COLLECT` throws as unreachable. Re-enabling
is therefore a small, well-marked change once the value type exists.

## `collect()` is the only thing that needs it

Verified across the whole repository: nothing else in the product wants a list-valued `Val`. Every other
aggregate (`count`, `sum`, `avg`, `min`, `max`) reduces to a scalar. So this is a single-feature dependency on a
shared type — which is precisely why it is awkward to schedule.

## What the change actually is

The headline is that this is **not** a product-wide edit. It is about six files. What is product-wide is the
*exposure*, and that distinction is the whole reason for deferring it.

### Forced — will not compile without

| File | Change |
|---|---|
| `Val.java` | add to the `permits` clause and to `@JsonSubTypes` (14 entries today) |
| `Type.java` | a new constant. **Id 8 is free** — the existing gap between `STRING(7)` and `ERR(9)` |
| `ValSerdeUtil.java` | two exhaustive `Type` switches with **no `default`** — the compiler finds both |
| `ValList.java` | new. `ValXml` is the shape to copy |

### Needed for correctness — compiles, but wrong without

- **`ValSerialiser`** — a `Serialiser[]` indexed by `Type.getId()`, already sized `maxId + 1`, so **slot 8 exists
  and is null**. An entry fills a hole rather than resizing anything. Forgetting it **fails loudly**: `write` has
  an explicit `Objects.requireNonNull` naming the type. Not a silent corruption risk.
- **`ValComparators`** — a missing `Type` entry falls back to a string comparator, so ordering works but sorts as
  text. This one degrades quietly, so it is the easier of the two to overlook.

### Not needed — the other 279 files

285 files reference `Val`. The remaining 279 need no change, and this was checked rather than assumed:

- There are **zero** pattern switches on the sealed `Val` that lack a `default`. (`grep 'case Val[A-Z]'` returns
  nothing across the repository.)
- The main output path is `NumberFormatter.asStringWithNoFormatting`, a `switch (val)` whose last arm is
  `default -> val.toString()`. So the standing claim that "renderers just stringify" holds.

## The actual risk, and why this waits

The edit is small and the compiler covers most of it. What warrants care is that **285 files can suddenly receive
a value type they have never seen**, and their behaviour will be by-default rather than by-design.

`ValXml` is the precedent worth studying. When it was added, several places chose to enumerate it explicitly —
`StateValueProxy` switches on `state.val().type()` and names `XML` in two places with no `default`, and Plan B's
own filter does something similar. Nothing forced those decisions; someone made them. A list type presents the
same decision at each of those sites, and **none of them will be forced to make it** — they will silently take a
`default` branch and stringify, which is exactly the behaviour being removed from `collect()`.

That is a reviewable, bounded piece of work, but it is judgement spread across Plan B, dashboards, StroomQL and
extraction rather than a mechanical edit inside Graph DB. Doing it as part of graph work would bury those
decisions in an unrelated change.

## Constraints to carry into the implementation

- **A 255-element ceiling is inherited, not chosen.** `ValSerialiser.writeArray` throws above 255 values. That
  becomes `collect()`'s real limit and must be documented as such in
  [10-limits.md](10-limits.md) — not discovered by a user at element 256.
- **No GWT constraint.** `stroom.query.language.functions` appears in no `.gwt.xml`, so a new `Val` need not be
  GWT-safe. Worth re-checking if that ever changes, since it would otherwise be discovered late and expensively.
- **`ValNumber` is a sealed intermediate.** `Val.permits` lists only six types because `ValNumber` itself permits
  the numeric ones. A list is a sibling of `ValString`, so it goes in `Val.permits` directly.

## Suggested shape when it is picked up

1. Add the type, the four forced changes, and the two behavioural entries.
2. Make a **deliberate decision at each site that already enumerates `Type.XML`**, and record it — even "stringify
   is correct here" is worth writing down, because the next person cannot tell a considered default from an
   unconsidered one.
3. Document the 255-element cap in [10-limits.md](10-limits.md).
4. Re-enable `collect()`: remove the rejection in `CypherToLogicalPlan.compileAggregateColumn`, restore the
   shape-specific messages for `collect(*)` and `collect(<variable>)`, and re-specify the executor's `case COLLECT`
   (its previous comma-joining implementation is in git history — do not restore it).
5. Restore `collect(DISTINCT …)`. The `DISTINCT` validation currently allows it on `count(...)` only; it named
   `collect(...)` too before the rejection landed.

The tests that pinned the old behaviour were **converted rather than deleted**, and assert the rejection message.
They will fail when `collect()` is re-enabled — deliberately, so the executor's behaviour has to be re-specified
rather than silently inherited.

## Next

- [12-future-work.md](12-future-work.md) — everything else deferred, with sizing.
- [epoch0-development-plan.md](epoch0-development-plan.md) — the sequenced plan this was item 3.2 of.
