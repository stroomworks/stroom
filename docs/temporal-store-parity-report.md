# Temporal store parity: `UpdatableTemporalStore` vs Plan B `TemporalStateDb`

**Requirement:** the two must behave identically as temporal state stores, differing only in the
SQL store's extra CRUD operations and in performance.

**Verdict — revision 3: the question no longer applies, and Option A has been undone.**

Revision 2 reported parity, reached by implementing **Option A**: Plan B adopted the SQL store's
snapshot semantics, inside shared Plan B code, for every temporal state store in the system. That has
since been **reverted** — `TemporalStateDb` and `PlanBSearchHelper` are back to `origin/master`, with
one additive method that nothing upstream calls.

Two things follow:

- **The requirement above lapsed.** Parity mattered because a floor map's events store could have
  been either store. It now has a document type of its own, which is Plan B by construction, while
  facts remain SQL by construction — nothing is interchangeable, so there is nothing to keep in step.
- **`TestTemporalStoreParity` is deleted.** Keeping it would have compared an explicit contract
  against an inferred one across a translation layer, which tests neither.

What survives revision 2 is the *defect analysis*: a snapshot read that strips the caller's lower
bound, described below as "indefensible under any reading of any question". That was true, it was
the reason for all of this, and it is now addressed — not by changing Plan B for everyone, but by
`TemporalStateDb.searchSnapshot`, which takes the bound as an argument and is reached only through
the floor map's own search provider. See `docs/floormap-events-backend-design.md` §13.

The SQL store keeps its own `getNotBefore`, added at the same time and unexercised by the floor map.

**Everything below is retained as the record of how revision 2 decided, not as a live position.**

> **Verified for revision 2.** The parity suite was re-run against a live MySQL and both live
> stores: **9 tests, 0 failures, 0 skipped**. The date-parsing behaviour in *What parity cost* §3
> was verified separately by running `DateUtil.parseUnknownString` against the literals in question.
>
> Note that `stroom-sqlstore-impl-db` needs a database: without one all 9 fail to provision with
> `Communications link failure`, which looks like a behavioural failure and is not.

**Read this first if you are acting on the report:** §*What parity cost* — Option A was adopted
complete with the three behaviours revision 1 warned about, and all three are now live on both
stores.

---

## Revision 2 — what changed, when, and who changed it

**The change is ours, not upstream's.** This matters more than it sounds.

```
b1c8cb2870  2026-08-27  stroomworks  "Fix map to handle events plan b store"
            PlanBSearchHelper.java   +73    ← getQueryTime, removeTimeTerms
            TemporalStateDb.java     +113   ← searchAsAt
            TestTemporalStateDb.java +209
17371533de  2026-09-07  upstream     "Traces processing (#5772)"
```

`origin/master` contains **zero** occurrences of `getQueryTime` or `searchAsAt`. The latest-per-key
read was added **here**, eleven days *before* upstream's Traces work, to two files this fork does not
own.

`TestTemporalStoreParity`'s javadoc previously said it "arrived with upstream's Traces work on
2026-09-09". It did not, and the mistake is dangerous rather than merely untidy: if the behaviour is
believed to be upstream's, nobody marks it `STROOMWORKS-LOCAL` and the next merge from master
silently reverts the map's read semantics. Neither changed file carries a marker today — see
`docs/temporal-store-read-mode-plan.md` Phase 0.

---

## What parity cost

Revision 1 set out what Option A would break. It was adopted anyway, so those costs are now live.
All three are **shared by both stores**, which is what parity means here.

### 1. History queries on Plan B now return a snapshot

Predicted under *Option A — who loses, concretely*, and it happened. Any query carrying an upper time
bound gets one row per key instead of every version in the range. A density histogram is the obvious
casualty: it asks for all events in a window and now gets one per key.

The Floor Map's histogram escapes only because it sends **no `TimeRange` at all**, taking the
all-history path. That is a deliberate choice documented in `HistogramQueryHelper.run`, not luck —
but it is also why that read is unbounded, which is
`docs/task-histogram-reads-whole-store-and-truncates-silently.md`.

### 2. The caller's lower bound was discarded on *both* stores — fixed, commit `60f80885d5`

