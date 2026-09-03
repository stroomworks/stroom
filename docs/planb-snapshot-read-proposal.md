# Proposal: a "state as at time T" read for Plan B temporal state stores

**To:** Plan B maintainers
**From:** Stroomworks (Enterprise Floor Mapping)
**Status:** proposal — nothing has been changed in Plan B, and we would not change it without you

---

## The ask, in one sentence

We would like a way to ask a `TEMPORAL_STATE` store for **one row per key — the latest version at or
before a given time** — with the reduction done inside the store rather than by the caller.

Plan B can already do this for **one** key, in `TemporalStateDb.getState`: it seeks to
`(key, T)` and iterates in reverse, taking the first entry whose prefix still matches. There is no
equivalent for **all** keys. That is the gap.

## Why this is a gap in Plan B's own terms

`TEMPORAL_STATE` answers two genuinely different questions, and today it can only answer one of them
efficiently.

Take a store recording where people are:

| Key | Effective time | Value |
|---|---|---|
| `alice` | 09:00 | `desk-1` |
| `alice` | 09:30 | `desk-2` |
| `bob` | 09:00 | `desk-3` |

- **"Show me the morning's activity"** — every matching row. Plan B does this well: a search with a
  time range returns all three.
- **"Where is everyone at 09:45?"** — one row per key: `alice@09:30`, `bob@09:00`. Plan B has no way
  to express this over all keys.

A caller wanting the second today has three choices, none good:

1. **Call `getState` per key** — needs to know the key set in advance, and is N round trips.
2. **Fetch the history and reduce it** — correct, but transfers everything. Concretely: our events
   store had ~5,000 rows against a client-side row cap of 1,000, so the reduction saw 20% of the
   data, truncated in LMDB key order, and silently produced the subset whose keys sorted first.
3. **Bound the query to a short recent window and reduce that** — what we do now. It bounds the
   transfer but drops any entity whose last event is older than the window, and it interacts badly
   with `condense` (below).

The second question is not exotic. "What is the state of everything, as at this time" is the
natural read for any temporal state store; it is what makes the store *temporal* rather than a log.

### The `condense` interaction, which we think is the strongest argument

`condense` removes consecutive entries with identical values, keeping the earliest of each run.

Under a **snapshot** read that is lossless — the value at any time T is unchanged, because you take
the latest entry at or before T and the surviving earliest-of-run *is* that entry.

Under a **windowed** read it is lossy. A key re-emitting an unchanged value has its repeats removed,
the survivor falls outside the window, and the key disappears from the result entirely — while the
store still holds its correct current value. So `condense` is safe with the read we are asking for
and unsafe with the workaround we are using instead. We currently have to tell users to leave
`condense` off, which we would rather not do.

---

## What would change in Plan B

### 1. A way for a query to request the reduction — explicitly

**We are specifically asking for this to be explicit, not inferred from the shape of the
expression.** A caller should have to say "as at T"; nothing about an ordinary time predicate should
change the shape of a result.

The syntax is yours to choose. Below are five shapes we considered, with what each would cost, since
two facts about the current plumbing rule some of them out and are easy to miss.

> **Checked against the StroomQL grammar** as documented in the Stroom skill's
> `references/api-query.md`, which states it was verified against `Tokeniser.java`,
> `TokenType.java`, `SearchRequestFactory.java` and live `validateQuery` (Stroom 7.x, 2026-07-11).
> We could not run `validateQuery` ourselves — no Stroom instance to hand — so please treat the
> syntax notes as "consistent with the documented grammar" rather than "executed".

#### Two constraints worth knowing first

**`Query.timeRange` never reaches the store.** `StateSearchProvider.createResultStore` builds
`new ExpressionCriteria(query.getExpression())` and discards everything else on the `Query` — params,
time range, the lot. By the time a search reaches `TemporalStateDb`, the *only* caller-supplied
channel is the expression. Anything carried elsewhere needs a new pass-through added.

**`window <field> by <duration>` is the wrong precedent to copy.** It looks like the natural model —
an existing StroomQL clause about time — but `processWindow` builds a `HoppingWindow` into
`TableSettings`, which is consumed by coprocessors *after* the store returns. Only `fieldIndex`
crosses into the DB. A `TableSettings`-shaped signal cannot reach a reduction that has to happen
during the scan.

#### Suggestion A — a reserved query field: `where StateAt = '2024-03-01T09:45:00Z'`

The cheapest thing that is still explicit, and **it needs no grammar change at all**: the grammar
already has `term = field , cond , value`, with `field = name` and `value` including `datetime`, so

```
from people_events
where StateAt = '2024-03-01T09:45:00.000Z'
select Key, EffectiveTime, Value
```

parses today. (A bare `2024-03-01T09:45:00.000Z` is also a valid `datetime` token, so the quotes are
optional.) `StateAt` would be a field the store recognises and consumes: it strips the term before
building the row predicate and uses its value as the reduction boundary.

- **Grammar change: none.** It is an ordinary expression term, so it already flows through
  `ResultStoreManager`, `StateSearchProvider` and `ExpressionCriteria` untouched.
