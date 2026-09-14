# Plan: making the temporal stores' read mode explicit

**Component:** `stroom-sqlstore` (wholly local) and `stroom-planb`'s temporal-state search path
(local additions to upstream files)
**Status:** plan, nothing built. Supersedes the constraint that shaped
`docs/floormap-event-expiry-requirements.md` §5 — see *Why now*.
**Related:** `docs/planb-snapshot-read-proposal.md` (the original ask, still the best statement of
the shape), `docs/floormap-event-expiry-plan.md` (M3, which routes around the problem),
`docs/task-histogram-reads-whole-store-and-truncates-silently.md`

---

## Why now

Every awkward thing in the Floor Map's read paths traces to one design decision, and we had been
treating that decision as someone else's to change. It is not.

- **`stroom-sqlstore` does not exist on `origin/master`.** The whole module is ours.
- **Plan B's snapshot path is ours too.** `getQueryTime`, `removeTimeTerms` and `searchAsAt` were
  added by `b1c8cb2870` (2026-08-27), a local commit. `origin/master` has none of them.

So the constraint that ruled out fixing this at source — that it meant changing upstream's design —
was false. What remains is a change to **our own code**, in one upstream-owned file and one local
module.

## The problem, stated once

**One channel carries two intents.** A time predicate on a temporal store might mean *"filter rows
by time"* or *"give me one row per key as at this instant"*, and the store infers which from the
shape of the terms: `getQueryTime` treats `EQUALS`, `<` or `<=` on the time field as a snapshot
request, and `removeTimeTerms` then discards **every** time term including the lower bound.

It was a reasonable fix for the problem it solved. A UI asking for an instant sends
`TimeRange(t, t)`, which renders as `EffectiveTime >= t AND EffectiveTime < t` — an empty interval
no row can satisfy — so applying it literally returned nothing, always. Lifting the term out was the
right instinct. Inferring the *mode* from it was the part that has cost us.

Three consequences, each now costing real code:

| | Cannot be expressed | What we do instead |
|---|---|---|
| Event expiry | "latest per key at or before `T`, but not older than `F`" — the lower bound is discarded | M3: a `having` clause above the store, with the floor as a per-tick parameter |
| Histogram | "all rows between `A` and `B`" — any upper bound triggers snapshot mode | pass no range at all, read the whole store, bin client-side, cap at 10 000, never detect truncation |
| Readability | the mode is invisible in the query text | the same StroomQL means different things depending on a term the client added afterwards |

## What the workarounds cost, concretely

Worth totalling, because it is the case for fixing the cause:

- **M3** is seven work items of client code, a document field, a per-tick parameter, a neutral-floor
  convention that must be honoured at three call sites, and a `having` clause in user-editable text
  that silently disables expiry if deleted. It also required discovering and working around an
  unrelated upstream defect (`docs/task-query-having-on-an-alias-filters-everything-out.md`).
- **The histogram** transfers the whole store on every range change and is wrong past 10 000 rows,
  in a way nothing reports.
- **Show All** on the Map tab infers its extent from those truncated rows, where the Editor tab asks
  its store and gets an exact answer.
- **`TestTemporalStoreParity`** pins the discarding as intended behaviour, so the workarounds are
  now load-bearing in the test suite too.

## The shape of the fix

**Make the mode explicit, and honour ordinary time predicates literally.** Two capabilities, both
needed by the Floor Map alone:

1. **Snapshot** — one row per key, latest at or before `T`, optionally **with a floor**: omit a key
   whose latest row predates `F`. That is event expiry, expressed where it belongs.
2. **Range** — every row with `A <= time < B`. That is the histogram, and it also makes a store
   browser's time filter mean what it says.

With those, a time predicate that is *not* part of an explicit snapshot request is simply a filter,
as it is everywhere else in Stroom.

## Choosing the syntax

`docs/planb-snapshot-read-proposal.md` §1 sets out five shapes and argues for explicitness. Its
analysis stands; what has changed is that we can now pick one rather than ask for one. In brief:

| | Shape | Note |
|---|---|---|
| **A** | `where StateAt = '...'` — a reserved query field | No grammar change. Reads as a filter but is not one, which is the objection |
| **B** | `from people_events as at '09:45'` | Clearest at the point of use; needs a grammar change |
| **C** | `from snapshot('people_events', '09:45')` | No grammar change if function-valued data sources are supported; they are not today |
| **D** | A request API beside `search`, in the style of `getState` | Explicit, no grammar change, but invisible to anyone writing StroomQL by hand |
| **E** | A store setting `readMode: SNAPSHOT \| HISTORY` | **Rejected in the proposal and still rejected**: the Floor Map needs both answers from one store |

**These serve two different callers, and the Floor Map is the awkward one.** B and C read well for
a person writing a query by hand, but they carry the instant as a **literal** — and the Floor Map's
instant moves three times a second as the scrubber does. Literals in saved query text would mean
rewriting that text per tick, which is precisely the client-side substitution just removed from this
codebase.

**Recommendation: D is the mechanism, B is the affordance.** The request carries the snapshot
instant and the floor as values, so programmatic callers never touch the query text. B sits on top
of it for people writing queries by hand, lowering onto the same criteria.

