# Temporal store parity: `UpdatableTemporalStore` vs Plan B `TemporalStateDb`

**Requirement:** the two must behave identically as temporal state stores, differing only in the
SQL store's extra CRUD operations and in performance.

**Verdict:** they do not. Three differences, all in the handling of time terms, demonstrated by a
head-to-head test running both stores live. Four other behaviours match.

**Test:** `TestTemporalStoreParity` in `stroom-sqlstore-impl-db`.

---

## Result — the test has now run

**8 tests, 3 failures.** Head-to-head, both stores live, same data, same criteria.

| Case | Result |
|---|---|
| No time term | **Parity** |
| `Key` filter | **Parity** |
| Same key and instant written twice | **Parity** |
| Lower bound alone (`>= T2`) | **Parity** |
| **Upper bound (`<= T`)** | **Differ** |
| **Exact time (`= T`)** | **Differ** |
| **Both bounds (`>= T2 AND <= T3`)** | **Differ** |

T1, T2 and T3 are defined below.

### The three differences, as the test reports them

**Times used throughout.** Three effective times a year apart, plus a query time that falls between
two of them so a snapshot has to resolve backwards rather than land on a stored row:

| | Value |
|---|---|
| `T1` | `2020-01-01T00:00:00.000Z` |
| `T2` | `2021-01-01T00:00:00.000Z` |
| `T3` | `2022-01-01T00:00:00.000Z` |
| the upper bound in case 1 | `2021-06-01T00:00:00.000Z` — between T2 and T3 |

**Data in both stores:** `gate@T1`, `gate@T2`, `door@T1`. Two keys: `gate` has two versions
straddling the query times, and `door` has a single version at T1 only. `door` is the discriminator
— it is the key whose presence or absence separates "the state as at T" from "the rows at T".

**1. Upper bound** — `EffectiveTime <= 2021-06-01` (between T2 and T3)

```
SQL   : door@T1, gate@T2
PlanB : door@T1, gate@T1, gate@T2
```

The SQL store returns the state *as at* that time — one row per key. Plan B returns every version
at or before it.

**2. Exact time** — `EffectiveTime = 2021-01-01` (T2)

```
SQL   : door@T1, gate@T2
PlanB : gate@T2
```

The SQL store resolves a *snapshot at* T2, so it includes `door@T1` — door's latest version at or
before T2. Plan B matches the instant exactly, so door is absent.

**3. Both bounds** — `EffectiveTime >= T2 AND <= T3`

```
SQL   : door@T1, gate@T2
PlanB : gate@T2
```

**The SQL store returns a row outside the range the caller asked for.** `door@T1` is before the
requested lower bound of T2. The upper bound switches the query to the snapshot path, and
`getFilteredExpression` then strips *every* time term from the SQL condition — including the
caller's lower bound. This is the sharpest of the three: the other two are defensible as different
questions, but this one answers a question nobody asked.

### A correction to this report's earlier predictions

Two cases were predicted wrongly, and two first-draft tests passed for the wrong reason:

- **The exact-time case initially passed.** Its fixture had a single key, whose latest version at or
  before T2 *is* the row at T2, so both semantics coincide. Adding `door@T1` — a key existing only
  before the query time — made it discriminate. A test that cannot distinguish the two behaviours is
  not evidence of parity.
- **The lower-bound case passes, but not for the predicted reason.** A lone `>=` never reaches the
  snapshot path: `getQueryTime` accepts only `EQUALS`, `<` and `<=`. The lower-bound stripping only
  bites when an upper bound is present too — which is why the both-bounds case was added, and it
  fails.

## A worked example to judge the options against

One store, three realistic queries. Everything below is expressed in terms of this.

> **Every number here is asserted by a passing test**, not reasoned about:
> `TestTemporalStoreParity.testWorkedExampleInTheParityReport` runs these three queries against both
> live stores and pins each result set. If either store's behaviour changes, that test fails and this
> section is known to be stale. It asserts what each store *does*, so unlike the parity cases it
> passes today.