- **Scope: Plan B only.** Nothing outside `TemporalStateDb` needs to know the field exists.
- **Reversible.** If it turns out to be the wrong shape, nothing else has been committed to.

One implementation detail: the field must be **stripped before the predicate is built**, or
`PlanBSearchHelper` will register it via `ExpressionUtil.fields` and then fail with
`Unexpected field`. That strip-before-mapping pattern already exists in this codebase — our SQL
store does exactly this for a denormalised label field — so it is a known shape rather than a new
trick.

The honest cost: it is a field that is not a field. It would want suppressing from field lists, and
rejecting with a clear message when combined with a condition other than equality, or a reader will
eventually try `StateAt > x` and get something meaningless.

#### Suggestion B — a clause on the datasource: `from people_events as at '09:45'`

Reads best of the five, and says plainly in the query text what kind of read it is.

This one **does** need a grammar change, and in the most rigid part of the syntax. The production is
currently:

```ebnf
from = "from" , name ;      (* nothing may follow the name *)
```

`from` is required, must be first, and clause order is enforced throughout. So this adds an optional
tail to `from` — the token vocabulary is already there (`AS` and `BY` exist as "additional tokens",
and `AS` is currently used only in `selitem` and `show as`), but the parse rule is new. Beyond the
parser it needs somewhere on `Query` (or a per-datasource options object) to carry the value, and
`StateSearchProvider` passing it into the criteria.

The cost is that it touches the **shared** query language, so it needs agreement wider than Plan B —
every datasource inherits the syntax whether or not it can honour it, which raises the question of
what `as at` should do against a non-temporal source. Our suggestion would be a clear parse error
rather than silent acceptance.

We would see this as the destination if the query language is open to extension, with A as a first
cut.

#### Suggestion C — a function-valued datasource: `from snapshot('people_events', '09:45')`

**Correcting ourselves: this does not work, and our reason for suggesting it was wrong.** We believed
the `from` clause already accepted functions, on the strength of the floor map's
`from param('EventStore')`. It does not. The grammar is `from = "from" , name`, with
`name = bareword | string` — a function call is not a name. The floor map's `param('EventStore')`
never reaches the parser: `FloorMapMapPresenter.resolveQueryParams` does a **textual string replace**
of `param('X')` with the quoted store name before the query is submitted. (StroomQL's own parameter
syntax is `${name}`, which is a value, not a datasource.)

So C needs a grammar change as well — and a deeper one than B, because it changes what a datasource
*is* rather than adding a modifier after it. We withdraw it.

#### Suggestion D — a request API alongside `search`, in the style of `getState`

`TemporalStateRequest` already exists for the single-key read. A bulk analogue —
`getStates(criteria, at)` — keeps everything inside Plan B, needs no query-language change at all,
and is entirely yours to shape.

The trade-off is that it bypasses StroomQL, so a caller loses user-editable query text, column
expressions and the result-store machinery. For us that matters: the floor map's events query is
edited by users and its `where` clause filters which entities appear. Passing an
`ExpressionCriteria` into such a method would retain the filtering, which makes this viable rather
than merely possible.

**This is the right answer if the query language is off-limits**, and we would be content with it.

#### Suggestion E — a store setting: `readMode: SNAPSHOT | HISTORY` on `TemporalStateSettings`

Trivial to implement, and we think wrong, but worth recording so it is not proposed later as an
obvious simplification.

It is the wrong granularity. The same store legitimately needs both reads: our floor map asks one
`TEMPORAL_STATE` store for "where is everyone now" (snapshot) to draw the map, and asks the *same
store* for "every event this morning" (history) to draw the timeline's density chart. A per-store
mode forces one of those to be wrong, or forces the data to be duplicated into two stores.

#### One thing that already works, worth noting

The *history* half needs nothing new. `term = field , "between" , value , "and" , value` is already
in the grammar, so

```
from people_events
where EffectiveTime between '2024-03-01T08:00:00.000Z' and '2024-03-01T12:00:00.000Z'
select Key, EffectiveTime, Value
```

is valid today and is exactly the read Plan B already does well. Only the snapshot half needs a way
to be asked for — which is why we have not proposed anything that changes how history is expressed.

#### Summary

| | Grammar change | Scope of agreement needed | Keeps StroomQL | Our view |
|---|---|---|---|---|
| **A** reserved field `StateAt` | **none — parses today** | Plan B only | yes | **best first cut** |
| **B** `from … as at T` | yes | shared query language | yes | **best destination** |
| **C** `from snapshot(…)` | **yes** (deeper than B) | shared query language | yes | **withdrawn** — see above |
| **D** bulk request API | none | Plan B only | no | acceptable fallback |
| **E** per-store setting | none | Plan B only | yes | wrong granularity |

What matters to us is only the property, not the spelling: **requestable, and inert for every query
that does not request it.** Any of A, B or D gives us that.

### 2. `TemporalStateDb.search` implements it

One pass over the key space, keeping the best entry `<= T` per key, then emit. The per-key primitive
already exists in `getState`; this is its bulk form.

