# Plan B: a `where` term on a field the `select` list omits silently returns zero rows

**Component:** `stroom-planb` — `PlanBSearchHelper`, and the five state DBs that call it
**Severity:** high. A query returns a **wrong answer** — an empty result — with no error, no warning
and nothing in the logs.
**Found by:** checking a Floor Map test fixture query. Not a Floor Map defect; the
floor map is safe, but only by accident of its generated query's shape — see *Why this matters
beyond the query bar*.
**Status:** diagnosed and reproduced against a live instance; not fixed.

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

The shapes it is most likely to be met in, all explained by the same rule:

| Query | Rows | Why |
|---|---|---|
| `where EffectiveTime < …` `group by Key` `select Key, count()` | **0** | `count()` references no field, so `EffectiveTime` is not in the select list |
| `where EffectiveTime < …` `group by Key` `select Key, count(), max(toLong(EffectiveTime))` | rows, and `count()` correct | the `max` puts `EffectiveTime` in the select list |
| `where EffectiveTime < …` `group by Key` `select Key, max(toLong(EffectiveTime))` | rows | same |
| `where Key != 'forklift-7'` `group by Key` `select Key, count()` | rows | the filtered field *is* selected |

## The mechanism

`TemporalStateDb.search` (line 235) builds the values extractor from the field index:

```java
final ValuesExtractor valuesExtractor = createValuesExtractor(
        fieldIndex,
        getKeyExtractionFunction(readTxn),
        getValExtractionFunction(readTxn));
PlanBSearchHelper.search(readTxn, criteria, fieldIndex, …, valuesExtractor, dbi);
```

`PlanBSearchHelper.search` (lines 53–54) then **appends the expression's fields to that same field
index** — after the extractor has already been built for the shorter one:

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

Position 1 is past the end of a one-value row. The predicate reads nothing, evaluates false, and
every row is discarded. No exception is thrown, so nothing surfaces.

**Ordering is the whole bug.** The helper's own comment on those lines — *"Ensure we have fields for all
expression criteria"* — describes exactly the right intention, executed one step too late.

## Scope

All five state DBs that call `PlanBSearchHelper.search` have the same ordering, extractor first:

| File | extractor | helper |
|---|---|---|
| `StateDb.java` | 204 | 208 |
| `RangeStateDb.java` | 218 | 222 |
| `TemporalStateDb.java` | 235 | 239 |
| `TemporalRangeStateDb.java` | 245 | 249 |
| `TraceDb.java` | 327 | 331 |

Only `TEMPORAL_STATE` was tested. The other four are inferred from identical structure, so confirm
before writing a fix that claims to cover them.

**A SQL Temporal Store is not affected.** `UpdatableTemporalStoreDaoImpl` applies the criteria as a
SQL `WHERE`, so rows are filtered in the database and no field index is involved. Query 4's
equivalent against a SQL Temporal Store returns correctly filtered rows. This is therefore also a
**store-type divergence**: the same StroomQL gives different answers depending on which store backs
it, which is the harder half of the problem to discover.

## Why this matters beyond the query bar

`Format.TEXT` is applied to every field in `createValueFunctionFactories`, including
`EffectiveTime`. That is a second thing worth a look while in here — a date compared as text is
correct only while the text form is ISO-8601, which it is today by accident of StroomQL setting no
`Format` on select columns.

More pressingly: **any feature that filters a Plan B store by a range it does not also select will
silently draw nothing.** The Floor Map is one query away from this. Its events query passes the
playback range as a `TimeRange`, which `ResultStoreManager.addTimeRangeExpression` turns into
`EffectiveTime` terms — and the generated query happens to select `EffectiveTime` as its first
column, so the terms work. Delete that column from a saved events query and the map goes blank, with
the on-canvas status line reporting *"No events at this time"* — which would be actively misleading,
because the store is full.

So the feature is correct today, and correct for a reason nobody chose.

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