**One correction to how cheap D is.** An earlier draft of this plan said the request would carry
them "the way `TimeRange` does today". It does not: `TimeRange` never reaches the store either.
`StateSearchProvider.createResultStore` builds `new ExpressionCriteria(query.getExpression())`
(`:223`) and discards everything else on the `Query` — params, time range, all of it. **The
expression is the only caller-supplied channel that reaches `TemporalStateDb` today**, which is
exactly why `b1c8cb2870` put the trigger in the expression: it had no other option without adding
plumbing. So D is not free — it needs a second value threaded through
`SearchProvider.createResultStore` → `reader.search(...)`, either by widening `ExpressionCriteria`
or by passing a small read-mode object alongside it. Small, ours, and worth naming rather than
assuming.

### Expressing the staleness tolerance in syntax B

The instant alone is `from people_events as at '09:45'`. The tolerance has to join it, and there are
two shapes:

```
from people_events as at '09:45' within 3h        -- duration
from people_events as at '09:45' since '06:45'    -- absolute
```

**`within` is the better primary form**, for three reasons:

- **It states the intent rather than a derived value.** "The state at 09:45, using only entries
  within 3 hours of it" is the question. `since '06:45'` makes a reader subtract to see what the
  tolerance was, and makes the *writer* compute it.
- **It is what the caller holds.** The Floor Map stores a `SimpleDuration` and a scrubber position;
  `within` takes them as they are, where `since` obliges the client to compute `t − D` first.
- **It composes with a relative instant.** `as at now() within 3h` reads cleanly; the `since`
  equivalent is `as at now() since now() - 3h`, which says `now()` twice and invites the two to
  drift apart.

Duration literals are precedented — `DateExpressionParser.parseDuration` already handles `3h`,
`1d` and the rest, and StroomQL's existing `window <field> by <duration>` clause establishes that a
time clause may take one. (Borrowing `window`'s *vocabulary* is fine; the proposal's warning about
`window` is about where its signal lands, not about how it reads.)

**Accept both, lowering `since` onto `within`** if absolute floors turn out to be wanted — they are
the same criteria once the instant is known.

**Two rules the grammar should carry:**

- **`within` requires `as at`.** A tolerance with no anchor is meaningless, and making it a suffix
  of the snapshot clause is what "part of one question" means concretely.
- **`within D` means `[T − D, T]`**, anchored on the snapshot instant rather than on `now()`. Worth
  stating explicitly because the two coincide in the common case and diverge exactly when someone
  scrubs back — which is the case the Floor Map cares about most.

**Decide this before writing anything**, because it determines whether the work is a parser change
plus plumbing (B) or plumbing alone (D).

## How expiry works under this design

Asserted above and worth spelling out, because it is the main thing the change buys and it differs
from M3 in more than mechanism.

**The document field is unchanged.** `FloorMapDoc.eventExpiry`, a `SimpleDuration`, absent reads as
24 hours, edited in the timeline settings dialog. Everything in W1 and W4 of
`docs/floormap-event-expiry-plan.md` survives as written.

**The floor travels on the request, not in the text.** `readEvents` already holds the scrubber
position `t`, and the document holds the duration, so `run(query, params, 0L, t)` becomes something
like `run(query, params, snapshotAt(t, expiry))` — and **no change to the query text at all**. Note
this needs the pass-through named above: nothing but the expression reaches the store today. If the
tolerance is expressed as a duration (`within`), the client passes the `SimpleDuration` it already
has and the store does the subtraction against the instant it was given.

**What each execution passes, which is where this gets simpler than M3:**

| Execution | Request carries | Result |
|---|---|---|
| Map overlay | snapshot at `t`, floor `t − D` | one row per entity, entities unseen since the floor omitted |
| Timeline histogram | a **range** `[A, B)` | every row in the visible window — no whole-store read, no client-side discard |
| Facts history | nothing | full history, as now |
| Events Query tab | nothing | exactly what the user wrote, unfiltered |

Compare M3's version of that table: a `having` clause present in all four, a floor parameter bound
at three call sites, and a `Long.MIN_VALUE` convention that any new call site must know to honour.
**The neutral floor disappears entirely**, because "no snapshot request" is expressible where "no
filter" was not.

**What this changes for the user, in both directions.**

- *Better:* expiry cannot be broken by editing the query. Under M3 deleting one line silently
  disables it — a consequence we documented and accepted because there was no alternative. Here
  there is nothing in the text to delete.
- *Better:* the Events Query tab shows unfiltered history, which is what a query editor should do.
  Under M3 it showed the clause and had to be given a neutral floor to stop it filtering.
- *Worse:* expiry becomes **invisible** in the query. Someone reading the events query cannot see
  why the map shows fewer entities than the tab does. The help text carries more weight as a result,
  and should say plainly that the map applies the expiry and the tab does not — W7's text needs
  rewriting for this, not just retitling.

**What does not change.**

- **`condense` is still incompatible** (R12). It collapses a run of identical values to its earliest
  entry, so a stationary entity's last-seen time regresses and it expires while still being
  reported. That is a property of `condense` against any age-based rule, wherever the rule is
  applied.
