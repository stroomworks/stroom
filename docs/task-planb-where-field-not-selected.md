# Plan B: a `where` term on a field the `select` list omits silently returns zero rows

**Component:** `stroom-planb` — `PlanBSearchHelper.search`, and the five state DBs that call it
**Severity:** high. A query returns a **wrong answer** — an empty result that looks like a
successful search over no data. The cause is an exception, but it is caught, recorded on the result
store, logged at `debug` only, and followed by a normal completion signal, so in practice there is
nothing to see.
**Found by:** checking a Floor Map test fixture query. Not a Floor Map defect; the
floor map is safe, but only by accident of its generated query's shape — see *Why this matters
beyond the query bar*.
**Status:** open, and **narrower than first written**. `TemporalStateDb` gained a second search
path that does the registration in the right order, so on that one store the defect now needs a
query with no liftable time term. The other four state DBs are unchanged. See *What a later change
fixed by accident*.

> **Re-verified 2026-09-09**, at the storage layer rather than through StroomQL, against the
> current code:
>
> | Case | Result |
> |---|---|
> | fall-through path, filter on `Key`, field index **contains** `Key` | 2 rows — correct |
> | fall-through path, filter on `Key`, field index **omits** `Key` | **throws `ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1`** |
> | `searchAsAt` path, filter on `EffectiveTime`, field index **omits** it | 2 rows — correct |
>
> So the defect is live on the fall-through path, upstream's `searchAsAt` ordering is sound, and the
> narrowing described above is confirmed rather than inferred. The third row is what makes the first
> two worth trusting: the same malformed shape succeeds on the corrected path, so this is the
> ordering and not something else.
>
> The StroomQL-level reproduction table below was recorded before `TemporalStateDb` gained its
> second path and **has not been re-run** — queries 1 and 2 should be unaffected by it, and queries
> 3 and 4 are predicted to have changed. The storage-layer result above is the authoritative one.

---

## The rule

> On a Plan B store, a `where` term on a field that the `select` list does not **also** reference
> filters out **every** row.

It is not specific to time fields, to `group by`, or to `count()` — each of those turns out to be an
instance of the general rule, and they are tabulated below because they are the shapes it is most
likely to be met in.

## Reproduction

Against a `TEMPORAL_STATE` store holding 207 rows across 6 keys, one of which is
`alice@example.org`:

| # | Query | Rows | |
|---|---|---|---|
| 1 | `where Key = 'alice@example.org'` → `select Key, EffectiveTime` | 51 | correct |
| 2 | `where Key = 'alice@example.org'` → `select EffectiveTime, EffectiveTime as "T2"` | **0** | **wrong** |
| 3 | `where EffectiveTime < '2026-09-04T05:00:00.000Z'` → `select Key, EffectiveTime` | rows, correctly filtered | correct |
| 4 | `where EffectiveTime < '2026-09-04T05:00:00.000Z'` → `select Key, Key as "K2"` | **0** | **wrong** |

**2 and 4 are the same query as 1 and 3 with one column renamed.** In each failing case the filtered
field is absent from the select list, and in each passing case it is present. Nothing else differs —
no aggregation, no grouping, no time semantics.

**Which of these still reproduce.** Queries 3 and 4 filter on `EffectiveTime` with `<`, which is now
lifted by `getQueryTime`, so on a `TEMPORAL_STATE` store they take the `searchAsAt` path — where the
ordering is correct. Query 4 is therefore predicted to **pass** there now, and to still fail on the
other four state DBs, which have no such path. **Query 2 is the live reproduction**: it filters on
`Key`, nothing lifts it, and every store falls through to the broken ordering. Use query 2.

The shapes it is most likely to be met in, all explained by the same rule. These were observed on a
`TEMPORAL_STATE` store before it gained its second path, so the first three are now predicted to
behave differently there — but not on the other four DBs, and not for any filter on a non-time
field:

| Query | Rows | Why |
|---|---|---|
| `where EffectiveTime < …` `group by Key` `select Key, count()` | **0** | `count()` references no field, so `EffectiveTime` is not in the select list |
| `where EffectiveTime < …` `group by Key` `select Key, count(), max(toLong(EffectiveTime))` | rows, and `count()` correct | the `max` puts `EffectiveTime` in the select list |
| `where EffectiveTime < …` `group by Key` `select Key, max(toLong(EffectiveTime))` | rows | same |
| `where Key != 'forklift-7'` `group by Key` `select Key, count()` | rows | the filtered field *is* selected |