**Store `people_events`** — where each person is, over a morning:

| Key | Effective time | Value |
|---|---|---|
| `alice` | 09:00 | `desk-1` |
| `alice` | 09:30 | `desk-2` |
| `bob` | 09:00 | `desk-3` |

**Query 1 — "where is everyone now?"** The floor map at 09:45: `EffectiveTime <= 09:45`

| | Rows returned | What the user sees |
|---|---|---|
| Snapshot | `alice@09:30`, `bob@09:00` | two people, in the right places |
| Filter | `alice@09:00`, `alice@09:30`, `bob@09:00` | **alice drawn twice**, at two desks |

**Query 2 — "show me the morning's activity."** A histogram: `EffectiveTime >= 08:00 AND <= 12:00`

| | Rows returned | What the user sees |
|---|---|---|
| Filter | all three rows | three events — the correct density |
| Snapshot | `alice@09:30`, `bob@09:00` | **two events** — alice's 09:00 move is missing from the chart |

**Query 3 — "what changed after 09:15?"** `EffectiveTime >= 09:15 AND <= 12:00`

| | Rows returned | What the user sees |
|---|---|---|
| Filter | `alice@09:30` | one change, correct |
| Snapshot **as implemented today** | `alice@09:30`, `bob@09:00` | **`bob@09:00` — a row from before 09:15**, which the caller explicitly excluded |

Query 3 is the bug. Queries 1 and 2 show that *both* semantics are needed: neither is right for
everything. That is the crux — this is not "which store is correct" but "callers ask two different
questions and the current design guesses which".

---

## Option A — Plan B adopts snapshot semantics

**What changes:** Plan B's column above becomes the snapshot column. Both stores answer Query 1
correctly.

**Who gains:** the floor map. Its events query works on either store, the 20-second window and the
client-side `latestPerEntity` reduction both go, and an entity that stops emitting stays on the map.

**Who loses, concretely:** anyone querying a Plan B `TEMPORAL_STATE` store for *history* over a time
range — Query 2. A dashboard showing "all state changes today" (`EffectiveTime >= day() AND <=
now()`) currently returns every change; afterwards it returns one row per key. The chart silently
loses rows. No error, no warning.

**The parser decision, made concrete.** Whether that dashboard actually breaks depends on a detail:

| Plan B copies… | `EffectiveTime >= day() AND <= now()` | Effect |
|---|---|---|
| the SQL store's parser (`DateUtil.parseUnknownString`) | `now()` fails to parse → snapshot not triggered → full history | dashboard **unaffected**; but snapshot then only fires for absolute times, so the semantics depend on how a user typed the date |
| the query engine's parser | `now()` resolves → snapshot triggered | dashboard **breaks**, as does every other time-ranged query on any temporal-state store |

The first is nearly invisible and nearly useless outside the floor map, whose client sends an
absolute epoch value. The second is correct and has the wide blast radius. **This choice matters
more than the implicit-versus-explicit one**, and it is not obvious which way is safer.

**Other costs:** the bulk reduction cannot stream on prefix change (variable-length prefixes for
`HASH_LOOKUP`, `UID_LOOKUP`, `TAGS`, `VARIABLE`), so it needs map-accumulated grouping — which means
buffering instead of streaming, and unbounded heap proportional to distinct key count on a store
type built for large cardinality. And `stroom-sqlstore` does not exist on `origin/master`: the
"convention" being copied is this fork's, so changing `TemporalStateDb` locally means editing
pristine upstream files, with permanent merge exposure.

---

## Option B — the SQL store adopts filter semantics

**What changes:** the SQL store's column becomes the filter column. Both stores answer Query 2
correctly, and Query 3's bug disappears because nothing is stripped.

**Who loses, concretely:** every current caller of Query 1.

- The floor map's **facts** query draws each object once per version it has ever had — a desk moved
  three times appears three times, at three places.
- An XSLT `lookup()` at an event's timestamp returns *every* version of that key instead of the one
  in force, so a translation gets a list where it expected a value.