- **The boundary rule still has to be stated** — whether a row exactly at the floor is kept — and
  tested. Moving the comparison into the store does not decide it.
- **Changing the duration still takes effect on the next tick**, with no re-read or migration.

**One genuinely new question.** Is "latest per key at or before `T`, but only if that latest is at or
after `F`" a coherent thing to ask a temporal store, or is it a floor-map concern that has been
pushed down a layer? I think coherent: it is "what was the state at `T`, discounting anything that
has not been confirmed since `F`", which is a staleness tolerance and a normal thing to want of a
state store. But it is worth asking aloud before it becomes an API, because the alternative —
returning the row and letting the caller drop it — keeps the store simpler at the cost of
transferring rows nobody wants.

## Phases

**Phase 0 — mark what we already own.** `b1c8cb2870` added 395 lines to two upstream-owned Plan B
files with **no `STROOMWORKS-LOCAL` markers**. A merge from master could silently drop or conflict
with the snapshot path the Floor Map depends on. This is independent of everything else here and
should happen regardless of what is decided below.

**Phase 1 — choose the syntax.** Above. One decision, no code.

**Phase 2 — implement in both stores.**
- Plan B: `searchAsAt` gains a floor — one condition on the retention test, because entries arrive
  time-ascending per key so a latest-below-floor implies all-below-floor. `getQueryTime` becomes an
  explicit-request reader rather than a term sniffer. `removeTimeTerms` stops discarding what the
  caller meant literally.
- SQL store: the same floor on the `max(effective_time)` sub-select, and the same explicit trigger.
- `TestTemporalStoreParity` is rewritten: the case pinning "a lower bound adds nothing" becomes its
  inverse, and both stores are asserted to answer the explicit forms identically.

**Phase 3 — the Floor Map switches to it.**
- Expiry becomes the snapshot floor — see *How expiry works under this design*. No `having`, no
  per-tick parameter, no neutral-floor convention, no clause in user-editable text.
- The histogram asks for a range and stops reading the whole store; the 10 000 cap stops being
  reachable in normal use, and truncation detection becomes a safety net rather than the only
  defence.
- Show All can use a real extent — though a Plan B `getTimeRange` (option 4 of the histogram
  write-up) is still the better answer for that specific symptom.

**Phase 4 — retire the workarounds**, and delete rather than keep: the `having` clause, the neutral
floor, and the parts of the expiry plan that exist only to route around the inference.

## What to do about event expiry meanwhile

Two honest routes.

**Route 1 — do the store work first, and let expiry come with it.** Expiry becomes a floor on a
snapshot request: a document field, one argument at one call site, and nothing else. Most of M3's
seven work items disappear. The cost is that expiry waits for Phases 1–2.

**Route 2 — ship M3 now, migrate later.** Expiry lands sooner, then Phase 3 unwinds the `having`
clause, the parameter and the neutral-floor convention — including from any document whose query
text a user has edited by then.

**Recommendation: Route 1**, unless expiry is urgent. M3 is a substantial amount of client code
whose main purpose is to avoid touching a store we now know we own, and building it in order to
delete it is the expensive way round. Route 2 is defensible if the map is needed before the store
work can be scheduled — but it should be a deliberate choice, not a default.

## Blast radius — smaller than feared

A13 in the expiry requirements assumed this touches "every consumer of both stores". Checked:

- **Reference-data lookups are unaffected.** `ReferenceData` dispatches to `planBLookup.lookup` and
  `sqlStoreLookup.lookup` — the **lookup** path, not `search`. That is the highest-volume and most
  correctness-critical consumer of both stores, and it never goes near `getQueryTime`.
- **Affected:** the Floor Map; the store data-browsing UIs, but only if a user types a time
  predicate; and any hand-written dashboard, query or `View` against a temporal store.
- **Unknowable:** ad-hoc queries people have saved. With no production data and setups that can be
  rebuilt, this is a smaller risk here than it would normally be.

So A13 is still the real objection, but it is narrower than stated: this changes what a time
predicate means for *queries*, not for lookups.

## Assumptions

**B1 — we are free to change both stores' read semantics**, including breaking the current inferred
behaviour. Stated by the user, and consistent with there being no production data.

**B2 — the lookup path is genuinely separate** and needs no change. Rests on `ReferenceData:255–264`
dispatching to `lookup` rather than `search`.

**B3 — a floor filters correctly at the retention test in `searchAsAt`**, because entries arrive
time-ascending per key. Reasoned, not yet tested; Phase 2 should test it first, the way A1 was
tested before the expiry plan relied on it.

**B4 — the SQL store's `max()` sub-select accepts the same treatment**, for the same reason: the max
of a floor-filtered set is either above the floor or the set is empty.

**B5 — no upstream merge will bring a competing implementation.** `origin/master` has no snapshot
path today. If upstream later adds one, ours will conflict — which is what Phase 0's markers exist
to make visible rather than silent.

**B6 — the Floor Map is the only consumer that needs both modes from one store.** If something else
turns out to need it too, that strengthens the case rather than weakening it, and rules out
option E again.