Query 3 below was revision 1's clearest bug: the SQL store returning a row from before the caller's
own lower bound. `searchAsAt` calls `PlanBSearchHelper.removeTimeTerms`, which strips every time term
exactly as `getFilteredExpression` does — so Plan B copied the bug along with the feature.

Revision 1 recommended fixing this *first and separately*, because "returning a row outside the
caller's requested range is indefensible under any reading". **That has now been done, in both
places.** `TemporalStateDb.searchAsAt` and `UpdatableTemporalStoreDaoImpl.search` each take the
caller's lower bound as a cutoff on the snapshot rather than discarding it; Query 3 below now
returns `alice` alone, and the parity test that asserted the old behaviour was rewritten rather than
deleted, as `theLowerBoundNarrowsTheSnapshotRatherThanBeingDiscarded`.

Two things the fix turned up that revision 1 did not anticipate:

- **A zero-width range must be exempt.** `>= T AND < T` is the framework's own "as at T" idiom, so
  an unguarded cutoff made every such read return nothing. Both stores ignore a floor that is not
  strictly before the query time.
- **It is also the mechanism for Floor Map event expiry**, which had been planned as a `having`
  clause instead — see `docs/floormap-event-expiry-requirements.md` §0.1. Fixing the defect removed
  the need for the feature-specific workaround.

### 3. Read mode depends on whether a date literal parses — silently

Revision 1 called this "the parser decision" and said it "matters more than the implicit-versus-explicit one".
It was decided by default: Plan B copied `DateUtil.parseUnknownString`, the first row of that table.

Both stores select snapshot mode like this:

```java
try {
    return DateUtil.parseUnknownString(term.getValue());
} catch (final RuntimeException e) {
    // Ignore and keep checking
}
```

The **row filter** uses a different and far more permissive parser —
`DateExpressionParser.parse(value, dateTimeSettings)` in `ExpressionPredicateFactory` — which is
timezone-aware and understands relative expressions. So the two paths disagree about what a date is,
and the disagreement chooses the semantics:

| Literal | `getQueryTime` | Row filter | Resulting semantics |
|---|---|---|---|
| `"2026-09-09T08:48:02.000Z"` | parses → `1788943682000` | parses | **Snapshot** — one row per key |
| `"2026-09-09T08:48:02.0"` | **throws**, term ignored | parses | **Filter** — every version in range |
| `now()`, `day()` | **throws**, term ignored | parses | **Filter** |

Verified by running `DateUtil.parseUnknownString` directly on those literals: the zone-qualified form
returns epoch millis, the unqualified form throws `IllegalArgumentException` — `looksLikeISODate`
accepts it, the strict ISO parse rejects it for want of a zone offset, and the epoch-millis fallback
then fails too.

**Consequences worth stating plainly.**

- Two queries that differ only in whether the date carries a `Z` return **different shapes of
  answer**, with no error and nothing in the response to say which happened.
- Every query using a relative date (`now()`, `day()`) silently gets filter semantics. Revision 1
  noted this made the change "nearly invisible and nearly useless outside the floor map"; that is
  exactly how it has turned out.
- It is, accidentally, the only way to get a **bounded range read** out of these stores today. That
  is not a design and must not be relied on — but it explains any experiment that appears to
  contradict the snapshot behaviour described here.

---

## The three cases that revision 1 recorded as failures

All three are now enabled and **passing**. Kept because they are the sharpest statement of what the
two semantics are, and because the fixture is the one the tests still use.

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

| Case | Revision 1 — SQL | Revision 1 — Plan B | Now, both |
|---|---|---|---|
| **1. Upper bound** `<= 2021-06-01` | `door@T1, gate@T2` | `door@T1, gate@T1, gate@T2` | `door@T1, gate@T2` |
| **2. Exact time** `= T2` | `door@T1, gate@T2` | `gate@T2` | `door@T1, gate@T2` |
| **3. Both bounds** `>= T2 AND <= T3` | `door@T1, gate@T2` | `gate@T2` | `door@T1, gate@T2` |

Case 3 is still wrong on both stores: **`door@T1` is before the caller's own lower bound of T2.** The
upper bound switches the query to the snapshot path, and every time term is then stripped — the
caller's lower bound with them. See *What parity cost* §2.

Note the three conditions are not equivalent even now: `getQueryTime` accepts `EQUALS`, `<` and `<=`
and treats all three as `<= T`, so case 2's "exact time" is an at-or-before snapshot, not an equality
match.

