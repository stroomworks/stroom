# A `having` clause naming a `select` alias silently rejects every row

**Component:** `stroom-query-common` — `SearchRequestFactory` (the `additionalFields` path) with
`RowUtil.createColumnNameValExtractor`
**Severity:** high for anyone who hits it. The query parses, the parameter substitutes, no error is
raised, and **every row is filtered out**. A filter that quietly matches nothing is the worst shape
a filter can take.
**Status:** open, pre-existing, and pinned by tests in `TestHavingOnSelectAlias`.

---

## What happens

```
from "events"
having "Effective Time" > param('ExpiryFloor')
select EffectiveTime as "Effective Time", Key as "Entity ID"
```

This looks unremarkable — the `having` names a column the `select` defines — and it returns nothing,
whatever the data and whatever the floor.

The parse is fine: the clause becomes `tableSettings.aggregateFilter` with
`field="Effective Time"`, `condition=GREATER_THAN`, and the substituted floor. The damage is in the
column list:

| name | expression | visible |
|---|---|---|
| `Effective Time` | `${EffectiveTime}` | true |
| `Entity ID` | `${Key}` | true |
| `__stream_id__` | `${StreamId}` | false |
| `__event_id__` | `${EventId}` | false |
| `__annotation_id__` | `${annotation:Id}` | false |
| **`Effective Time`** | **`'Effective Time'`** | **false** |

A **second column with the same name** has been added, and its expression is the name *as a string
literal* rather than the value.

## Why

`SearchRequestFactory` collects every field a `having` clause names into `additionalFields`
(`:401`), and after processing the `select` it adds a column for each one it does not already hold
(`:937`–`:945`):

```java
for (final AbstractToken token : additionalFields) {
    final String fieldName = token.getUnescapedText();
    if (!addedFields.contains(fieldName)) {
        final String id = "__" + fieldName.replaceAll("\\s", "_") + "__";
        tableSettingsBuilder.addColumns(createColumn(token, id, fieldName, fieldName, false, ...
```

That exists so a `having` can filter on something the `select` omits, which is reasonable. But
`addedFields` records **source field names**, not the aliases the columns ended up with. So when a
`select` renames a field, the alias looks unseen, and a column is added whose *expression* is the
alias text — `createColumn(..., fieldName, fieldName, ...)` passes the name where the expression
belongs.

The filter then binds to the wrong one. `RowUtil.createColumnNameValExtractor` builds a `HashMap`
keyed by column name:

```java
fieldPositionMap.put(column.getName(), new ValuesFunctionFactory(column, i));
```

Two columns share the name, so **the last wins** — and the added literal is last. Every row presents
the constant text `"Effective Time"` where a date is wanted, no row can be after any floor, and the
result is empty.

## The rule, as tested

`TestHavingOnSelectAlias` covers all five spellings:

| `select` | `having` names | Result |
|---|---|---|
| `EffectiveTime as "Effective Time"` | the alias | **rejects every row** |
| `EffectiveTime as "Effective Time"` | the source field | **`MatchException: Field not found`** |
| `EffectiveTime` | the source field | filters correctly |
| `EffectiveTime as EffectiveTime` | the same name | filters correctly |
| `EffectiveTime as "EffectiveTime"` | the same name | filters correctly |

**The rule is that the `having` field and the column's name must be the same string.** When they
agree no duplicate is added and everything works. When the `select` renames and the `having` follows
the rename, it breaks — which is the intuitive thing to write.

Note the two failure modes differ in kind: naming the source field throws during predicate
construction, so it surfaces as an error; naming the alias builds a perfectly good predicate over
the wrong column, so it surfaces as nothing at all.

## Suggested fix

Three candidates, cheapest first:

1. **Record aliases in `addedFields`, not just source field names.** The added column exists to
   cover fields the `select` omits; a renamed field has not been omitted. This is the smallest
   change and it makes the intuitive query work.
2. **Do not add a column whose name already exists**, whatever the reason. A guard at `:939` on the
   *column names built so far* rather than on `addedFields`.
3. **Make `createColumnNameValExtractor` prefer a visible column** over a hidden one when names
   collide. This treats the symptom rather than the cause, but it would also protect against any
   other source of duplicate names.

(1) and (2) are complementary and both worth doing. (3) is defence in depth.

Whichever is taken, the added column's expression should be the field reference (`${EffectiveTime}`)
rather than the field's name — a column whose value is its own name is not useful to anybody, and it
is what makes the collision silent rather than merely redundant.

## Verification

`TestHavingOnSelectAlias` asserts the current behaviour, including the two broken spellings. It is
written so that fixing this makes `theFilterBindsToTheLiteralAndSoRejectsEveryRow` and
`zeroFloorDoesNotRescueIt` **fail**, and its javadoc says those should then be rewritten to assert
that the row passes rather than deleted.

There was no test coverage of `having` anywhere in the repository before this; these are the first.