Substituting a non-time field for `EffectiveTime` in the first of those — `where Type = 'x'`
`group by Key` `select Key, count()` — is the shape to reach for now, on any of the five stores.

## The mechanism

`TemporalStateDb.search` has two paths. The one that matters here is the fall-through, taken when
the criteria carry no `EQUALS`/`<`/`<=` term on `EffectiveTime`. It builds the values extractor from
the field index (line 271):

```java
final ValuesExtractor valuesExtractor = createValuesExtractor(
        fieldIndex,
        getKeyExtractionFunction(readTxn),
        getValExtractionFunction(readTxn));
PlanBSearchHelper.search(readTxn, criteria, fieldIndex, …, valuesExtractor, dbi);
```

`PlanBSearchHelper.search` (line 275 in this caller) then **appends the expression's fields to that
same field index** — after the extractor has already been built for the shorter one:

```java
final List<String> fields = ExpressionUtil.fields(criteria.getExpression());
fields.forEach(fieldIndex::create);
```

So for query 4 above: the index is `[Key]` when the extractor is built, giving one value per row.
`EffectiveTime` is then appended at position 1, and `createValueFunctionFactories` hands the
predicate that position:

```java
final Integer index = fieldIndex.getPos(fieldName);
return new ValuesFunctionFactory(Column.builder().format(Format.TEXT).build(), index);
```

Position 1 is past the end of a one-value row, so reading it **throws**:

```
java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1
    at stroom.query.language.functions.ArrayValues.getValue(ArrayValues.java:29)
    at stroom.query.common.v2.ValuesFunctionFactory.lambda$createStringExtractor$0
    at stroom.query.common.v2.ExpressionPredicateFactory$StringEquals.test
    at stroom.planb.impl.dao.PlanBSearchHelper.search(PlanBSearchHelper.java:137)
```

**An earlier version of this document said no exception was thrown and the predicate simply
evaluated false. That was wrong**, and the real chain matters because it splits the defect in two.
`StateSearchProvider` (line 270) wraps the scan in `catch (RuntimeException e)`, logs it at
**`LOGGER.debug`**, adds it to the result store's errors, and then **still calls
`signalComplete()`**:

```java
} catch (final RuntimeException e) {
    LOGGER.debug(e::getMessage, e);
    resultStore.addError(e);
}
...
resultStore.signalComplete();
```

So the query completes normally with zero rows, and the cause is logged only if debug logging
happens to be on for that class. That is why it presents as a silent wrong answer: **the storage
layer is loud and the search provider silences it.**

Two things are therefore worth fixing, and they are independent. The ordering is the defect. The
swallowing is why nobody found out — a scan that threw is not a completed search, and reporting it
as one costs a caller the chance to distinguish "no data" from "your query could not be run".

**Ordering is the whole bug.** The helper's own comment on those lines — *"Ensure we have fields for all
expression criteria"* — describes exactly the right intention, executed one step too late.

## Scope

All five state DBs that call `PlanBSearchHelper.search` still have the same ordering, extractor
first:

| File | extractor | helper | has a correctly-ordered second path? |
|---|---|---|---|
| `StateDb.java` | 204 | 208 | no |
| `RangeStateDb.java` | 218 | 222 | no |
| `TemporalStateDb.java` | 271 | 275 | **yes** — `searchAsAt` |
| `TemporalRangeStateDb.java` | 245 | 249 | no |
| `TraceDb.java` | 932 | 936 | no |

So the blast radius is: **all five on the fall-through path, and four of the five on every path.**

Only `TEMPORAL_STATE` was tested. The other four are inferred from identical structure, so confirm
before writing a fix that claims to cover them.

**A SQL Temporal Store is not affected.** `UpdatableTemporalStoreDaoImpl` applies the criteria as a
SQL `WHERE`, so rows are filtered in the database and no field index is involved. Query 4's
equivalent against a SQL Temporal Store returns correctly filtered rows. This is therefore also a
**store-type divergence**: the same StroomQL gives different answers depending on which store backs
it, which is the harder half of the problem to discover.

## What a later change fixed by accident, and what that tells you

`TemporalStateDb` gained a `searchAsAt` path — a latest-per-key read, entered when
`PlanBSearchHelper.getQueryTime` finds an `EQUALS`, `<` or `<=` term on `EffectiveTime`. It was added
for latest-per-key semantics, not for this defect, but it registers the fields in the right order and
says so:

```java
// Ensure we have fields for all remaining expression criteria, and
// do so before the extractor snapshots the field list.
ExpressionUtil.fields(expression).forEach(fieldIndex::create);

final ValuesExtractor valuesExtractor = createValuesExtractor(
        fieldIndex,
        getKeyExtractionFunction(readTxn),
        getValExtractionFunction(readTxn));
```

Two things follow.

**The fix is already written, in the same file.** Whoever takes this on has a worked example of the
correct ordering ten lines from the incorrect one, which is the cheapest possible starting point and
settles any argument about intent.

**But a second correct path is not a fix — it is a divergence.** The same store now answers the same
malformed query correctly or incorrectly depending on whether a time term happens to be liftable.
That is worse to diagnose than the original defect, because the reproduction becomes conditional on a
clause unrelated to the field that breaks. It is also fragile in an obvious way: the ordering
requirement now has one caller that honours it and five that do not, with nothing in the code
preventing the next one from getting it wrong. That is the argument for option 3 below rather than
option 1.

## Why this matters beyond the query bar

`Format.TEXT` is applied to every field in `createValueFunctionFactories`, including
`EffectiveTime`. That is a second thing worth a look while in here — a date compared as text is
correct only while the text form is ISO-8601, which it is today by accident of StroomQL setting no
`Format` on select columns.

More pressingly: **any feature that filters a Plan B store by a field it does not also select will
silently draw nothing.** The Floor Map is one query away from this, and is currently safe twice over
by coincidence rather than design. Its events query passes the playback range as a `TimeRange`, which
`ResultStoreManager.addTimeRangeExpression` turns into `EffectiveTime` terms — so it now routes to
`searchAsAt`, where the ordering is correct; and the generated query selects `EffectiveTime` as its
first column anyway, which is what protected it before that path existed.

Neither protection covers a **non-time** filter. Add `where Type = 'forklift'` to a saved events
query without selecting `Type`, and the map goes blank with the on-canvas status line reporting
*"No events at this time"* — actively misleading, because the store is full.

So the feature is correct today, and correct for two reasons nobody chose.

## Suggested fix

**Move the field registration ahead of the extractor.** Three options, in increasing order of how
well they stop it recurring:

1. **Hoist the two lines** into each of the five DBs, before `createValuesExtractor`. Smallest
   diff, duplicated five times, and the next state DB added will get it wrong.
2. **Expose `PlanBSearchHelper.prepareFieldIndex(criteria, fieldIndex)`** and have each DB call it
   before building its extractor, removing the mutation from `search`. One copy of the logic, but
   the ordering requirement is still a convention a caller can forget.
3. **Have `search` take a factory rather than a built extractor** —
   `Function<FieldIndex, ValuesExtractor>` — so the helper registers the fields and then asks for
   the extractor. The order becomes unexpressible in the wrong sequence, which is the only version
   that makes this unrepeatable. Recommended.

### And separately, stop reporting a failed scan as a completed one

`StateSearchProvider` catches every `RuntimeException` from the scan, logs it at `debug`, adds it to
the result store and then signals completion regardless. That is what turned a thrown exception into
a silent empty result, and it will do the same for the next storage-layer fault — so it is worth
fixing whether or not the ordering is fixed with it.

The minimum is to log at `error` rather than `debug`. Better is to distinguish a scan that failed
from one that legitimately found nothing, so a caller can tell "no data" from "your query could not
be run" — the Floor Map, for instance, reports *"No events at this time"* on an empty result and
would otherwise keep saying that about a query which never ran.

Note this is also why a never-written Plan B store presents as an empty one, which the Floor Map has
its own report-once guard for. Same root, different symptom.

**Risk: low.** The fix makes the extractor produce values for fields that are filtered but not
selected — exactly what already happens for every query that selects the filtered field, which is
most of them. Consumers read by field-index position and render only the query's own columns, so
trailing extra values are already the normal case.

## Verification

A test per state DB: a search whose expression filters on a field the field index does **not**
already contain, asserting the rows come back filtered rather than empty. Query 4 above is the
minimal case and fits in one test:

- store two rows with different `EffectiveTime`
- search with `EffectiveTime <` between them, and a field index containing only `Key`
- assert one row, not zero

There appears to be no such test today, which is consistent with the bug surviving: every existing
search presumably selects what it filters on.