- The Editor's `fetchAtTime` and `fetchAll` still need snapshot behaviour, so the store ends up
  implementing both anyway.

**Not recommended.** It trades a working system for a tidier one, and the inconsistency moves rather
than disappears.

> **Note — this is Option C with the reduction deleted instead of relocated.** Option B is right
> that filter semantics is the correct *primitive*; where it goes wrong is discarding the snapshot
> reduction rather than making it something a caller can ask for. Every caller that needs Query 1
> would then have to rebuild the reduction itself, over the wire, which the 1000-of-5014 figure
> under Option C shows does not work. If Option B looks appealing, Option C is the version of it
> that survives contact with the data.

---

## Option C — snapshot as an explicit, pushdown-able reduction over the filter primitive

**The insight this rests on.** Filter is the **primitive**; snapshot is **derivable** from it. Given
the full history at or before T you can always compute one row per key. The reverse is impossible —
you cannot recover history from a snapshot. So the two semantics are not peers to choose between:
one is a reduction over the other, and the design should say so.

That reframes the whole question. It is not "which semantic is correct" but **where the reduction
runs**.

| Where the reduction runs | Rows transferred | Viable? |
|---|---|---|
| **Inside the store** | one per key | **Yes** — and this is exactly what the SQL store already does: `MAX(effective_time)` grouped by key, over the primary-key index |
| **In the query layer, pushed down to the store** | one per key | **Yes** |
| **Over the wire, on the client** | all history ≤ T | **No** — see below |

**Why the client-side version does not work, on this deployment's own data.** The events store held
**5014 rows** when this was investigated. The floor map's overlay requests
`OffsetRange(0, 1000)` — and that cap is applied **server-side when the result page is built**,
before `latestPerEntity` runs on the client. So fetching full history and reducing locally would see
1000 of 5014 rows, truncated in LMDB key order, and roughly **80% of entities' latest positions
would never arrive**. It does not fail loudly; it silently draws the subset whose keys sort first.

So the SQL store is not doing something conceptually different from "filter then reduce". It **is**
filter-then-reduce, with the reduction pushed down into the database where the transfer cost
disappears. Plan B's actual gap is not that it filters — filtering is right — it is that **it has no
way to accept a pushed-down reduction**, so the reduction ends up over the wire, where it breaks.

### What to build

A query states which question it asks; every store answers it; the reduction is pushed down wherever
the store can do it.

**History — already valid StroomQL today, and already what Plan B does well:**

```
from people_events
where EffectiveTime between '2024-03-01T08:00:00.000Z' and '2024-03-01T12:00:00.000Z'
select Key, EffectiveTime, Value
```

**Snapshot — needs a way to be asked for.** The cheapest form that parses under the current grammar
is a reserved field the store consumes:

```
from people_events
where StateAt = '2024-03-01T09:45:00.000Z'
select Key, EffectiveTime, Value
```

> An earlier draft of this section sketched `from people_events at 09:45` and
> `from people_events between 08:00 and 12:00`. **Neither is valid StroomQL.** The grammar is
> `from = "from" , name` with nothing permitted after the name, `between` is a condition inside a
> `where` term rather than a `from` modifier, and `09:45` is not a `datetime` token — that requires
> the full `YYYY-MM-DDThh:mm:ss.sss` form. A nicer surface syntax such as `from … as at T` is
> possible but is a change to the most rigid part of the grammar; see
> `planb-snapshot-read-proposal.md` for the options and their costs.

- **SQL store:** already has the machinery — its `MAX`-grouped-by-key subquery becomes what `at`
  compiles to, instead of something inferred from term shape.
- **Plan B:** implements `at` with the reverse-seek `getState` already uses per key, generalised to
  all keys.
- **Any other store:** may fall back to fetching history and reducing, **with a documented row
  ceiling that errors rather than truncates**. Correct-but-slow, never silently wrong.

### Why this is better than aligning the two stores

- It explains *why* filter is the primitive, rather than asserting the two semantics are equally
  valid and picking one.