Three implementation notes from reading the code — offered because they cost us time to find, not
because we assume you have not considered them:

**Do not group by "prefix changed" during a single forward scan.** Stored key prefixes are
variable length for several key types: `HASH_LOOKUP` appends a variable-length sequence number on a
clash (`LmdbKeySequence.addSequenceNumber` after the fixed hash), `UID_LOOKUP` and `TAGS` use
minimal-length uids, and `VariableKeySerde` mixes inline and lookup encodings within one store. So
one key's stored bytes can be a **byte-prefix of another's**, and their entries interleave. Grouping
on prefix change misgroups. A map keyed on the copied prefix bytes is correct regardless of
ordering.

**Decode times rather than comparing time bytes.** `NanoTimeSerde` writes a signed offset from the
year 2000, so byte order is not time order across the epoch boundary.

**Buffering has a memory cost worth bounding.** `search` currently streams into the
`ValuesConsumer`; a reduction has to accumulate winners and emit at the end, which is
O(distinct keys) heap held inside the read transaction. On a store built for large cardinality that
wants a cap or a spill strategy rather than being unbounded. We would rather it errored than
truncated silently — a caller can handle "too many keys", but cannot detect a quietly partial
answer.

---

## Effect on callers

**If the trigger is explicit: no existing caller changes behaviour at all.** That is the main reason
we are asking for explicit rather than implicit, and we think it is worth being concrete about what
the alternative would cost.

### Why we are *not* asking for it to be implicit

The obvious cheap implementation is "if the criteria carry an upper time bound, reduce". We
implemented that convention in our own fork's SQL-backed store and we now think it was a mistake.
Three problems, all of which would land on Plan B's users if it were copied:

**Every time-ranged query would change shape.** `ResultStoreManager` injects
`EffectiveTime < to` for any datasource exposing a time field, and every dashboard time preset
except "All time" sets a `to` (`TimeRanges`: `now()-1m`, `hour()`, `day()`, …). So a dashboard
showing "all state changes today" would silently start returning one row per key. No error, no
warning, fewer rows.

**It makes the semantics depend on date format.** Our implementation parses the bound with
`DateUtil.parseUnknownString`, which handles epoch millis and ISO-8601 only. Relative bounds throw,
the exception is swallowed, and the query silently falls back to full history. The upshot is that
whether a query is a snapshot depends on *how the user typed the date* — which also means our
convention does not actually fire for the presets people use.

**It reinterprets bounds it should honour.** Because the time term is lifted out and then stripped
from the predicate, a query for `EffectiveTime >= 09:15 AND <= 12:00` against our store returns
`bob@09:00` — a row **before the lower bound the caller asked for**. That is a bug in our store,
which we are fixing on our side; we mention it only as evidence of where implicit triggering leads.

### What existing Plan B callers would see

| Caller | Effect |
|---|---|
| Existing StroomQL / dashboard queries | **None** — they do not request the reduction |
| `PlanBDataPresenter` (the Data tab) | **None** — it issues `limit 100` with no time bound |
| `PlanBQueryService.getLocalValue` / lookups | **None** — already per-key via `getState` |
| Remote query resources | **None** unless the reduction is requested |
| New callers wanting bulk state-at-T | Gain a read that is currently not expressible |

We have no visibility of deployed user content, which is exactly why we would not want an implicit
trigger: the blast radius cannot be enumerated from the source tree.

---

## An unrelated bug we noticed, offered for your judgement

While reading `getState` we think we found a latent correctness issue. Please treat this as a
question rather than a report — you will know the encodings better than we do.

`getState` checks `ByteBufferUtils.containsPrefix(entry.getKey(), prefix)` and then, if it passes,
decodes and returns that entry. It never compares the decoded key to the requested one.

Because stored prefixes are variable length, one key's bytes can extend another's — most concretely
after a hash clash, where an id becomes `hash || sequenceNumber` while the unclashed id is just
`hash`. A `getState` for the unclashed key computes `prefix = hash`; an entry for the clashed key is
stored as `hash || seq || time`, which **does** start with `hash`, so `containsPrefix` passes and
the wrong key's state is returned.

Whether the reverse scan reaches such an entry first depends on byte values — it needs
`seq[0] > time[0]` at that position, which for millisecond times and low sequence numbers is close
rather than impossible. So: requires a hash clash, plus unfavourable byte ordering, and is silent
when it happens. An equality check on the decoded key would make it robust regardless, and the same
check is needed by the bulk reduction above.

---

## What we would do on our side

- Request the reduction explicitly from the floor map's events query.
- Delete our 20-second query window and the client-side reduction that compensates for its absence.
- Stop telling users to leave `condense` off.
- Fix the lower-bound-stripping bug in our own store, independently of this.

## What we are asking for

A view on whether the capability is one you want in Plan B, and if so, on the shape of the
explicit trigger. We are happy to do the implementation work and raise it for review rather than
asking you to build it — what we cannot do is decide the API or take on a fork-local change to
`TemporalStateDb`, which would break on every merge.

Happy to walk through any of this, and to share the parity tests we used to characterise the
current behaviour.