### What revision 1 got wrong

Two predictions were wrong and two first-draft tests passed for the wrong reason. Kept because both
mistakes are the kind that recur:

- **The exact-time case initially passed.** Its fixture had a single key, whose latest version at or
  before T2 *is* the row at T2, so both semantics coincide. Adding `door@T1` — a key existing only
  before the query time — made it discriminate. A test that cannot distinguish two behaviours is not
  evidence of parity.
- **The lower-bound case passed, but not for the predicted reason.** A lone `>=` never reaches the
  snapshot path: `getQueryTime` accepts only `EQUALS`, `<` and `<=`. The stripping only bites when an
  upper bound is present too — which is why the both-bounds case was added, and why it failed.

---

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

## Option A — Plan B adopts snapshot semantics  ⛔ **ADOPTED, THEN REVERTED**

> **This is what was built**, in `b1c8cb2870` on 2026-08-27, **and undone on 2026-09-17** — see the
> verdict at the top. Everything below was written as a
> prediction; see *What parity cost* for which parts came true. The section is unedited so the
> prediction can be judged against the outcome.

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
| ~~**Today**~~ *(pre-2026-08-27)* | client works around it | correct | broken | none | no |
| **A** — Plan B snapshots **← today** | correct | **breaks** on Plan B | still broken | medium | no — done locally |
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

These were properties of the SQL store alone when revision 1 was written. Plan B copied
`getQueryTime` verbatim, so **all three now apply to both stores**:

- **Only absolute times count.** Relative bounds throw and are swallowed, silently selecting full
  history.
- **`<` is treated as `<=`.** All three accepted conditions become `effective_time <= T`.
- **Position is ignored.** `ExpressionUtil.terms` recurses through `OR` and `NOT`, so a nested time
  term switches the whole query to a snapshot — and all time terms are then stripped from the
  condition, including an explicit lower bound the caller wrote, which the snapshot may violate.

The third is why `testLowerTimeBoundSelectsTheSameRowsInBothStores` is in the parity test: a
`>=` term alone should behave identically in both stores. It does — because a lone `>=` never
reaches the snapshot path at all. `testWorkedExampleInTheParityReport` is the one that pins the
stripping, and it asserts the wrong-but-matching behaviour of both.

The first edge is no longer a footnote. *What parity cost* §3 shows it is now the de-facto switch
between snapshot and filter semantics, selected by how a caller happened to spell a date.

---

## Recommended next steps — revision 2

Option A is built, so the open items are what it left behind rather than which option to pick.

**1. ~~Stop stripping the caller's lower bound — now in two places.~~ DONE — commit `60f80885d5`.**
Both stores now honour it. See *What parity cost* §2 for what the fix actually involved, including
the zero-width-range guard it needed and the Floor Map feature it subsumed.

**2. Decide what a date literal means before something depends on the accident.** *What parity cost*
§3 is the sharpest remaining defect: a `Z` on the end of a timestamp silently changes the shape of
the answer, and relative dates always take the filter path. Three options, none of them large:

| | Effect |
|---|---|
| Use `DateExpressionParser` in `getQueryTime` too | Consistent — but every relative-dated query on any temporal store switches to snapshot semantics. Wide blast radius, and it removes today's only bounded-range read. |
| Reject an unparseable time term instead of ignoring it | Turns a silent semantic switch into an error. Smallest change, and it stops the accident being load-bearing. |
| Leave it, and document it | Cheapest, but §3 shows people are already relying on it without knowing they are. |

**3. Mark the local change.** `PlanBSearchHelper` and `TemporalStateDb` carry 186 lines of this
fork's code with no `STROOMWORKS-LOCAL` marker. Until they do, a merge from master can revert the
Floor Map's read semantics without anyone noticing.

**4. Option C is still the only design that answers both questions.** Parity did not make the
underlying problem go away — it made both stores answer the snapshot question and neither answer the
history one. `docs/planb-explicit-read-mode-proposal.md` is Option C written up for the Plan B
maintainer. Nothing in the Floor Map is blocked on it; see
`docs/floormap-single-read-feasibility.md`.

To re-run:

```
./gradlew :stroom-sqlstore:stroom-sqlstore-impl-db:test --tests '*TestTemporalStoreParity*'
```