- It gives each store a defined obligation, rather than requiring Plan B to reproduce a heuristic —
  `getQueryTime`'s three edges stop mattering, because nothing is inferred from term shape,
  condition operator or date format.
- Query 3's bug cannot exist: the caller's bounds are never reinterpreted or stripped.
- Queries 1 **and** 2 are both expressible and correct, on both stores. No other option manages
  that.
- A future store type is correct-but-slow rather than broken.

**Cost:** new query surface, and every existing caller has to say what it means. Larger than A, and
it needs Plan B's owners. But it removes the *class* of problem rather than aligning one instance
of it — the real defect is that **result shape depends on a heuristic over the caller's
expression**, and that stays true even if Plan B is made to match the SQL store exactly.

---

## Option D — change nothing

Worth stating, because it is the status quo and it is not absurd.

The floor map already works around the difference client-side: it queries a 20-second window and
reduces to one row per entity in `latestPerEntity`. Queries 1 and 2 both work today, by the caller
compensating.

**What you keep:** an entity that stops emitting disappears from the map after 20 seconds; Plan B's
`condense` setting must stay off or stationary entities vanish; the guide has to tell users to emit
at least every 20 seconds; and Query 3's bug stays.

**When this is the right answer:** if the floor map is the only consumer that cares, and the
20-second constraint is acceptable operationally. It is cheap and it is reversible.

---

## Side by side

| | Query 1 (where now) | Query 2 (history) | Query 3 (bug) | Cost | Upstream needed |
|---|---|---|---|---|---|
| **Today** | client works around it | correct | broken | none | no |
| **A** — Plan B snapshots | correct | **breaks** on Plan B | still broken | medium | **yes** |
| **B** — SQL filters | **breaks** everywhere | correct | fixed | medium | no |
| **C** — explicit, pushdown-able | correct | correct | fixed | large | **yes** |
| **D** — nothing | works around it | correct | broken | none | no |

Two things fall out of that table:

- **No option except C gets all three right.** A and B each fix one column by breaking another.
- **Query 3 is orthogonal.** It is fixed by B and C, and untouched by A and D — but it can also be
  fixed *on its own*, today, without choosing between any of these, by making
  `getFilteredExpression` stop stripping the caller's lower bound. That is a small local change and
  it is worth doing regardless.

---

## `getQueryTime`'s three edges

Whatever is decided, these are properties of the current SQL behaviour that any "make them the same"
work has to either reproduce or deliberately fix:

- **Only absolute times count.** Relative bounds throw and are swallowed, silently selecting full
  history.
- **`<` is treated as `<=`.** All three accepted conditions become `effective_time <= T`.
- **Position is ignored.** `ExpressionUtil.terms` recurses through `OR` and `NOT`, so a nested time
  term switches the whole query to a snapshot — and all time terms are then stripped from the
  condition, including an explicit lower bound the caller wrote, which the snapshot may violate.

The third is why `testLowerTimeBoundSelectsTheSameRowsInBothStores` is in the parity test: a
`>=` term alone should behave identically in both stores, and if the SQL store strips it, it does
not.

---

## Recommended next step

**Do the Query 3 fix now, on its own.** It needs no decision between the options, it is local to
`getFilteredExpression`, and returning a row outside the caller's requested range is indefensible
under any reading. It is the only part of this report that is unambiguously a bug rather than a
design choice.

**Then choose between A, C and D** — knowing that only C gets all three queries right, that A buys
the floor map's correctness by breaking history queries on Plan B, and that D is the status quo with
its costs written down. A and C both need Plan B's owners; D needs nobody.

The floor map does not need this decision to keep working. It needs one before the events store can
be relied on to behave like the facts store.

Independently of that, **the both-bounds case is a bug in the SQL store on its own terms.** Returning
a row outside the caller's requested range is not a defensible reading of any question; it is
`getFilteredExpression` stripping more than it should. Worth fixing whether or not the two stores are
ever aligned, and it is a small local change.

To re-run:

```
./gradlew :stroom-sqlstore:stroom-sqlstore-impl-db:test --tests '*TestTemporalStoreParity*'
```
